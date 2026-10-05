package com.firstt175.novaframe.session.diagnostics

import com.firstt175.novaframe.session.NovaLog

import android.app.ActivityManager
import android.content.Context

/**
 * Backs the "CLEAN RAM" button on the drawer's HUD & OVERLAY page (see
 * [com.firstt175.novaframe.session.overlay.SettingsDrawerOverlay]).
 *
 * Deliberately limited to what a normal, non-rooted Android app can actually
 * do: [ActivityManager.killBackgroundProcesses] on other apps' already-cached
 * processes — the same effect as swiping an app away in Recents, just for
 * every cached app at once. It never touches:
 *  - this app's own process, or the game currently being captured/mirrored
 *    (both passed in via [keepPackages]);
 *  - system UI, launcher, or input-method packages, since killing those just
 *    bounces the user out of what they're doing for no RAM benefit;
 *  - anything at [ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE]
 *    or more important — an active foreground/background service (music,
 *    downloads, ...) is not "background junk", it's something running for a
 *    reason, so only fully cached (idle, no active component) processes are
 *    eligible.
 *
 * It does not, and cannot, drop kernel page cache the way a rooted "clear
 * RAM" tool would (e.g. `echo 1 > /proc/sys/vm/drop_caches`) — that needs
 * root, which this app does not have even when Shizuku is available (Shizuku
 * runs as the adb shell UID, not root). See [ShizukuDisplayPermission] if a
 * privileged path is ever added here.
 */
object RamCleaner {
    private const val TAG = "RamCleaner"

    private val ALWAYS_KEEP_PREFIXES = listOf(
        "com.android.systemui",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.android.launcher",
        "android",
    )

    /**
     * Kills every other app's cached background processes. Returns the number
     * of distinct packages killed. Cheap IPC only (no I/O) — safe to call from
     * any thread, but callers still do it off the main thread since a fresh
     * [ActivityManager.getRunningAppProcesses] call plus several kills can add
     * up to a few milliseconds of binder round-trips.
     */
    fun cleanBackgroundApps(context: Context, keepPackages: Set<String>): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0
        val processes = runCatching { am.runningAppProcesses }.getOrNull() ?: return 0

        val targets = LinkedHashSet<String>()
        for (proc in processes) {
            // Only fully cached/idle processes — never a running service.
            if (proc.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) continue
            for (pkg in proc.pkgList.orEmpty()) {
                if (pkg in keepPackages) continue
                if (ALWAYS_KEEP_PREFIXES.any { pkg.startsWith(it) }) continue
                targets += pkg
            }
        }

        for (pkg in targets) {
            runCatching { am.killBackgroundProcesses(pkg) }
                .onFailure { NovaLog.e(TAG, "killBackgroundProcesses($pkg) failed", it) }
        }
        NovaLog.i(TAG, "cleanBackgroundApps: killed ${targets.size} package(s): $targets")
        return targets.size
    }
}
