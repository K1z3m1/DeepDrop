package com.prirai.android.lr.middleware

import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.MediaSessionAction
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store
import android.os.SystemClock

internal class MediaSessionThrottleMiddleware :
    Middleware<BrowserState, BrowserAction> {
    private companion object {
        const val POSITION_THROTTLE_MS = 1000L
    }

    private val lastPositionAt = mutableMapOf<String, Long>()
    private val lastMetadataKey = mutableMapOf<String, String>()

    override fun invoke(
        store: Store<BrowserState, BrowserAction>,
        next: (BrowserAction) -> Unit,
        action: BrowserAction,
    ) {
        when (action) {
            is MediaSessionAction.ActivatedMediaSessionAction -> {
                val state = store.state
                val tab = state.tabs.firstOrNull { it.id == action.tabId }
                    ?: state.customTabs.firstOrNull { it.id == action.tabId }
                if (tab?.mediaSessionState?.controller != null) {
                    return
                }
            }

            is MediaSessionAction.UpdateMediaPlaybackStateAction -> {
                val state = store.state
                val tab = state.tabs.firstOrNull { it.id == action.tabId }
                    ?: state.customTabs.firstOrNull { it.id == action.tabId }
                val current: MediaSession.PlaybackState? = tab?.mediaSessionState?.playbackState
                if (current == action.playbackState) {
                    return
                }
            }

            is MediaSessionAction.UpdateMediaPositionStateAction -> {
                val now = SystemClock.elapsedRealtime()
                val last = lastPositionAt[action.tabId]
                if (last != null && now - last < POSITION_THROTTLE_MS) {
                    return
                }
                lastPositionAt[action.tabId] = now
            }

            is MediaSessionAction.UpdateMediaMetadataAction -> {
                val md = action.metadata
                val key = (md.title.orEmpty()) + "|" +
                    (md.artist.orEmpty()) + "|" +
                    (md.album.orEmpty())
                if (lastMetadataKey[action.tabId] == key) {
                    return
                }
                lastMetadataKey[action.tabId] = key
            }

            is MediaSessionAction.UpdateMediaFeatureAction -> {
                val state = store.state
                val tab = state.tabs.firstOrNull { it.id == action.tabId }
                    ?: state.customTabs.firstOrNull { it.id == action.tabId }
                if (tab?.mediaSessionState?.features == action.features) return
            }

            is MediaSessionAction.UpdateMediaMutedAction -> {
                val state = store.state
                val tab = state.tabs.firstOrNull { it.id == action.tabId }
                    ?: state.customTabs.firstOrNull { it.id == action.tabId }
                if (tab?.mediaSessionState?.muted == action.muted) return
            }

            is MediaSessionAction.UpdateMediaFullscreenAction -> {
                val state = store.state
                val tab = state.tabs.firstOrNull { it.id == action.tabId }
                    ?: state.customTabs.firstOrNull { it.id == action.tabId }
                val ms = tab?.mediaSessionState
                if (ms != null &&
                    ms.fullscreen == action.fullScreen &&
                    ms.elementMetadata == action.elementMetadata
                ) return
            }

            is MediaSessionAction.DeactivatedMediaSessionAction -> {
                lastPositionAt.remove(action.tabId)
                lastMetadataKey.remove(action.tabId)
            }

            else -> {
            }
        }

        next(action)
    }
}
