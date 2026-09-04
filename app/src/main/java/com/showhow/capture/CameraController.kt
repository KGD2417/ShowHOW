package com.showhow.capture

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Preview, ImageAnalysis and ImageCapture bound together, because the Show
 * screen needs all three at once: something to look at, frames for the scene
 * check and gestures, and a still photo per step.
 */
class CameraController(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null

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

            val preview = Preview.Builder().build()
                .also { it.surfaceProvider = view.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    analyzer?.let { a.setAnalyzer(ContextCompat.getMainExecutor(context), it) }
                }

            val still = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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

    fun unbind() {
        provider?.unbindAll()
        provider = null
        capture = null
    }
}
