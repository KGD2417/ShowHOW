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
    /** Below this score a box is not drawn. Every box on screen is a claim. */
    val detectMinScore: Float = 0.4f,

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

    // --- Linking words that confirm a candidate cut ---
    val linkWordsHi: List<String> = listOf("phir", "ab", "uske baad", "next", "then"),
    val linkWordsMr: List<String> = listOf("mag", "ata", "tyanantar", "next", "then"),
) {
    companion object {
        val DEFAULT = Policy()

        /** Lenient on purpose: an unknown key in a hand-edited file is not a crash. */
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun parse(text: String): Policy = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    fun linkWords(lang: String): List<String> =
        if (lang.startsWith("mr")) linkWordsMr else linkWordsHi
}
