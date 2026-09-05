package com.showhow.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.showhow.core.DwellLatch
import com.showhow.core.Policy
import java.io.File
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Hand signs from MediaPipe's gesture recognizer, so a guide can be walked with
 * wet or gloved hands and nothing gets touched.
 *
 * Frames arrive through [onFrame] from the one analyzer the ViewModel binds,
 * so the bitmap is converted once and shared with the scene check rather than
 * twice by two analyzers fighting over the same camera.
 *
 * The canned gesture classifier already emits Open_Palm / Closed_Fist /
 * Thumb_Up / Pointing_Up, so there is no landmark geometry to write here. What
 * it does not emit is a swipe: that is motion over time, not a pose, and it
 * would need its own tracker for two commands the palm and fist already cover.
 *
 * ponytail: no swipes. Add a landmark-x tracker if the palm/fist pair turns out
 * to be too coarse in front of a real user.
 *
 * The model is gitignored and will be missing on a fresh install, so every path
 * here is written around it being absent: no model means Gesture.NONE forever,
 * the Player keeps its buttons, and nothing calls a fake.
 */
class MediaPipeGestureSource(
    private val context: Context,
    private val modelFile: File,
    private val policy: () -> Policy,
) : GestureSource, AutoCloseable {

    private val _gestures = MutableSharedFlow<Gesture>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var recognizer: GestureRecognizer? = null
    private var latch = DwellLatch<Gesture>(policy().gestureDwellMs)
    private var dwellMs = policy().gestureDwellMs

    @Volatile
    private var dead = false

    override fun start(): Flow<Gesture> = _gestures

    /**
     * One camera frame. Runs on the analysis executor, never the main thread --
     * a miss here is an ANR in front of a jury. See CameraController.
     */
    fun onFrame(bitmap: Bitmap, rotationDegrees: Int) {
        val r = recognizer() ?: return
        val result = runCatching {
            r.recognize(
                BitmapImageBuilder(bitmap).build(),
                ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build(),
            )
        }.getOrElse {
            Log.w(TAG, "recognize failed, hand signs are off", it)
            dead = true
            return
        }
        val p = policy()
        if (p.gestureDwellMs != dwellMs) {
            // policy.json changed under us. This is the whole point of it.
            dwellMs = p.gestureDwellMs
            latch = DwellLatch(dwellMs)
        }
        val seen = best(result, p.gestureMinConfidence)
        latch.update(System.currentTimeMillis(), seen)?.let { _gestures.tryEmit(it) }
    }

    /** Highest scoring hand in the frame, if it clears the bar and we know it. */
    private fun best(
        result: com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult,
        minConfidence: Float,
    ): Gesture? = result.gestures()
        .flatten()
        .filter { it.score() >= minConfidence }
        .maxByOrNull { it.score() }
        ?.let { MAPPING[it.categoryName()] }

    /**
     * LOAD -> VERIFY -> INIT, once, down the delegate ladder. Each fall is
     * logged, because "it was slow on the demo phone" is unanswerable without
     * knowing which delegate actually took the job.
     */
    private fun recognizer(): GestureRecognizer? {
        recognizer?.let { return it }
        if (dead) return null
        synchronized(this) {
            recognizer?.let { return it }
            if (dead) return null
            if (!modelFile.isFile) {
                Log.w(TAG, "no gesture model at $modelFile, hand signs are off")
                dead = true
                return null
            }
            for (delegate in LADDER) {
                val started = System.currentTimeMillis()
                val built = runCatching { build(delegate) }.getOrElse {
                    Log.w(TAG, "gesture model would not load on $delegate", it)
                    null
                }
                if (built != null) {
                    Log.i(TAG, "gesture model on $delegate in ${System.currentTimeMillis() - started} ms")
                    recognizer = built
                    return built
                }
            }
            Log.w(TAG, "no delegate would take the gesture model, hand signs are off")
            dead = true
            return null
        }
    }

    private fun build(delegate: Delegate): GestureRecognizer =
        GestureRecognizer.createFromOptions(
            context,
            GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelFile.absolutePath)
                        .setDelegate(delegate)
                        .build(),
                )
                // IMAGE, not LIVE_STREAM: the analyzer already hands us one
                // frame at a time on its own thread with KEEP_ONLY_LATEST, so a
                // second async queue would only add latency to drop the same
                // frames twice.
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build(),
        )

    /** RELEASE. */
    override fun close() {
        synchronized(this) {
            runCatching { recognizer?.close() }
            recognizer = null
            dead = true
        }
    }

    private companion object {
        const val TAG = "GestureSource"

        /** Where the model is unzipped on the phone. Gitignored, never committed. */
        val LADDER = listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU)

        val MAPPING = mapOf(
            "Open_Palm" to Gesture.OPEN_PALM,
            "Closed_Fist" to Gesture.FIST,
            "Thumb_Up" to Gesture.THUMB_UP,
            "Pointing_Up" to Gesture.POINT,
        )
    }
}

/** Path under filesDir. `.task` is gitignored, so expect it to be missing. */
const val GESTURE_MODEL = "models/gesture_recognizer.task"

/** The real source if the model is on the phone, silence if it is not. */
fun gestureSourceOrNone(context: Context, modelFile: File, policy: () -> Policy): GestureSource =
    if (modelFile.isFile) MediaPipeGestureSource(context, modelFile, policy) else NoGestures

/**
 * What the production path falls back to. An empty flow, so the Player simply
 * keeps its buttons -- no fake palm every two seconds pretending a model loaded.
 */
object NoGestures : GestureSource {
    override fun start(): Flow<Gesture> = kotlinx.coroutines.flow.emptyFlow()
}
