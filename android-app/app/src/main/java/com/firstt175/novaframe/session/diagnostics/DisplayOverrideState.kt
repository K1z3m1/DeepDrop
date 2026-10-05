package com.firstt175.novaframe.session.diagnostics

import android.content.Context

/**
 * Tracks the temporary display override owned by NovaFrame.
 * The ownership marker is persisted so a process death/reboot can be detected
 * without touching a user's own global display configuration.
 */
object DisplayOverrideState {
    private const val PREFS = "display_override_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_OWNER = "owner"

    @Volatile
    private var ownerPkg: String? = null

    @Synchronized
    fun markApplied(pkg: String) {
        ownerPkg = pkg
    }

    @Synchronized
    fun markApplied(ctx: Context, pkg: String) {
        ownerPkg = pkg
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, true).putString(KEY_OWNER, pkg).apply()
    }

    @Synchronized
    fun clearIfOwner(pkg: String) {
        if (ownerPkg == pkg) ownerPkg = null
    }

    @Synchronized
    fun clearIfOwner(ctx: Context, pkg: String) {
        if (ownerPkg == pkg) ownerPkg = null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_OWNER, null) == pkg) {
            prefs.edit().remove(KEY_ACTIVE).remove(KEY_OWNER).apply()
        }
    }

    @Synchronized
    fun clear() {
        ownerPkg = null
    }

    @Synchronized
    fun clear(ctx: Context) {
        ownerPkg = null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ACTIVE).remove(KEY_OWNER).apply()
    }

    fun currentOwner(): String? = ownerPkg

    fun currentOwner(ctx: Context): String? = ownerPkg ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .takeIf { it.getBoolean(KEY_ACTIVE, false) }
        ?.getString(KEY_OWNER, null)

    fun isPersistentlyActive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)
}
