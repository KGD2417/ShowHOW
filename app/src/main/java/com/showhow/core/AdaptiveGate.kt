package com.showhow.core

/**
 * Tracks the room's own noise floor so a fixed threshold cannot fail us.
 *
 * A ceiling fan floors a room around -20 dBFS. A gate nailed at -38 dB would
 * see that as continuous speech and never find a single pause, so the take
 * would come back as one enormous step.
 *
 * Pure arithmetic. No android imports, so the fan-noise room is a JVM test
 * that runs in milliseconds instead of a trip to a real kitchen.
 */
class AdaptiveGate(private val policy: Policy = Policy.DEFAULT) {

    /** Last sanitized sample, in dBFS. What the debug screen shows on the left. */
    var levelDb: Double = SANE_MIN
        private set

    /**
     * Current noise floor estimate, in dBFS.
     *
     * Starts at the top of the scale and falls into the room. The fast fall
     * gets from 0 to a -20 dB fan in about half a second, which is why there
     * is no priming special case here.
     */
    var floorDb: Double = SANE_MAX
        private set

    /** The live threshold. What the debug screen shows on the right of the slash. */
    val gateDb: Double
        get() = (floorDb + policy.speechMarginDb).coerceIn(policy.gateMinDb, policy.gateMaxDb)

    /** Feed one dBFS sample. Returns true if this sample counts as speech. */
    fun update(dbfs: Double): Boolean {
        // A silent mic reports -Infinity, and a divide-by-zero somewhere in the
        // level meter reports NaN. Neither may reach the arithmetic below.
        val x = sanitize(dbfs)
        levelDb = x

        val speech = x > gateDb
        floorDb = when {
            x < floorDb -> floorDb + policy.gateFallCoef * (x - floorDb)
            // ponytail: the floor is frozen while speech is present, which is
            // what makes "a long sentence cannot drag the floor up" true. A
            // room that gets genuinely louder is picked up in the next pause.
            speech -> floorDb
            else -> floorDb + policy.gateRiseCoef * (x - floorDb)
        }.coerceIn(SANE_MIN, SANE_MAX)

        return speech
    }

    fun reset() {
        floorDb = SANE_MAX
        levelDb = SANE_MIN
    }

    companion object {
        const val SANE_MIN = -120.0
        const val SANE_MAX = 0.0

        fun sanitize(dbfs: Double): Double =
            if (dbfs.isNaN() || dbfs == Double.NEGATIVE_INFINITY) SANE_MIN
            else dbfs.coerceIn(SANE_MIN, SANE_MAX)
    }
}
