package com.showhow.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.showhow.ai.AiStack
import com.showhow.ai.DETECTOR_MODEL
import com.showhow.ai.Detections
import com.showhow.ai.TakeStep
import com.showhow.ai.Coach
import com.showhow.ai.DeviceAsr
import com.showhow.ai.DetectorCaptioner
import com.showhow.ai.GESTURE_MODEL
import com.showhow.ai.Gesture
import com.showhow.ai.DeviceNarrator
import com.showhow.ai.MediaPipeGestureSource
import com.showhow.ai.ObjectDetectSource
import com.showhow.ai.RealSceneCheck
import com.showhow.ai.frameStatsOf
import com.showhow.ai.VoskAsr
import com.showhow.ai.VoskStream
import com.showhow.ai.gestureSourceOrNone
import com.showhow.ai.Word
import com.showhow.capture.AudioRecorder
import com.showhow.capture.CameraController
import com.showhow.capture.MotionSource
import com.showhow.core.AdaptiveGate
import com.showhow.core.Mode
import com.showhow.core.ModeEngine
import com.showhow.core.ModeInputs
import com.showhow.core.correctionEvidence
import com.showhow.core.mapSnapsToSteps
import com.showhow.core.pickFrames
import com.showhow.core.LinkWordConfirmer
import com.showhow.core.FrameStats
import com.showhow.core.Policy
import com.showhow.core.Sample
import com.showhow.core.SpokenWord
import com.showhow.core.StepCutter
import com.showhow.data.Guide
import com.showhow.data.GuideStore
import com.showhow.data.PolicyRepository
import com.showhow.data.Step
import com.showhow.data.asDraft
import com.showhow.data.Provenance
import com.showhow.data.provenanceOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the debug screen shows. One object so it repaints atomically. */
data class DebugState(
    val levelDb: Double = -120.0,
    val gateDb: Double = -45.0,
    val floorDb: Double = -60.0,
    val accelVariance: Double = 0.0,
    val mode: Mode = Mode.TAP,
    val reason: String = "TAP <- start",
    val switches: Int = 0,
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val samples: Int = 0,
    val liveCuts: Int = 0,
    val snaps: Int = 0,
    val policyError: String? = null,
)

/**
 * What the phone is doing between "Done" and the review screen.
 *
 * Named after what the user sees, not after the class doing the work, because
 * the whole point of the processing screen is that a person watching it can
 * tell what their phone is busy with.
 */
enum class BuildStage { IDLE, TRANSCRIBING, CUTTING, PHOTOS, CAPTIONS, COACHING, SAVING, DONE }

/** An answer to a question about a guide, assembled from what was recorded. */
data class Answer(val stepIndex: Int, val transcript: String)

class ShowHowViewModel(app: Application) : AndroidViewModel(app) {

    private val policyRepo = PolicyRepository(app).also { it.start() }

    /**
     * Declared first on purpose. Everything below is handed a `{ policy.value }`
     * lambda, and Kotlin initialises properties in source order -- a model
     * source that reads its knobs while being constructed would find null.
     */
    val policy: StateFlow<Policy> = policyRepo.policy

    val guides = GuideStore(File(app.filesDir, "guides"))

    private val gestureSource = gestureSourceOrNone(
        app,
        File(app.filesDir, GESTURE_MODEL),
    ) { policy.value }

    private val detector = ObjectDetectSource(
        app,
        File(app.filesDir, DETECTOR_MODEL),
        minScore = { policy.value.detectMinScore },
    )

    /**
     * Speech goes to the phone's own recogniser when it has one.
     *
     * That is the only hardware-accelerated route available: Vosk is Kaldi, a
     * C++ decoder with no delegate, so it is CPU whatever model is fed to it,
     * while the system engine runs on the DSP and is markedly better on
     * English. It is the on-device recogniser specifically, which is
     * contractually not allowed to use the network -- the ordinary one is, and
     * appears nowhere in this app.
     *
     * Vosk stays as the fallback for phones without it, and for Hindi and
     * Marathi if the system has no pack for them.
     */
    private val deviceAsr = if (DeviceAsr.available(app)) DeviceAsr(app) else null

    /**
     * The fallback, and the reason there is one.
     *
     * The system engine is faster and better when it answers, but it is a
     * service owned by someone else: it can decline a file, lack a language
     * pack, or simply return nothing, and none of that is visible until a take
     * comes back with an empty transcript. Vosk always answers. It loads
     * lazily, so on a phone where the system engine works this costs nothing
     * but disk.
     */
    private val voskAsr = VoskAsr.orNoop(File(app.filesDir, VoskAsr.MODELS_DIR))

    /**
     * Reads a step aloud, offline, in a synthetic voice.
     *
     * A second way to hear a step, not a replacement for the first. The
     * expert's own recording stays on disk and stays the default: it is the
     * evidence that a real person did this job, and a synthetic voice reading a
     * transcript is only ever as good as the transcript. Where the recogniser
     * misheard, the expert's audio is still right and the spoken version is
     * confidently wrong -- so the person following gets both and picks.
     */
    private val narrator = DeviceNarrator(app)

    /**
     * The on-device model that turns one expert's take into a guide a stranger
     * can follow, and answers the questions the expert never thought to answer.
     *
     * It is the only model here that is allowed to say something the expert did
     * not, which is why it is also the only one whose output is labelled. See
     * [Coach].
     *
     * Loaded lazily and never on the recording path: a 2B model is most of a
     * gigabyte of native memory and the mic, the camera, Vosk and two MediaPipe
     * graphs are already holding the rest. It is first touched when a guide is
     * built, by which point the recorder and the live recognizer have stopped.
     */
    private val coach = Coach(
        app,
        File(app.filesDir, Coach.COACH_MODEL),
        maxContextChars = { policy.value.coachContextChars },
    )

    /** Whether the coach model is on this phone, so the UI can say so plainly. */
    val coachPresent: Boolean get() = coach.present

    val coachDelegate: String get() = coach.delegateName

