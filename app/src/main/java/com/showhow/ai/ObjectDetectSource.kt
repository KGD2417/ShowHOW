package com.showhow.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.File

/**
 * One box to draw over the viewfinder. Coordinates are 0..1 of the frame, so
 * the UI never has to know what resolution the analyzer happens to be running
 * at.
 */
data class DetectionBox(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * What is in front of the camera, from MediaPipe's object detector.
 *
 * This exists for one reason: the boxes on screen have to be claims the app can
 * defend. A judge who asks "what produced 'laptop 0.74'?" gets an answer -- an
 * EfficientDet-Lite running on this phone, with the score it actually returned.
 * Drawing the same three labels every time would have been half a day cheaper
 * and would have been a lie.
 *
 * Same shape as [MediaPipeGestureSource] on purpose: same delegate ladder, same
 * fire-and-forget failure, same behaviour when the model is not on the phone --
 * an empty list, so the overlay simply draws nothing.
 *
 * ponytail: no tracking between frames, so a box can flicker on a borderline
 * score. Raise [minScore] first; a per-label DwellLatch is the upgrade if that
 * is not enough.
 */
class ObjectDetectSource(
    private val context: Context,
    private val modelFile: File,
    private val minScore: () -> Float,
    private val maxResults: Int = 4,
) : AutoCloseable {

    @Volatile
    private var detector: ObjectDetector? = null

    @Volatile
    private var dead = false

    /** Which delegate took the job, for the telemetry panel. */
    @Volatile
    var delegateName: String = "--"
        private set

    /**
     * One camera frame. Runs on the analysis executor, never the main thread.
     *
     * @param rotationDegrees the frame's rotation, so the boxes land the right
     *   way up. MediaPipe reports coordinates in the *rotated* image, which is
     *   why the normalisation below swaps width and height at 90 and 270.
     */
    fun onFrame(bitmap: Bitmap, rotationDegrees: Int): List<DetectionBox> {
        val d = detector() ?: return emptyList()
        val result = runCatching {
            d.detect(
                BitmapImageBuilder(bitmap).build(),
                ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build(),
            )
        }.getOrElse {
            Log.w(TAG, "detect failed, boxes are off", it)
            dead = true
            return emptyList()
        }

        val turned = rotationDegrees % 180 != 0
        val w = (if (turned) bitmap.height else bitmap.width).toFloat().coerceAtLeast(1f)
        val h = (if (turned) bitmap.width else bitmap.height).toFloat().coerceAtLeast(1f)
        val floor = minScore()

        return result.detections().mapNotNull { det ->
            val top = det.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            if (top.score() < floor) return@mapNotNull null
            val r = det.boundingBox()
            DetectionBox(
                label = top.categoryName().ifBlank { "object" },
                score = top.score(),
                left = r.left / w,
                top = r.top / h,
                right = r.right / w,
                bottom = r.bottom / h,
            )
        }
    }

    /** LOAD -> VERIFY -> INIT, once, down the delegate ladder. Each fall logged. */
    private fun detector(): ObjectDetector? {
        detector?.let { return it }
        if (dead) return null
        synchronized(this) {
            detector?.let { return it }
            if (dead) return null
            if (!modelFile.isFile) {
                Log.w(TAG, "no detector model at $modelFile, boxes are off")
                dead = true
                return null
            }
            for (delegate in LADDER) {
                val started = System.currentTimeMillis()
                val built = runCatching { build(delegate) }.getOrElse {
                    Log.w(TAG, "detector would not load on $delegate", it)
                    null
                }
                if (built != null) {
                    Log.i(TAG, "detector on $delegate in ${System.currentTimeMillis() - started} ms")
                    delegateName = delegate.name
                    detector = built
                    return built
                }
            }
            Log.w(TAG, "no delegate would take the detector, boxes are off")
            dead = true
            return null
        }
    }

    private fun build(delegate: Delegate): ObjectDetector =
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelFile.absolutePath)
                        .setDelegate(delegate)
                        .build(),
                )
                // IMAGE, for the same reason the gesture recognizer uses it: the
                // analyzer already hands over one frame at a time with
                // KEEP_ONLY_LATEST, so a second async queue only adds latency.
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(maxResults)
                .build(),
        )

    /** RELEASE. */
    override fun close() {
        synchronized(this) {
            runCatching { detector?.close() }
            detector = null
            dead = true
        }
    }

    private companion object {
        const val TAG = "ObjectDetect"
        val LADDER = listOf(Delegate.NPU, Delegate.GPU, Delegate.CPU)
    }
}

/** Path under filesDir. `.tflite` is gitignored, so expect it to be missing. */
const val DETECTOR_MODEL = "models/object_detector.tflite"
