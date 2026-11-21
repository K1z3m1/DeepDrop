package app.fluxa

import app.fluxa.BuildConfig
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import org.lsposed.hiddenapibypass.HiddenApiBypass
import kotlin.math.roundToInt

/**
 * Changes the screen resolution + DPI through the window manager (needs the WRITE_SECURE_SETTINGS
 * permission, granted once over adb) and remembers the original values so they can be restored.
 *
 * The "original" is the panel's REAL size + DPI, read live from the window manager (initial display
 * size / density), so it stays correct even when the screen was already changed before the app opened.
 * Restoring clears the override; it only happens from the confirm dialog or the restore button.
 */
object DisplayTuner {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    const val GRANT_COMMAND = "adb shell pm grant ${BuildConfig.APPLICATION_ID} $PERMISSION"

    const val MIN_PERCENT = 10
    /** 10..100 = render below the panel's size, 101..200 = above it (Android clamps the forced size to 2x the original). */
    const val MAX_PERCENT = 200
    const val PERCENT_STEP = 5
    const val DEFAULT_PERCENT = 75
    const val MIN_DPI = 100
    const val MAX_DPI = 1600
    const val DPI_STEP = 5

    private const val PREFS = "fluxa"
    const val K_ENABLED = "disp_enabled"
    const val K_PERCENT = "disp_percent"
    const val K_DPI_AUTO = "disp_dpi_auto"
    const val K_DPI = "disp_dpi"
    const val K_UPSCALE = "disp_upscale"
    const val K_UPSCALE_FILTER = "disp_upscale_filter"
    const val K_UPSCALE_SHARP = "disp_upscale_sharp"
    const val K_UPSCALE_SHARP_METHOD = "disp_upscale_sharp_method"
    const val K_ENH_DEBAND = "disp_enh_deband"
    const val K_ENH_DENOISE = "disp_enh_denoise"
    const val K_ENH_AA = "disp_enh_aa"
    const val K_ENH_DITHER = "disp_enh_dither"
    const val K_LOOK_BRIGHT = "disp_look_bright"
    const val K_LOOK_CONTRAST = "disp_look_contrast"
    const val K_LOOK_SAT = "disp_look_sat"
    const val K_LOOK_VIB = "disp_look_vib"
    const val K_LOOK_TEMP = "disp_look_temp"
    const val K_LOOK_TINT = "disp_look_tint"
    const val K_UP_PERCENT = "disp_up_percent"
    const val K_CAP_CUBIC = "cap_cubic"

    // Upscale modes: ids shared with the native side (FX_UP_*)
    const val FILTER_NEAREST = 0
    const val FILTER_LINEAR = 1
    const val FILTER_CUBIC = 2
    const val FILTER_SGSR = 3
    const val FILTER_FSR1 = 4
    const val FILTER_LANCZOS = 5
    const val FILTER_CATROM = 6
    const val FILTER_NIS = 7
    const val FILTER_ANIME = 8
    const val FILTER_MITCHELL = 9
    const val FILTER_LANCZOS3 = 10
    const val FILTER_SPLINE36 = 11
    const val FILTER_EWA = 12
    /** Highest valid upscale filter id (the native side clamps to the same value). */
    const val FILTER_MAX = FILTER_EWA

    /** Strength ceiling of the sharpen / denoise / deband sliders: 100 = old maximum, 101..200 = overdrive. */
    const val STRENGTH_MAX = 200

    // Sharpen passes that follow the upscale: ids shared with the native side (FX_SHARP_*)
    const val SHARP_RCAS = 0
    const val SHARP_CAS = 1
    const val SHARP_ADAPTIVE = 2
    const val SHARP_LUMA = 3
    const val SHARP_CLARITY = 4
    const val SHARP_MAX = SHARP_CLARITY

    // Colour grade ("look"): ranges shared with the native side (FX_LOOK_*)
    /** Colour sliders run -GRADE_RANGE..GRADE_RANGE, 0 = untouched. */
    const val GRADE_RANGE = 100

    // Anti-aliasing before the upscale: ids shared with the native side (FX_AA_*)
    const val AA_OFF = 0
    const val AA_FXAA = 1
    const val AA_SMAA = 2