    /** Read the step aloud instead of playing the take. Off by default. */
    private val _readAloud = MutableStateFlow(false)
    val readAloud: StateFlow<Boolean> = _readAloud.asStateFlow()

    fun setReadAloud(on: Boolean) {
        _readAloud.value = on
        if (!on) narrator.stop()
    }

    /** Speak one step. Returns when the phone has finished saying it. */
    suspend fun speak(text: String) {
        narrator.speak(text, _lang.value)
    }

    fun stopSpeaking() {
        narrator.stop()
    }

    /** Nothing in here is canned any more. Gemma would only make it prettier. */
    val ai: AiStack = AiStack(
        asr = deviceAsr ?: voskAsr,
        captioner = DetectorCaptioner(detector),
        gestures = gestureSource,
        sceneCheck = RealSceneCheck(),
    )

    /**
     * Hand signs for the Player to act on: OPEN_PALM next, FIST back, THUMB_UP
     * replay the audio. Empty when no model is on the phone, so the buttons
     * stay the way anyone reaches the end of a guide -- gestures are a second
     * way in, never the only one.
     */
    val gestures: Flow<Gesture> = gestureSource.start()

    /**
     * What the camera can see right now, for the boxes over the viewfinder.
     *
     * Empty when the detector model is not on the phone, and the overlay then
     * draws nothing at all. Every box on screen is a claim with a model behind
     * it; there is no decorative one.
     */
    private val _detections = MutableStateFlow(Detections())
    val detections: StateFlow<Detections> = _detections.asStateFlow()

    /**
     * When the detector last saw anything, so a frame it happens to miss does
     * not blink every box off and straight back on. A borderline score at ten
     * frames a second reads as a fault in the app rather than what it is.
     */
    private var lastSeenAtMs = 0L

    /** When the last frame was actually run through the models. */
    private var lastFrameAtMs = 0L

    /** Which delegate each vision model landed on, for the telemetry panel. */
    val detectorDelegate: String get() = detector.delegateName

    val gestureDelegate: String
        get() = (gestureSource as? MediaPipeGestureSource)?.delegateName ?: "--"

    /**
     * How much the live camera looks like the photo saved for the step being
     * watched, 0..1, or 0 when nothing is being watched.
     *
     * Advisory, and that is a product claim, not a nicety: the Player may say
     * "this doesn't look like the photo", and it may never disable Next.
     * Compare against [Policy.sceneAdviseMinSimilarity].
     */
    private val _sceneSimilarity = MutableStateFlow(0f)
    val sceneSimilarity: StateFlow<Float> = _sceneSimilarity.asStateFlow()

    private var sceneReference: Bitmap? = null

    /**
     * What the expert is saying, live, while recording.
     *
     * A preview and never the record: the guide is built from a second pass
     * over the finished WAV, so this can drop a chunk under load without
     * costing anything on disk. Empty when there is no Vosk model, and the
     * Show screen then falls back to the level meter, which is still true.
     */
    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private var liveStream: VoskStream? = null
    private var pcmJob: Job? = null

    /**
     * The photo the live camera is compared against. The Player calls this
     * when the step changes, and with null when it leaves.
     */
    fun watchScene(photo: File?) {
        sceneReference?.recycle()
        // A guide photo is a full-resolution JPEG and SceneHash only ever
        // looks at 32x32 of it, so decode it small and keep it small.
        // Upright, or the scene check compares a sideways photo to an upright
        // camera frame and reports that a correct workbench looks wrong.
        sceneReference = photo?.let { decodeUpright(it, 8) }
        _sceneSimilarity.value = 0f
    }

