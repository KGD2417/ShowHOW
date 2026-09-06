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
 * A frame's worth of boxes, plus the shape of the frame they came from.
 *
 * The aspect ratio travels with them because the viewfinder centre-crops: the
 * preview scales the frame to cover the view and throws the overflow away, so
 * anything drawing these has to crop the same way or every box lands somewhere
 * the object is not.
 */
data class Detections(
    val boxes: List<DetectionBox> = emptyList(),
    val frameAspect: Float = 4f / 3f,
)

/**
 * One model in the stack: the file it lives in, and the floor it needs.
 *
 * Two floors and not one, because the two models are not equally sure of
 * themselves. COCO is off-the-shelf EfficientDet with tens of thousands of
 * images behind every class and is worth believing at 0.5. The fine-tuned tool
 * detector has 875 images behind six classes and calls a screwdriver it has
 * correctly found 0.3. One shared floor either hides the screwdriver or fills
 * the glass with COCO's guesses.
 */
data class DetectorModel(
    val file: File,
    /**
     * The only labels this model is believed for, or null for all of them.
     *
     * The fine-tuned model's `labels.txt` also carries laptop, person, keyboard
     * and mouse -- a handful of images each, scraped along with the tool
     * datasets. COCO has tens of thousands of each and is simply better at
     * them, so the fine-tuned model is held to what it was actually trained
     * for and the overlap never produces two boxes on one laptop.
     */
    val labels: Set<String>? = null,
    val minScore: () -> Float,
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
 * **Two models run per frame, not one.** Fine-tuning for the tools threw away
 * the eighty classes COCO had, and a bench with no laptop on it is not much of
 * a laptop repair guide. So the stock COCO detector runs beside the fine-tuned
 * one and the two sets of boxes are concatenated -- a philips head on a laptop
 * lid is two true boxes, not a contradiction, and nothing here has to choose
 * between them.
 *
 * Each model is held to its own labels ([DetectorModel.labels]) and its own
 * floor, which is what keeps the seam clean: the fine-tuned model answers for
 * the screwdriver and the screw heads, COCO answers for the room, and neither
 * is asked about the other's subject.
 *
 * A model missing from the phone is skipped with a line in logcat and the other
 * carries on -- push only COCO and the tools go quiet, push only the tools and
 * the laptop does. Both missing is an empty list, and the overlay draws nothing.
 *
 * Same shape as [MediaPipeGestureSource] on purpose: same delegate ladder, same
 * fire-and-forget failure.
 *
 * ponytail: two models is two inferences per frame, ~36 ms of NPU rather than
 * 18. That fits the analyzer's interval; if it stops fitting, drop COCO to
 * every other frame before doing anything cleverer. Boxes also do not track
 * between frames, so a borderline score can flicker -- raise the floor first, a
 * per-label DwellLatch is the upgrade if that is not enough.
 */
class ObjectDetectSource(
    private val context: Context,
    private val models: List<DetectorModel>,
    private val maxResults: Int = 4,
) : AutoCloseable {

    /** Each loaded model with the floor it is judged by. Null until first frame. */
    @Volatile
    private var loaded: List<Pair<ObjectDetector, DetectorModel>>? = null

    @Volatile
    private var dead = false

    /** Which delegate(s) took the job, for the telemetry panel. */
    @Volatile
    var delegateName: String = "--"
        private set

    /**
     * One camera frame, through every loaded model.
     *
     * @param rotationDegrees the frame's rotation, so the boxes land the right
     *   way up. MediaPipe reports coordinates in the *rotated* image, which is
     *   why the normalisation below swaps width and height at 90 and 270.
     */
    fun onFrame(bitmap: Bitmap, rotationDegrees: Int): Detections {
        val running = detectors() ?: return Detections()

        val turned = rotationDegrees % 180 != 0
        val w = (if (turned) bitmap.height else bitmap.width).toFloat().coerceAtLeast(1f)
        val h = (if (turned) bitmap.width else bitmap.height).toFloat().coerceAtLeast(1f)

        // Wrapped once and handed to both: it is the same pixels either way, and
        // a second wrap is a second copy for nothing.
        val image = BitmapImageBuilder(bitmap).build()
        val options = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()

        val boxes = running.flatMap { (detector, model) ->
            val result = runCatching { detector.detect(image, options) }.getOrElse {
                Log.w(TAG, "detect failed, boxes are off", it)
                dead = true
                return Detections()
            }
            val floor = model.minScore()
            result.detections().mapNotNull { det ->
                val top = det.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                if (top.score() < floor) return@mapNotNull null
                val name = top.categoryName().ifBlank { "object" }
                if (model.labels?.contains(name) == false) return@mapNotNull null
                val r = det.boundingBox()
                DetectionBox(
                    label = name,
                    score = top.score(),
                    left = r.left / w,
                    top = r.top / h,
                    right = r.right / w,
                    bottom = r.bottom / h,
                )
            }
        }
        return Detections(boxes, w / h)
    }

    /** LOAD -> VERIFY -> INIT, once, each model down the delegate ladder. */
    private fun detectors(): List<Pair<ObjectDetector, DetectorModel>>? {
        loaded?.let { return it }
        if (dead) return null
        synchronized(this) {
            loaded?.let { return it }
            if (dead) return null

            val built = mutableListOf<Pair<ObjectDetector, DetectorModel>>()
            val delegates = mutableListOf<String>()
            for (model in models) {
                if (!model.file.isFile) {
                    Log.w(TAG, "no model at ${model.file}, running without it")
                    continue
                }
                for (delegate in LADDER) {
                    val started = System.currentTimeMillis()
                    val one = runCatching { build(model.file, delegate) }.getOrElse {
                        Log.w(TAG, "${model.file.name} would not load on $delegate", it)
                        null
                    } ?: continue
                    Log.i(
                        TAG,
                        "${model.file.name} on $delegate in ${System.currentTimeMillis() - started} ms",
                    )
                    built += one to model
                    delegates += delegate.name
                    break
                }
            }

            if (built.isEmpty()) {
                Log.w(TAG, "no model would load, boxes are off")
                dead = true
                return null
            }
            delegateName = delegates.distinct().joinToString("+")
            loaded = built
            return built
        }
    }

    private fun build(file: File, delegate: Delegate): ObjectDetector =
        ObjectDetector.createFromOptions(
            context,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(file.absolutePath)
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
            loaded?.forEach { (detector, _) -> runCatching { detector.close() } }
            loaded = null
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

/**
 * The stock COCO detector, run alongside the fine-tuned one.
 *
 * Its own file rather than something swapped over [DETECTOR_MODEL], so both can
 * be on the phone at once -- which is the whole point.
 */
const val DETECTOR_MODEL_COCO = "models/object_detector_coco.tflite"
