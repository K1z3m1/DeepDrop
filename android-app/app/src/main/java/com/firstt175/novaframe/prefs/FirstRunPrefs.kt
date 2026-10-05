package com.firstt175.novaframe.prefs

import android.content.Context

/**
 * Tracks whether the user has acknowledged the first-launch disclosure
 * screen ([com.firstt175.novaframe.ui.screens.AppDisclosureScreen]) that explains what
 * the app requests and does before any permission dialog appears. Shown
 * exactly once — after acknowledgement it never
 * reappears, though the same information stays reachable later from
 * Settings → Setup.
 */
object FirstRunPrefs {
    private const val NAME = "lsfg_first_run"
    private const val KEY_DISCLOSURE_ACKNOWLEDGED = "disclosure_acknowledged"

    fun isDisclosureAcknowledged(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, false)

    fun setDisclosureAcknowledged(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCLOSURE_ACKNOWLEDGED, true)
            .apply()
    }
}
