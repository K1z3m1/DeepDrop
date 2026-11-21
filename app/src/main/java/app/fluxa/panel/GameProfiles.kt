package app.fluxa

import android.content.Context
import org.json.JSONObject

/**
 * Per-game profiles: the frame-generation / upscale / enhance / colour settings remembered for one
 * game (keyed by package name, which [OverlayAccessibilityService] already tracks). While a session
 * runs and a game with a profile comes to the front, its values are applied live; a game without a
 * profile gets the values the session started with back. Only package names and numbers are stored.
 */
object GameProfiles {
    private const val PREFS = "fluxa_game_profiles"
    private const val K_AUTO = "auto_apply"
    private const val P = "p_"

    /** Frame-generation settings are session-only in the app (not saved to prefs), so they live here. */
    data class Snapshot(
        val multiplier: Int,
        val targetMode: Boolean,
        val targetFps: Int,
        val genMode: Int,
        val flowSide: Int,
        val presentMode: Int,
        val upFilter: Int,
        val upSharp: Int,
        val upSharpMethod: Int,
        val upPercent: Int,
        val deband: Int,
        val denoise: Int,
        val aa: Int,
        val dither: Boolean,
        val look: DisplayTuner.Look,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("mult", multiplier); put("tmode", targetMode); put("tfps", targetFps)
            put("gen", genMode); put("flow", flowSide); put("present", presentMode)
            put("upf", upFilter); put("ups", upSharp); put("upm", upSharpMethod); put("upp", upPercent)
            put("deb", deband); put("den", denoise); put("aa", aa); put("dit", dither)
            put("lb", look.brightness); put("lc", look.contrast); put("ls", look.saturation)
            put("lv", look.vibrance); put("lt", look.temperature); put("li", look.tint)
        }.toString()

