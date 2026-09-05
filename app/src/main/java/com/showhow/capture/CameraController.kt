package com.showhow.capture

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Preview, ImageAnalysis and ImageCapture bound together, because the Show
 * screen needs all three at once: something to look at, frames for the scene
 * check and gestures, and a still photo per step.
 */
class CameraController(private val context: Context) {

    private companion object {
        /** Big enough to read on a phone, small enough to keep a guide portable. */
        val PHOTO_SIZE = Size(1280, 960)

        /** What the gesture and object models actually want. */
        val ANALYSIS_SIZE = Size(640, 480)
    }

    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

    /**
     * Frame analysis runs here, not on the main thread. A gesture model on a
     * five-year-old phone takes tens of milliseconds a frame; on the main
     * executor that is a dropped-frame preview and, eventually, an ANR in front
     * of a jury. One thread, so KEEP_ONLY_LATEST still means what it says.
     */
    private var analysisExecutor: ExecutorService? = null

    /** The view to drop inside an AndroidView { }. */
    fun previewView(): PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    fun bind(
        owner: LifecycleOwner,
        view: PreviewView,
        analyzer: ImageAnalysis.Analyzer? = null,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p

            // All three use cases are pinned to 4:3 so they see the same slice
            // of the sensor. Left alone CameraX gives Preview 16:9 and analysis
            // 4:3, and then boxes are computed from a wider field of view than
            // the one on screen. No arithmetic in the overlay can rescue a box
            // drawn from a different picture.
            val preview = Preview.Builder()
                .setResolutionSelector(fourThree().build())
                .build()
                .also { it.surfaceProvider = view.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // The models downscale their input anyway, so a 12 MP frame is
                // 12 MP of copying and colour conversion thrown away every
                // frame. This is the single cheapest thing that keeps gesture
                // latency sane on an old phone.
                .setResolutionSelector(resolution(ANALYSIS_SIZE))
                .build()
                .also { a ->
                    analyzer?.let {
                        val ex = Executors.newSingleThreadExecutor()
                        analysisExecutor = ex
                        a.setAnalyzer(ex, it)
                    }
                }

            val still = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // A step photo is looked at inside a phone-width card. At full
                // sensor resolution the first real take on the demo phone put
                // 4.3 MB on disk per step, which is 50 MB for a ninety second
                // guide and makes "a guide you can drag between phones" a lie.
                .setResolutionSelector(resolution(PHOTO_SIZE))
                .build()
            capture = still

            p.unbindAll()
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, still)
        }, ContextCompat.getMainExecutor(context))
    }

    suspend fun takePhoto(out: File): File = suspendCancellableCoroutine { cont ->
        val c = capture
        if (c == null) {
            cont.resumeWithException(IllegalStateException("camera not bound"))
            return@suspendCancellableCoroutine
        }
        out.parentFile?.mkdirs()
        c.takePicture(
            ImageCapture.OutputFileOptions.Builder(out).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    cont.resume(out)
                }

                override fun onError(e: ImageCaptureException) {
                    cont.resumeWithException(e)
                }
            },
        )
    }

    private fun fourThree(): ResolutionSelector.Builder =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)

    /** 4:3, at the nearest available size to the one asked for. */
    private fun resolution(size: Size): ResolutionSelector =
        fourThree()
            .setResolutionStrategy(
                ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()

    fun unbind() {
        provider?.unbindAll()
        provider = null
        capture = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}
