package com.showhow.ai

/**
 * Where a named component is on screen, or why the app cannot say.
 *
 * There is no third case and deliberately no "probably here" box. A rectangle
 * drawn over a laptop labelled "RAM" is a claim, and a learner with a
 * screwdriver will act on it; if the detector did not report it, nothing here
 * will draw it.
 */
sealed interface Localization {

    /** The loaded detector actually returned this, above the score floor. */
    data class Found(val label: String, val score: Float, val box: DetectionBox) : Localization

    /**
     * Nothing can be highlighted, and [reason] says which kind of nothing.
     *
     * A learner is shown the step's saved photograph instead, which is a real
     * picture of the right answer taken by the expert -- a better fallback than
     * a guess, and the reason [Uncertain] is never a failure state.
     */
    data class Uncertain(val requested: String, val reason: String) : Localization
}

/**
 * Ask the loaded object detector to point at a named component.
 *
 * **The detector on this phone today is generic COCO.** It knows "laptop",
 * "keyboard", "mouse", "person". It has no label for a RAM module, an SSD, a
 * heatsink, a screw or a screwdriver, and it will never acquire one by being
 * asked more politely. Every one of those requests comes back [Uncertain] with
 * that said in words, and no box is drawn.
 *
 * The swap is a file and a config edit, no rebuild:
 *
 *   1. push a fine-tuned `.tflite` over `filesDir/models/object_detector.tflite`
 *   2. add its labels to `componentAliases` in policy.json
 *
 * The aliases are what make it swappable rather than hardcoded. MediaPipe's
 * ObjectDetector cannot be asked what vocabulary a model has before running it,
 * so the app cannot discover the answer -- it has to be told, and policy.json is
 * where this project tells it things during the hours nobody can compile. An
 * empty alias list for a component is the honest state today and reads as "this
 * detector has no label for that", which is exactly true.
 */
class ComponentLocator(
    /** component name -> the detector labels that mean it. From policy.json. */
    private val aliases: () -> Map<String, List<String>>,
    /** Below this a box is not worth pointing at. From policy.json. */
    private val minScore: () -> Float,
) {

    /**
     * @param seen what the detector returned for the current frame. Empty when
     *   the camera is off or no model is on the phone -- both [Uncertain], and
     *   distinguished in the reason, because "nothing is looking" and "it looked
     *   and did not find it" are different things to tell someone.
     */
    fun locate(component: String, seen: Detections): Localization {
        val want = component.trim().lowercase()
        if (want.isEmpty()) return Localization.Uncertain(component, "no component asked for")

        val labels = aliases()[want].orEmpty()
        if (labels.isEmpty()) {
            return Localization.Uncertain(
                component,
                "the detector on this phone has no label for \"$component\"",
            )
        }
        if (seen.boxes.isEmpty()) {
            return Localization.Uncertain(component, "the camera is not showing anything recognised")
        }

        val floor = minScore()
        val hit = seen.boxes
            .filter { box -> labels.any { it.equals(box.label, ignoreCase = true) } }
            .maxByOrNull { it.score }
            ?: return Localization.Uncertain(component, "not in this frame")

        if (hit.score < floor) {
            // Seen, but not clearly enough to point at. Saying so beats drawing
            // a box the learner would trust more than the detector does.
            return Localization.Uncertain(
                component,
                "possibly in frame, but below the confidence needed to point at it",
            )
        }
        return Localization.Found(hit.label, hit.score, hit)
    }

    /**
     * Whether this detector claims to know a component at all.
     *
     * For the telemetry panel and for anything that wants to hide a control
     * rather than offer one that will always answer "cannot say".
     */
    fun supports(component: String): Boolean =
        aliases()[component.trim().lowercase()].orEmpty().isNotEmpty()

    /** Every component the current configuration claims a label for. */
    fun vocabulary(): Set<String> = aliases().filterValues { it.isNotEmpty() }.keys
}
