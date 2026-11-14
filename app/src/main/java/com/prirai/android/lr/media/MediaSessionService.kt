package com.prirai.android.lr.media

import android.app.Service
import android.content.Intent
import android.os.Build
import android.util.Log
import com.prirai.android.lr.ext.components
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.feature.media.service.AbstractMediaSessionService
import mozilla.components.support.base.android.NotificationsDelegate

class MediaSessionService : AbstractMediaSessionService() {
    override val crashReporter: CrashReporting? = null
    override val notificationsDelegate: NotificationsDelegate by lazy { components.notificationsDelegate }
    override val store: BrowserStore by lazy { components.store }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val state = try { store.state } catch (_: Throwable) { null }
        if (state != null) {
            val playing = (state.tabs + state.customTabs).filter { tab ->
                tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PLAYING ||
                    tab.mediaSessionState?.playbackState == MediaSession.PlaybackState.PAUSED
            }
            Log.i(
                TAG,
                "onTaskRemoved: stopping ${playing.size} tab(s) with active media",
            )
            playing.forEach { tab ->
                try {
                    tab.mediaSessionState?.controller?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "controller.stop() failed for tab ${tab.id}", t)
                }
            }
        }

        super.onTaskRemoved(rootIntent)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            Log.i(TAG, "onTaskRemoved: defensive stopSelf completed")
        } catch (t: Throwable) {
            Log.w(TAG, "defensive stopSelf failed", t)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LRMediaSvc"
    }
}
