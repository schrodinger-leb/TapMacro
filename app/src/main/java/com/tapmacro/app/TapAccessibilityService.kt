package com.tapmacro.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TapAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TapAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Dispatches a synthetic tap (or long-press, if durationMs is large) at (x, y).
     */
    fun performTap(x: Float, y: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x, y)
        val safeDuration = durationMs.coerceAtLeast(1L)
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, null)
    }
}
