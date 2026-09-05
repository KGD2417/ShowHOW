package com.showhow.ui

import android.app.Application
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.showhow.ai.AiStack
import com.showhow.ai.FakeCaptioner
import com.showhow.ai.FakeSceneCheck
import com.showhow.ai.GESTURE_MODEL
import com.showhow.ai.Gesture
import com.showhow.ai.MediaPipeGestureSource
import com.showhow.ai.VoskAsr
import com.showhow.ai.gestureSourceOrNone
import com.showhow.ai.Word
import com.showhow.capture.AudioRecorder
import com.showhow.capture.CameraController
import com.showhow.capture.MotionSource
import com.showhow.core.AdaptiveGate
import com.showhow.core.Mode
import com.showhow.core.ModeEngine
import com.showhow.core.ModeInputs
import com.showhow.core.mapSnapsToSteps
import com.showhow.core.LinkWordConfirmer
import com.showhow.core.Policy
import com.showhow.core.Sample
import com.showhow.core.SpokenWord
import com.showhow.core.StepCutter
import com.showhow.data.Guide
import com.showhow.data.GuideStore
import com.showhow.data.PolicyRepository
import com.showhow.data.Step
import java.io.File
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
    val policyError: String? = null,
)

class ShowHowViewModel(app: Application) : AndroidViewModel(app) {

    private val policyRepo = PolicyRepository(app).also { it.start() }
    val guides = GuideStore(File(app.filesDir, "guides"))

    private val gestureSource = gestureSourceOrNone(
        app,
        File(app.filesDir, GESTURE_MODEL),
    ) { policy.value }

    /** Speech and hand signs are real. Captions and the scene check are not yet. */
    val ai: AiStack = AiStack(
        asr = VoskAsr.orNoop(File(app.filesDir, VoskAsr.MODEL_DIR)),
        captioner = FakeCaptioner(),
        gestures = gestureSource,
        sceneCheck = FakeSceneCheck(),
    )

    /**
     * Hand signs for the Player to act on: OPEN_PALM next, FIST back, THUMB_UP
     * replay the audio. Empty when no model is on the phone, so the buttons
     * stay the way anyone reaches the end of a guide -- gestures are a second
     * way in, never the only one.
     */
    val gestures: Flow<Gesture> = gestureSource.start()

    /**
     * The one analyzer the camera binds. One frame, converted once, handed to
     * everything that wants it -- two analyzers would fight over the same
     * camera and convert the same bitmap twice.
     */
    val frameAnalyzer = ImageAnalysis.Analyzer { proxy ->
        try {
            val hands = gestureSource as? MediaPipeGestureSource
            if (hands != null) {
                runCatching { proxy.toBitmap() }.getOrNull()
                    ?.let { hands.onFrame(it, proxy.imageInfo.rotationDegrees) }
            }
        } finally {
            // Not closing it stalls the pipeline after two frames.
            proxy.close()
        }
    }

    val policy: StateFlow<Policy> = policyRepo.policy

    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _debug = MutableStateFlow(DebugState())
    val debug: StateFlow<DebugState> = _debug.asStateFlow()

    private val _library = MutableStateFlow<List<Guide>>(emptyList())
    val library: StateFlow<List<Guide>> = _library.asStateFlow()

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
        _debug.value = _debug.value.copy(recording = true, samples = 0, liveCuts = 0)

        levelJob = viewModelScope.launch {
            recorder.levels.collect { db -> onLevel(db.toDouble()) }
        }
        recordJob = viewModelScope.launch {
            recorder.record(guides.takeFile(id))
        }
        // First photo now, so step one always has a picture even if the expert
        // starts talking before the camera settles.
        snap()
    }

    /** @return the guide id, or null if nothing was recorded. */
    fun stopRecording(onDone: (String?) -> Unit) {
        val id = currentId
        recorder.stop()
        levelJob?.cancel()
        levelJob = null
        recordJob = null
        _debug.value = _debug.value.copy(recording = false)
        if (id == null) {
            onDone(null)
            return
        }
        viewModelScope.launch {
            onDone(buildGuide(id))
        }
    }

    private suspend fun buildGuide(id: String): String {
        val p = policy.value
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
        // The recogniser runs once over the whole take, so its word clock and
        // the sample log's clock are the same clock. An ASR that is off or
        // fails returns nothing, and the confirmer then abstains.
        val words = runCatching { ai.asr.transcribe(guides.takeFile(id)) }.getOrDefault(emptyList())
        val confirmer = LinkWordConfirmer(
            p.linkWords(LANG),
            words.map { SpokenWord(it.text, it.startMs) },
            p.confirmWindowMs,
            p.confirmMinLinkWords,
        )
        val ranges = StepCutter(p, confirmer).cut(samples.toList(), durationMs)
        // Photos were snapped at live boundaries; these are the final ones.
        // There are usually more of the former, so the pairing is by time.
        val snaps = mapSnapsToSteps(snapTimesMs.toList(), ranges)
        meanWordConfidence = meanConfidence(words.takeLast(p.speechUnclearWindowWords))
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
                transcript = transcriptFor(words, r.startMs, r.endMs),
                modeHint = modeHint(wordsIn(words, r.startMs, r.endMs), p),
            )
        }
        val guide = Guide(
            id = id,
            title = "Guide ${guides.ids().size + 1}",
            lang = LANG,
            createdAt = System.currentTimeMillis(),
            steps = steps,
        )
        guides.save(guide)
        refreshLibrary()
        return id
    }

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
     * Report the largest face the camera can see, in pixels. Wired to a
     * detector in phase 7; until one calls this, [faceHeightPx] stays 0 and
     * userFar stays false rather than being guessed at from brightness.
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
        viewModelScope.launch {
            runCatching { cam.takePhoto(guides.snapFile(id, index)) }
        }
    }

    override fun onCleared() {
        recorder.stop()
        motionJob?.cancel()
        policyRepo.stop()
        // RELEASE, the last rung of the model ladder. A Vosk model is native
        // memory the GC cannot see.
        (ai.asr as? AutoCloseable)?.let { runCatching { it.close() } }
        (gestureSource as? AutoCloseable)?.let { runCatching { it.close() } }
        super.onCleared()
    }

    private companion object {
        // ponytail: guides are Hindi until the Show screen offers the choice,
        // which is why linkWordsMr is still dead. One StateFlow when it does.
        const val LANG = "hi"
    }
}
