package app.fluxa

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.util.concurrent.TimeUnit

/**
 * What the RAM detail page can do to an app, and what it takes.
 *
 *  - Stop (end background processes): works for every app, no extra rights.
 *  - Force stop: Android does not let a normal app do it, so the app-info page opens, where "Force stop" is one tap.
 *  - Freeze (disable the app until you unfreeze it): Android only lets the shell do this, so the page hands
 *    over the one-line adb command. A normal app cannot do it by itself.
 *
 * Android also hides other apps' processes and memory from a normal app, so the list is built from
 * Usage access (the same source as the task manager): recently used apps, with the ones whose last event
 * was "came to the front" marked as in use right now.
 */
class AppControl(private val ctx: Context) {
    class AppItem(val pkg: String, val label: String, val icon: Drawable?, val last: Long, val foreground: Boolean)

    private val prefs = ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
    private val am get() = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // ------------------------------------------------------------------ list

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccess() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Recently used apps, newest first. Blocking; call off the main thread. */
    fun recent(limit: Int = 40): List<AppItem> {
        val pm = ctx.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .filter { it != ctx.packageName && it != home }
            .toSet()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - TimeUnit.HOURS.toMillis(24), now)
        val last = HashMap<String, Long>()
        val fg = HashMap<String, Boolean>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // 1 = moved to foreground, 2 = moved to background
            if ((e.eventType == 1 || e.eventType == 2) && e.packageName in launchable) {
                if (e.timeStamp >= (last[e.packageName] ?: 0L)) {
                    last[e.packageName] = e.timeStamp
                    fg[e.packageName] = e.eventType == 1
                }
            }
        }
        return last.entries.sortedByDescending { it.value }.take(limit).mapNotNull { (pkg, t) ->
            runCatching {
                AppItem(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(), pm.getApplicationIcon(pkg), t, fg[pkg] == true)
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ actions

    /** Ends the app's background processes. Does nothing to an app that is on screen. */
    fun stop(pkg: String) {
        runCatching { am.killBackgroundProcesses(pkg) }
    }

    fun openAppInfo(pkg: String) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Ends the app's background processes and opens its app-info page, where "Force stop" is one tap. */
    fun forceStop(pkg: String) {
        stop(pkg)
        openAppInfo(pkg)
    }

    fun adbFreeze(pkg: String) = "adb shell pm disable-user --user 0 $pkg"
    fun adbUnfreeze(pkg: String) = "adb shell pm enable $pkg"

    /** Called after the person ran the adb command, so the page can offer "unfreeze" later. */
    fun markFrozen(pkg: String, label: String, frozen: Boolean) {
        val set = frozenRaw().filterNot { it.substringBefore('|') == pkg }.toMutableSet()
        if (frozen) set += "$pkg|$label"
        prefs.edit().putStringSet(KEY_FROZEN, set).apply()
    }

    /** Frozen apps as (package, label), kept by Fluxa so they can be listed even though they leave the launcher. */
    fun frozen(): List<Pair<String, String>> =
        frozenRaw().map { it.substringBefore('|') to it.substringAfter('|', it.substringBefore('|')) }.sortedBy { it.second.lowercase() }

    private fun frozenRaw(): Set<String> = prefs.getStringSet(KEY_FROZEN, emptySet()) ?: emptySet()

    private companion object {
        const val KEY_FROZEN = "frozen_apps"
    }
}
