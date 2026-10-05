package com.firstt175.novaframe.session.service

import com.firstt175.novaframe.session.overlay.OverlayManager

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Required declaration so the user can enable the "LSFG Touch Passthrough" service
 * from Settings → Accessibility. The service has two independent jobs:
 *
 *  Hosts the overlay window as TYPE_ACCESSIBILITY_OVERLAY ("trusted overlay" mode,
 *     always on — see [OverlayManager.show]), which is the primary fix
 *     for touch pass-through on strict AOSP builds.
 *
 * Gesture forwarding (this file's [forwardTap] / [forwardSwipe]) is a *secondary*,
 * fallback for the small set of devices where even the trusted-overlay path
 * still drops or mis-routes a touch — instead of relying on the overlay window's
 * touchable-region being honoured, the accessibility service synthesizes the same
 * gesture directly at the coordinates the game should have received it at. It is
 * always enabled (there is no user setting for it).
 * Note that [AccessibilityService.dispatchGesture] targets whatever window currently
 * has focus, which is indistinguishable from "the game" only when the overlay
 * itself is confirmed non-interactive — enabling this on a device where normal
 * pass-through already works would inject a duplicate touch on top of the one the
 * system already delivered.
 *
 * No caller wires this in yet. It is exposed as a small, self-contained API on the
 * service so a future touch-fallback path (e.g. a watchdog in [OverlayManager] that
 * detects pass-through failures) can call it without needing to know about
 * AccessibilityService internals.
 */
class NovaAccessibilityService : AccessibilityService() {

    // No foreground-app monitoring: sessions are started explicitly from Game Launcher.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "NovaA11yService"

        /** Minimum duration Android accepts for a dispatched tap gesture. */
        private const val MIN_TAP_DURATION_MS = 16L

        /** Default duration for a forwarded swipe when the caller doesn't specify one. */
        private const val DEFAULT_SWIPE_DURATION_MS = 120L

        @Volatile
        var instance: NovaAccessibilityService? = null
            private set

        /**
         * True once [dispatchGesture] has completed at least one round trip
         * (success or failure) — lets a caller distinguish "never tried" from
         * "tried and the driver/OEM rejected it" when deciding whether to keep
         * relying on this fallback for the rest of the session.
         */
        @Volatile
        var lastDispatchSucceeded: Boolean? = null
            private set

        /**
         * Forwards a single tap at [x], [y] (screen coordinates, same space as the
         * overlay's own WindowManager metrics) to whatever the accessibility
         * service currently sees as the active window.
         *
         * Returns false immediately (no dispatch attempted) if the service isn't
         * bound — callers should treat that the same as a failed dispatch and fall
         * back to whatever pass-through path they already use.
         */
        fun forwardTap(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null): Boolean =
            forwardSwipe(x, y, x, y, MIN_TAP_DURATION_MS, onResult)

        /**
         * Forwards a straight-line swipe/drag from ([x1],[y1]) to ([x2],[y2]) over
         * [durationMs]. A tap is just a zero-length swipe, hence [forwardTap]
         * delegates here.
         *
         * Safe to call from any thread — gesture dispatch is posted to the main
         * looper internally, matching [AccessibilityService.dispatchGesture]'s
         * requirement that it run on the service's main thread.
         */
        fun forwardSwipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
            onResult: ((Boolean) -> Unit)? = null,
        ): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "forwardSwipe: service not bound, skipping")
                onResult?.invoke(false)
                return false
            }
            val duration = durationMs.coerceAtLeast(MIN_TAP_DURATION_MS)
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatch = {
                val accepted = svc.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            lastDispatchSucceeded = true
                            onResult?.invoke(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            lastDispatchSucceeded = false
                            Log.w(TAG, "forwardSwipe: gesture cancelled by system")
                            onResult?.invoke(false)
                        }
                    },
                    null,
                )
                if (!accepted) {
                    // dispatchGesture() returned false synchronously — the OS rejected
                    // the request outright (e.g. another gesture already in flight)
                    // and neither callback above will fire.
                    lastDispatchSucceeded = false
                    Log.w(TAG, "forwardSwipe: dispatchGesture rejected the request")
                    onResult?.invoke(false)
                }
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                dispatch()
            } else {
                Handler(Looper.getMainLooper()).post(dispatch)
            }
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        lastDispatchSucceeded = null
        return super.onUnbind(intent)
    }
}
