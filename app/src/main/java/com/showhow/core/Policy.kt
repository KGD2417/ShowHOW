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
     * Floor for the **fine-tuned tool detector**. Every box on screen is a claim.
     *
     * Lower than [detectMinScoreCoco] because it is a smaller model: 875 images
     * over six classes, and on the bench it calls a screwdriver it has found
     * correctly around 0.3. At 0.55 it drew nothing at all; at 0.02 it boxed a
     * sticker and called it a philips head. 0.30 is where the tools appear and
     * the stickers do not.
     */
    val detectMinScore: Float = 0.30f,

    /**
     * Floor for the **stock COCO detector** that runs beside it.
     *
     * Higher, because it can afford to be: laptop, keyboard, mouse and person
     * come back at 0.7-plus on a lit bench, and anything it is only half sure
     * of is a guess this app does not need.
     */
    val detectMinScoreCoco: Float = 0.50f,

    /**
     * Floors for individual labels, overriding their model's.
     *
     * The five screw-head classes sit here because they are the weakest thing
     * either model does and they misfire on the tool rather than the work: a
     * `hex_screw` box lands on a philips driver held up to the camera. They
     * have between 23 and a couple of hundred training images each, against
     * the bulk of the set behind `screwdriver`, so they are asked to be much
     * more certain before they may draw. `screwdriver` keeps the model's own
     * floor, because it is the label this detector exists to produce.
     *
     * A map in policy.json, so a class that starts misbehaving in the room can
     * be raised out of the way without a rebuild.
     */
    val detectLabelMinScore: Map<String, Float> = mapOf(
        // Lowest floor in the app, because this is the label the whole
        // detector exists to produce and the one a learner is waiting to see.
        // It can be this low now that the graph allowlist stops `hand` and
        // `person` taking its result slot -- before that, raising or lowering
        // it changed nothing, because the detection was being discarded
        // upstream rather than failing a threshold.
        "screwdriver" to 0.20f,
        // The head a laptop actually uses, and the best supported of the five.
        "philips_screw" to 0.40f,
        // The other heads exist mostly to tell one driver from another, and
        // they are the ones that were misfiring on the driver itself.
        "pozidriv_screw" to 0.50f,
        "torx_screw" to 0.50f,
        "hex_screw" to 0.50f,
        // 23 training images. Treat anything it says as a rumour.
        "square_screw" to 0.70f,
    ),

    // --- Capture (how often the camera saves a frame while recording) ---
    /**
     * How often a frame is kept while the expert is talking.
     *
     * The expert should not have to think about photography. They do the job
     * once, in one take, and the camera keeps a frame every couple of seconds
     * throughout; which of those frames matters is decided afterwards, once the
     * transcript exists and the steps are known.
     *
     * This is the whole reason the guide can show the right moment. A photo
     * taken only at a pause is a photo of the expert pausing -- the interesting
     * frame is halfway through the sentence, while their hands are on the part.
     * You cannot go back for it, so everything is kept and pickFrames chooses.
     */
    val snapIntervalMs: Long = 2000,
    /**
     * Hard cap on kept frames, so a forgotten recording cannot fill the phone.
     *
     * 90 at two seconds is three minutes of continuous capture, which is longer
     * than any guide this app is built for. Past the cap the periodic capture
     * simply stops; the take keeps recording.
     */
    val maxSnaps: Int = 90,

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
     * How much a frame gains from the expert naming something over it.
     *
     * The largest single weight after sharpness, and deliberately above
     * [frameDetectionWeight]: "this is the philips screwdriver" is a statement
     * by the person who does this for a living, while a box is a guess by a
     * model that has never seen most of their toolbox. When the two disagree
     * about which frame shows the work, the human wins.
     *
     * Sharpness still outranks it, because a blurred photograph of the right
     * moment helps nobody.
     */
    val frameSpokenWeight: Double = 0.45,

    /**
     * How far from a frame a word still counts as being about it, either side.
     *
     * Two and a half seconds, against a snap every two: someone holds a tool up
     * for a beat before naming it and a beat after, so the frames worth keeping
     * straddle the phrase rather than land on it. Too narrow and the naming
     * falls between two snaps and credits neither.
     */
    val frameSpokenWindowMs: Long = 2500,

    /** Named things needed for full marks. One is already the moment. */
    val frameNamedForFullCredit: Double = 1.0,

    /**
     * The words this job turns on, which a general recogniser splits or blurs.
     *
     * Every one of these is already in the shipped Vosk model's 368k-word
     * vocabulary -- the model can say "screwdriver", it just often prefers the
     * likelier "screw driver". [correctDomainTokens] puts them back; see its
     * KDoc for why the rules are as timid as they are.
     *
     * In policy.json so a different trade is a file push. Adding a word here
     * costs nothing; adding a *short* one costs nothing either, because the
     * corrector will only ever join or exact-match it, never guess at it.
     */
    val domainWords: List<String> = listOf(
        "screwdriver",
        "screwdrivers",
        "unscrew",
        "clockwise",
        "counterclockwise",
        "anticlockwise",
        "laptop",
        "keyboard",
        "panel",
        "screw",
        "screws",
        "philips",
        "phillips",
        "torx",
        "spudger",
        "motherboard",
        "heatsink",
        "battery",
    ),

    /**
     * Sounds that are never content, removed before a learner reads the step.
     *
     * Hesitations only. **"like", "so", "actually", "right", "basically" and
     * "well" are deliberately absent**, and adding them here is a decision to
     * make with a take in your ears: every one is a real word in a real
     * instruction -- "turn it *like* this", "*so* it sits flat", "*right* up
     * against the frame" -- and stripping them deletes meaning from the guide
     * in a way nobody reading the result could detect.
     *
     * English only. Vosk returns Devanagari for Hindi and its hesitations do
     * not transliterate to these, so a Hindi take is left alone rather than
     * half-cleaned.
     */
    val fillerWords: List<String> = listOf(
        "um", "umm", "uhm", "uh", "uhh", "erm", "er",
        "hmm", "hm", "mmm", "mm", "mhm", "ah", "eh", "huh",
    ),

    /**
     * The silence that ends a sentence inside a step.
     *
     * Well under [pauseMs], which ends a whole step: a speaker draws a longer
     * breath between tasks than between sentences, and this has to fit inside
     * that. Set it at or above [pauseMs] and every step becomes one sentence,
     * because any gap big enough to break a sentence would already have cut.
     */
    val sentenceGapMs: Long = 700,

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

    // --- Naming a component the detector can point at ---
    /**
     * Component name -> the detector labels that mean it.
     *
     * **Empty for every laptop part today, and that is the honest state.** The
     * loaded model is generic COCO: it knows laptop, keyboard, mouse, person,
     * and has no label for a RAM module, an SSD, a heatsink, a screw or a
     * screwdriver. A component with no labels here is reported as "this
     * detector has no label for that" and no box is drawn.
     *
     * MediaPipe cannot be asked what vocabulary a model has before running it,
     * so the app cannot discover this -- it has to be told. Pushing a
     * fine-tuned .tflite and adding its labels here swaps the whole thing in
     * with no rebuild, which is the point of it living in policy.json.
     */
    val componentAliases: Map<String, List<String>> = mapOf(
        "laptop" to listOf("laptop"),
        "keyboard" to listOf("keyboard"),
        "mouse" to listOf("mouse"),
        "screen" to listOf("tv", "laptop"),
        // Filled in once a fine-tuned detector replaced the COCO one. These
        // are the classes it was actually trained on, which is the only thing
        // that may appear here -- a name with no matching label is a box the
        // app promises and the model cannot draw.
        "screwdriver" to listOf("screwdriver"),
        "screw" to listOf(
            "philips_screw", "pozidriv_screw", "torx_screw",
            "hex_screw", "square_screw",
        ),
        "philips" to listOf("philips_screw"),
        "pozidriv" to listOf("pozidriv_screw"),
        "torx" to listOf("torx_screw"),
        "hex" to listOf("hex_screw"),
        "square" to listOf("square_screw"),
        // Still genuinely absent: no dataset covered these.
        "ram" to emptyList(),
        "ssd" to emptyList(),
        "heatsink" to emptyList(),
        "battery" to emptyList(),
    ),
    /** Below this a box is not worth pointing at and calling by name. */
    val componentMinScore: Float = 0.60f,

    // --- Verification cascade (does the bench look like the step?) ---
    /**
     * Above this many changed dHash bits between frames the phone counts as
     * moving, and the frame's evidence starts being discounted.
     *
     * A frame taken mid-swing is how an app ends up telling someone their
     * perfectly correct bench looks wrong. It is no longer thrown away, though
     * -- see [settleWeight]. A hand-held phone over a workbench is never
     * perfectly still, and a hard cutoff here binned most frames and let the
     * few that got through decide alone.
     */
    val checkSettledMaxChange: Int = 14,
    /** At or above this similarity the scene matches the step's photograph. */
    val checkCorrectSimilarity: Float = 0.70f,
    /** ...and above this it is worth mentioning, not worth insisting on. */
    val checkLikelySimilarity: Float = 0.55f,
    /**
     * How much of the photograph's object evidence must be in front of the
     * camera before the step counts as done.
     *
     * The one number that decides a page turn now, because it is the only one
     * that is about the *work* rather than about the room. The scene
     * comparison it replaced measures structure and colour over the whole
     * frame, so it demanded the learner's bench look like the expert's -- same
     * desk, same light, same angle -- and no amount of doing the job correctly
     * on a different table could satisfy it.
     *
     * 0.75 and not 0.5: counted rather than merely named (see [labelOverlap]),
     * half the evidence is a panel with one of its two screws out, and that is
     * a step in progress rather than a step finished.
     */
    val checkLabelOverlap: Double = 0.75,

    /**
     * How fast confidence follows agreeing frames, 0..1 per frame.
     *
     * Frames arrive every 100 ms, so 0.25 means roughly half a second of
     * frames that agree before [checkLabelOverlap] can be reached. That delay
     * is the feature: one frame over a threshold used to be a verdict, and a
     * hand passing across the bench read as the work being finished.
     *
     * Raise it and the page turns sooner and on flimsier evidence. Lower it
     * and a learner waits with the job already done.
     */
    val confidenceRiseCoef: Float = 0.25f,

    /**
     * ...and how fast it fades when there is nothing to judge at all.
     *
     * Slower than it rises, deliberately, and the same asymmetry as
     * [AdaptiveGate]'s floor. A reach across the camera blanks two or three
     * frames, and that must not undo ten good ones -- an absence is not the
     * learner getting it wrong.
     */
    val confidenceFallCoef: Float = 0.08f,

    /** Above this confidence there is something worth mentioning on screen. */
    val confidenceLikely: Double = 0.15,

    /**
     * How far confidence must fall back before a band is given up again.
     *
     * Enter high, leave low, exactly as [ModeEngine]'s Schmitt triggers do. A
     * value resting on a band edge otherwise repaints the screen twice a
     * second, and that flicker is the problem this product exists to solve.
     */
    val confidenceHysteresis: Double = 0.10,

    // --- How long a model is allowed to keep the build waiting ---
    /**
     * Longest the recogniser may hold up a guide build, per take.
     *
     * A 2.7 GB Kaldi graph decoding two minutes of audio on the CPU is the case
     * this exists for. Past this the words are dropped and the cutter falls
     * back to pauses alone, which is exactly what it does on a phone with no
     * recogniser at all -- a known-good state, and a far better one than a
     * Processing screen that never moves.
     */
    val asrTimeoutMs: Long = 90_000,

    /**
     * ...and the same for the coach.
     *
     * Deliberately shorter than [asrTimeoutMs], because the transcript is
     * load-bearing and the coach is not: without it the steps stay in the
     * expert's own words, which is a complete guide. Absent and slow are the
     * same thing to a person standing over a finished job.
     */
    val coachTimeoutMs: Long = 45_000,

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
    /**
     * How long the Player waits after a step's audio ends before moving on, or
     * **0 to never move on by itself**, which is the default.
     *
     * It used to be two seconds, and that was wrong for the job this app is
     * for. The narration for "undo the ten base screws" lasts four seconds and
     * the work lasts two minutes; advancing when the *talking* stops walks away
     * from a learner who has barely picked up a screwdriver, and they then have
     * to find their place again with their hands full. Which is exactly the
     * moment they cannot.
     *
     * So the step stays until a person moves it -- Next, an open palm, or a
     * thumb to hear it again. Set a positive value here to bring the old
     * behaviour back for a hands-free demo.
     */
    val autoAdvanceMs: Long = 0,

    // --- Scene check (advisory: it never disables anything) ---
    /** Below this similarity the Player may say "this doesn't look like the photo". */
    /**
     * Whether How mode starts by reading the step aloud rather than playing
     * the take.
     *
     * True, because the rewritten step is a sentence written to be followed --
     * "flip the laptop over and remove the four screws" -- while the take is
     * the expert mid-job, with the pauses and the reaching. The learner can
     * still switch to the expert's own voice in one tap, and should whenever
     * the recogniser has clearly misheard: that audio is the evidence and it is
     * always right, where the transcript is only usually right.
     */
    val readAloudDefault: Boolean = true,

    /**
     * How closely the camera must match the step's photograph before the guide
     * moves on by itself. 0 turns it off and waits for a person.
     *
     * The on/off switch, and now only that: any positive value arms the page
     * turn and 0 waits for a person. What the page turn actually needs is
     * [StepConfidence.mayAdvance] -- confidence earned over frames, reaching
     * [checkLabelOverlap]. Two thresholds on two different measurements is how
     * the app came to be asked for 70% and to behave like 72%, and how a bench
     * whose objects plainly agreed could still sit there unmoved.
     * The old 0.60 was set on the theory that turning early costs a tap
     * of Back while never turning strands someone holding a screwdriver -- and
     * on the bench it turned on a desk that merely looked like the desk, with
     * the learner's hand not even in shot, which reads as the app claiming
     * they finished work they had not started. Early is not cheap when it is
     * wrong out loud.
     */
    val advanceOnMatchSimilarity: Float = 0.70f,

    /**
     * How long the match has to hold before the page turns.
     *
     * A hand passing across the bench can match a photograph for a frame or
     * two. A step that is actually finished stays finished, so the match has to
     * survive a second and a half of looking.
     */
    val advanceOnMatchDwellMs: Long = 1500,

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
