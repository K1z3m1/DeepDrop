package app.fluxa

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Shared logic for the game-mode buttons (do not disturb, brightness lock) and the RAM cleaner, used
 * by both the home tab ([HomeLauncher]) and the in-game drawer ([DrawerOverlay]).
 */
class GameTools(private val ctx: Context) {
    enum class Need { NONE, POLICY_ACCESS, WRITE_SETTINGS, FAILED }

    private val prefs = ctx.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
    private val nm get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val am get() = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm get() = ctx.packageManager
    private val ui = Handler(Looper.getMainLooper())

    // ---- do not disturb
    fun dndOn(): Boolean {
        if (!nm.isNotificationPolicyAccessGranted) return false
        val f = nm.currentInterruptionFilter
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    fun toggleDnd(): Need {
        if (!nm.isNotificationPolicyAccessGranted) return Need.POLICY_ACCESS
        val target = if (dndOn()) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        return if (runCatching { nm.setInterruptionFilter(target) }.isSuccess) Need.NONE else Need.FAILED
    }

    // ---- brightness lock
    private fun raw(pct: Int) = (pct.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)

    private fun currentPct(): Int {
        val v = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (v * 100 / 255).coerceIn(1, 100)
    }

    /** The level the lock holds (percent); defaults to the current brightness the first time. */
    fun savedLevel(): Int = prefs.getInt(KEY_LEVEL, currentPct())

    /** The lock only counts while auto-brightness is still off (the user may have switched it back on). */
    fun lockOn(): Boolean {
        if (!prefs.getBoolean(KEY_LOCKED, false)) return false
        return runCatching {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }.getOrDefault(false)
    }

    private fun write(rawValue: Int) {
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, rawValue) }
    }

    /** Slider moved: remember the level, and apply it right away when the lock is on. */
    fun setLevel(pct: Int) {
        prefs.edit().putInt(KEY_LEVEL, pct).apply()
        if (lockOn() && Settings.System.canWrite(ctx)) write(raw(pct))
    }

    fun toggleLock(): Need {
        if (!Settings.System.canWrite(ctx)) return Need.WRITE_SETTINGS
        val cr = ctx.contentResolver
        if (!lockOn()) {
            val prevMode = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE) }
                .getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val prevRaw = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
            prefs.edit().putInt(KEY_PREV_MODE, prevMode).putInt(KEY_PREV_RAW, prevRaw).putBoolean(KEY_LOCKED, true).apply()
            runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) }
            write(raw(savedLevel()))
        } else {
            val prevMode = prefs.getInt(KEY_PREV_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            write(prefs.getInt(KEY_PREV_RAW, 128))
            runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, prevMode) }
            prefs.edit().putBoolean(KEY_LOCKED, false).apply()
        }
        return Need.NONE
    }

    /** Shows the toast and opens the Settings page a [Need] asks for. */
    fun handle(need: Need) {
        when (need) {
            Need.NONE -> Unit
            Need.POLICY_ACCESS -> {
                I18n.toastMake(ctx, "ต้องให้สิทธิ์ \"ห้ามรบกวน\" ให้แอพนี้ก่อน แล้วกลับมากดอีกครั้ง", Toast.LENGTH_LONG).show()
                open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            Need.WRITE_SETTINGS -> {
                I18n.toastMake(ctx, "ต้องให้สิทธิ์ \"แก้ไขการตั้งค่าระบบ\" ให้แอพนี้ก่อน แล้วกลับมากดอีกครั้ง", Toast.LENGTH_LONG).show()
                open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
            Need.FAILED -> I18n.toastMake(ctx, "เปลี่ยนโหมดห้ามรบกวนไม่ได้", Toast.LENGTH_SHORT).show()
        }
    }

    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- RAM
    class Ram(val total: Long, val avail: Long) {
        val used get() = (total - avail).coerceAtLeast(0)
        val percent get() = if (total > 0) (used * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    fun ram(): Ram = ActivityManager.MemoryInfo().let { am.getMemoryInfo(it); Ram(it.totalMem, it.availMem) }

    fun fmt(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    private fun launchable(): List<String> {
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != ctx.packageName && it != home }
    }

    /** Asks Android to stop the background processes of the installed apps; [onDone] gets the freed bytes on the main thread. */
    fun clean(onDone: (Long) -> Unit) {
        val before = ram().avail
        thread(name = "ram-clean") {
            launchable().forEach { runCatching { am.killBackgroundProcesses(it) } }
            Thread.sleep(1500)
            val freed = ram().avail - before
            ui.post { onDone(freed) }
        }
    }

    fun cleanMessage(freed: Long): String =
        if (freed > 8L * 1048576) "ทำความสะอาดแล้ว · RAM ว่างเพิ่ม ${fmt(freed)}"
        else "ทำความสะอาดแล้ว · ไม่มีแอพเบื้องหลังที่ต้องปิดเพิ่ม"

    companion object {
        private const val KEY_LOCKED = "bl_locked"
        private const val KEY_LEVEL = "bl_level"
        private const val KEY_PREV_MODE = "bl_prev_mode"
        private const val KEY_PREV_RAW = "bl_prev_raw"
    }
}
