package app.fluxa

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Vibrator
import android.provider.Settings
import android.view.Display
import android.view.Surface

/**
 * Which buttons the in-game panel shows and in what order. Same "fluxa" SharedPreferences as the rest
 * of the app. Ids are the stable names of the tiles in [DrawerOverlay]; a tile that is not listed yet
 * (added by a later version) is appended after the saved ones, so nothing ever disappears by itself.
 */
object PanelLayout {
    private const val K_ORDER = "panel_tile_order"
    private const val K_HIDDEN = "panel_tile_hidden"

    /** The settings tile can be moved but never hidden, so the user cannot lock themselves out of this page. */
    const val PINNED = "panel"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): List<String> =
        (p(ctx).getString(key, "") ?: "").split(',').filter { it.isNotEmpty() }

    /** Saved order merged with [defaults]: unknown saved ids are dropped, new ids go to the end. */
    fun order(ctx: Context, defaults: List<String>): List<String> {
        val saved = read(ctx, K_ORDER).filter { it in defaults }.distinct()
        return saved + defaults.filter { it !in saved }
    }

    fun hidden(ctx: Context): Set<String> = read(ctx, K_HIDDEN).toSet() - PINNED

    fun setHidden(ctx: Context, id: String, hide: Boolean) {
        if (id == PINNED) return
        val set = read(ctx, K_HIDDEN).toMutableSet()
        if (hide) set += id else set -= id
        p(ctx).edit().putString(K_HIDDEN, set.joinToString(",")).apply()
    }

    /** Swaps [id] with [neighbour] in the saved order. */
    fun swap(ctx: Context, defaults: List<String>, id: String, neighbour: String) {
        val list = order(ctx, defaults).toMutableList()
        val a = list.indexOf(id)
        val b = list.indexOf(neighbour)
        if (a < 0 || b < 0) return
        list[a] = neighbour
        list[b] = id
        p(ctx).edit().putString(K_ORDER, list.joinToString(",")).apply()
    }

    /**
     * Saves what the drag editor produced. [order] is the new slot order of the buttons that were on screen
     * (buttons this phone cannot show keep their old hidden state), [hidden] the ones switched off.
     */
    fun save(ctx: Context, order: List<String>, hidden: Set<String>) {
        val keepHidden = read(ctx, K_HIDDEN).toSet() - order.toSet()
        val allHidden = (keepHidden + hidden) - PINNED
        p(ctx).edit()
            .putString(K_ORDER, order.joinToString(","))
            .putString(K_HIDDEN, allHidden.joinToString(","))
            .apply()
    }

    fun reset(ctx: Context) {
        p(ctx).edit().remove(K_ORDER).remove(K_HIDDEN).apply()
    }
}

/**
 * The phone's own buttons and quick toggles for the panel. Every function says whether this phone can do it
 * ([supported] checks), so the panel only shows what really works here.
 *
 * Wi-Fi and Bluetooth are deliberately not controlled from the panel (security): no switching, no quick-tile helper.
 */
class PhoneButtons(private val ctx: Context) {
    private val app = ctx.applicationContext
    private val pm = ctx.packageManager
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ------------------------------------------------------------------ system launch helper

    private fun start(intent: Intent): Boolean =
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    // ------------------------------------------------------------------ orientation