        companion object {
            fun fromJson(s: String): Snapshot? = runCatching {
                val j = JSONObject(s)
                Snapshot(
                    multiplier = FluxaOptions.clampMultiplier(j.getInt("mult")),
                    targetMode = j.getBoolean("tmode"),
                    targetFps = FluxaOptions.clampTargetFps(j.getInt("tfps")),
                    genMode = FluxaOptions.clampGenMode(j.getInt("gen")),
                    flowSide = FluxaOptions.clampFlowSide(j.getInt("flow")),
                    presentMode = FluxaOptions.clampPresentMode(j.getInt("present")),
                    upFilter = j.getInt("upf").coerceIn(DisplayTuner.FILTER_NEAREST, DisplayTuner.FILTER_MAX),
                    upSharp = j.getInt("ups").coerceIn(0, DisplayTuner.STRENGTH_MAX),
                    upSharpMethod = j.getInt("upm").coerceIn(DisplayTuner.SHARP_RCAS, DisplayTuner.SHARP_MAX),
                    upPercent = j.getInt("upp").coerceIn(DisplayTuner.MIN_UP_PERCENT, DisplayTuner.MAX_UP_PERCENT),
                    deband = j.getInt("deb").coerceIn(0, DisplayTuner.STRENGTH_MAX),
                    denoise = j.getInt("den").coerceIn(0, DisplayTuner.STRENGTH_MAX),
                    aa = j.getInt("aa").coerceIn(DisplayTuner.AA_OFF, DisplayTuner.AA_SMAA),
                    dither = j.getBoolean("dit"),
                    look = DisplayTuner.Look(
                        j.getInt("lb"), j.getInt("lc"), j.getInt("ls"),
                        j.getInt("lv"), j.getInt("lt"), j.getInt("li"),
                    ),
                )
            }.getOrNull()
        }
    }

    /** What the session had before the first profile was applied; handed back for games without one. */
    private var base: Snapshot? = null

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- storage

    fun has(ctx: Context, pkg: String): Boolean = prefs(ctx).contains(P + pkg)

    fun get(ctx: Context, pkg: String): Snapshot? =
        prefs(ctx).getString(P + pkg, null)?.let { Snapshot.fromJson(it) }

    fun save(ctx: Context, pkg: String, snap: Snapshot) {
        prefs(ctx).edit().putString(P + pkg, snap.toJson()).apply()
    }

    fun remove(ctx: Context, pkg: String) {
        prefs(ctx).edit().remove(P + pkg).apply()
    }

    /** Package names that have a profile, sorted by their app label. */
    fun all(ctx: Context): List<String> =
        prefs(ctx).all.keys.filter { it.startsWith(P) }.map { it.removePrefix(P) }
            .sortedBy { label(ctx, it).lowercase() }

    fun autoOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_AUTO, true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_AUTO, on).apply()

    fun label(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** A game can only be told when the accessibility service saw an app come to the front. */
    fun knownGame(pkg: String): Boolean = pkg.isNotEmpty() && pkg != "?"

    // ---- capture / apply

    /** The values in force right now: live presenter state while a session runs, saved prefs for the rest. */
    fun capture(ctx: Context): Snapshot {
        val c = DisplayTuner.config(ctx)
        return Snapshot(
            multiplier = FluxaOptions.clampMultiplier(Fluxa.multiplier),
            targetMode = Fluxa.targetMode,
            targetFps = FluxaOptions.clampTargetFps(Fluxa.targetFps),
            genMode = FluxaOptions.clampGenMode(Fluxa.genMode),
            flowSide = FluxaOptions.clampFlowSide(Fluxa.flowSide),
            presentMode = FluxaOptions.clampPresentMode(Fluxa.presentMode),
            upFilter = c.upscaleFilter,
            upSharp = c.upscaleSharp,
            upSharpMethod = c.upscaleSharpMethod,
            upPercent = c.upPercent,
            deband = c.deband,
            denoise = c.denoise,
            aa = c.aa,
            dither = c.dither,
            look = DisplayTuner.look(ctx),
        )
    }

    /**
     * Applies [s] to the running presenter and persists the display values (the panel pages read them
     * from prefs). [onOutputPercent] runs when the upscale amount changed, because that resizes the output.
     */
    fun apply(ctx: Context, s: Snapshot, onOutputPercent: () -> Unit = {}) {
        val oldPercent = DisplayTuner.config(ctx).upPercent
        Fluxa.reconfigure(s.multiplier, s.flowSide)
        Fluxa.setTargetMode(s.targetMode)
        Fluxa.setTargetFps(s.targetFps)
        Fluxa.setGenMode(s.genMode)
        Fluxa.setPresentMode(s.presentMode)

        DisplayTuner.saveUpscale(ctx, s.upFilter, s.upSharp, s.upSharpMethod)
        DisplayTuner.saveEnhance(ctx, s.deband, s.denoise, s.aa, s.dither)
        DisplayTuner.saveLook(ctx, s.look)
        DisplayTuner.saveUpPercent(ctx, s.upPercent)

        Fluxa.setUpscale(s.upFilter, s.upSharp, s.upSharpMethod)
        // the panel's on/off switches stay as the user left them
        if (DisplayTuner.enhanceOn(ctx)) Fluxa.setEnhance(s.deband, s.denoise, s.aa, s.dither)
        if (DisplayTuner.lookOn(ctx)) Fluxa.setLook(s.look)
        if (s.upPercent != oldPercent) onOutputPercent()
    }

    // ---- session switching

    /**
     * A game came to the front during a session: apply its profile, or hand back the session's own
     * values when it has none. Returns the profile's game label when one was applied (for a toast).
     */
    fun onGameChanged(ctx: Context, pkg: String, onOutputPercent: () -> Unit): String? {
        if (!autoOn(ctx) || !knownGame(pkg)) return null
        val profile = get(ctx, pkg)
        if (profile != null) {
            if (base == null) base = capture(ctx)
            apply(ctx, profile, onOutputPercent)
            return label(ctx, pkg)
        }
        base?.let {
            apply(ctx, it, onOutputPercent)
            base = null
        }
        return null
    }

    /** Session ended: put the pre-profile values back in prefs so the next start is not left on a game's look. */
    fun endSession(ctx: Context) {
        val b = base ?: return
        base = null
        DisplayTuner.saveUpscale(ctx, b.upFilter, b.upSharp, b.upSharpMethod)
        DisplayTuner.saveEnhance(ctx, b.deband, b.denoise, b.aa, b.dither)
        DisplayTuner.saveLook(ctx, b.look)
        DisplayTuner.saveUpPercent(ctx, b.upPercent)
    }

    /** A new session starts from the app's own values. */
    fun beginSession() {
        base = null
    }
}
