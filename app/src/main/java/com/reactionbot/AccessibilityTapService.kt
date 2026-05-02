package com.reactionbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that dispatches synthetic tap gestures.
 * Uses dispatchGesture() which bypasses normal touch latency.
 * Must be enabled by user in Settings > Accessibility.
 */
class AccessibilityTapService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        BotState.accessibilityService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only dispatch gestures, not consume events
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (BotState.accessibilityService === this) {
            BotState.accessibilityService = null
        }
    }

    /**
     * Fires a 1ms tap stroke at (x, y) in screen coordinates.
     * Sub-millisecond latency path: dispatchGesture → InputManagerService
     */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        /**
         * Called from ScreenCaptureService on the image reader thread.
         * Thread-safe: BotState.accessibilityService is @Volatile.
         */
        fun tapAt(x: Float, y: Float) {
            BotState.accessibilityService?.tap(x, y)
        }
    }
}
