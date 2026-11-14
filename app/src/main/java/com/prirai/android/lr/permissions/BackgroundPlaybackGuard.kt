package com.prirai.android.lr.permissions

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.support.ktx.kotlin.getOrigin

internal class BackgroundPlaybackGuard(
    private val store: BrowserStore,
    private val allowlist: BackgroundPlaybackAllowlist,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        val allowed = allowlist.origins.value
        val state = store.state
        val playing = (state.tabs + state.customTabs).filter { tab ->
            tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING
        }
        if (playing.isEmpty()) return

        var stopped = 0
        playing.forEach { tab ->
            val origin = tab.content.url.getOrigin()
            if (origin != null && origin in allowed) {
                return@forEach
            }
            try {
                tab.mediaSessionState?.controller?.stop()
                stopped++
            } catch (t: Throwable) {
                Log.w(TAG, "stop() failed for tab ${tab.id}", t)
            }
        }
        if (stopped > 0) {
            Log.i(
                TAG,
                "App backgrounded; stopped $stopped tab(s) not on bg-playback allow-list",
            )
        }
    }

    companion object {
        private const val TAG = "LRBgPlayGuard"

        fun install(store: BrowserStore, allowlist: BackgroundPlaybackAllowlist) {
            val guard = BackgroundPlaybackGuard(store, allowlist)
            ProcessLifecycleOwner.get().lifecycle.addObserver(guard)
        }
    }
}
