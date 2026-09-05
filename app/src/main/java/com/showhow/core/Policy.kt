package com.showhow.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every tunable number in ShowHow. Nothing in this app is allowed to hardcode
 * one of these values -- the whole point is that during the ten hours when we
 * cannot compile, we can still push a new policy.json to the phone and change
 * the app's behaviour.
 */
@Serializable
data class Policy(
    // --- AdaptiveGate ---
    /** Noise floor drops toward a quiet sample at this rate. Fast on purpose. */
    val gateFallCoef: Double = 0.25,
    /** ...and creeps up at this rate, so a long sentence cannot drag it along. */
    val gateRiseCoef: Double = 0.004,
    /** Speech has to beat the floor by this much. */
    val speechMarginDb: Double = 9.0,
    val gateMinDb: Double = -45.0,
    val gateMaxDb: Double = -6.0,

    // --- StepCutter ---
    /** Silence longer than this is a step boundary. */
    val pauseMs: Long = 1200,
    /** An utterance shorter than this merges into the one after it. */
    val minUtteranceMs: Long = 2500,
    val maxSteps: Int = 12,

    // --- ModeEngine (Schmitt triggers: enter != exit, so nothing flickers) ---
    val inHandEnterVar: Double = 0.09,
    val inHandExitVar: Double = 0.05,
    val roomLoudEnterDb: Double = -26.0,
    val roomLoudExitDb: Double = -32.0,
    /** A candidate mode must survive this long before it is committed. */
    val dwellMs: Long = 400,
    /** Below this mean recogniser confidence the take counts as unclear speech. */
    val speechUnclearConfThreshold: Float = 0.55f,
    /** How many of the most recent words that mean is taken over. */
    val speechUnclearWindowWords: Int = 12,
    /**
     * A detected face shorter than this many pixels means the user is out of
     * arm's reach, so TALK beats TAP.
     *
     * ponytail: nothing measures this yet -- it needs the MediaPipe face
     * detector that lands with the gesture model. Until then userFar stays
     * false, because a brightness or loudness proxy would be a guess, and this
     * app does not guess at things it cannot honestly measure.
     */
    val userFarFaceHeightPx: Double = 90.0,

    // --- Object detection (the boxes over the viewfinder) ---
    /**
     * Below this score a box is not drawn. Every box on screen is a claim.
     *
     * 0.4 put four half-guesses on the glass at once and read as noise.
     */
    val detectMinScore: Float = 0.55f,

    // --- Frame picking (which photo represents a step) ---
    /**
     * Below this mean luma gradient a frame is a smear, not a photograph.
     *
     * 0.02 let through frames taken while the phone was still swinging; 0.10
     * threw away every frame of a plain dark laptop lid, which is a legitimate
     * thing for a step to look like.
     */
    val frameMinSharpness: Double = 0.05,
    /** Below this average brightness the lens was against something. */
    val frameMinLuma: Double = 22.0,
    /** Above it, straight into a worklight. Both are frames of nothing. */
    val frameMaxLuma: Double = 242.0,
    /**
     * How different a step's photo must be from the previous step's, in bits of
     * a 64-bit dHash. Below this the guide shows the same picture twice and the
     * learner cannot tell the steps apart.
     */
    val frameMinHammingFromPrevious: Int = 6,
    /** A frame with detail in it is worth more than a flat one. */
    val frameSharpnessWeight: Double = 0.5,
    /**
     * ...and one taken late in the step is worth more than one taken early.
     *
     * The point of a step's photograph is to show what it looks like when the
     * step is *done*. The frame nearest the start is a picture of the work not
     * yet begun.
     */
    val frameLatenessWeight: Double = 0.3,
    /** ...and one the detector recognised something in beats one it did not. */
    val frameDetectionWeight: Double = 0.2,
    /** Boxes needed for full marks on that last term. Past this it says nothing more. */
    val frameDetectionsForFullCredit: Double = 3.0,

    /**
     * Tools worth telling the coach about when the guide names one.
     *
     * Matched against what the *expert* said and wrote, never against the
     * detector: the loaded model knows ordinary object classes and has no label
     * for a screwdriver, so a tool in this list reaching the prompt means a
     * human named it. In policy.json so another trade can be supported without
     * a rebuild.
     */
    val toolWords: List<String> = listOf(
        "screwdriver", "phillips", "ph0", "ph1", "torx", "spudger", "pry",
        "tweezers", "pliers", "brush", "paste", "anti-static", "strap",
        "\u092a\u0947\u091a\u0915\u0938", "\u0938\u094d\u0915\u094d\u0930\u0942\u0921\u094d\u0930\u093e\u0907\u0935\u0930", "\u091a\u093f\u092e\u091f\u0940",
    ),

    // --- Coach (the on-device model that rewrites steps and answers questions) ---
    /**
     * Chars of guide handed to the model per call.
     *
     * The one coach number worth turning during Red Light. Context is what
     * costs time: a twelve step guide is a few thousand characters and a 2B
     * model reads every one of them before it starts answering. Cut this and
     * answers get faster and shallower; raise it and a long guide starts
     * taking ten seconds a question, which is longer than the learner will
     * wait with a screwdriver in their hand.
     */
    val coachContextChars: Int = 4000,

    // --- Player ---
    /** How long the Player waits after a step's audio ends before moving on. */
    val autoAdvanceMs: Long = 2000,

    // --- Scene check (advisory: it never disables anything) ---
    /** Below this similarity the Player may say "this doesn't look like the photo". */
    val sceneAdviseMinSimilarity: Float = 0.55f,

    // --- Hand signs ---
    /** A pose must hold this long before it moves a step. Anti-flicker. */
    val gestureDwellMs: Long = 350,
    /** Below this classifier score the pose is not looked at at all. */
    val gestureMinConfidence: Float = 0.6f,

    // --- LinkWordConfirmer: the second opinion on a candidate cut ---
    /** How far either side of a candidate cut a linking word still counts. */
    val confirmWindowMs: Long = 2500,
    /** Linking words needed inside that window to keep a cut. 0 disables the veto. */
    val confirmMinLinkWords: Int = 1,

    // --- Correction evidence (did the expert take something back?) ---
    /**
     * How much a retraction word is worth on its own.
     *
     * Deliberately below [correctionMinStrength]. A marker alone must never
     * clear the bar, or this becomes a keyword matcher rewriting a real
     * person's instructions, and "no problem" is not a correction.
     */
    val correctionMarkerWeight: Double = 0.35,
    /** A content word said on both sides of the marker: the same action, redone. */
    val correctionRepeatWeight: Double = 0.40,
    /** People stumble when they are correcting themselves. */
    val correctionHesitationWeight: Double = 0.20,
    /** A repair follows immediately; a minute later is a new thought. */
    val correctionProximityWeight: Double = 0.20,
    /**
     * Taken off when a linking word follows the marker.
     *
     * The one list that says "a new step starts here" is the same list that
     * says "this was not a repair of the last one". Heavy enough to sink a
     * marker and a repeat together, because "undo the screw, no problem, then
     * undo the other screw" repeats a whole verb and is still two steps.
     */
    val correctionLinkWordPenalty: Double = 0.50,
    /** How close the marker must be to what it retracts, for the timing signal. */
    val correctionWindowMs: Long = 4000,
    /** Below this total the evidence is not worth telling the coach about. */
    val correctionMinStrength: Double = 0.55,
    /**
     * Words people use to take something back, as the recogniser spells them.
     *
     * Devanagari first for the same reason the linking words are -- the Vosk
     * Hindi model emits "नहीं", never "nahi", so a romanised-only list matches
     * nothing while looking entirely correct in the config file.
     */
    val correctionMarkers: List<String> = listOf(
        "नहीं", "नही", "गलत", "रुको", "अरे", "माफ", "सॉरी", "चुकले", "थांबा",
        "no", "nahi", "nahin", "galat", "ruko", "arre", "sorry", "oops",
        "wait", "actually", "not this", "thamba", "chukle",
    ),
    /** Fillers. They do not mean a correction; they raise the odds of one. */
    val hesitationMarkers: List<String> = listOf(
        "मतलब", "यानी", "वो", "क्या", "म्हणजे",
        "uh", "um", "umm", "err", "hmm", "matlab", "yaani", "mhanje", "i mean",
    ),

    // --- Linking words that confirm a candidate cut ---
    /**
     * Linking words **as the recogniser spells them**, which for Hindi and
     * Marathi means Devanagari.
     *
     * This is the whole trick and it is easy to get wrong: the Vosk Hindi model
     * emits "फिर", never "phir". A romanised list therefore matches nothing, the
     * confirmer abstains on every cut, and the second opinion silently stops
     * existing while still looking correct in the config file. Romanised forms
     * stay in the list because they cost nothing and a future model may use
     * them, but the Devanagari is what actually fires.
     */
    val linkWordsHi: List<String> = listOf(
        "फिर", "अब", "उसके बाद", "तब", "आगे", "बाद में", "इसके बाद",
        "और अब", "और फिर", "पहले", "सबसे पहले", "अंत में", "आखिर में",
        "phir", "ab", "uske baad", "tab", "aage", "baad mein", "iske baad",
        "pehle", "sabse pehle", "ant mein", "aakhir mein",
    ),
    val linkWordsMr: List<String> = listOf(
        "मग", "आता", "त्यानंतर", "नंतर", "पुढे", "यानंतर",
        "आणि मग", "आणि आता", "पहिले", "शेवटी", "सर्वप्रथम",
        "mag", "ata", "tyanantar", "nantar", "pudhe", "yanantar",
        "pahile", "shevti", "sarvapratham",
    ),
    /** English guides need their own list; they were falling back to Hindi. */
    val linkWordsEn: List<String> = listOf(
        "then", "next", "after that", "now", "first", "finally",
        "once", "after", "lastly", "and then", "now then",
    ),
) {
    companion object {
        val DEFAULT = Policy()

        /** Lenient on purpose: an unknown key in a hand-edited file is not a crash. */
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            // ...and neither is an unknown *value*. This also decodes guides,
            // and GuideStore.load returns null on any parse failure -- so
            // without this, one enum constant a future build adds would cost
            // the reader the entire guide rather than one label on one step.
            // Coercion needs a default to fall back to, which every such field
            // has.
            coerceInputValues = true
        }

        fun parse(text: String): Policy = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    fun linkWords(lang: String): List<String> = when {
        lang.startsWith("mr") -> linkWordsMr
        lang.startsWith("en") -> linkWordsEn
        else -> linkWordsHi
    }
}
