package com.showhow.ai

import android.graphics.Bitmap
import com.showhow.core.FrameStats
import com.showhow.core.SceneHash

/**
 * Real, shipping tonight, no model file: dHash for structure plus an HSV
 * histogram for colour. A couple of milliseconds on a mid-range phone.
 *
 * Advisory only. The Player uses the number to say "this doesn't look like the
 * photo" -- it never stops anyone from moving on.
 */
class RealSceneCheck : SceneCheck {

    override fun compare(live: Bitmap, saved: Bitmap): Float {
        val a = pixels(live)
        val b = pixels(saved)
        return SceneHash.similarity(
            SceneHash.dHash(a, SIZE, SIZE), SceneHash.dHash(b, SIZE, SIZE),
            SceneHash.hsvHistogram(a, SIZE, SIZE), SceneHash.hsvHistogram(b, SIZE, SIZE),
        )
    }

    private fun pixels(bmp: Bitmap): IntArray {
        val small = bmp.scale(SIZE, SIZE)
        val out = IntArray(SIZE * SIZE)
        small.getPixels(out, 0, SIZE, 0, 0, SIZE, SIZE)
        if (small !== bmp) small.recycle()
        return out
    }

    private fun Bitmap.scale(w: Int, h: Int): Bitmap =
        if (width == w && height == h) this else Bitmap.createScaledBitmap(this, w, h, true)

    private companion object {
        // 32x32 is plenty for both a 9x8 dHash and a 36-bin histogram.
        const val SIZE = 32
    }
}

/**
 * Measure one snapped frame so [com.showhow.core.pickFrames] can judge it.
 *
 * The only Android in the whole business of choosing a photograph: pixels out
 * of a Bitmap and into four numbers. Everything that decides anything is
 * arithmetic in core, on those numbers, testable without a phone.
 *
 * Measured at 64x64 rather than the scene check's 32x32, because sharpness is
 * a question about fine detail and 32 pixels of a workbench is all fine detail.
 * The dHash still samples its own 9x8 grid off this, unchanged.
 *
 * @param detections how many boxes the detector returned for this frame, or 0.
 *   A count, never a claim about what was in shot -- the loaded model knows
 *   COCO classes and nothing about a RAM module.
 */
fun frameStatsOf(bitmap: Bitmap, snapIndex: Int, tMs: Long, detections: Int): FrameStats {
    val size = MEASURE_SIZE
    val small = if (bitmap.width == size && bitmap.height == size) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, size, size, true)
    }
    val px = IntArray(size * size)
    small.getPixels(px, 0, size, 0, 0, size, size)
    if (small !== bitmap) small.recycle()
    return FrameStats(
        snapIndex = snapIndex,
        tMs = tMs,
        sharpness = SceneHash.sharpness(px, size, size),
        meanLuma = SceneHash.meanLuma(px),
        dHash = SceneHash.dHash(px, size, size),
        detections = detections,
    )
}

private const val MEASURE_SIZE = 64
