package com.showhow.core

/**
 * "Does the scene still look like the photo we saved?" in a couple of
 * milliseconds, with no model file: a 64-bit dHash for structure plus an HSV
 * histogram distance for colour.
 *
 * Advisory only. It returns a number; it never gets a vote on whether the user
 * may continue.
 *
 * Takes plain ARGB ints so it is testable on the JVM; the Bitmap adapter is
 * in ai/RealSceneCheck.
 */
object SceneHash {

    private const val W = 9
    private const val H = 8
    private const val HUE_BINS = 12
    private const val SAT_BINS = 3

    /** 64-bit difference hash of a downscaled grayscale image. */
    fun dHash(argb: IntArray, width: Int, height: Int): Long {
        var bits = 0L
        for (y in 0 until H) {
            for (x in 0 until W - 1) {
                val a = luma(sample(argb, width, height, x, y, W, H))
                val b = luma(sample(argb, width, height, x + 1, y, W, H))
                bits = (bits shl 1) or if (a > b) 1L else 0L
            }
        }
        return bits
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Normalised hue x saturation histogram. */
    fun hsvHistogram(argb: IntArray, width: Int, height: Int): DoubleArray {
        val hist = DoubleArray(HUE_BINS * SAT_BINS)
        if (argb.isEmpty()) return hist
        // Every 4th pixel: 16x fewer reads, same histogram to three decimals.
        val step = maxOf(1, (width * height) / 4096)
        var n = 0
        var i = 0
        while (i < width * height && i < argb.size) {
            val (h, s) = hueSat(argb[i])
            val hb = ((h / 360.0) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            val sb = (s * SAT_BINS).toInt().coerceIn(0, SAT_BINS - 1)
            hist[hb * SAT_BINS + sb] += 1.0
            n++
            i += step
        }
        if (n > 0) for (k in hist.indices) hist[k] /= n
        return hist
    }

    /** L1 distance, 0.0 identical .. 1.0 nothing in common. */
    fun histDistance(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0
        for (k in a.indices) d += kotlin.math.abs(a[k] - b[k])
        return (d / 2.0).coerceIn(0.0, 1.0)
    }

    /**
     * Similarity in 0.0 .. 1.0. Structure counts for 70%, colour for 30% --
     * a kitchen changes colour under a different bulb but keeps its shape.
     */
    fun similarity(
        liveHash: Long, savedHash: Long,
        liveHist: DoubleArray, savedHist: DoubleArray,
    ): Float {
        val structure = 1.0 - hamming(liveHash, savedHash) / 64.0
        val colour = 1.0 - histDistance(liveHist, savedHist)
        return (0.7 * structure + 0.3 * colour).coerceIn(0.0, 1.0).toFloat()
    }

    private fun sample(argb: IntArray, w: Int, h: Int, x: Int, y: Int, gw: Int, gh: Int): Int {
        if (argb.isEmpty() || w <= 0 || h <= 0) return 0
        val sx = ((x.toLong() * w) / gw).toInt().coerceIn(0, w - 1)
        val sy = ((y.toLong() * h) / gh).toInt().coerceIn(0, h - 1)
        return argb[(sy * w + sx).coerceIn(0, argb.size - 1)]
    }

    private fun luma(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    private fun hueSat(p: Int): Pair<Double, Double> {
        val r = ((p shr 16) and 0xFF) / 255.0
        val g = ((p shr 8) and 0xFF) / 255.0
        val b = (p and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val s = if (max <= 0.0) 0.0 else d / max
        val h = when {
            d == 0.0 -> 0.0
            max == r -> 60.0 * (((g - b) / d) % 6.0)
            max == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        return Pair(if (h < 0) h + 360.0 else h, s)
    }
}
