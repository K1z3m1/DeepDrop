package com.prirai.android.lr.browser.profile

import android.content.Context
import com.prirai.android.lr.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.recover.RecoverableTab
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import mozilla.components.support.base.log.logger.Logger
import com.prirai.android.lr.browser.tabgroups.TabGroupDatabase

class DefaultProfileTabMigration(
    private val applicationContext: Context,
) : Middleware<BrowserState, BrowserAction> {
    private val logger = Logger("DefaultProfileTabMigration")
    private val prefs by lazy { UserPreferences(applicationContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        if (prefs.defaultProfileMigrationDone) {
            next(action)
            return
        }

        if (action !is TabListAction.RestoreAction) {
            next(action)
            return
        }

        val (needsRewrite, keepAsIs) = action.tabs.partition { rt ->
            !rt.state.private && rt.state.contextId == LEGACY_DEFAULT_CONTEXT_ID
        }

        if (needsRewrite.isEmpty()) {
            markMigrationDoneEmpty()
            next(action)
            return
        }

        logger.info(
            "Clearing legacy \"$LEGACY_DEFAULT_CONTEXT_ID\" contextId on " +
                "${needsRewrite.size} restored tab(s) (leaving ${keepAsIs.size} untouched)",
        )

        val rewrittenTabs: List<RecoverableTab> = needsRewrite.map { rt ->
            rt.copy(state = rt.state.copy(contextId = null))
        }

        val rewrittenById = rewrittenTabs.associateBy { it.state.id }
        val merged: List<RecoverableTab> = action.tabs.map { original ->
            rewrittenById[original.state.id] ?: original
        }

        val rewrittenAction = action.copy(tabs = merged)
        next(rewrittenAction)

        finaliseMigration(migratedTabCount = needsRewrite.size)
    }

    private fun markMigrationDoneEmpty() {
        prefs.defaultProfileMigrationDone = true
    }

    private fun finaliseMigration(migratedTabCount: Int) {
        prefs.defaultProfileMigrationDone = true
        logger.info("Default-profile v2 migration committed ($migratedTabCount tab(s) cleared)")

        scope.launch {
            try {
                val db = TabGroupDatabase
                    .getInstance(applicationContext)
                val updated = db.tabGroupDao()
                    .reassignContextIdGroups(from = LEGACY_DEFAULT_CONTEXT_ID, to = null)
                if (updated > 0) {
                    logger.info("Cleared $updated tab group row(s) from \"$LEGACY_DEFAULT_CONTEXT_ID\" to null")
                }
            } catch (t: Throwable) {
                logger.error("Failed to clear tab group contextIds during migration", t)
            }
        }
    }

    companion object {
        const val LEGACY_DEFAULT_CONTEXT_ID: String = "profile_default"
    }
}
