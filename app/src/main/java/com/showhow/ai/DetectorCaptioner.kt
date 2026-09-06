package com.showhow.ai

import android.graphics.ImageDecoder
import java.io.File

/**
 * Describes a step's photo by naming what is actually in it.
 *
 * This replaces the last canned answer in the app. FakeCaptioner cycled four
 * sentences about a coffee filter, which read fine in a mockup and read as a
 * lie the first time someone recorded a laptop repair -- the guide came back
 * saying "hand on the lid, turning it anticlockwise" over a picture of a
 * keyboard.
 *
 * So the caption is a list of the things the detector found, in the order it
 * was most sure about them. It is a plain description rather than a sentence,
 * which is the honest shape for what a box-and-label model actually knows.
 *
 * No model, no caption. An empty string is correct: the transcript already
 * carries what the step is, and every screen falls back to it.
 */
class DetectorCaptioner(private val detector: ObjectDetectSource) : Captioner {

    override suspend fun caption(jpg: File): String = captionOf(labels(jpg))

    /**
     * Every box the detector found in this photograph, one entry per box, most
     * confident first.
     *
     * With the repeats left in, which is the whole reason this exists beside
     * [caption]. Two philips heads is two entries, and that is what lets a step
     * check tell "both screws are out" from "one is". The caption drops them
     * because a list that reads "screw, screw, laptop" is not a description.
     */
    suspend fun labels(jpg: File): List<String> {
        if (!jpg.exists()) return emptyList()
        // The step photos are capped at 1280x960 and the detector downscales
        // anyway, so decoding at half size costs nothing and saves a few MB
        // of churn per guide.
        val bitmap = runCatching {
            // ImageDecoder honours the EXIF rotation CameraX writes; a sideways
            // photo detects a good deal less than an upright one.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpg)) { decoder, _, _ ->
                decoder.setTargetSampleSize(2)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return emptyList()

        return try {
            // The photo is already upright on disk, unlike a camera frame.
            detector.onFrame(bitmap, 0).boxes
                .sortedByDescending { it.score }
                .map { it.label }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * A detector's labels as a line a person reads. Distinct, and cut short.
 *
 * Separate from the labels themselves because they are two different things
 * with two different jobs: this one goes on a screen and into a prompt, the
 * list goes into an arithmetic comparison and keeps its repeats.
 */
fun captionOf(labels: List<String>): String =
    labels.distinct().take(MAX_THINGS).joinToString(", ")

/** Past three or four, a list stops being a description. */
private const val MAX_THINGS = 4
