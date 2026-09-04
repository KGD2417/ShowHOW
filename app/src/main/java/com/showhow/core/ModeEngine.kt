package com.showhow.core

enum class Mode { EASY, HANDS, TALK, TAP }

/** Everything the engine is allowed to look at. Numbers in, decision out. */
data class ModeInputs(
    val easyMode: Boolean = false,
    val accelVariance: Double = 0.0,
    val dbfs: Double = -60.0,
    val speechUnclear: Boolean = false,
    val userFar: Boolean = false,
)

data class ModeDecision(val mode: Mode, val reason: String)

/** enter and exit deliberately differ, so a value sitting on the line cannot flip. */
private class Schmitt(private val enter: Double, private val exit: Double) {
    var state = false
        private set

    fun update(v: Double): Boolean {
        // enter > exit for "rises into true", enter < exit for the other way.
        state = if (enter >= exit) {
            if (state) v >= exit else v > enter
        } else {
            if (state) v <= exit else v < enter
        }
        return state
    }
}

/**
 * Picks the interaction mode. Contains no AI at all -- four booleans, a
 * decision table and a stopwatch. Decides in well under a millisecond.
 */
class ModeEngine(private val policy: Policy = Policy.DEFAULT) {

    private val inHand = Schmitt(policy.inHandEnterVar, policy.inHandExitVar)
    private val roomLoud = Schmitt(policy.roomLoudEnterDb, policy.roomLoudExitDb)

    var mode: Mode = Mode.TAP
        private set

    var reason: String = "TAP <- start"
        private set

    private var pending: ModeDecision? = null
    private var pendingSince = 0L

    val isInHand: Boolean get() = inHand.state
    val isRoomLoud: Boolean get() = roomLoud.state

    /**
     * @return true if this call committed a switch.
     *
     * The dwell is the most important thing in this file. Without it a sample
     * sitting on a threshold repaints the screen twice a second, and that
     * flicker is exactly the problem the product exists to solve.
     */
    fun update(nowMs: Long, inputs: ModeInputs): Boolean {
        val held = inHand.update(inputs.accelVariance)
        val loud = roomLoud.update(AdaptiveGate.sanitize(inputs.dbfs))
        val candidate = decide(inputs, held, loud)

        if (candidate.mode == mode) {
            pending = null
            return false
        }
        val p = pending
        if (p == null || p.mode != candidate.mode) {
            pending = candidate
            pendingSince = nowMs
            return false
        }
        if (nowMs - pendingSince < policy.dwellMs) return false

        mode = candidate.mode
        reason = candidate.reason
        pending = null
        return true
    }

    /** First match wins. EASY over HANDS over TALK over TAP. */
    private fun decide(i: ModeInputs, held: Boolean, loud: Boolean): ModeDecision = when {
        i.easyMode ->
            ModeDecision(Mode.EASY, "EASY <- user setting")
        loud ->
            ModeDecision(Mode.HANDS, "HANDS <- room is loud (${fmt(i.dbfs)} dBFS)")
        i.speechUnclear ->
            ModeDecision(Mode.HANDS, "HANDS <- speech was unclear")
        !held ->
            ModeDecision(Mode.TALK, "TALK <- phone is flat (var ${fmt(i.accelVariance)})")
        i.userFar ->
            ModeDecision(Mode.TALK, "TALK <- user is far")
        else ->
            ModeDecision(Mode.TAP, "TAP <- held, quiet, close")
    }

    private fun fmt(v: Double): String {
        if (!v.isFinite()) return "--"
        val r = Math.round(v * 10.0) / 10.0
        return r.toString()
    }
}
