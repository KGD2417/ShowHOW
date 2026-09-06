package com.showhow.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.showhow.ai.AiStack
import com.showhow.ai.DETECTOR_MODEL
import com.showhow.ai.DETECTOR_MODEL_COCO
import com.showhow.ai.DetectorModel
import com.showhow.ai.DetectionBox
import com.showhow.ai.Detections
import com.showhow.ai.TakeStep
import com.showhow.ai.AnswerEvidence
import com.showhow.ai.Coach
import com.showhow.ai.ComponentLocator
import com.showhow.ai.Localization
import com.showhow.ai.learnerContext
import com.showhow.ai.DeviceAsr
import com.showhow.ai.DetectorCaptioner
import com.showhow.ai.captionOf
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
import com.showhow.core.CheckInputs
import com.showhow.core.StepCheck
import com.showhow.core.labelShortfall
import com.showhow.core.StepConfidence
import com.showhow.core.correctDomainText
import com.showhow.core.correctDomainTokens
import com.showhow.core.correctionEvidence
import com.showhow.core.mapSnapsToSteps
import com.showhow.core.namedNear
import com.showhow.core.plainEnglish
import com.showhow.core.stripFillers
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    val guides = GuideStore(File(app.filesDir, "guides")) { msg, t ->
        Log.e(TAG, msg, t)
    }

    private val gestureSource = gestureSourceOrNone(
        app,
        File(app.filesDir, GESTURE_MODEL),
    ) { policy.value }

    // Both detectors, every frame. The fine-tuned one knows the tools and
    // nothing else; COCO keeps the laptop, keyboard, mouse and person it threw
    // away. Either file may be absent -- the other still runs.
    private val detector = ObjectDetectSource(
        app,
        listOf(
            DetectorModel(
                File(app.filesDir, DETECTOR_MODEL),
                labels = TOOL_LABELS,
            ) { label -> floorFor(label, policy.value.detectMinScore) },
            DetectorModel(
                File(app.filesDir, DETECTOR_MODEL_COCO),
                labels = COCO_LABELS,
            ) { label -> floorFor(label, policy.value.detectMinScoreCoco) },
        ),
    )

    /**
     * The floor for one label: its own if policy names one, else its model's.
     *
     * In policy.json rather than in code so a class that starts misfiring on
     * the bench can be quietened during Red Light, when nobody can compile.
     */
    private fun floorFor(label: String, fallback: Float): Float =
        policy.value.detectLabelMinScore[label] ?: fallback

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

    /**
     * Read the step aloud instead of playing the take.
     *
     * On by default now, from [Policy.readAloudDefault]: a learner opening a
     * guide wants to be told what to do, and the rewritten step is a sentence
     * written to be followed. The expert's own audio stays one tap away and is
     * still the evidence -- where the recogniser misheard, that recording is
     * right and the sentence is confidently wrong.
     */
    private val _readAloud = MutableStateFlow(policy.value.readAloudDefault)
    val readAloud: StateFlow<Boolean> = _readAloud.asStateFlow()

    fun setReadAloud(on: Boolean) {
        _readAloud.value = on
        if (!on) narrator.stop()
    }

    /**
     * Speak one step. Returns when the phone has finished saying it.
     *
     * @param lang defaults to the guide's language, because a step is the
     *   expert's own words. The app's own lines -- the coach's answers, the
     *   nudge to the next step -- pass "en" explicitly: they are written in
     *   English and a Hindi voice reading English mangles every word.
     */
    suspend fun speak(text: String, lang: String = _lang.value) {
        narrator.speak(text, lang)
    }

    fun stopSpeaking() {
        narrator.stop()
    }

    /** Nothing in here is canned any more. Gemma would only make it prettier. */
    /** Concrete, because the VM needs [DetectorCaptioner.labels] and not only
     * the one-line caption the [Captioner] interface promises. */
    private val captioner = DetectorCaptioner(detector)

    val ai: AiStack = AiStack(
        asr = deviceAsr ?: voskAsr,
        captioner = captioner,
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
     * When each label was last actually detected, and the boxes it had then.
     *
     * Per label, and that is the whole point. The old version held the *frame*:
     * any frame with a box in it replaced everything, so a rock-solid `laptop`
     * beside a flickering `screwdriver` gave the screwdriver no hold at all --
     * it vanished the first frame the laptop came back alone. On the bench that
     * is the tool sitting in plain sight while the app says it cannot see it.
     *
     * A label survives [DETECTION_HOLD_MS] past the last frame it was found in,
     * with the boxes from that frame, so two screws stay two boxes.
     */
    private val heldBoxes = LinkedHashMap<String, HeldLabel>()

    private data class HeldLabel(val atMs: Long, val boxes: List<DetectionBox>)

    /** This frame's detections, plus labels still inside their hold. */
    private fun hold(seen: Detections, now: Long): Detections {
        seen.boxes.groupBy { it.label }.forEach { (label, boxes) ->
            heldBoxes[label] = HeldLabel(now, boxes)
        }
        heldBoxes.entries.removeAll { now - it.value.atMs > DETECTION_HOLD_MS }
        return seen.copy(boxes = heldBoxes.values.flatMap { it.boxes })
    }

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

    /**
     * How much the bench looks like the step, settled deterministically.
     *
     * Advisory in the strongest sense: no control anywhere reads it. It is
     * computed from SceneHash and the detector's own labels, costs a couple of
     * milliseconds, and never wakes the coach -- a 2B model asked on every
     * frame would drain the phone and freeze the screen for seconds at a time.
     */
    private val _stepCheck = MutableStateFlow(StepCheck.UNCERTAIN)
    val stepCheck: StateFlow<StepCheck> = _stepCheck.asStateFlow()

    /**
     * Whether the guide may turn its own page. See [StepConfidence.mayAdvance].
     *
     * A flow rather than a condition the Player assembles from two others,
     * because it was assembled from two others and they were read at different
     * moments off different frames -- which is how the bar came to say "that
     * looks right" over a guide that would not move.
     */
    private val _mayAdvance = MutableStateFlow(false)
    val mayAdvance: StateFlow<Boolean> = _mayAdvance.asStateFlow()

    /**
     * How sure the check is, 0..1, smoothed over frames.
     *
     * The number the Player puts on screen. It used to show [sceneSimilarity]
     * raw, which is a different measurement from the one the verdict was made
     * on and jumps ten points between two frames of the same still bench --
     * so the percentage and the sentence under it disagreed, and a learner
     * believes the percentage.
     */
    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    /**
     * The running verdict. Rebuilt at every step change, which is also when a
     * policy.json pushed to the phone mid-session takes effect.
     */
    private var stepConfidence = StepConfidence(policy.value)

    /**
     * Labels the step's photograph had that the camera is not showing, so the
     * Player can say what it is still waiting for instead of a bare percentage
     * the learner cannot act on.
     */
    private val _missingLabels = MutableStateFlow<List<String>>(emptyList())
    val missingLabels: StateFlow<List<String>> = _missingLabels.asStateFlow()

    /** dHash of the previous analysed frame, for "has the scene settled". */
    private var lastFrameHash: Long? = null

    /**
     * A copy of the most recent camera frame, for the coach to look at.
     *
     * Kept because the analyzer recycles its own bitmaps every frame -- by the
     * time a learner has finished typing a question the frame they were asking
     * about is long freed. One copy, downscaled, replaced each time and the old
     * one recycled by hand: two of these alive at once is how this app used to
     * be killed.
     *
     * Null when the camera is off, which is exactly when there is nothing to
     * look at and the coach should answer from the guide alone.
     */
    private var lastFrame: Bitmap? = null

    /**
     * Guards [lastFrame] against the race that crashed the app.
     *
     * The analyzer replaces this bitmap ten times a second and recycles the one
     * it replaces. A learner's question is answered on another thread, seconds
     * later, and MediaPipe reads the pixels natively for as long as it is
     * looking at them -- so the frame handed to the coach was being freed while
     * the vision encoder was still reading it:
     *
     *   Abort message: 'Error, cannot access an invalid/free'd bitmap here!'
     *
     * A SIGABRT from native code, so runCatching never saw it and the text-only
     * fallback never ran. Checking isRecycled before handing it over does not
     * help either: it is true when checked and false a millisecond later, which
     * is the definition of this bug rather than a fix for it.
     *
     * So the copy is taken under the same lock that does the recycling, and
     * what the coach gets is a bitmap nothing else has a reference to.
     */
    private val frameLock = Any()

    private fun keepFrame(frame: Bitmap) {
        val scaled = runCatching {
            val w = VISION_FRAME_PX
            val h = (frame.height * (w.toFloat() / frame.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, w, h, true)
        }.getOrNull() ?: return
        synchronized(frameLock) {
            val old = lastFrame
            lastFrame = scaled
            if (old !== scaled) runCatching { old?.recycle() }
        }
    }

    /**
     * A private copy of the current frame for the coach, or null.
     *
     * The caller owns it and must recycle it. Taken under [frameLock] so the
     * analyzer cannot free the source midway through the copy.
     */
    private fun frameForCoach(): Bitmap? = synchronized(frameLock) {
        val f = lastFrame ?: return null
        if (f.isRecycled) return null
        runCatching { f.copy(f.config ?: Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    /**
     * The step's own photograph, decoded small, for the coach to look at.
     *
     * Its own decode rather than [sceneReference]: that one is owned by the
     * camera analyzer and recycled the moment the learner changes step, and
     * handing a recycled bitmap to a native graph is a crash rather than a
     * worse answer. Sampled at 8 like everywhere else -- the vision encoder
     * resizes it far smaller than that anyway.
     */
    private fun guidePhoto(g: Guide, stepIndex: Int): Bitmap? {
        val photo = g.steps.getOrNull(stepIndex)?.photo.orEmpty()
        val file = guides.goalImage(g.id, photo) ?: return null
        return runCatching { decodeUpright(file, 8) }.getOrNull()
    }

    /**
     * Re-read this guide's photographs with the detector that is on the phone
     * now, and write what it actually sees back into the guide.
     *
     * The captions are a record of what *a* detector saw, and this app has
     * changed which one it runs. A guide recorded against COCO's full class
     * list has steps captioned `person, book`; the narrowed allowlist plus the
     * fine-tuned tool model can never say either word again, so every step
     * check on that guide fails a comparison against a model that no longer
     * exists -- and the screen says "still looking for person, book" over a
     * bench that is exactly right.
     *
     * Filtering the dead labels out (see [watchScene]) stops the false
     * complaint but leaves the photographs under-described: step one shows a
     * screwdriver the current model would find and the old one had no word
     * for. So the photographs are read again rather than edited around.
     *
     * Once per guide, ever: it runs only when a caption contains something the
     * detector cannot produce, which is exactly the definition of stale.
     *
     * @return true when anything changed, so the caller can reload.
     */
    suspend fun refreshCaptions(id: String): Boolean = withContext(Dispatchers.IO) {
        val vocabulary = detector.vocabulary()
        if (vocabulary.isEmpty()) return@withContext false
        val guide = guides.loadForLearner(id) ?: return@withContext false

        val stale = guide.steps.any { step ->
            // A guide written before `objects` existed has counts nobody
            // recorded, and re-reading the photograph is the only way to get
            // them -- so a missing list is as stale as a retired label.
            (step.caption.isNotBlank() && step.objects.isEmpty()) ||
                step.caption.split(",")
                    .map { it.trim().lowercase() }
                    .any { it.isNotBlank() && it !in vocabulary }
        }
        if (!stale) return@withContext false

        val byPhoto = guide.steps
            .filter { it.photo.isNotBlank() }
            .distinctBy { it.photo }
            .mapNotNull { step ->
                val file = guides.goalImage(id, step.photo) ?: return@mapNotNull null
                step.photo to runCatching { captioner.labels(file) }.getOrDefault(emptyList())
            }
            .toMap()
        if (byPhoto.isEmpty()) return@withContext false

        guides.recaption(id, byPhoto)
        Log.i(
            TAG,
            "recaptioned ${byPhoto.size} photos: " +
                byPhoto.values.joinToString(" | ") { it.joinToString(",") },
        )
        true
    }

    /** Detector labels from the photograph of the step being watched. */
    @Volatile
    private var expectedLabels: List<String> = emptyList()

    /**
     * Point at a named component, or say why not.
     *
     * Today every laptop part comes back Uncertain, because the loaded model is
     * generic COCO and has no label for one. Pushing a fine-tuned .tflite and
     * adding its labels to componentAliases in policy.json swaps that in with
     * no rebuild.
     */
    private val locator = ComponentLocator(
        aliases = { policy.value.componentAliases },
        minScore = { policy.value.componentMinScore },
    )

    fun locate(component: String): Localization = locator.locate(component, _detections.value)

    /** What the current detector claims to be able to find. For telemetry. */
    fun componentVocabulary(): Set<String> = locator.vocabulary()

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
    private var snapJob: Job? = null

    /**
     * The photo the live camera is compared against. The Player calls this
     * when the step changes, and with null when it leaves.
     */
    fun watchScene(photo: File?, expected: List<String> = emptyList()) {
        // Only labels this phone's detector could actually report. A guide
        // recorded against a wider allowlist still carries `person` and `book`
        // in its captions, and nothing loaded here will ever produce one --
        // so counting them as "not seen" turns a retired model into a
        // permanent complaint about the learner's bench. [refreshCaptions]
        // repairs the guide properly; this is the guard for the case it
        // cannot, such as a step whose photograph has been deleted.
        expectedLabels = detector.vocabulary()
            .takeIf { it.isNotEmpty() }
            ?.let { vocab -> expected.filter { it.trim().lowercase() in vocab } }
            ?: expected
        lastFrameHash = null
        // A new step is a new question. Nothing carried over from the last one
        // is evidence about this one.
        stepConfidence = StepConfidence(policy.value)
        _stepCheck.value = StepCheck.UNCERTAIN
        _mayAdvance.value = false
        _confidence.value = 0f
        _missingLabels.value = expected
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
                    // One list, drawn and judged. Held per label -- see [hold]
                    // -- and then used for both, because a box on screen that
                    // the check cannot see is the app arguing with itself in
                    // front of the person holding the phone.
                    val seen = hold(detector.onFrame(frame, 0), now)
                    _detections.value = seen
                    reference?.let {
                        _sceneSimilarity.value =
                            runCatching { ai.sceneCheck.compare(frame, it) }.getOrDefault(0f)
                    }
                    // The cascade, on the frame that is already decoded and
                    // already upright. Arithmetic only; no model is woken.
                    // Repeats intact: two boxes labelled philips_screw is two
                    // screws, and that is the difference the check turns on.
                    updateStepCheck(frame, seen.boxes.map { b -> b.label })
                    // And a copy for the coach, in case the learner asks about
                    // what is in front of them.
                    keepFrame(frame)
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

    /**
     * Feed one frame to the running check.
     *
     * Reuses the same 32x32 pixels SceneHash works on, so the cost is one small
     * scale and a dHash -- there is no second decode and no second model.
     */
    private fun updateStepCheck(frame: Bitmap, seen: List<String>) {
        val hash = runCatching { RealSceneCheck.hashOf(frame) }.getOrNull() ?: return
        val changed = lastFrameHash?.let { com.showhow.core.SceneHash.hamming(hash, it) }
        lastFrameHash = hash
        val inputs = CheckInputs(
            sceneSimilarity = _sceneSimilarity.value,
            // No previous frame yet: treat it as settled rather than as a
            // huge change, or the first frame after every step is wasted.
            frameToFrameChange = changed ?: 0,
            expected = expectedLabels,
            seen = seen,
        )
        // One accumulator, so the bar, the percentage and the page turn are
        // three readings of the same number rather than three opinions.
        _stepCheck.value = stepConfidence.update(inputs)
        _confidence.value = stepConfidence.value
        _mayAdvance.value = stepConfidence.mayAdvance
        _missingLabels.value = labelShortfall(expectedLabels, seen)
    }

    /**
     * Every background job in this class starts here, and the guarantee is the
     * one that matters on a stage: a job that throws logs and dies alone
     * instead of taking the app with it.
     *
     * `viewModelScope.launch` does not swallow anything -- an exception out of
     * one of these reaches the thread's uncaught handler, which is a crash
     * dialog in front of a judge. Twelve of them ran unguarded: the recorder
     * against a microphone another app was holding, the guide build against a
     * full disk, the coach against a model that would not load.
     */
    private fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = viewModelScope.launch(
        context + CoroutineExceptionHandler { _, t -> Log.e(TAG, "background job died", t) },
        block = block,
    )

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
        launch {
            policy.collect { p ->
                // Retune live. This is the whole point of policy.json.
                gate = AdaptiveGate(p)
                engine = ModeEngine(p)
                _debug.value = _debug.value.copy(policyError = policyRepo.lastError)
            }
        }
        motionJob = launch {
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
            "detector (tools)" to File(dir, DETECTOR_MODEL).isFile,
            "detector (coco)" to File(dir, DETECTOR_MODEL_COCO).isFile,
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

        levelJob = launch {
            recorder.levels.collect { db -> onLevel(db.toDouble()) }
        }
        startLiveTranscript()
        recordJob = launch {
            recorder.record(guides.takeFile(id))
        }
        // First photo now, so step one always has a picture even if the expert
        // starts talking before the camera settles.
        snap()
        startPeriodicCapture()
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
        pcmJob = launch(Dispatchers.Default) {
            recorder.pcm.collect { chunk ->
                stream.feed(chunk)?.let {
                    _liveTranscript.value = correctDomainText(it, policy.value.domainWords)
                }
            }
        }
    }

    /**
     * Keep a frame every couple of seconds for the whole take.
     *
     * The expert is doing a job, not making a film, and should not have to
     * think about when to photograph anything. Frames are kept throughout and
     * the choice is made afterwards, when the transcript exists and the steps
     * are known -- see core/pickFrames. The unchosen ones are deleted at the
     * end, so a guide costs a few megabytes rather than fifty.
     *
     * The alternative, and what this replaces, was photographing only at
     * detected pauses. That reliably produced a picture of the expert pausing:
     * hands away from the work, looking at the phone. The frame worth showing
     * is halfway through the sentence, and there is no going back for it.
     */
    private fun startPeriodicCapture() {
        snapJob?.cancel()
        snapJob = launch {
            while (_debug.value.recording) {
                kotlinx.coroutines.delay(policy.value.snapIntervalMs.coerceAtLeast(250))
                if (!_debug.value.recording) break
                if (snapTimesMs.size >= policy.value.maxSnaps) {
                    android.util.Log.i(TAG, "snap cap reached, capture stops; the take continues")
                    break
                }
                snap()
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
        snapJob?.cancel()
        snapJob = null
        stopLiveTranscript()
        _debug.value = _debug.value.copy(recording = false)
        if (id == null) {
            go(Screen.Library)
            return
        }
        _buildProgress.value = BuildStage.TRANSCRIBING
        go(Screen.Processing)
        launch {
            // The expert has just done the job once and talked through it, and
            // the take is already on disk. Whatever goes wrong from here -- a
            // full disk, a model that will not load, a photograph that will not
            // decode -- must not end with them looking at a Processing screen
            // that never moves, and must not lose the recording.
            val built = runCatching { buildGuide(id) }.getOrElse { t ->
                Log.e(TAG, "building $id failed, falling back to the raw take", t)
                salvageGuide(id)
            }
            _buildProgress.value = BuildStage.DONE
            go(Screen.Review(built))
        }
    }

    /**
     * The guide you get when building one properly did not work.
     *
     * One step spanning the whole take, no photograph, no transcript -- and the
     * expert's own audio, which is the part that cannot be regenerated and the
     * part they actually came to record. Everything else in a guide can be
     * rebuilt from it later; the ninety seconds cannot.
     *
     * Saved under the same id, so the Review screen opens on it and Split still
     * works. If even this write fails there is nothing left to do but say so --
     * the folder is still on disk with the WAV in it.
     */
    private fun salvageGuide(id: String): String {
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
        val guide = Guide(
            id = id,
            title = "Unfinished recording",
            lang = _lang.value,
            createdAt = System.currentTimeMillis(),
            steps = listOf(
                Step(
                    index = 0,
                    title = "Step 1",
                    startMs = 0,
                    endMs = durationMs,
                    modeHint = "This guide could not be built. The recording is here in full.",
                ),
            ),
        )
        if (!guides.save(guide)) Log.e(TAG, "could not even save the salvage guide for $id")
        refreshLibrary()
        return id
    }

    private suspend fun buildGuide(id: String): String = withContext(Dispatchers.Default) {
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
            // Bounded here rather than inside each recogniser, because the bound
            // belongs to the guide build and not to any one model: a 2.7 GB
            // Kaldi graph decoding a long take on the CPU is the case, and an
            // unbounded wait is a Processing screen that never moves.
            withTimeoutOrNull(p.asrTimeoutMs) {
                runCatching { ai.asr.transcribe(guides.takeFile(id), _lang.value) }
                    .getOrDefault(emptyList())
            }.orEmpty().let { corrected(it, p.domainWords) }
        } else {
            emptyList()
        }
        if (timed && words.isEmpty()) Log.w(TAG, "no words from the recogniser for $id")

        _buildProgress.value = BuildStage.CUTTING
        val confirmer = LinkWordConfirmer(
            p.linkWords(_lang.value),
            words.map { SpokenWord(it.text, it.startMs, it.endMs) },
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
        val stats = frameStats(id, words.map { SpokenWord(it.text, it.startMs, it.endMs) }, p)
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
            // One detector pass, kept twice: the line a person reads and the
            // list with its repeats intact, which is what the step check needs
            // to tell one screw out from two.
            val found = photo?.let { captioner.labels(it) }.orEmpty()
            Step(
                index = r.index,
                title = "Step ${r.index + 1}",
                caption = captionOf(found),
                objects = found,
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
        // The coach is the largest model in the app by two orders of magnitude
        // and the only one whose load time has never been measured. Absent, it
        // is a correct no-op and the steps stay in the expert's own words --
        // so a coach that is merely slow gets to be absent too, rather than
        // holding the whole build open behind it.
        val (coachTitle, coached) =
            withTimeoutOrNull(p.coachTimeoutMs) { coachSteps(steps, words) }
                ?: run {
                    Log.w(TAG, "coach did not answer within ${p.coachTimeoutMs} ms")
                    "" to steps
                }

        _buildProgress.value = BuildStage.SAVING
        val guide = Guide(
            id = id,
            // The coach names the job from what actually happened. Blank
            // when there is no coach or it would not commit to one, and the
            // expert renames it on the Review screen either way.
            title = coachTitle.ifBlank { "New job" },
            lang = _lang.value,
            createdAt = System.currentTimeMillis(),
            steps = coached,
        )
        if (!guides.save(guide)) Log.e(TAG, "could not save $id")
        // Everything the picker passed over. Capturing densely and keeping it
        // all would cost fifty megabytes a guide; the take, the chosen frames
        // and guide.json are what a guide actually is.
        discardUnusedSnaps(id, steps.map { it.photo }.filter { it.isNotBlank() }.toSet())
        refreshLibrary()
        id
    }

    /** Delete captured frames no step ended up using. */
    private fun discardUnusedSnaps(id: String, kept: Set<String>) {
        var freed = 0L
        for (i in snapTimesMs.indices) {
            val f = guides.snapFile(id, i)
            if (f.name !in kept && f.isFile) {
                freed += f.length()
                f.delete()
            }
        }
        if (freed > 0) android.util.Log.i(TAG, "freed ${freed / 1024} KB of unused frames")
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
    private fun frameStats(
        id: String,
        spoken: List<SpokenWord>,
        p: Policy,
    ): List<FrameStats> {
        // Everything the expert might name while showing it. The tools they
        // work with and the parts they work on, from policy.json, so a
        // different trade needs no rebuild.
        val named = (p.toolWords + p.domainWords).map { it.lowercase() }.toSet()
        return snapTimesMs.mapIndexedNotNull { i, tMs ->
            val file = guides.snapFile(id, i)
            if (!file.isFile) return@mapIndexedNotNull null
            val bmp = decodeUpright(file, 4) ?: return@mapIndexedNotNull null
            try {
                // Two kinds of evidence that this frame is worth showing.
                //
                // Boxes: something the detector recognises is in shot. A count
                // and never a claim about what the thing was.
                //
                // Words: the expert named something around this moment. That
                // covers every tool and part no model on this phone can see,
                // which on a real bench is most of them.
                frameStatsOf(bmp, i, tMs, detector.onFrame(bmp, 0).boxes.size).copy(
                    namedThings = namedNear(tMs, spoken, named, p.frameSpokenWindowMs),
                )
            } finally {
                bmp.recycle()
            }
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
    private suspend fun coachSteps(steps: List<Step>, words: List<Word>): Pair<String, List<Step>> {
        if (!coach.present) return "" to steps
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
        val out = runCatching { coach.rewrite(jobFrom(steps), take) }
            .getOrElse {
                android.util.Log.w(TAG, "coach pass failed, keeping the raw steps", it)
                com.showhow.ai.CoachGuide("", emptyList())
            }
        val written = out.steps
        return out.title to steps.mapIndexed { i, s ->
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
        recordJob = launch {
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
    /**
     * Delete a guide and everything in it.
     *
     * The take, the frames, the re-recorded clips and both copies of the JSON.
     * There is no undo and no bin: a guide is a folder, and a phone that fills
     * up with test recordings the night before a demo is a phone nobody can
     * record on. The Library asks twice before calling this.
     */
    fun deleteGuide(id: String) {
        guides.delete(id)
        if (_editing.value?.id == id) _editing.value = null
        refreshLibrary()
    }

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
        /**
         * How well supported the answer is, after grounding. The Player says so
         * in words; it never hides the answer or a control because of it.
         */
        val evidence: AnswerEvidence = AnswerEvidence.UNCERTAIN,
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
        coachJob = launch {
            // Assembled here because this is the only place that knows all
            // three: the guide on disk, the step the learner is looking at, and
            // what the detector is reporting through the camera this second.
            val context = learnerContext(
                guide = g.copy(title = g.title.ifBlank { jobFrom(g.steps) }),
                stepIndex = stepIndex,
                question = question,
                // What both detectors are reporting this second -- which now
                // includes `screwdriver` and the screw heads, not just COCO's
                // furniture. Empty when the camera is off.
                seenNow = _detections.value.boxes.map { it.label },
                toolWords = policy.value.toolWords,
                // Same filter as the step check. Telling the model "the
                // detector saw a book in this step's photo" is a false claim
                // about a photograph, and it is the sort of false claim that
                // comes back out of a 2B model as advice.
                detectable = detector.vocabulary(),
            )
            // With the camera on, the coach is handed a private copy of the
            // frame and can answer about the thing in the learner's hand --
            // which kind of screwdriver it is, whether it matches the screws.
            // The labels above say *that* a screwdriver is in shot; the picture
            // is what lets the coach say which one, and answer a question no
            // label list anticipated.
            val frame = frameForCoach()
            // And the expert's own picture of this step beside it, so the
            // question the learner actually asked -- "is this the thing in the
            // guide?" -- is one the model can answer by looking rather than by
            // reading a label list. Null when the step has no photograph, and
            // then the coach describes the bench instead of comparing it.
            val goal = guidePhoto(g, stepIndex)
            val (evidence, text) = try {
                if (frame != null) coach.see(context, frame, goal) else coach.answer(context)
            } finally {
                // Ours alone, so freeing them here cannot pull the pixels out
                // from under anything else.
                runCatching { frame?.recycle() }
                runCatching { goal?.recycle() }
            }
            if (text.isBlank()) {
                // No model, or it failed. Say so rather than showing an empty
                // card that looks like the app hung.
                _coachAnswer.value = CoachAnswer(question, thinking = false)
                return@launch
            }
            _coachAnswer.value = CoachAnswer(question, text, thinking = false, evidence = evidence)
            // English, because that is what the coach answers in -- reading it
            // with a Hindi voice would mangle every word.
            if (_readAloud.value) narrator.speak(text, "en")
        }
    }

    // --- listening in another language -------------------------------------

    /**
     * The language the synthetic voice reads in, which need not be the language
     * the guide was recorded in.
     *
     * Separate from [lang], which is what the *expert spoke* and is a fact about
     * the recording. This is what the *learner wants to hear* and is a
     * preference. Conflating them would have a Hindi listener's question
     * transcribed by the English recogniser.
     */
    private val _listenLang = MutableStateFlow("")
    val listenLang: StateFlow<String> = _listenLang.asStateFlow()

    /** True while Gemma is rendering a step into [listenLang]. */
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating.asStateFlow()

    fun setListenLang(code: String) {
        if (_listenLang.value == code) return
        // The old language's prefetch is now work nobody wants, and it holds
        // the model's one lock -- leaving it running is what makes the switch
        // feel slow, because the first sentence in the new language queues
        // behind however much of the guide was still being rendered.
        prefetchJob?.cancel()
        prefetchJob = null
        _listenLang.value = code
        narrator.stop()
    }

    /**
     * The step's text in the language the learner is listening in.
     *
     * Falls back to [text] whenever anything is missing -- no coach model, a
     * translation that came back empty, a language nobody asked to translate
     * into. Falling back to the original is always safe: it is the real step,
     * just not in their language. Returning nothing would be a silent guide.
     *
     * Cached into guide.json on the first pass, so the wait is once per step
     * per language, ever -- and [prefetchFrom] moves most of those waits off
     * the moment a learner is standing there listening.
     */
    suspend fun spokenIn(g: Guide, stepIndex: Int, text: String): String {
        val want = _listenLang.value
        val step = g.steps.getOrNull(stepIndex) ?: return text
        if (want.isBlank() || want == g.lang || text.isBlank()) return text

        step.translated[want]?.takeIf { it.isNotBlank() }?.let {
            prefetchFrom(g, stepIndex + 1, want)
            return it
        }
        if (!coach.present) return text

        _translating.value = true
        val out = try {
            translateInto(g, step, text, want)
        } finally {
            _translating.value = false
        }
        prefetchFrom(g, stepIndex + 1, want)
        return out.ifBlank { text }
    }

    /**
     * Render one step into [want] and write it back to the guide. "" on any
     * failure, which the caller reads as "show the original".
     */
    private suspend fun translateInto(g: Guide, step: Step, text: String, want: String): String {
        val out = runCatching { coach.translate(text, want) }.getOrDefault("")
        if (out.isBlank()) return ""

        // Cache it, so this step is instant from now on. A write that fails is
        // a slower guide, not a broken one.
        //
        // Re-read before writing. [g] is the copy the Player screen captured
        // when it opened, and every step translated after the first would
        // otherwise be written on top of that same stale snapshot -- so step
        // two's translation erased step one's, and a five-step guide kept
        // exactly one. Whatever is on disk now is the base.
        runCatching {
            val current = guides.load(g.id) ?: g
            val updated = current.copy(
                steps = current.steps.map {
                    if (it.index == step.index) it.copy(translated = it.translated + (want to out))
                    else it
                },
            )
            guides.save(updated)
        }
        return out
    }

    private var prefetchJob: Job? = null

    /**
     * Translate the rest of the guide while the learner is still listening to
     * this step.
     *
     * The whole complaint about switching language was the wait, and the wait
     * is a 2B model rendering one sentence -- seconds, once per step, always
     * at the exact moment somebody is standing there holding a screwdriver
     * waiting to be told what to do. Every one of those seconds is available
     * for free while the previous step is being read aloud.
     *
     * One job, in order, behind the coach's own lock, and abandoned the moment
     * the learner picks a different language. It never speaks and never
     * touches [translating]: a background job that lit up "Putting it into
     * Hindi" would be reporting on work nobody is waiting for.
     */
    private fun prefetchFrom(g: Guide, from: Int, want: String) {
        if (prefetchJob?.isActive == true) return
        if (from > g.steps.lastIndex || !coach.present) return
        // Off the main thread: this reads and rewrites guide.json once per
        // step, and the learner is watching an animation while it happens.
        prefetchJob = launch(Dispatchers.IO) {
            for (i in from..g.steps.lastIndex) {
                // Re-read each time: the foreground translation of the step
                // being read writes into the same file, and prefetching on a
                // stale snapshot would overwrite it.
                val fresh = guides.load(g.id) ?: return@launch
                if (_listenLang.value != want) return@launch
                val s = fresh.steps.getOrNull(i) ?: return@launch
                if (!s.translated[want].isNullOrBlank()) continue
                val text = s.instruction
                    .ifBlank { s.transcript }
                    .ifBlank { s.caption }
                    .ifBlank { s.title }
                if (text.isNotBlank()) translateInto(fresh, s, text, want)
            }
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
        questionJob = launch {
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
            val terms = policy.value.domainWords
            val fromDevice = deviceAsr?.transcribeText(slice, _lang.value).orEmpty()
            val fillers = policy.value.fillerWords.map { it.lowercase() }.toSet()
            if (fromDevice.isNotBlank()) {
                // No word clocks on this path, so the pauses are not there to
                // punctuate with. Hesitations still go.
                stripFillers(correctDomainText(fromDevice, terms), fillers)
            } else {
                // The system engine said nothing. That is either a quiet step
                // or an engine that will not take a file, and from here the two
                // look identical -- so ask the one that always answers rather
                // than hand back an empty guide.
                android.util.Log.i(TAG, "system engine returned nothing, falling back to vosk")
                stripFillers(
                    correctDomainText(
                        runCatching { voskAsr.transcribe(slice, _lang.value) }
                            .getOrDefault(emptyList())
                            .joinToString(" ") { it.text },
                        terms,
                    ),
                    fillers,
                )
            }
        } finally {
            slice.delete()
        }
    }

    /**
     * The domain corrector over timed words, with the clocks kept intact.
     *
     * A merge takes the first word's start and the last word's end, so a step
     * that ends on "screw driver" still ends where the expert stopped talking.
     * Confidence takes the lower of the two, because a pair is only as good as
     * its worse half.
     */
    private fun corrected(words: List<Word>, terms: List<String>): List<Word> {
        if (words.isEmpty() || terms.isEmpty()) return words
        val fixes = correctDomainTokens(words.map { it.text }, terms)
        val out = ArrayList<Word>(fixes.size)
        var i = 0
        for (fix in fixes) {
            val first = words[i]
            val last = words[i + fix.took - 1]
            out += Word(
                fix.text,
                first.startMs,
                last.endMs,
                minOf(first.confidence, last.confidence),
            )
            i += fix.took
        }
        return out
    }

    private fun wordsIn(words: List<Word>, startMs: Long, endMs: Long): List<Word> =
        words.filter { it.startMs >= startMs && it.startMs < endMs }

    /**
     * The step's words, as English rather than as recogniser output.
     *
     * Hesitations out, sentences in, using the pauses the expert actually left.
     * Nothing is added and no word is changed -- see [plainEnglish].
     */
    private fun transcriptFor(words: List<Word>, startMs: Long, endMs: Long): String {
        val p = policy.value
        return plainEnglish(
            wordsIn(words, startMs, endMs).map { SpokenWord(it.text, it.startMs, it.endMs) },
            p.fillerWords.map { it.lowercase() }.toSet(),
            p.sentenceGapMs,
        )
    }

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
        launch {
            runCatching { cam.takePhoto(guides.snapFile(id, index)) }
        }
    }

    override fun onCleared() {
        recorder.stop()
        motionJob?.cancel()
        policyRepo.stop()
        stopListening()
        synchronized(frameLock) {
            runCatching { lastFrame?.recycle() }
            lastFrame = null
        }
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
        /**
         * How big a frame the coach is shown.
         *
         * Gemma 3n resizes to its own encoder input anyway, so anything larger
         * is memory and copy time for nothing.
         */
        const val VISION_FRAME_PX = 512

        val WHITESPACE = Regex("\\s+")

        /** "Step 4" and nothing else. A title the app wrote, safe to rewrite. */
        val PLACEHOLDER_TITLE = Regex("""Step \d+""")

        const val TAG = "ShowHow"

        /** A WAV with only a header in it is a recording that never started. */
        const val WAV_HEADER_BYTES = 44L

        /** How long a box survives a frame the detector missed it in. */
        const val DETECTION_HOLD_MS = 500L

        /**
         * What the fine-tuned detector is believed for.
         *
         * Its `labels.txt` also lists laptop, person, hand, keyboard and mouse,
         * picked up with the tool datasets and backed by a handful of images
         * each. COCO runs beside it with tens of thousands per class, so those
         * are COCO's to answer and this list is deliberately shorter than the
         * model's own vocabulary. Grow it only when the model earns a class.
         */
        /**
         * What COCO is believed for, which is the room and nothing in a hand.
         *
         * Unrestricted, COCO calls a screwdriver "scissors" -- it has no
         * screwdriver class, scissors is the nearest elongated metal thing it
         * knows, and it says so at a confidence that clears any sane floor. It
         * does the same with knife, remote and toothbrush. None of those are
         * ever the subject of a laptop repair, and the fine-tuned model owns
         * everything held in a hand, so COCO is asked only about the furniture.
         *
         * **`person` is not here either.** A demonstrator's own arm is the
         * largest, easiest thing in almost every frame of this app, and COCO
         * boxed it at 0.7-plus across half the viewfinder -- competing with the
         * tool for a result slot and for the eye. Nobody watching a repair guide
         * needs to be told there is a person in it, and the hands are already
         * tracked properly by the gesture recognizer, which returns landmarks
         * rather than a rectangle over everything.
         */
        val COCO_LABELS = setOf(
            "laptop",
            "keyboard",
            "mouse",
            "tv",
            "cell phone",
        )

        val TOOL_LABELS = setOf(
            "screwdriver",
            "philips_screw",
            "pozidriv_screw",
            "torx_screw",
            "hex_screw",
            "square_screw",
        )

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
