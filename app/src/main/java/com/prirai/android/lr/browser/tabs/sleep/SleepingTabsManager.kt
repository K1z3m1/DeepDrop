package com.prirai.android.lr.browser.tabs.sleep

import android.content.ComponentCallbacks2
import android.content.Context
import com.prirai.android.lr.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.lib.state.ext.flowScoped

class SleepingTabsManager private constructor(
    private val appContext: Context,
) {
    private val prefs = UserPreferences(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleJob: Job? = null
    private var storeScope: CoroutineScope? = null

    fun start(store: BrowserStore) {
        storeScope = store.flowScoped(dispatcher = Dispatchers.Main) { flow ->
            flow
                .map { state: BrowserState ->
                    state.selectedTabId to state.tabs
                        .filter { it.engineState.engineSession != null }
                        .map { it.id }
                        .toSet()
                }
                .distinctUntilChanged()
                .collect {
                    enforceLiveTabCap(store)
                }
        }

        startIdleSweeper(store)
    }

    fun onPreferencesChanged(store: BrowserStore) {
        startIdleSweeper(store)
        enforceLiveTabCap(store)
    }

    private fun startIdleSweeper(store: BrowserStore) {
        idleJob?.cancel()
        val mode = prefs.getSleepingTabsMode()
        if (mode.idleTimeoutMs <= 0L) return
        idleJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(mode.sweepIntervalMs)
                sweepIdleTabs(store)
            }
        }
    }

    fun onTrimMemory(store: BrowserStore, level: Int) {
        if (prefs.sleepingTabsMode == UserPreferences.SLEEPING_TABS_OFF) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                suspendAllExceptSelected(store)
            }
            return
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            suspendAllExceptSelected(store)
        }
    }

    fun onAppBackgrounded(store: BrowserStore) {
        val mode = prefs.getSleepingTabsMode()
        if (mode.suspendOnBackground) {
            suspendAllExceptSelected(store)
        }
    }

    private fun sweepIdleTabs(store: BrowserStore) {
        val mode = prefs.getSleepingTabsMode()
        if (mode.idleTimeoutMs <= 0L) return
        val now = System.currentTimeMillis()
        val selectedId = store.state.selectedTabId

        for (tab in store.state.tabs) {
            if (tab.id == selectedId) continue
            if (!canSuspend(store, tab)) continue
            val idle = now - lastActivity(tab)
            if (idle >= mode.idleTimeoutMs) {
                store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
            }
        }
    }

    private fun enforceLiveTabCap(store: BrowserStore) {
        val mode = prefs.getSleepingTabsMode()
        val cap = mode.maxLiveTabs
        if (cap <= 0) return

        val selectedId = store.state.selectedTabId
        val others = store.state.tabs.filter {
            it.id != selectedId && it.engineState.engineSession != null
        }
        val over = others.size - (cap - 1)
        if (over <= 0) return

        val excess = others
            .filter { canSuspend(store, it) }
            .sortedBy { lastActivity(it) } // oldest first
            .take(over)

        for (tab in excess) {
            store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
        }
    }

    private fun suspendAllExceptSelected(store: BrowserStore) {
        val selectedId = store.state.selectedTabId
        for (tab in store.state.tabs) {
            if (tab.id == selectedId) continue
            if (!canSuspend(store, tab)) continue
            store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
        }
    }

    private fun canSuspend(store: BrowserStore, tab: TabSessionState): Boolean {
        if (tab.engineState.engineSession == null) return false
        val playing = tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
        if (playing) return false
        if (tab.content.download != null) return false
        if (tab.content.promptRequests.isNotEmpty()) return false
        if (tab.content.permissionRequestsList.isNotEmpty()) return false
        return true
    }

    private fun lastActivity(tab: TabSessionState): Long =
        maxOf(tab.lastAccess, tab.lastMediaAccessState.lastMediaAccess)

    fun stop() {
        storeScope?.cancel()
        storeScope = null
        scope.cancel()
    }

    companion object {
        @Volatile
        private var instance: SleepingTabsManager? = null

        fun getInstance(context: Context): SleepingTabsManager =
            instance ?: synchronized(this) {
                instance ?: SleepingTabsManager(context.applicationContext).also { instance = it }
            }
    }
}

data class SleepingTabsMode(
    val idleTimeoutMs: Long,
    val maxLiveTabs: Int,
    val suspendOnBackground: Boolean,
    val sweepIntervalMs: Long = 60_000L,
) {
    companion object {
        val Off = SleepingTabsMode(
            idleTimeoutMs = 0L,
            maxLiveTabs = 0,
            suspendOnBackground = false,
        )
        val Balanced = SleepingTabsMode(
            idleTimeoutMs = 30 * 60_000L, // 30 min
            maxLiveTabs = 8,
            suspendOnBackground = true,
            sweepIntervalMs = 60_000L,
        )
        val SingleTab = SleepingTabsMode(
            idleTimeoutMs = 0L,
            maxLiveTabs = 1,
            suspendOnBackground = true,
        )
        val Aggressive = SleepingTabsMode(
            idleTimeoutMs = 5 * 60_000L, // 5 min
            maxLiveTabs = 3,
            suspendOnBackground = true,
            sweepIntervalMs = 30_000L,
        )
    }
}
