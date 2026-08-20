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
     * onDone is guaranteed to fire exactly once, either when the gesture
     * actually completes/cancels, or immediately if the system rejects the
     * dispatch outright -- Android does NOT call the gesture callback at all
     * in that case, so without this check a rejected dispatch would leave
     * the caller waiting forever (this was the cause of recording appearing
     * to "freeze" after a while).
     */
    fun performTap(x: Float, y: Float, durationMs: Long, onDone: (() -> Unit)? = null) {
        val path = Path()
        path.moveTo(x, y)
        val safeDuration = durationMs.coerceAtLeast(1L)
        val stroke = GestureDescription.StrokeDescription(path, 0, safeDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }
        }, null)

        if (!accepted) {
            onDone?.invoke()
        }
    }
}