    /** 0 = auto-rotate, 1 = locked landscape, 2 = locked portrait. */
    fun rotationMode(): Int {
        val cr = app.contentResolver
        val auto = runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION) }.getOrDefault(1)
        if (auto == 1) return 0
        val user = runCatching { Settings.System.getInt(cr, Settings.System.USER_ROTATION) }.getOrDefault(Surface.ROTATION_0)
        return if (user == landscapeRotation()) 1 else 2
    }

    fun rotationSupported() = pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_ACCELEROMETER)

    fun canWriteSettings() = Settings.System.canWrite(app)

    /** Natural orientation of the panel (phones: portrait). The rotation value of "landscape" depends on it. */
    private fun naturalPortrait(): Boolean {
        val d = (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            ?: return true
        val size = android.graphics.Point()
        @Suppress("DEPRECATION")
        d.getRealSize(size)
        val rotated = d.rotation == Surface.ROTATION_90 || d.rotation == Surface.ROTATION_270
        val portraitNow = size.y >= size.x
        return if (rotated) !portraitNow else portraitNow
    }

    private fun landscapeRotation() = if (naturalPortrait()) Surface.ROTATION_90 else Surface.ROTATION_0
    private fun portraitRotation() = if (naturalPortrait()) Surface.ROTATION_0 else Surface.ROTATION_90

    /** auto -> landscape -> portrait -> auto. False when "Modify system settings" is not granted. */
    fun cycleRotation(): Boolean {
        if (!canWriteSettings()) return false
        val cr = app.contentResolver
        when (rotationMode()) {
            0 -> {
                runCatching { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) }
                runCatching { Settings.System.putInt(cr, Settings.System.USER_ROTATION, landscapeRotation()) }
            }
            1 -> runCatching { Settings.System.putInt(cr, Settings.System.USER_ROTATION, portraitRotation()) }
            else -> runCatching { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) }
        }
        return true
    }

    // ------------------------------------------------------------------ volume / ringer

    fun volumeSupported() = !audio.isVolumeFixed

    fun volume(up: Boolean) {
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    /** 0 = normal, 1 = vibrate, 2 = silent. */
    fun ringerMode(): Int = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_VIBRATE -> 1
        AudioManager.RINGER_MODE_SILENT -> 2
        else -> 0
    }

    @Suppress("DEPRECATION")
    private fun hasVibrator() = (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.hasVibrator() == true

    /** normal -> vibrate (if the phone can) -> silent -> normal. Silent needs the do-not-disturb permission. */
    fun cycleRinger(): GameTools.Need {
        val next = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> if (hasVibrator()) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (next == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted) return GameTools.Need.POLICY_ACCESS
        return if (runCatching { audio.ringerMode = next }.isSuccess) GameTools.Need.NONE else GameTools.Need.FAILED
    }

    // ------------------------------------------------------------------ the phone's navigation / power buttons

    /** Back, Home, Recents, shade, quick settings, power menu, lock, screenshot all go through the accessibility service. */
    fun globalSupported() = OverlayAccessibilityService.instance != null

    fun global(action: Int): Boolean = OverlayAccessibilityService.instance?.performGlobalAction(action) ?: false

    companion object {
        const val BACK = AccessibilityService.GLOBAL_ACTION_BACK
        const val HOME = AccessibilityService.GLOBAL_ACTION_HOME
        const val RECENTS = AccessibilityService.GLOBAL_ACTION_RECENTS
        const val SHADE = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        const val QUICK = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        const val POWER = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
        const val LOCK = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        const val SHOT = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
    }
}

/** Flashlight through the camera API (needs no camera permission); keeps the live on/off state. */
class Torch(ctx: Context, private val onChange: () -> Unit) {
    private val cm = ctx.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Camera id of a flash unit (back camera first), or null when the phone has none. */
    private val id: String? = runCatching {
        val withFlash = cm.cameraIdList.filter {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        withFlash.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
    }.getOrNull()

    val supported: Boolean get() = id != null

    @Volatile
    var on = false
        private set

    private var registered = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != id) return
            on = enabled
            onChange()
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == id) onChange()
        }
    }

    fun register(handler: Handler) {
        if (id == null || registered) return
        registered = runCatching { cm.registerTorchCallback(callback, handler) }.isSuccess
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { cm.unregisterTorchCallback(callback) }
    }

    /** False when the flash cannot be switched right now (the camera is in use by another app). */
    fun toggle(): Boolean {
        val cam = id ?: return false
        return runCatching { cm.setTorchMode(cam, !on) }.isSuccess
    }
}
