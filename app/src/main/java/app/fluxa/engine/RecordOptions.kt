package app.fluxa

import android.content.Intent

/**
 * Screen-record settings, started from the record button in the panel (no mode to pick in the app).
 *
 * With frame generation running the video is made from the frames the presenter shows (generated +
 * real). In a panel-only session (app opened from the launcher) the screen is mirrored straight
 * into the encoder instead.
 */
data class RecordOptions(
    val enabled: Boolean = false,
    /** [AUDIO_APP] = app (internal) sound only, [AUDIO_APP_MIC] = app sound + outside sound (mic). */
    val audio: Int = AUDIO_APP,
    /** Video orientation: true = portrait, false = landscape. */
    val portrait: Boolean = false,
) {
    val withMic: Boolean get() = audio == AUDIO_APP_MIC

    fun writeTo(intent: Intent): Intent {
        intent.putExtra(EXTRA_ENABLED, enabled)
        intent.putExtra(EXTRA_AUDIO, audio)
        intent.putExtra(EXTRA_PORTRAIT, portrait)
        return intent
    }

    companion object {
        const val EXTRA_ENABLED = "rec"
        const val EXTRA_AUDIO = "rec_audio"
        const val EXTRA_PORTRAIT = "rec_portrait"

        const val AUDIO_APP = 0
        const val AUDIO_APP_MIC = 1

        fun clampAudio(value: Int): Int = if (value == AUDIO_APP_MIC) AUDIO_APP_MIC else AUDIO_APP

        const val KEY_AUDIO = "ui_rec_audio"
        /** 0 = follow the screen, 1 = landscape, 2 = portrait. */
        const val KEY_ORIENT = "rec_orient"
        const val ORIENT_AUTO = 0
        const val ORIENT_LANDSCAPE = 1
        const val ORIENT_PORTRAIT = 2

        /** Options for a recording that starts now; [screenPortrait] resolves "follow the screen". */
        fun fromPrefs(prefs: android.content.SharedPreferences, screenPortrait: Boolean): RecordOptions {
            val portrait = when (prefs.getInt(KEY_ORIENT, ORIENT_AUTO)) {
                ORIENT_LANDSCAPE -> false
                ORIENT_PORTRAIT -> true
                else -> screenPortrait
            }
            return RecordOptions(
                enabled = true,
                audio = clampAudio(prefs.getInt(KEY_AUDIO, AUDIO_APP)),
                portrait = portrait,
            )
        }

        fun fromIntent(intent: Intent?): RecordOptions {
            if (intent == null || !intent.getBooleanExtra(EXTRA_ENABLED, false)) return RecordOptions()
            return RecordOptions(
                enabled = true,
                audio = clampAudio(intent.getIntExtra(EXTRA_AUDIO, AUDIO_APP)),
                portrait = intent.getBooleanExtra(EXTRA_PORTRAIT, false),
            )
        }
    }
}