    /** Upscale amount on top of the render size: 0 = no upscale, N = output is N % larger than the render. */
    const val MIN_UP_PERCENT = 0
    const val MAX_UP_PERCENT = 300
    /** Safety cap on the output area (pixels) so a 200 % output cannot exhaust GPU memory. */
    const val MAX_OUT_PIXELS = 16_000_000L
    const val UP_PERCENT_STEP = 5
    /** Smallest side Android accepts for a forced display size. */
    private const val MIN_SIDE_PX = 200
    private const val K_APPLIED = "disp_applied"
    private const val K_ORIG_W = "disp_orig_w"
    private const val K_ORIG_H = "disp_orig_h"
    private const val K_ORIG_DPI = "disp_orig_dpi"
    private const val K_ORIG_HAD_SIZE = "disp_orig_had_size"
    private const val K_ORIG_HAD_DPI = "disp_orig_had_dpi"

    /** Size in the display's natural orientation (what `wm size` uses) + density. */
    data class Original(val width: Int, val height: Int, val dpi: Int, val hadForcedSize: Boolean, val hadForcedDpi: Boolean)

    data class Config(
        val enabled: Boolean,
        val percent: Int,
        val dpiAuto: Boolean,
        val dpi: Int,
        val upscale: Boolean,
        /** One of the FILTER_* modes (nearest ... Lanczos-3, Spline36, EWA Lanczos, SGSR-style, NIS-style, FSR 1). */
        val upscaleFilter: Int = FILTER_LINEAR,
        /** Sharpen strength after upscaling: 0 = off, 1..200 (above 100 = overdrive). */
        val upscaleSharp: Int = 0,
        /** Which sharpen pass the strength drives: [SHARP_RCAS], [SHARP_CAS], [SHARP_ADAPTIVE], [SHARP_LUMA] or [SHARP_CLARITY]. */
        val upscaleSharpMethod: Int = SHARP_RCAS,
        /** Upscale amount relative to the render size: 0 = off, N = output is N % larger than what is rendered. */
        val upPercent: Int = 0,
        /** Deband strength (final pass at output size): 0 = off, 1..200. */
        val deband: Int = 0,
        /** Denoise / deblock strength (pre pass at render size): 0 = off, 1..200. */
        val denoise: Int = 0,
        /** Anti-aliasing before the upscale: [AA_OFF], [AA_FXAA] or [AA_SMAA]. */
        val aa: Int = AA_OFF,
        /** Dither noise before the 8-bit store. */
        val dither: Boolean = false,
        /** Colour grade, each -[GRADE_RANGE]..[GRADE_RANGE], 0 = untouched. */
        val brightness: Int = 0,
        val contrast: Int = 0,
        val saturation: Int = 0,
        val vibrance: Int = 0,
        val temperature: Int = 0,
        val tint: Int = 0,
    ) {
        /** The extra enhancement fields in the native packed int (bits 12..30 above the sharpen bits). */
        fun enhancePacked(): Int = packEnhance(deband, denoise, aa, dither)

        /** Colour grade in the native 64-bit "look" value. */
        fun lookPacked(): Long =
            packLook(brightness, contrast, saturation, vibrance, temperature, tint)
    }

