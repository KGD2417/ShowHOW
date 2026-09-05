package com.showhow.eval

import com.showhow.core.SpokenWord

/**
 * The laptop-repair session ShowHow is judged on, written down.
 *
 * Fixtures rather than recordings, because a recording needs a phone, a room
 * and a person, and the point of these is that they run on every machine, in
 * every build, in under a second, and give the same answer every time.
 *
 * **What this harness evaluates is ShowHow, not Gemma.** No model file is
 * needed or wanted here: the coach's replies are canned, chosen to be the
 * shapes a small model actually produces -- including the wrong ones -- and
 * every assertion is about what this app does when handed them. Whether Gemma
 * writes a good instruction is a question for a phone with 2 GB free; whether
 * this app attributes an invented instruction to a real person is a question
 * for a JVM, and it is the more dangerous of the two.
 */
data class Fixture(
    val id: Int,
    val name: String,
    /** What it is checking, for the report. */
    val checks: String,
    /** The take as the recogniser heard it, or empty for a take with no ASR. */
    val words: List<SpokenWord> = emptyList(),
    /** Detector labels per step, or empty for a phone with no detector model. */
    val captions: List<String> = emptyList(),
    /** What the coach replied, or null for a phone with no coach model. */
    val coachReply: String? = null,
    /** A learner's question, for the Q&A fixtures. */
    val question: String = "",
    /** What the coach replied to it, or null. */
    val answerReply: String? = null,
    /** Live detector labels during the question. */
    val seenNow: List<String> = emptyList(),
)

/** A sentence on the take's clock, one word every 400ms. */
fun said(text: String, startMs: Long = 0, gapMs: Long = 400): List<SpokenWord> =
    text.split(" ").filter { it.isNotBlank() }
        .mapIndexed { i, w -> SpokenWord(w, startMs + i * gapMs) }

/**
 * Fourteen scenarios: seven about building a guide, seven about answering a
 * question in the middle of following one.
 */
val FIXTURES: List<Fixture> = listOf(
    Fixture(
        id = 1,
        name = "normal expert narration",
        checks = "guide structure, provenance",
        words = said("पहले लैपटॉप बंद करो और चार्जर निकालो", 0),
        captions = listOf("laptop, keyboard"),
        coachReply = "1|EXPERT|Power down|Shut the laptop down and unplug the charger.|",
    ),
    Fixture(
        id = 2,
        name = "hesitation",
        checks = "correction handling: a stumble is not a retraction",
        words = said("तो अब मतलब हम बेस के पेंच खोलेंगे", 0),
        captions = listOf("laptop"),
        coachReply = "1|EXPERT|Undo the base screws|Remove the screws around the base.|",
    ),
    Fixture(
        id = 3,
        name = "expert correction",
        checks = "correction handling: the corrected action must win",
        words = said("remove this screw no sorry not this one remove the side screw", 0),
        captions = listOf("laptop, screwdriver"),
        coachReply = "1|EXPERT|Remove the side screw|Undo the screw on the side, not the one on top.|",
    ),
    Fixture(
        id = 4,
        name = "irrelevant narration",
        checks = "guide structure: an aside is flagged, never deleted",
        words = said("एक मिनट रुको मेरा फोन बज रहा है", 0),
        captions = listOf("person"),
        coachReply = "1|SKIP|||GENERAL: the expert's phone rang here",
    ),
    Fixture(
        id = 5,
        name = "repeated instruction",
        checks = "correction handling: a repeat is evidence of a repair",
        words = said("pull the connector no pull the connector gently", 0),
        captions = listOf("laptop"),
        coachReply = "1|EXPERT|Free the connector|Pull the connector gently.|EXPERT: not by the wires",
    ),
    Fixture(
        id = 6,
        name = "missing ASR",
        checks = "fallback: a silent step still becomes a step",
        words = emptyList(),
        captions = listOf("laptop, keyboard"),
        coachReply = "1|VISUAL|Open the case|The base is off and the board is exposed.|",
    ),
    Fixture(
        id = 7,
        name = "missing detector",
        checks = "fallback: no captions, no visual claims",
        words = said("अब रैम मॉड्यूल बाहर निकालो", 0),
        captions = listOf(""),
        coachReply = "1|VISUAL|Remove the RAM|Lift the module out.|",
    ),
    Fixture(
        id = 8,
        name = "missing Coach",
        checks = "fallback: the expert's own words survive untouched",
        words = said("पहले लैपटॉप बंद करो", 0),
        captions = listOf("laptop"),
        coachReply = null,
    ),
    Fixture(
        id = 9,
        name = "correct screwdriver question",
        checks = "QA classification: the guide names it",
        question = "is this the right screwdriver?",
        answerReply = "[guide] The guide says a PH0 Phillips for the base screws.",
        seenNow = listOf("laptop"),
    ),
    Fixture(
        id = 10,
        name = "unsupported screwdriver question",
        checks = "uncertainty: nothing identifies the driver in shot",
        question = "is this screwdriver correct?",
        answerReply = "[uncertain] Nothing here identifies which driver you are holding. " +
            "Match it to the screw head before turning.",
        seenNow = emptyList(),
    ),
    Fixture(
        id = 11,
        name = "RAM location question",
        checks = "QA classification: answered from the guide",
        question = "where is the RAM?",
        answerReply = "[guide] Step 3 has it under the black shield next to the fan.",
        seenNow = listOf("laptop"),
    ),
    Fixture(
        id = 12,
        name = "SSD location question",
        checks = "unsupported claims: the detector has no SSD label",
        question = "where is the SSD?",
        answerReply = "[seen] I can see the SSD in the top right of the frame.",
        seenNow = listOf("laptop", "keyboard"),
    ),
    Fixture(
        id = 13,
        name = "general knowledge question",
        checks = "provenance: knowledge is never promoted to a guide fact",
        question = "what screwdriver do laptops usually take?",
        answerReply = "[general] Laptop base screws are usually Phillips PH0.",
        seenNow = listOf("laptop"),
    ),
    Fixture(
        id = 14,
        name = "uncertain visual question",
        checks = "uncertainty: a visual claim with the camera off",
        question = "does this look right?",
        answerReply = "[seen] Yes, that looks correct.",
        seenNow = emptyList(),
    ),
)
