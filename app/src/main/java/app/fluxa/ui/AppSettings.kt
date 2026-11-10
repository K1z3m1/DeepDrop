package app.fluxa

import android.app.Activity
import android.content.Context
import android.os.Build

/**
 * User-facing look & feel settings (theme, blur, animations, UI sizes, language). Stored in the same
 * "fluxa" SharedPreferences as everything else; read through [load], which every [Kit] calls first,
 * so the main app, the compare screen and the in-game drawer all agree.
 */
object AppSettings {
    const val K_BASE = "ui_theme_base"          // 0 dark, 1 AMOLED, 2 light, 3 RedMagic game
    const val K_ACCENT = "ui_theme_accent"      // 0..5 presets, 6 custom hue
    const val K_HUE = "ui_theme_hue"            // 0..360 for the custom accent
    const val K_ICON_ANIM = "ui_anim_icons"
    const val K_BLUR = "ui_blur"
    const val K_PAGE_ANIM = "ui_page_anim"      // 0 none, 1 fade, 2 slide, 3 zoom, 4 rise
    const val K_ANIM_MS = "ui_anim_ms"
    const val K_TEXT_SCALE = "ui_text_scale"    // percent
    const val K_RADIUS = "ui_radius_scale"      // percent
    const val K_GAP = "ui_card_gap"             // dp
    const val K_TAB_LABELS = "ui_tab_labels"
    const val K_LANG = "ui_lang"                // 0 th, 1 en, 2 zh, 3 es, 4 ja, 5 ko

    const val BASE_GAME = 3
    const val ACCENT_RED = 5

    const val ANIM_NONE = 0
    const val ANIM_FADE = 1
    const val ANIM_SLIDE = 2
    const val ANIM_ZOOM = 3
    const val ANIM_RISE = 4

    var base = BASE_GAME; private set
    var accent = ACCENT_RED; private set
    var hue = 30; private set
    var iconAnim = true; private set
    var blur = false; private set
    var pageAnim = ANIM_SLIDE; private set
    var animMs = 280; private set
    var textScale = 1f; private set
    var radiusScale = 1f; private set
    var cardGap = 8; private set
    var tabLabels = true; private set
    var lang = 0; private set

    /** True for the RedMagic "Game Space" look: black surfaces, red accent, chamfered corners. */
    val game: Boolean get() = base == BASE_GAME

    /** Blur of the page transition needs RenderEffect (Android 12+). */
    val canBlurViews: Boolean get() = Build.VERSION.SDK_INT >= 31

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE)
        base = p.getInt(K_BASE, BASE_GAME).coerceIn(0, 3)
        accent = p.getInt(K_ACCENT, ACCENT_RED).coerceIn(0, 6)
        hue = p.getInt(K_HUE, 30).coerceIn(0, 360)
        iconAnim = p.getBoolean(K_ICON_ANIM, true)
        blur = p.getBoolean(K_BLUR, false)
        pageAnim = p.getInt(K_PAGE_ANIM, ANIM_SLIDE).coerceIn(0, 4)
        animMs = p.getInt(K_ANIM_MS, 280).coerceIn(100, 600)
        textScale = p.getInt(K_TEXT_SCALE, 100).coerceIn(80, 140) / 100f
        radiusScale = p.getInt(K_RADIUS, 100).coerceIn(20, 160) / 100f
        cardGap = p.getInt(K_GAP, 8).coerceIn(4, 28)
        tabLabels = p.getBoolean(K_TAB_LABELS, true)
        lang = p.getInt(K_LANG, 0).coerceIn(0, I18n.LANG_NAMES.size - 1)
        Palette.applyTheme(base, accent, hue, blur)
    }

    /** Hash of everything that needs a screen rebuild when it changes. */
    fun signature(): Int = listOf(
        base, accent, hue, blur, textScale, radiusScale, cardGap, tabLabels, lang, Palette.PRIMARY, Palette.BG,
    ).hashCode()

    fun reset(ctx: Context) {
        ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE).edit()
            .remove(K_BASE).remove(K_ACCENT).remove(K_HUE).remove(K_ICON_ANIM).remove(K_BLUR)
            .remove(K_PAGE_ANIM).remove(K_ANIM_MS).remove(K_TEXT_SCALE).remove(K_RADIUS)
            .remove(K_GAP).remove(K_TAB_LABELS)
            .apply()
        load(ctx)
    }

    /**
     * Plays the activity open / close transition chosen in Settings. Call right after startActivity
     * (opening = true) and from finish() (opening = false).
     */
    @Suppress("DEPRECATION")
    fun transition(activity: Activity, opening: Boolean) {
        val (enter, exit) = when (pageAnim) {
            ANIM_NONE -> 0 to 0
            ANIM_FADE -> if (opening) android.R.anim.fade_in to android.R.anim.fade_out
            else android.R.anim.fade_in to android.R.anim.fade_out
            ANIM_SLIDE -> if (opening) R.anim.fluxa_slide_in to R.anim.fluxa_slide_out
            else R.anim.fluxa_slide_pop_in to R.anim.fluxa_slide_pop_out
            ANIM_ZOOM -> if (opening) R.anim.fluxa_zoom_in to R.anim.fluxa_zoom_out
            else R.anim.fluxa_zoom_pop_in to R.anim.fluxa_zoom_pop_out
            else -> if (opening) R.anim.fluxa_rise_in to android.R.anim.fade_out
            else android.R.anim.fade_in to R.anim.fluxa_rise_out
        }
        if (Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(
                if (opening) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE, enter, exit,
            )
        } else {
            activity.overridePendingTransition(enter, exit)
        }
    }
}
