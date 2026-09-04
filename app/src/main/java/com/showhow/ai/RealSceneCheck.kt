package com.showhow.ai

import android.graphics.Bitmap
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
