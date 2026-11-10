package app.fluxa

import android.content.Context
import android.widget.ImageView

/** Bottom tab icon: a ready-made Material vector, tinted by state. No animation. */
class TabIcon(context: Context, private val kind: Kind) : ImageView(context) {
    enum class Kind(val res: Int) {
        HOME(R.drawable.fluxa_ic_home),
        FRAME(R.drawable.fluxa_ic_frame),
        SCREEN(R.drawable.fluxa_ic_screen),
        COLOR(R.drawable.fluxa_ic_palette),
        SETTINGS(R.drawable.fluxa_ic_settings),
    }

    private var active = false

    init {
        setImageResource(kind.res)
        scaleType = ScaleType.FIT_CENTER
        setColorFilter(Palette.MUTED)
    }

    fun setActive(on: Boolean) {
        active = on
        setColorFilter(if (on) Palette.PRIMARY else Palette.MUTED)
    }

    /** Kept for callers; icons no longer animate. */
    fun play() {}
}
