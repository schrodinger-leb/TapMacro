package com.tapmacro.app

/**
 * A single recorded tap.
 * @param x, y        screen coordinates of the tap
 * @param delayMs     time to wait after the PREVIOUS event before firing this one
 * @param durationMs  how long the touch was held down (tap vs long-press)
 */
data class TapEvent(
    val x: Float,
    val y: Float,
    val delayMs: Long,
    val durationMs: Long
)