    data class Plan(val width: Int, val height: Int, val dpi: Int)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun config(ctx: Context): Config {
        val p = prefs(ctx)
        return Config(
            enabled = p.getBoolean(K_ENABLED, false),
            percent = p.getInt(K_PERCENT, DEFAULT_PERCENT).coerceIn(MIN_PERCENT, MAX_PERCENT),
            dpiAuto = p.getBoolean(K_DPI_AUTO, true),
            dpi = p.getInt(K_DPI, 400).coerceIn(MIN_DPI, MAX_DPI),
            upscale = p.getBoolean(K_UPSCALE, true),
            upscaleFilter = p.getInt(K_UPSCALE_FILTER, FILTER_LINEAR).coerceIn(FILTER_NEAREST, FILTER_MAX),
            upscaleSharp = p.getInt(K_UPSCALE_SHARP, 0).coerceIn(0, STRENGTH_MAX),
            upscaleSharpMethod = p.getInt(K_UPSCALE_SHARP_METHOD, SHARP_RCAS).coerceIn(SHARP_RCAS, SHARP_MAX),
            upPercent = p.getInt(K_UP_PERCENT, 0).coerceIn(MIN_UP_PERCENT, MAX_UP_PERCENT),
            deband = p.getInt(K_ENH_DEBAND, 0).coerceIn(0, STRENGTH_MAX),
            denoise = p.getInt(K_ENH_DENOISE, 0).coerceIn(0, STRENGTH_MAX),
            aa = p.getInt(K_ENH_AA, AA_OFF).coerceIn(AA_OFF, AA_SMAA),
            dither = p.getBoolean(K_ENH_DITHER, false),
            brightness = p.getInt(K_LOOK_BRIGHT, 0).coerceIn(-GRADE_RANGE, GRADE_RANGE),
            contrast = p.getInt(K_LOOK_CONTRAST, 0).coerceIn(-GRADE_RANGE, GRADE_RANGE),
            saturation = p.getInt(K_LOOK_SAT, 0).coerceIn(-GRADE_RANGE, GRADE_RANGE),
            vibrance = p.getInt(K_LOOK_VIB, 0).coerceIn(-GRADE_RANGE, GRADE_RANGE),
            temperature = p.getInt(K_LOOK_TEMP, 0).coerceIn(-GRADE_RANGE, GRADE_RANGE),
            tint = p.getInt(K_LOOK_TINT, 0).coerceIn(-GRADE_RANGE, GRADE_RANGE),
        )
    }

    /** (mode id, short label) the user can pick; cubic only appears once a run reported support. */
    fun upscaleModes(ctx: Context): List<Pair<Int, String>> = buildList {
        add(FILTER_NEAREST to "Nearest")
        add(FILTER_LINEAR to "Linear")
        if (cubicSupported(ctx)) add(FILTER_CUBIC to "Cubic")
        add(FILTER_MITCHELL to "Mitchell")
        add(FILTER_CATROM to "Catmull-Rom")
        add(FILTER_LANCZOS to "Lanczos-2")
        add(FILTER_LANCZOS3 to "Lanczos-3")
        add(FILTER_SPLINE36 to "Spline36")
        add(FILTER_EWA to "EWA Lanczos")
        add(FILTER_SGSR to "SGSR")
        add(FILTER_NIS to "NIS")
        add(FILTER_FSR1 to "FSR1")
        add(FILTER_ANIME to "Anime")
    }

    /** Whether the GPU the presenter ran on can sample with the cubic filter (learned on a run). */
    fun cubicSupported(ctx: Context): Boolean = prefs(ctx).getBoolean(K_CAP_CUBIC, false)

    fun setCubicSupported(ctx: Context, value: Boolean) {
        if (cubicSupported(ctx) != value) prefs(ctx).edit().putBoolean(K_CAP_CUBIC, value).apply()
    }

    /** Persists the live upscale amount (the drawer changes it while a session runs). */
    fun saveUpPercent(ctx: Context, percent: Int) {
        prefs(ctx).edit().putInt(K_UP_PERCENT, percent.coerceIn(MIN_UP_PERCENT, MAX_UP_PERCENT)).apply()
    }

    /** Persists the live upscale choice (the drawer changes it while a session runs). */
    fun saveUpscale(ctx: Context, mode: Int, sharp: Int, sharpMethod: Int = SHARP_RCAS) {
        prefs(ctx).edit()
            .putInt(K_UPSCALE_FILTER, mode)
            .putInt(K_UPSCALE_SHARP, sharp)
            .putInt(K_UPSCALE_SHARP_METHOD, sharpMethod)
            .apply()
    }

    /**
     * Packs deband / denoise / AA / dither the way the native side reads them (FX_UP_DEBAND etc.):
     * deband bits 12..19, denoise bits 20..27, AA bits 28..29, dither bit 30.
     */
    fun packEnhance(deband: Int, denoise: Int, aa: Int, dither: Boolean): Int =
        (deband.coerceIn(0, STRENGTH_MAX) shl 12) or
            (denoise.coerceIn(0, STRENGTH_MAX) shl 20) or
            (aa.coerceIn(AA_OFF, AA_SMAA) shl 28) or
            (if (dither) 1 shl 30 else 0)

