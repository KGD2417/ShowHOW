package com.showhow.core

/**
 * Fires a value once, and only after it has held steady long enough.
 *
 * A hand in front of a camera is classified thirty times a second, and a pose
 * halfway between an open palm and a fist flickers between the two answers.
 * Without a dwell the Player would skip five steps in a second; without the
 * fire-once rule it would skip one per frame for as long as the hand is up.
 *
 * Same shape as the dwell in [ModeEngine], and here for the same reason:
 * flicker is the problem this product exists to remove.
 */
class DwellLatch<T : Any>(private val dwellMs: Long) {

    private var candidate: T? = null
    private var since = 0L
    private var fired: T? = null

    /**
     * @param value what is being seen right now, or null for nothing.
     * @return the value to act on this tick, or null. Acting on the same value
     *   again needs it to go away and come back.
     */
    fun update(nowMs: Long, value: T?): T? {
        if (value != candidate) {
            candidate = value
            since = nowMs
        }
        // Anything other than what we last fired clears the latch, so palm ->
        // fist -> palm is three separate actions rather than one.
        if (value != fired) fired = null
        if (value == null || fired != null) return null
        if (nowMs - since < dwellMs) return null
        fired = value
        return value
    }

    fun reset() {
        candidate = null
        fired = null
        since = 0L
    }
}