    /**
     * The one analyzer the camera binds. One frame, converted once, handed to
     * everything that wants it -- three analyzers would fight over the same
     * camera and convert the same bitmap three times.
     */
    val frameAnalyzer = ImageAnalysis.Analyzer { proxy ->
        var source: Bitmap? = null
        var frame: Bitmap? = null
        try {
            val now = System.currentTimeMillis()
            // Thirty frames a second buys nothing: a hand has to hold a pose for
            // gestureDwellMs anyway, and a box that moves faster than the eye is
            // just heat. Every skipped frame is also two bitmaps not allocated.
            if (now - lastFrameAtMs >= FRAME_INTERVAL_MS) {
                lastFrameAtMs = now
                val hands = gestureSource as? MediaPipeGestureSource
                val reference = sceneReference
                source = runCatching { proxy.toBitmap() }.getOrNull()
                frame = source?.let { upright(it, proxy.imageInfo.rotationDegrees) }
                if (frame != null) {
                    // Rotated once here and handed to everything already upright.
                    // MediaPipe's own Android samples rotate the bitmap rather
                    // than pass a rotation into ImageProcessingOptions, which
                    // sidesteps whose sign convention wins. It also fixes the
                    // scene check, which was comparing a sideways camera frame
                    // against an upright photo and calling a correct bench wrong.
                    hands?.onFrame(frame, 0)
                    val seen = detector.onFrame(frame, 0)
                    if (seen.boxes.isNotEmpty()) {
                        lastSeenAtMs = now
                        _detections.value = seen
                    } else if (now - lastSeenAtMs > DETECTION_HOLD_MS) {
                        _detections.value = seen
                    }
                    reference?.let {
                        _sceneSimilarity.value =
                            runCatching { ai.sceneCheck.compare(frame, it) }.getOrDefault(0f)
                    }
                }
            }
        } finally {
            // Both bitmaps are native memory the GC only frees when it feels
            // like it. Two per frame, unrecycled, is how this app grew until
            // Android killed it. Freed here, by hand, every frame.
            if (frame !== source) frame?.recycle()
            source?.recycle()
            // Not closing the proxy stalls the pipeline after two frames.
            proxy.close()
        }
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _debug = MutableStateFlow(DebugState())
    val debug: StateFlow<DebugState> = _debug.asStateFlow()

    private val _library = MutableStateFlow<List<Guide>>(emptyList())
    val library: StateFlow<List<Guide>> = _library.asStateFlow()

    /** How far through building a guide the phone is. Drives the Processing screen. */
    private val _buildProgress = MutableStateFlow(BuildStage.IDLE)
    val buildProgress: StateFlow<BuildStage> = _buildProgress.asStateFlow()

    /**
     * The guide being reviewed, held here rather than re-read from disk so that
     * splitting and joining repaint immediately instead of after a save.
     */
    private val _editing = MutableStateFlow<Guide?>(null)
    val editing: StateFlow<Guide?> = _editing.asStateFlow()

    /**
     * The language of the next take, and of every take already recorded in it.
     *
     * Not a preference and not a guess. A Vosk model speaks one language, so
     * this decides which model is loaded and therefore what words can come
     * back at all -- recording English against the Hindi model does not fail,
     * it returns Devanagari nonsense. The picker only ever offers languages
     * whose model is actually on this phone.
     */
    /**
     * Languages this phone can actually transcribe. Empty means ASR is off.
     *
     * Declared before [_lang], which reads it: Kotlin initialises properties in
     * source order, and this class has already been bitten once by a field that
     * read a later one and found null.
     *
     * The system recogniser carries its own packs, so when it is in use the
     * picker offers all three and a missing pack shows up as an empty
     * transcript rather than as a missing button. Vosk can only offer what has
     * been unzipped onto the phone.
     */
    val languages: List<String> =
        if (deviceAsr != null) VoskAsr.LANGUAGES
        else VoskAsr.languagesPresent(File(app.filesDir, VoskAsr.MODELS_DIR))

    private val _lang = MutableStateFlow(languages.firstOrNull() ?: "en")
    val lang: StateFlow<String> = _lang.asStateFlow()

    fun setLang(code: String) {
        if (code in languages) _lang.value = code
    }

    /** User override. Beats every other rule in the decision table. */
    private val _easyMode = MutableStateFlow(false)
    val easyMode: StateFlow<Boolean> = _easyMode.asStateFlow()

    /**
     * The live mode and the sentence explaining it, for the Player's mode bar.
     * Separate from [debug] on purpose: the Player should not have to know what
     * a Schmitt trigger is to render "TALK <- phone is flat".
     */
    private val _mode = MutableStateFlow(Mode.TAP)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _reason = MutableStateFlow("TAP <- start")
    val reason: StateFlow<String> = _reason.asStateFlow()

    private val recorder = AudioRecorder()
    private val motion = MotionSource(app)
    private var gate = AdaptiveGate(policy.value)
    private var engine = ModeEngine(policy.value)

    private val samples = mutableListOf<Sample>()
    private var recordJob: Job? = null
    private var levelJob: Job? = null
    private var motionJob: Job? = null
    private var startedAt = 0L
    private var camera: CameraController? = null
    private var currentId: String? = null

    // Live boundary detection, used only to decide when to snap a photo. The
    // authoritative cut happens once at the end over the whole sample log.
    private var silenceStart = -1L
    private var sawSpeech = false

    /** When each photo was taken, on the take's clock. Defect #1 lived here. */
    private val snapTimesMs = mutableListOf<Long>()

    /**
     * Mean recogniser confidence over the last words of the most recent take.
     * 1f until something has actually been transcribed, so a phone with no
     * model never reports speech as unclear -- silence is not the same as
     * mumbling, and claiming otherwise would push the app into HANDS forever.
     */
    private var meanWordConfidence = 1f

    /** Height in pixels of the largest detected face, or 0 when nothing detects. */
    private var faceHeightPx = 0.0

    init {
        // Which recogniser won, said once, at startup. "It was all over the
        // place" is unanswerable without knowing which engine produced it.
        android.util.Log.i(
            TAG,
            "asr = " + (if (deviceAsr != null) "device (on-device system engine)" else "vosk (cpu)") +
                ", languages = " + languages,
        )
        refreshLibrary()
        viewModelScope.launch {
            policy.collect { p ->
                // Retune live. This is the whole point of policy.json.
                gate = AdaptiveGate(p)
                engine = ModeEngine(p)
                _debug.value = _debug.value.copy(policyError = policyRepo.lastError)
            }
        }
        motionJob = viewModelScope.launch {
            motion.variance().collect { v -> onMotion(v) }
        }
    }

    fun go(screen: Screen) {
        _screen.value = screen
        if (screen is Screen.Library) refreshLibrary()
        if (screen is Screen.Review) openForReview(screen.guideId)
    }

    fun setEasyMode(on: Boolean) {
        _easyMode.value = on
    }

    fun attachCamera(controller: CameraController?) {
        camera = controller
    }

    fun refreshLibrary() {
        _library.value = guides.list()
    }

    /** Turn a camera frame the right way up. A no-op when it already is. */
    private fun upright(frame: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) return frame
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
        }.getOrDefault(frame)
    }

    /**
     * Which model files are actually on this phone.
     *
     * Load-bearing at 3am: every "it is not detecting anything" turns out to be
     * a model that was never pushed, and this is faster than reading logcat on
     * someone else's laptop.
     */
    fun modelsPresent(): List<Pair<String, Boolean>> {
        val dir = getApplication<Application>().filesDir
        return listOf(
            "on-device recogniser" to (deviceAsr != null),
            "vosk languages" to VoskAsr.languagesPresent(File(dir, VoskAsr.MODELS_DIR)).isNotEmpty(),
            "gesture" to File(dir, GESTURE_MODEL).isFile,
            "detector" to File(dir, DETECTOR_MODEL).isFile,
        )
    }

    /** Bytes on disk for a guide folder. What the library card shows. */
    fun sizeOnDisk(id: String): Long =
        guides.dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // --- recording ---------------------------------------------------------

    fun startRecording() {
        if (recordJob != null) return
        val id = guides.newId()
        currentId = id
        samples.clear()
        silenceStart = -1L
        sawSpeech = false
        snapTimesMs.clear()
        gate = AdaptiveGate(policy.value)
        startedAt = System.currentTimeMillis()
        _debug.value = _debug.value.copy(
            recording = true, samples = 0, liveCuts = 0, snaps = 0, elapsedMs = 0,
        )

        levelJob = viewModelScope.launch {
            recorder.levels.collect { db -> onLevel(db.toDouble()) }
        }
        startLiveTranscript()
        recordJob = viewModelScope.launch {
            recorder.record(guides.takeFile(id))
        }
        // First photo now, so step one always has a picture even if the expert
        // starts talking before the camera settles.
        snap()
    }

    /**
     * Words on the glass while the expert is still talking.
     *
     * Runs on its own job so a slow recognizer can never back up into the mic
     * read loop. If there is no model this does nothing at all and the Show
     * screen shows the meter instead.
     */
    private fun startLiveTranscript() {
        _liveTranscript.value = ""
        // The live caption is always Vosk, whichever engine builds the guide.
        // The system recogniser is request-response per utterance and cannot be
        // fed half a second at a time, and a viewfinder with no words on it
        // looks broken even when the transcript arrives correctly at Review.
        val stream = (voskAsr as? VoskAsr)?.openStream(_lang.value) ?: return
        liveStream = stream
        pcmJob = viewModelScope.launch(Dispatchers.Default) {
            recorder.pcm.collect { chunk ->
                stream.feed(chunk)?.let { _liveTranscript.value = it }
            }
        }
    }

    private fun stopLiveTranscript() {
        pcmJob?.cancel()
        pcmJob = null
        liveStream?.let { runCatching { it.close() } }
        liveStream = null
    }

    /**
     * "Next step" while recording: take a picture of what is being pointed at
     * right now.
     *
     * Deliberately not a cut. The authoritative boundaries come out of the
     * whole sample log at the end, and a person tapping a button is worse at
     * finding them than the gate is. What a person is better at is knowing
     * which moment is worth a photograph, and mapSnapsToSteps will pair that
     * photograph to whichever final step it falls inside.
     */
    fun markStep() {
        if (!_debug.value.recording) return
        snap()
    }

    /**
     * Stop, then build. The screen goes to Processing immediately and on to
     * Review when the build finishes, because transcribing a ninety second
     * take is seconds of real work and a frozen button is how a demo dies.
     */
    fun stopRecording() {
        val id = currentId
        recorder.stop()
        levelJob?.cancel()
        levelJob = null
        recordJob = null
        stopLiveTranscript()
        _debug.value = _debug.value.copy(recording = false)
        if (id == null) {
            go(Screen.Library)
            return
        }
        _buildProgress.value = BuildStage.TRANSCRIBING
        go(Screen.Processing)
        viewModelScope.launch {
            val built = buildGuide(id)
            _buildProgress.value = BuildStage.DONE
            go(Screen.Review(built))
        }
    }

    private suspend fun buildGuide(id: String): String {
        val p = policy.value
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
        // The recogniser runs once over the whole take, so its word clock and
        // the sample log's clock are the same clock. An ASR that is off or
        // fails returns nothing, and the confirmer then abstains.
        _buildProgress.value = BuildStage.TRANSCRIBING
        // A recogniser with word clocks can inform the cut. One without can
        // only be asked about a step once the step exists, so the order of
        // these two stages depends on which one is running.
        val timed = ai.asr.hasWordTimings
        val words = if (timed) {
            runCatching { ai.asr.transcribe(guides.takeFile(id), _lang.value) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        _buildProgress.value = BuildStage.CUTTING
        val confirmer = LinkWordConfirmer(
            p.linkWords(_lang.value),
            words.map { SpokenWord(it.text, it.startMs) },
            p.confirmWindowMs,
            p.confirmMinLinkWords,
        )
        val ranges = StepCutter(p, confirmer).cut(samples.toList(), durationMs)

        _buildProgress.value = BuildStage.PHOTOS
        // Photos were snapped at live boundaries; these are the final ones.
        // There are usually more of the former, so the pairing is by time.
        //
        // Measured first, so the choice can be about which frame is worth
        // showing rather than only which step it fell in. A phone still moving
        // when the shutter fired, a lens against the bench, or a repeat of the
        // previous step's picture are all rejected, and a step whose every
        // frame is one of those keeps no photo at all -- the Player shows the
        // instruction and the expert's voice, which is a complete step.
        //
        // mapSnapsToSteps stays the answer when nothing could be measured: a
        // phone that would not decode its own JPEGs should still produce a
        // guide with pictures in it, chosen the old way, rather than none.
        val stats = frameStats(id)
        val snaps = if (stats.isEmpty()) {
            mapSnapsToSteps(snapTimesMs.toList(), ranges)
        } else {
            pickFrames(stats, ranges, p).map { it ?: -1 }
        }
        meanWordConfidence = meanConfidence(words.takeLast(p.speechUnclearWindowWords))

        _buildProgress.value = BuildStage.CAPTIONS
        val steps = ranges.map { r ->
            val photo = snaps[r.index].takeIf { it >= 0 }
                ?.let { guides.snapFile(id, it) }
                ?.takeIf { it.exists() }
            Step(
                index = r.index,
                title = "Step ${r.index + 1}",
                caption = photo?.let { ai.captioner.caption(it) }.orEmpty(),
                startMs = r.startMs,
                endMs = r.endMs,
                photo = photo?.name.orEmpty(),
                transcript = if (timed) {
                    transcriptFor(words, r.startMs, r.endMs)
                } else {
                    sliceTranscript(id, r.startMs, r.endMs)
                },
                modeHint = modeHint(wordsIn(words, r.startMs, r.endMs), p),
            )
        }

        // The coach reads the whole take at once, which is why it runs here and
        // not per step: "phir isko nikaalo" only becomes "now lift the RAM
        // module out" if the model has already seen the three steps before it.
        _buildProgress.value = BuildStage.COACHING
        val coached = coachSteps(steps, words)

        _buildProgress.value = BuildStage.SAVING
        val guide = Guide(
            id = id,
            title = "New job",
            lang = _lang.value,
            createdAt = System.currentTimeMillis(),
            steps = coached,
        )
        guides.save(guide)
        refreshLibrary()
        return id
    }

    /**
     * Measure every snap taken during the take.
     *
     * Runs once, over frames already on disk, after recording has stopped --
     * so it costs a decode per photo at a moment when nothing else is
     * competing for the phone. Sampled at 1/4 on the way in, because the
     * measurements are taken at 64x64 and decoding a full 1280x960 to throw
     * away 99% of it is how this app used to run out of memory.
     *
     * A frame that will not decode is simply left out. It cannot be scored, and
     * a frame that cannot be scored cannot be chosen, which is the correct
     * outcome rather than an error.
     */
    private fun frameStats(id: String): List<FrameStats> =
        snapTimesMs.mapIndexedNotNull { i, tMs ->
            val file = guides.snapFile(id, i)
            if (!file.isFile) return@mapIndexedNotNull null
            val bmp = decodeUpright(file, 4) ?: return@mapIndexedNotNull null
            try {
                // Detector boxes are evidence that something recognisable is in
                // shot. It only knows COCO classes, so this is a count and
                // never a claim about what the thing was.
                frameStatsOf(bmp, i, tMs, detector.onFrame(bmp, 0).boxes.size)
            } finally {
                bmp.recycle()
            }
        }

    /**
     * The coach's pass over a freshly cut guide.
     *
     * Per step and not all-or-nothing: a model that returns eight good lines
     * and drops two leaves those two showing the expert's own words, which is
     * a worse-looking guide and a true one. No model at all is the same path
     * with every line dropped, so nothing here needs a branch for it.
     */
    private suspend fun coachSteps(steps: List<Step>, words: List<Word>): List<Step> {
        if (!coach.present) return steps
        // The whole take in one call. Each step carries its clock, what the
        // expert said and what the detector reported seeing -- everything the
        // coach needs to notice that step 6 takes back step 5.
        val p = policy.value
        val take = steps.map { s ->
            TakeStep(
                startMs = s.startMs,
                endMs = s.endMs,
                transcript = s.transcript,
                caption = s.caption,
                hasPhoto = s.photo.isNotBlank(),
                // Evidence and never a verdict: the transcript is untouched,
                // and the coach is free to disagree with every word of this.
                correction = correctionFor(s, words, p),
            )
        }
        val written = runCatching { coach.rewrite(jobFrom(steps), take) }
            .getOrElse {
                android.util.Log.w(TAG, "coach pass failed, keeping the raw steps", it)
                emptyList()
            }
        return steps.mapIndexed { i, s ->
            val c = written.getOrNull(i) ?: return@mapIndexed s
            s.copy(
                title = c.title.ifBlank { s.title },
                instruction = c.instruction,
                // Already grounded against what the coach was handed, inside
                // Coach.rewrite -- a claimed source is never stronger than the
                // evidence for it.
                instructionSource = c.source,
                aside = c.aside,
                // Advice, never a gate. Nothing in the app blocks on it --
                // not Next, not Continue, not verification.
                warning = c.note.ifBlank { null } ?: s.warning,
                warningSource = if (c.note.isBlank()) s.warningSource else c.noteSource,
            )
        }
    }

    /**
     * A sentence for the prompt when a step looks like a self-correction.
     *
     * "Remove this screw... no, sorry, not this one. Remove the side screw." is
     * one run of speech with no pause in it, so it is one step to the cutter
     * and two contradictory instructions to a learner. Noticing that is worth
     * doing; deciding it is not, which is why this hands the coach prose to
     * read rather than a rewritten instruction.
     *
     * Word clocks are used when the recogniser gave any. When it did not -- the
     * system engine returns sentences -- the step's own words are laid on its
     * start time and the timing signal abstains rather than firing on every
     * step, which is how LinkWordConfirmer already behaves with nothing to vote
     * with.
     */
    private fun correctionFor(s: Step, words: List<Word>, p: Policy): String {
        val timed = words
            .filter { it.startMs >= s.startMs && it.startMs < s.endMs }
            .map { SpokenWord(it.text, it.startMs) }
        val untimed = s.transcript.split(WHITESPACE)
            .filter { it.isNotBlank() }
            .map { SpokenWord(it, s.startMs) }
        val e = correctionEvidence(timed.ifEmpty { untimed }, p, _lang.value) ?: return ""
        return "they say \"${e.supersededText}\", then \"${e.correctedText}\" " +
            "(${e.signals.joinToString("; ")})"
    }

    /**
     * What this guide is about, for the coach's prompt.
     *
     * The title is still "New job" at build time -- the expert renames it on
     * the Review screen, after this runs -- so the job is taken from what the
     * detector actually saw. "laptop, keyboard, screwdriver" is a poor title
     * and a good prompt: it is the difference between the coach writing about
     * a generic object and writing about a laptop.
     */
    private fun jobFrom(steps: List<Step>): String =
        steps.flatMap { it.caption.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(4).joinToString(", ") { it.key }
            .ifBlank { "an unknown repair job" }

    // --- review edits ------------------------------------------------------

    /**
     * Rename the job being reviewed.
     *
     * The title is what the Library lists and what the coach is told the job
     * is, and until now it was "New job" forever -- nothing anywhere changed
     * it. Held in [_editing] like every other Review edit, so it repaints
     * immediately and lands on disk when Save is tapped.
     */
    fun setTitle(text: String) {
        val g = _editing.value ?: return
        _editing.value = g.copy(title = text).asDraft()
    }

    private fun openForReview(id: String) {
        if (_editing.value?.id != id) _editing.value = guides.load(id)
    }

    /**
     * Cut one step in two at its midpoint.
     *
     * Both halves keep the same photo, because there is only one photo and
     * guessing which half it belongs to would be worse than showing it twice.
     * The transcript splits by word count, which lands close enough for a
     * person to read and fix in the same twenty seconds.
     */
    fun splitStep(index: Int) {
        val g = _editing.value ?: return
        val s = g.steps.getOrNull(index) ?: return
        if (s.endMs - s.startMs < 2) return
        val mid = s.startMs + (s.endMs - s.startMs) / 2
        val words = s.transcript.split(" ").filter { it.isNotBlank() }
        val half = words.size / 2
        val first = s.copy(endMs = mid, transcript = words.take(half).joinToString(" "))
        val second = s.copy(startMs = mid, transcript = words.drop(half).joinToString(" "))
        commit(g.steps.toMutableList().apply { set(index, first); add(index + 1, second) })
    }

    /**
     * Fold a step into the one above it. [index] is the lower of the pair, so
     * the "Join" control between two cards passes the index of the card below.
     */
    fun joinSteps(index: Int) {
        val g = _editing.value ?: return
        if (index <= 0 || index > g.steps.lastIndex) return
        val above = g.steps[index - 1]
        val here = g.steps[index]
        val merged = above.copy(
            endMs = here.endMs,
            transcript = listOf(above.transcript, here.transcript)
                .filter { it.isNotBlank() }.joinToString(" "),
            photo = above.photo.ifBlank { here.photo },
            caption = above.caption.ifBlank { here.caption },
            modeHint = above.modeHint.ifBlank { here.modeHint },
        )
        commit(
            g.steps.toMutableList().apply {
                set(index - 1, merged)
                removeAt(index)
            },
        )
    }

    /**
     * Renumber, and put the guide back to a draft.
     *
     * Renumbers [Step.index] only. It used to overwrite every title with
     * "Step N", which meant one Split or Join threw away every title the coach
     * had written for the whole guide -- so a title is now replaced only when
     * it is blank or is itself a positional placeholder that has gone stale.
     *
     * Every edit lands here, which is exactly why the draft reset lives here:
     * a verified tick on content the expert has not re-read is the app
     * vouching for something nobody checked.
     */
    private fun commit(steps: List<Step>) {
        val g = _editing.value ?: return
        _editing.value = g.copy(
            steps = steps.mapIndexed { i, s ->
                val placeholder = s.title.isBlank() || PLACEHOLDER_TITLE.matches(s.title)
                s.copy(index = i, title = if (placeholder) "Step ${i + 1}" else s.title)
            },
        ).asDraft()
    }

    /**
     * Remove a step from the guide.
     *
     * The step goes; its slice of the take does not. take.wav is untouched and
     * every other step keeps its own clock, so what is lost is a step in the
     * guide and never a second of what the expert actually recorded. That gap
     * in the timeline is simply never played, which is the correct outcome for
     * a step someone deliberately deleted.
     */
    fun deleteStep(index: Int) {
        val g = _editing.value ?: return
        if (index !in g.steps.indices) return
        // The last step cannot go: a guide with no steps is a guide the Player
        // refuses to open, and deleting your way into that is not an edit.
        if (g.steps.size <= 1) return
        commit(g.steps.toMutableList().apply { removeAt(index) })
    }

    /**
     * Move a step up or down the guide.
     *
     * The order a guide is read in and the order it was recorded in are
     * different things -- an expert doubles back, does the fiddly bit first
     * because the glue is drying, explains a thing after doing it. Each step
     * keeps its own start and end, so its audio and its photograph travel with
     * it and only the reading order changes.
     */
    fun moveStep(index: Int, delta: Int) {
        val g = _editing.value ?: return
        val to = index + delta
        if (index !in g.steps.indices || to !in g.steps.indices) return
        commit(g.steps.toMutableList().apply { add(to, removeAt(index)) })
    }

    /**
     * The expert's own words for a step, typed rather than spoken.
     *
     * Writes [Step.instruction] and never [Step.transcript]. The transcript is
     * the record of what was said out loud and stays the record; this is the
     * expert correcting how the step reads, which is a different thing and
     * deserves its own field.
     *
     * The source becomes EXPERT because it now is -- a human typed it. This is
     * the one path in the app that may raise a provenance rather than lower it,
     * and it may because the person doing the typing is the expert themselves.
     */
    fun editStep(index: Int, text: String) {
        val g = _editing.value ?: return
        val s = g.steps.getOrNull(index) ?: return
        commit(
            g.steps.toMutableList().apply {
                set(
                    index,
                    s.copy(
                        instruction = text,
                        instructionSource =
                            if (text.isBlank()) Provenance.UNKNOWN else Provenance.EXPERT,
                        // Typing a step over is also a decision that it belongs.
                        aside = if (text.isBlank()) s.aside else false,
                    ),
                )
            },
        )
    }

    /**
     * Which step is being re-recorded right now, or -1.
     *
     * Review shows this as the card's own state, so the person doing it can see
     * exactly which step the microphone is pointed at.
     */
    private val _reRecording = MutableStateFlow(-1)
    val reRecording: StateFlow<Int> = _reRecording.asStateFlow()

    /**
     * Record a replacement clip for one step.
     *
     * It lands beside the take as its own file rather than being spliced into
     * take.wav. Splicing would shift every later step's timestamps, which means
     * fixing one badly-worded step could silently break the four after it --
     * and this screen exists to make repairs cheap, not risky.
     */
    fun startReRecord(index: Int) {
        val g = _editing.value ?: return
        if (recordJob != null || index !in g.steps.indices) return
        _reRecording.value = index
        recordJob = viewModelScope.launch {
            recorder.record(guides.stepAudioFile(g.id, index))
        }
    }

    fun stopReRecord() {
        val g = _editing.value ?: return
        val index = _reRecording.value
        recorder.stop()
        recordJob = null
        _reRecording.value = -1
        if (index !in g.steps.indices) return
        val file = guides.stepAudioFile(g.id, index)
        // A recording that produced nothing but a header is not an improvement
        // on the slice it would have replaced.
        if (!file.exists() || file.length() <= WAV_HEADER_BYTES) {
            file.delete()
            return
        }
        commit(g.steps.toMutableList().apply { set(index, g.steps[index].copy(audio = file.name)) })
        saveEditing()
    }

    /** Save the working copy. Still a draft; nobody has said it is right yet. */
    fun saveEditing() {
        val g = _editing.value ?: return
        guides.save(g)
        refreshLibrary()
    }

    /**
     * The expert puts their name to this guide.
     *
     * Nothing may prevent it. Not a step the coach was unsure about, not a
     * warning, not a GENERAL provenance, not a step with no photograph -- every
     * one of those is a model's opinion, and a model does not get a vote on
     * whether a human may sign off their own work. The only thing that stops
     * verification is having nothing to verify.
     */
    fun verifyEditing() {
        val g = _editing.value ?: return
        if (g.steps.isEmpty()) return
        guides.saveVerified(g)
        _editing.value = guides.load(g.id) ?: g
        refreshLibrary()
    }

    // --- asking about a guide ----------------------------------------------

    /**
     * Which step answers a question, from what the expert actually said.
     *
     * A token overlap over the transcripts, not a model: the answer has to come
     * out of this guide, offline, in the time it takes to lift a thumb. It
     * cannot invent an answer, which is exactly the property that lets the
     * sheet promise nothing is sent anywhere.
     */
    fun ask(g: Guide, question: String): Answer? {
        // Takes the guide rather than an id on purpose: this runs on every
        // keystroke in the ask sheet, and re-reading guide.json each time would
        // put disk IO in the middle of typing.
        val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        if (terms.isEmpty()) return null
        return g.steps
            .map { s ->
                val hay = (s.transcript + " " + s.caption + " " + s.title).lowercase()
                s to terms.count { hay.contains(it) }
            }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?.let { Answer(it.index, it.transcript.ifBlank { it.caption }) }
    }

    // --- the coach, live in the Player -------------------------------------

    /**
     * A learner's question and what came back.
     *
     * [thinking] is a state and not a spinner detail: a 2B model takes seconds
     * to answer and a sheet that shows nothing for four of them reads as a
     * frozen app. [fromGuide] is false when the model went past what the expert
     * recorded, so the Player can label it -- see [Coach.BEYOND].
     */
    data class CoachAnswer(
        val question: String,
        val text: String = "",
        val thinking: Boolean = false,
        val fromGuide: Boolean = true,
    )

    private val _coachAnswer = MutableStateFlow<CoachAnswer?>(null)
    val coachAnswer: StateFlow<CoachAnswer?> = _coachAnswer.asStateFlow()

    private var coachJob: Job? = null

    /**
     * Ask the coach, and read the answer out loud if the Player is speaking.
     *
     * One question at a time: a second one cancels the first rather than
     * queueing, because a learner who rephrases wants the new answer and the
     * old one is now noise.
     */
    fun askCoach(g: Guide, stepIndex: Int, question: String) {
        coachJob?.cancel()
        if (question.isBlank()) {
            _coachAnswer.value = null
            return
        }
        _coachAnswer.value = CoachAnswer(question, thinking = true)
        coachJob = viewModelScope.launch {
            val text = coach.answer(
                job = g.title.ifBlank { jobFrom(g.steps) },
                // The instruction where there is one, the expert's words where
                // there is not. Both are true; the rewritten one reads better.
                steps = g.steps.map { it.instruction.ifBlank { it.transcript } },
                stepIndex = stepIndex,
                question = question,
            )
            if (text.isBlank()) {
                // No model, or it failed. Say so rather than showing an empty
                // card that looks like the app hung.
                _coachAnswer.value = CoachAnswer(question, thinking = false)
                return@launch
            }
            _coachAnswer.value = CoachAnswer(
                question = question,
                text = text.replace(Coach.BEYOND, "").trim(),
                thinking = false,
                fromGuide = !text.contains(Coach.BEYOND),
            )
            // English, because that is what the coach answers in -- reading it
            // with a Hindi voice would mangle every word.
            if (_readAloud.value) narrator.speak(text.replace(Coach.BEYOND, ""), "en")
        }
    }

    fun clearCoachAnswer() {
        coachJob?.cancel()
        coachJob = null
        _coachAnswer.value = null
        _question.value = ""
        stopListening()
    }

    /**
     * The question as the mic is hearing it, so the learner can see they were
     * heard before the model takes four seconds to answer.
     */
    private val _question = MutableStateFlow("")
    val question: StateFlow<String> = _question.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var questionJob: Job? = null
    private var questionStream: VoskStream? = null

    /**
     * Open the mic. Tapped once to start, once more to send.
     *
     * A tap and not a silence detector: this runs in a workshop with a hand in
     * a laptop, and a recogniser that decides on its own when a sentence ended
     * cuts half of them off. A tap and not a hold, because the one hand the
     * learner has spare is holding a screwdriver, and a hold that slips mid
     * question loses the question.
     *
     * The question is transcribed in the guide's language, not English. The
     * learner asks in whatever they speak; the coach answers in English.
     */
    fun startListening() {
        if (_listening.value) return
        val stream = (voskAsr as? VoskAsr)?.openStream(_lang.value)
        if (stream == null) {
            // No Vosk model for this language. The typed field is still there,
            // so this is a missing feature and not a broken screen.
            android.util.Log.w(TAG, "no recognizer for ${_lang.value}, questions must be typed")
            return
        }
        questionStream = stream
        _question.value = ""
        _listening.value = true
        // The wav is the recorder's price of admission, not something anyone
        // keeps: a question is not part of the guide.
        val scratch = File(getApplication<Application>().cacheDir, "question.wav")
        questionJob = viewModelScope.launch {
            launch(Dispatchers.Default) {
                recorder.pcm.collect { chunk -> stream.feed(chunk)?.let { _question.value = it } }
            }
            recorder.record(scratch)
            scratch.delete()
        }
    }

    /** Released. Whatever was heard becomes the question. */
    fun stopListening(): String {
        val heard = _question.value.trim()
        recorder.stop()
        questionJob?.cancel()
        questionJob = null
        questionStream?.let { runCatching { it.close() } }
        questionStream = null
        _listening.value = false
        return heard
    }

    /** Released with the guide to hand: stop listening and ask in one move. */
    fun stopListeningAndAsk(g: Guide, stepIndex: Int) {
        val heard = stopListening()
        if (heard.isNotBlank()) askCoach(g, stepIndex, heard)
    }

    /** Typing, for a phone with no model for this language and for the judge. */
    fun setQuestion(text: String) {
        _question.value = text
    }

    // --- live signals ------------------------------------------------------

    private fun onLevel(db: Double) {
        val t = System.currentTimeMillis() - startedAt
        gate.update(db)
        val speech = db > gate.gateDb
        samples += Sample(t, db)

        if (speech) {
            if (silenceStart >= 0L) {
                val len = t - silenceStart
                if (len >= policy.value.pauseMs && sawSpeech) {
                    // A boundary just went by: grab the frame for the new step.
                    snap()
                    _debug.value = _debug.value.copy(liveCuts = _debug.value.liveCuts + 1)
                }
                silenceStart = -1L
            }
            sawSpeech = true
        } else if (silenceStart < 0L) {
            silenceStart = t
        }

        _debug.value = _debug.value.copy(
            levelDb = gate.levelDb,
            gateDb = gate.gateDb,
            floorDb = gate.floorDb,
            elapsedMs = t,
            samples = samples.size,
        )
        pushMode(db)
    }

    private fun onMotion(variance: Double) {
        _debug.value = _debug.value.copy(accelVariance = variance)
        pushMode(_debug.value.levelDb)
    }

    /**
     * Report the largest face the camera can see, in pixels. Until a detector
     * calls this, [faceHeightPx] stays 0 and userFar stays false rather than
     * being guessed at from brightness.
     */
    fun feedFaceSize(px: Double) {
        faceHeightPx = px
    }

    private fun pushMode(db: Double) {
        val p = policy.value
        val committed = engine.update(
            System.currentTimeMillis(),
            ModeInputs(
                easyMode = _easyMode.value,
                accelVariance = _debug.value.accelVariance,
                dbfs = db,
                speechUnclear = meanWordConfidence < p.speechUnclearConfThreshold,
                // 0 means nothing is looking, which is not the same as a face
                // far away, so it must not read as "far".
                userFar = faceHeightPx > 0.0 && faceHeightPx < p.userFarFaceHeightPx,
            ),
        )
        if (committed) {
            _mode.value = engine.mode
            _reason.value = engine.reason
            _debug.value = _debug.value.copy(
                mode = engine.mode,
                reason = engine.reason,
                switches = _debug.value.switches + 1,
            )
        }
    }

    /**
     * Ask a timing-free recogniser about one step, by handing it only that
     * step's audio. The slice lives in cacheDir and is deleted straight after --
     * it is a question, not part of the guide.
     */
    private suspend fun sliceTranscript(id: String, startMs: Long, endMs: Long): String {
        val out = File(getApplication<Application>().cacheDir, "slice.wav")
        val slice = guides.sliceTake(guides.takeFile(id), startMs, endMs, out) ?: return ""
        return try {
            val fromDevice = deviceAsr?.transcribeText(slice, _lang.value).orEmpty()
            if (fromDevice.isNotBlank()) {
                fromDevice
            } else {
                // The system engine said nothing. That is either a quiet step
                // or an engine that will not take a file, and from here the two
                // look identical -- so ask the one that always answers rather
                // than hand back an empty guide.
                android.util.Log.i(TAG, "system engine returned nothing, falling back to vosk")
                runCatching { voskAsr.transcribe(slice, _lang.value) }
                    .getOrDefault(emptyList())
                    .joinToString(" ") { it.text }
            }
        } finally {
            slice.delete()
        }
    }

    private fun wordsIn(words: List<Word>, startMs: Long, endMs: Long): List<Word> =
        words.filter { it.startMs >= startMs && it.startMs < endMs }

    private fun transcriptFor(words: List<Word>, startMs: Long, endMs: Long): String =
        wordsIn(words, startMs, endMs).joinToString(" ") { it.text }

    private fun meanConfidence(words: List<Word>): Float =
        if (words.isEmpty()) 1f else words.map { it.confidence }.average().toFloat()

    /**
     * Advice for the mode bar, never a gate. A step the recogniser struggled
     * with is a step where asking the phone out loud will struggle too.
     */
    private fun modeHint(words: List<Word>, p: Policy): String =
        if (words.isNotEmpty() && meanConfidence(words) < p.speechUnclearConfThreshold) {
            "speech was unclear here, hand signs work better"
        } else {
            ""
        }

    private fun snap() {
        val id = currentId ?: return
        val cam = camera ?: return
        // The timestamp is taken here, not in the callback: the shutter is what
        // the step boundary lines up with, not whenever the JPEG finishes.
        val index = snapTimesMs.size
        snapTimesMs += System.currentTimeMillis() - startedAt
        _debug.value = _debug.value.copy(snaps = snapTimesMs.size)
        viewModelScope.launch {
            runCatching { cam.takePhoto(guides.snapFile(id, index)) }
        }
    }

    override fun onCleared() {
        recorder.stop()
        motionJob?.cancel()
        policyRepo.stop()
        stopListening()
        runCatching { coach.close() }
        // RELEASE, the last rung of the model ladder. Native memory the GC
        // cannot see.
        (ai.asr as? AutoCloseable)?.let { runCatching { it.close() } }
        (gestureSource as? AutoCloseable)?.let { runCatching { it.close() } }
        stopLiveTranscript()
        runCatching { detector.close() }
        sceneReference?.recycle()
        sceneReference = null
        narrator.release()
        super.onCleared()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** "Step 4" and nothing else. A title the app wrote, safe to rewrite. */
        val PLACEHOLDER_TITLE = Regex("""Step \d+""")

        const val TAG = "ShowHow"

        /** A WAV with only a header in it is a recording that never started. */
        const val WAV_HEADER_BYTES = 44L

        /** How long a box survives a frame the detector missed it in. */
        const val DETECTION_HOLD_MS = 500L

        /**
         * Ten frames a second through the vision models.
         *
         * The camera offers thirty. A hand has to hold a pose for
         * gestureDwellMs before anything happens, and a box that redraws faster
         * than an eye can follow is heat rather than information -- so two
         * frames in three were being converted, rotated, inferred on and thrown
         * away.
         */
        const val FRAME_INTERVAL_MS = 100L
    }
}
