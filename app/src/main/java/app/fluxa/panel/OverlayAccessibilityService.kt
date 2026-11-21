package app.fluxa

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts the overlay as TYPE_ACCESSIBILITY_OVERLAY: Android 12+ blocks touches that pass through an
 * untrusted opaque overlay; accessibility overlays are trusted, so touches reach the game.
 * It also remembers only the package name of the app in front (never its content), so the profile
 * can tell which game the playtime and fps belong to.
 */
class OverlayAccessibilityService : AccessibilityService() {
    private val launcher by lazy {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == launcher) {
            onHome?.invoke() // lets a panel-only session end when the user goes home
        }
        if (pkg == packageName || pkg == launcher || pkg == "android" ||
            pkg.contains("systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard")
        ) return
        foreground = pkg
        if (pkg != notified) {
            notified = pkg
            onGameChanged?.invoke(pkg)
        }
        onAppForeground?.invoke()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile
        var instance: OverlayAccessibilityService? = null

        /** Package of the last normal app that came to the front ("?" if unknown). */
        @Volatile
        var foreground: String? = null

        fun currentGame(): String = foreground ?: "?"

        /** Last package [onGameChanged] was told about; reset when a session starts so the first game counts. */
        @Volatile
        private var notified: String? = null

        fun resetGameNotify() {
            notified = null
        }

        /** A different normal app came to the front (set by a frame-generation session for per-game profiles). */
        @Volatile
        var onGameChanged: ((String) -> Unit)? = null

        /** The home screen / recents came to the front (set by a panel-only session). */
        @Volatile
        var onHome: (() -> Unit)? = null

        /** A normal app came to the front again (set by a panel-only session). */
        @Volatile
        var onAppForeground: (() -> Unit)? = null
    }
}
