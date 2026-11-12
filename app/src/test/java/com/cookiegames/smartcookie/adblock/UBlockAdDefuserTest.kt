package com.cookiegames.smartcookie.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UBlockAdDefuserTest {

    private val defuser = UBlockAdDefuser()

    @Test
    fun `cosmetic css is non-empty and well-formed`() {
        assertNotNull(defuser.cosmeticAdFilterCss)
        assertTrue(defuser.cosmeticAdFilterCss.contains("display: none !important"))
        assertTrue(defuser.cosmeticInjectionJs.contains("yload-ublock-cosmetic"))
    }

    @Test
    fun `general ad defuser excludes youtube and google`() {
        val js = defuser.generalAdDefuserJs
        assertTrue("Must exclude google", js.contains("host.indexOf('google.') !== -1"))
        assertTrue("Must exclude youtube", js.contains("host.indexOf('youtube.') !== -1"))
        assertTrue("Must exclude youtu.be", js.contains("host.indexOf('youtu.be') !== -1"))
        assertTrue("Must return 200 OK for blocked fetch", js.contains("status: 200"))
        assertTrue("Must set readyState 4 for blocked XHR", js.contains("readyState', { value: 4"))
    }

    @Test
    fun `youtube defuser does not seek video currentTime to duration`() {
        val js = defuser.youtubeAdDefuserJs
        // CRITICAL: Must never seek currentTime to duration on main video
        assertFalse(
            "Must NOT contain video.currentTime = video.duration as it kills main video playback on mid-rolls",
            js.contains("currentTime = video.duration")
        )
    }

    @Test
    fun `youtube defuser preserves playback tracking telemetry`() {
        val js = defuser.youtubeAdDefuserJs
        // Must NOT delete watchtime, qoe, or playback telemetry URLs
        assertFalse("Must not delete videostatsWatchtimeUrl", js.contains("delete obj.playbackTracking.videostatsWatchtimeUrl"))
        assertFalse("Must not delete qoeUrl", js.contains("delete obj.playbackTracking.qoeUrl"))
        assertFalse("Must not delete videostatsPlaybackUrl", js.contains("delete obj.playbackTracking.videostatsPlaybackUrl"))
    }

    @Test
    fun `youtube defuser includes mobile and desktop skip button selectors`() {
        val js = defuser.youtubeAdDefuserJs
        assertTrue("Must include desktop skip button", js.contains(".ytp-ad-skip-button"))
        assertTrue("Must include mobile skip button", js.contains(".ytm-ad-skip-button"))
        assertTrue("Must include overlay close button", js.contains(".ytp-ad-overlay-close-button"))
    }

    @Test
    fun `youtube defuser cleans encoding headers on fetch clone`() {
        val js = defuser.youtubeAdDefuserJs
        assertTrue("Must remove content-encoding from cloned fetch headers", js.contains("newHeaders.delete('content-encoding')"))
        assertTrue("Must remove content-length from cloned fetch headers", js.contains("newHeaders.delete('content-length')"))
    }

    @Test
    fun `youtube defuser resets playback rate after ad ends`() {
        val js = defuser.youtubeAdDefuserJs
        assertTrue("Must restore playbackRate to 1.0", js.contains("video.playbackRate = 1.0"))
    }
}
