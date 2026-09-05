package com.showhow.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.showhow.ai.AiStack
import com.showhow.ai.FakeCaptioner
import com.showhow.ai.FakeGestureSource
import com.showhow.ai.FakeSceneCheck
import com.showhow.ai.VoskAsr
import com.showhow.ai.Word
import com.showhow.capture.AudioRecorder
import com.showhow.capture.CameraController
import com.showhow.capture.MotionSource
import com.showhow.core.AdaptiveGate
import com.showhow.core.Mode
import com.showhow.core.ModeEngine
import com.showhow.core.ModeInputs
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

    /**
     * Speech is real. Captions, gestures and the scene check are still fakes --
     * phases 4, 6 and 7. Swapping one in is one line here and nothing else.
     */
    val ai: AiStack = AiStack(
        asr = VoskAsr.orNoop(File(app.filesDir, VoskAsr.MODEL_DIR)),
        captioner = FakeCaptioner(),
        gestures = FakeGestureSource(),
        sceneCheck = FakeSceneCheck(),
    )

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
    private var shots = 0

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
        shots = 0
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
        val steps = ranges.map { r ->
            val photo = guides.photoFile(id, r.index)
            Step(
                index = r.index,
                title = "Step ${r.index + 1}",
                caption = if (photo.exists()) ai.captioner.caption(photo) else "",
                startMs = r.startMs,
                endMs = r.endMs,
                photo = photo.name,
                transcript = transcriptFor(words, r.startMs, r.endMs),
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

    private fun pushMode(db: Double) {
        val committed = engine.update(
            System.currentTimeMillis(),
            ModeInputs(
                easyMode = _easyMode.value,
                accelVariance = _debug.value.accelVariance,
                dbfs = db,
                speechUnclear = false,
                userFar = false,
            ),
        )
        if (committed) {
            _debug.value = _debug.value.copy(
                mode = engine.mode,
                reason = engine.reason,
                switches = _debug.value.switches + 1,
            )
        }
    }

    private fun transcriptFor(words: List<Word>, startMs: Long, endMs: Long): String =
        words.filter { it.startMs >= startMs && it.startMs < endMs }
            .joinToString(" ") { it.text }

    private fun snap() {
        val id = currentId ?: return
        val cam = camera ?: return
        val index = shots++
        viewModelScope.launch {
            runCatching { cam.takePhoto(guides.photoFile(id, index)) }
        }
    }

    override fun onCleared() {
        recorder.stop()
        motionJob?.cancel()
        policyRepo.stop()
        // RELEASE, the last rung of the model ladder. A Vosk model is native
        // memory the GC cannot see.
        (ai.asr as? AutoCloseable)?.let { runCatching { it.close() } }
        super.onCleared()
    }

    private companion object {
        // ponytail: guides are Hindi until the Show screen offers the choice,
        // which is why linkWordsMr is still dead. One StateFlow when it does.
        const val LANG = "hi"
    }
}
