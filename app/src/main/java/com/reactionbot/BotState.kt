package com.reactionbot

import android.graphics.PointF

/**
 * Shared global state between services.
 * All fields are @Volatile for cross-thread visibility without locking overhead.
 */
object BotState {
    @Volatile var isRunning: Boolean = false
    @Volatile var isCalibrating: Boolean = false

    // Default positions spread across a portrait screen (1080x1920)
    val sensorPoints: Array<PointF> = arrayOf(
        PointF(135f, 1400f),
        PointF(405f, 1400f),
        PointF(675f, 1400f),
        PointF(945f, 1400f)
    )

    @Volatile var accessibilityService: AccessibilityTapService? = null
}