    /**
     * Packs the colour grade the way the native side reads it (FX_LOOK_*): brightness / contrast /
     * saturation / vibrance / temperature / tint as signed bytes at bits 0, 8, 16, 24, 32, 40.
     * All zero = untouched.
     */
    fun packLook(
        brightness: Int, contrast: Int,
        saturation: Int, vibrance: Int, temperature: Int, tint: Int,
    ): Long {
        fun signed(v: Int, shift: Int): Long =
            (v.coerceIn(-GRADE_RANGE, GRADE_RANGE).toLong() and 0xFFL) shl shift
        return signed(brightness, 0) or signed(contrast, 8) or signed(saturation, 16) or
            signed(vibrance, 24) or signed(temperature, 32) or signed(tint, 40)
    }

    /** Persists the live colour grade choice (the drawer changes it while a session runs). */
    fun saveLook(ctx: Context, look: Look) {
        prefs(ctx).edit()
            .putInt(K_LOOK_BRIGHT, look.brightness)
            .putInt(K_LOOK_CONTRAST, look.contrast)
            .putInt(K_LOOK_SAT, look.saturation)
            .putInt(K_LOOK_VIB, look.vibrance)
            .putInt(K_LOOK_TEMP, look.temperature)
            .putInt(K_LOOK_TINT, look.tint)
            .apply()
    }

    /** Colour grade as one value, so the drawer / service pass it around together. */
    data class Look(
        val brightness: Int = 0,
        val contrast: Int = 0,
        val saturation: Int = 0,
        val vibrance: Int = 0,
        val temperature: Int = 0,
        val tint: Int = 0,
    ) {
        fun packed(): Long = packLook(brightness, contrast, saturation, vibrance, temperature, tint)
    }

    fun look(ctx: Context): Look = config(ctx).let {
        Look(it.brightness, it.contrast, it.saturation, it.vibrance, it.temperature, it.tint)
    }

    /** Persists the live enhancement choice (the drawer changes it while a session runs). */
    fun saveEnhance(ctx: Context, deband: Int, denoise: Int, aa: Int, dither: Boolean) {
        prefs(ctx).edit()
            .putInt(K_ENH_DEBAND, deband)
            .putInt(K_ENH_DENOISE, denoise)
            .putInt(K_ENH_AA, aa)
            .putBoolean(K_ENH_DITHER, dither)
            .apply()
    }

    /** (AA id, short label) for the anti-aliasing picker. */
    val aaModes: List<Pair<Int, String>> = listOf(AA_OFF to "ปิด", AA_FXAA to "FXAA", AA_SMAA to "SMAA-lite")

    /** (method id, short label) for the sharpen pass picker. */
    val sharpMethods: List<Pair<Int, String>> = listOf(
        SHARP_RCAS to "RCAS", SHARP_CAS to "CAS", SHARP_ADAPTIVE to "Adaptive",
        SHARP_LUMA to "Luma", SHARP_CLARITY to "Clarity",
    )

    // ---- on/off switches of the in-game panel (the saved values stay, only whether they are applied changes)
    private const val K_ENH_ON = "fx_enh_on"
    private const val K_LOOK_ON = "fx_look_on"

