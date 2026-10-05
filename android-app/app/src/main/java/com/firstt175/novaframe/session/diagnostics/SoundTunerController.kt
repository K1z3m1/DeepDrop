package com.firstt175.novaframe.session.diagnostics

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * System-wide sound tuner: [Equalizer] + [BassBoost] + [Virtualizer] + [LoudnessEnhancer]
 * attached to the global output mix (audio session `0`) rather than to any single app's
 * session, so it colors whatever the captured game is currently playing without needing
 * that app's session ID — the same mechanism third-party system-wide equalizer apps rely
 * on. Requires `MODIFY_AUDIO_SETTINGS` (already held by this app for the volume slider).
 *
 * Availability of session-0 effect attachment varies by OEM/Android version, so every
 * call here is defensive: failures are logged and no-op rather than crashing the session.
 * [isEnabled] reflects whether at least one effect actually attached, so callers can tell
 * a real failure apart from a successful-but-silent attach.
 */
class SoundTunerController {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    var isEnabled: Boolean = false
        private set

    /** Attaches all four effects to the global mix (session 0) and enables them. Idempotent. */
    fun enable() {
        if (isEnabled) return
        runCatching {
            equalizer = Equalizer(0, 0).apply { enabled = true }
        }.onFailure { Log.w(TAG, "Equalizer attach failed", it) }
        runCatching {
            bassBoost = BassBoost(0, 0).apply { enabled = true }
        }.onFailure { Log.w(TAG, "BassBoost attach failed", it) }
        runCatching {
            virtualizer = Virtualizer(0, 0).apply { enabled = true }
        }.onFailure { Log.w(TAG, "Virtualizer attach failed", it) }
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
        }.onFailure { Log.w(TAG, "LoudnessEnhancer attach failed", it) }
        isEnabled = equalizer != null || bassBoost != null || virtualizer != null || loudnessEnhancer != null
        Log.i(TAG, "enable() eq=${equalizer != null} bass=${bassBoost != null} " +
            "virt=${virtualizer != null} loud=${loudnessEnhancer != null}")
    }

    /** Releases every attached effect. Safe to call whether or not [enable] succeeded. */
    fun disable() {
        runCatching { equalizer?.release() }.onFailure { Log.w(TAG, "Equalizer release failed", it) }
        runCatching { bassBoost?.release() }.onFailure { Log.w(TAG, "BassBoost release failed", it) }
        runCatching { virtualizer?.release() }.onFailure { Log.w(TAG, "Virtualizer release failed", it) }
        runCatching { loudnessEnhancer?.release() }.onFailure { Log.w(TAG, "LoudnessEnhancer release failed", it) }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        isEnabled = false
    }

    fun setBassBoostStrength(strength: Int) {
        runCatching { bassBoost?.setStrength(strength.coerceIn(0, 1000).toShort()) }
            .onFailure { Log.w(TAG, "setBassBoostStrength failed", it) }
    }

    fun setVirtualizerStrength(strength: Int) {
        runCatching { virtualizer?.setStrength(strength.coerceIn(0, 1000).toShort()) }
            .onFailure { Log.w(TAG, "setVirtualizerStrength failed", it) }
    }

    /** [gainMillibel] is the loudness target gain in millibels, 0..2000 (0..20 dB). */
    fun setLoudnessGain(gainMillibel: Int) {
        runCatching { loudnessEnhancer?.setTargetGain(gainMillibel.coerceIn(0, 2000)) }
            .onFailure { Log.w(TAG, "setLoudnessGain failed", it) }
    }

    /** [levelMillibel] is clamped to the live equalizer's own reported band range. */
    fun setEqBandLevel(band: Int, levelMillibel: Int) {
        val eq = equalizer ?: return
        runCatching {
            val range = eq.bandLevelRange
            val clamped = levelMillibel.coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(band.toShort(), clamped.toShort())
        }.onFailure { Log.w(TAG, "setEqBandLevel failed band=$band", it) }
    }

    /** A one-tap sound profile used by the settings UI. */
    data class Preset(
        val label: String,
        val bass: Int,
        val virtualizer: Int,
        val loudness: Int,
        val eqShapeAt: (freqHz: Int) -> Float,
    )

    /** Static per-band metadata read from the device's equalizer engine. */
    data class BandInfo(
        val bandCount: Int,
        /** [min, max] band level in millibels, as reported by the live effect. */
        val levelRange: IntArray,
        val centerFreqsHz: IntArray,
    )

    companion object {
        private const val TAG = "SoundTunerController"

        /**
         * A one-tap sound profile. [bass]/[virtualizer]/[loudness] are the same
         * native units as [setBassBoostStrength]/[setVirtualizerStrength]/[setLoudnessGain].
         * [eqShapeAt] shapes the per-band equalizer: given a band's *actual* reported
         * center frequency (Hz) it returns a gain in [-1f, 1f], where 1f maps to that
         * device's reported max band level, -1f to its min, and 0f to the midpoint
         * (flat/no change for the typical symmetric bandLevelRange). Frequency-based
         * rather than band-index-based because [BandInfo.bandCount] and its spacing
         * vary by OEM equalizer engine.
         */
        /** Built-in quick profiles offered above the manual sliders in the Sound Tuner UI. */
        val PRESETS: List<Preset> = listOf(
            Preset("Flat", bass = 0, virtualizer = 0, loudness = 0, eqShapeAt = { 0f }),
            Preset(
                "Bass", bass = 700, virtualizer = 300, loudness = 200,
                eqShapeAt = { hz ->
                    when {
                        hz < 250 -> 0.8f
                        hz < 1000 -> 0.2f
                        else -> -0.1f
                    }
                },
            ),
            Preset(
                "Vocal", bass = 100, virtualizer = 100, loudness = 300,
                eqShapeAt = { hz ->
                    when {
                        hz in 500..4000 -> 0.6f
                        hz < 500 -> -0.3f
                        else -> -0.1f
                    }
                },
            ),
            Preset(
                "FPS", bass = 50, virtualizer = 500, loudness = 400,
                eqShapeAt = { hz ->
                    when {
                        hz > 2000 -> 0.7f
                        hz < 500 -> -0.4f
                        else -> 0f
                    }
                },
            ),
            Preset(
                "Cinema", bass = 600, virtualizer = 800, loudness = 300,
                eqShapeAt = { hz ->
                    when {
                        hz < 250 -> 0.5f
                        hz > 6000 -> 0.3f
                        else -> -0.1f
                    }
                },
            ),
        )

        /** Maps a [-1f, 1f] preset shape value onto this device's actual reported band level range. */
        fun presetLevelForBand(shape: Float, levelRange: IntArray): Int {
            val clamped = shape.coerceIn(-1f, 1f)
            val mid = (levelRange[0] + levelRange[1]) / 2f
            val half = (levelRange[1] - levelRange[0]) / 2f
            return (mid + clamped * half).toInt().coerceIn(levelRange[0], levelRange[1])
        }

        /**
         * Reads band count / level range / center frequencies from a transient,
         * disabled [Equalizer] instance (never sets `enabled = true`, so no audible
         * effect attaches) and releases it immediately. Used to build the per-band
         * slider UI up front without requiring the tuner to already be enabled.
         * Returns null if session-0 Equalizer attachment isn't available on this
         * device — callers should just omit the per-band UI in that case.
         */
        fun queryBandInfo(): BandInfo? = runCatching {
            val eq = Equalizer(0, 0)
            try {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val freqs = IntArray(bands) { eq.getCenterFreq(it.toShort()) / 1000 }
                BandInfo(bands, intArrayOf(range[0].toInt(), range[1].toInt()), freqs)
            } finally {
                runCatching { eq.release() }
            }
        }.onFailure { Log.w(TAG, "queryBandInfo failed", it) }.getOrNull()
    }
}