    fun enhanceOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENH_ON, true)
    fun setEnhanceOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_ENH_ON, on).apply()
    fun lookOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_LOOK_ON, true)
    fun setLookOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_LOOK_ON, on).apply()
    fun setUpscaleOn(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_UPSCALE, on).apply()

    fun isApplied(ctx: Context): Boolean = prefs(ctx).getBoolean(K_APPLIED, false)

    @Volatile private var cached: Original? = null

    /**
     * The panel's REAL size + density, ignoring any size/density override already active (ours, `wm size`,
     * another app, the system "display size" setting). Asked live from the window manager every process
     * start, so opening the app while the screen is already changed no longer stores the changed values.
     */
    fun original(ctx: Context): Original? {
        cached?.let { return it }
        refreshOriginal(ctx)
        return cached
    }

    /** Re-reads the real panel values (call at app start). Falls back to the stored copy if the query fails. */
    fun captureOriginalIfNeeded(ctx: Context) = refreshOriginal(ctx)

    private fun refreshOriginal(ctx: Context) {
        val live = queryInitial()
        if (live != null) {
            cached = live
            prefs(ctx).edit()
                .putInt(K_ORIG_W, live.width).putInt(K_ORIG_H, live.height).putInt(K_ORIG_DPI, live.dpi)
                .putBoolean(K_ORIG_HAD_SIZE, false).putBoolean(K_ORIG_HAD_DPI, false) // restoring = clearing the override
                .apply()
            return
        }
        val p = prefs(ctx)
        val w = p.getInt(K_ORIG_W, 0)
        val h = p.getInt(K_ORIG_H, 0)
        val dpi = p.getInt(K_ORIG_DPI, 0)
        if (w > 0 && h > 0 && dpi > 0) {
            cached = Original(w, h, dpi, p.getBoolean(K_ORIG_HAD_SIZE, false), p.getBoolean(K_ORIG_HAD_DPI, false))
            return
        }
        // last resort (window manager query failed, nothing stored): only trust the current values when nothing is overridden
        if (isApplied(ctx) || overridden(ctx)) return
        val cur = current(ctx) ?: return
        cached = Original(cur.width, cur.height, cur.dpi, false, false)
        prefs(ctx).edit().putInt(K_ORIG_W, cur.width).putInt(K_ORIG_H, cur.height).putInt(K_ORIG_DPI, cur.dpi).apply()
    }

    /** Hidden IWindowManager.getInitialDisplaySize / getInitialDisplayDensity: the physical values (natural orientation). */
    private fun queryInitial(): Original? = runCatching {
        val wm = windowManager()
        val cls = Class.forName("android.view.IWindowManager")
        val pt = android.graphics.Point()
        cls.getMethod("getInitialDisplaySize", Int::class.java, android.graphics.Point::class.java)
            .invoke(wm, Display.DEFAULT_DISPLAY, pt)
        val dpi = cls.getMethod("getInitialDisplayDensity", Int::class.java).invoke(wm, Display.DEFAULT_DISPLAY) as Int
        if (pt.x > 0 && pt.y > 0 && dpi > 0) Original(pt.x, pt.y, dpi, false, false) else null
    }.getOrElse {
        null
    }

    /** Whether a forced size or density is active right now. */
    fun overridden(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val size = runCatching { !Settings.Global.getString(cr, "display_size_forced").isNullOrEmpty() }.getOrDefault(false)
        val dens = runCatching { !Settings.Secure.getString(cr, "display_density_forced").isNullOrEmpty() }.getOrDefault(false)
        return size || dens
    }

    /** What the display is set to right now, size in the natural orientation (like `wm size`). */
    fun current(ctx: Context): Original? {
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(m)
        val landscapeNow = display.rotation == android.view.Surface.ROTATION_90 ||
            display.rotation == android.view.Surface.ROTATION_270
        val w = if (landscapeNow) m.heightPixels else m.widthPixels
        val h = if (landscapeNow) m.widthPixels else m.heightPixels
        // natural orientation of the panel may be landscape (tablets): follow the real size's shape
        val real = cached
        val swap = real != null && (real.width >= real.height) != (w >= h)
        return Original(if (swap) h else w, if (swap) w else h, m.densityDpi, false, false)
    }

    private fun even(v: Double): Int = maxOf(2, (v / 2.0).roundToInt() * 2)

    fun plan(orig: Original, cfg: Config): Plan {
        val f = cfg.percent / 100.0
        val dpi = if (cfg.dpiAuto) (orig.dpi * f).roundToInt() else cfg.dpi
        var w = orig.width * f
        var h = orig.height * f
        // Android refuses sides under 200 px (it would clamp them and distort the aspect): lift both together
        val short = minOf(w, h)
        if (short < MIN_SIDE_PX) {
            val k = MIN_SIDE_PX / short
            w *= k
            h *= k
        }
        // round the long side to even, derive the other from the real aspect so the picture is never stretched
        val ratio = orig.height.toDouble() / orig.width
        val pw: Int
        val ph: Int
        if (orig.width >= orig.height) {
            pw = even(w); ph = even(pw * ratio)
        } else {
            ph = even(h); pw = even(ph / ratio)
        }
        return Plan(pw, ph, dpi.coerceIn(MIN_DPI, MAX_DPI))
    }

    /**
     * Output size for an upscale of [upPercent] on top of the render size [w] x [h]:
     * 0 -> no upscale, otherwise render x (1 + up / 100), area capped by [MAX_OUT_PIXELS].
     * Returns 0 x 0 when nothing is upscaled.
     */
    fun upscaleTarget(w: Int, h: Int, upPercent: Int): Pair<Int, Int> {
        if (upPercent <= 0) return 0 to 0
        val f = 1.0 + upPercent / 100.0
        var ow = even(w * f)
        var oh = even(h * f)
        val area = ow.toLong() * oh
        if (area > MAX_OUT_PIXELS) {
            val k = Math.sqrt(MAX_OUT_PIXELS.toDouble() / area)
            ow = even(ow * k)
            oh = even(oh * k)
        }
        return if (ow > w && oh > h) ow to oh else 0 to 0
    }

    /** Plan for the next start, or null when the feature is off / not permitted / nothing to change. */
    fun planForStart(ctx: Context): Plan? {
        val cfg = config(ctx)
        if (!cfg.enabled || !canWrite(ctx)) return null
        captureOriginalIfNeeded(ctx)
        val orig = original(ctx) ?: return null
        return plan(orig, cfg)
    }

    /** Applies [plan]. Nothing restores it automatically: only the "คืนค่าจอเดิม" button calls [restore]. */
    fun apply(ctx: Context, plan: Plan): Boolean {
        captureOriginalIfNeeded(ctx)
        if (!canWrite(ctx)) {
            return false
        }
        prefs(ctx).edit().putBoolean(K_APPLIED, true).commit() // only guards captureOriginalIfNeeded
        return runCatching {
            val wm = windowManager()
            setSize(wm, plan.width, plan.height)
            setDensity(wm, plan.dpi)
            true
        }.getOrElse {
            false
        }
    }

    /** Puts the stored original size + DPI back. */
    fun restore(ctx: Context): Boolean {
        val orig = original(ctx)
        val ok = runCatching {
            val wm = windowManager()
            // back to the panel's real values: dropping the override is exact (no stale stored numbers involved)
            clearSize(wm)
            clearDensity(wm)
            true
        }.getOrElse {
            false
        }
        if (ok) prefs(ctx).edit().putBoolean(K_APPLIED, false).commit()
        return ok
    }

    // ---------------------------------------------------------------- IWindowManager (hidden API)

    @Volatile private var exempted = false

    private fun windowManager(): Any {
        if (!exempted) {
            HiddenApiBypass.addHiddenApiExemptions("L")
            exempted = true
        }
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "window") as IBinder
        return Class.forName("android.view.IWindowManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private fun call(wm: Any, name: String, vararg args: Int) {
        val types = Array<Class<*>>(args.size) { Int::class.java }
        val method = Class.forName("android.view.IWindowManager").getMethod(name, *types)
        method.invoke(wm, *args.toTypedArray())
    }

    private val userId get() = Process.myUid() / 100000

    private fun setSize(wm: Any, w: Int, h: Int) = call(wm, "setForcedDisplaySize", Display.DEFAULT_DISPLAY, w, h)
    private fun clearSize(wm: Any) = call(wm, "clearForcedDisplaySize", Display.DEFAULT_DISPLAY)
    private fun setDensity(wm: Any, dpi: Int) = call(wm, "setForcedDisplayDensityForUser", Display.DEFAULT_DISPLAY, dpi, userId)
    private fun clearDensity(wm: Any) = call(wm, "clearForcedDisplayDensityForUser", Display.DEFAULT_DISPLAY, userId)
}
