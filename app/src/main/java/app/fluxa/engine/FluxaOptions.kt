package app.fluxa

import android.content.Intent

data class FluxaOptions(
    val enabled: Boolean = false,
    val multiplier: Int = DEFAULT_MULTIPLIER,
    /** DIS optical-flow resolution: short side in px. */
    val flowSide: Int = DEFAULT_FLOW_SIDE,
    val driverName: String? = null,
    val presentMode: Int = PRESENT_FIFO,
    /** GPU cost of the generation algorithm, see GEN_MODE_*. */
    val genMode: Int = GEN_MODE_LITE,
    /** Output rate for the target-fps mode (used only when [targetMode] is on). */
    val targetFps: Int = DEFAULT_TARGET_FPS,
    /** false = fixed xN multiplier; true = adaptive, generate just enough frames to reach [targetFps]. */
    val targetMode: Boolean = false,
) {
    /** What the native side gets: 0 while the multiplier mode is selected. */
    val effectiveTargetFps: Int get() = if (targetMode) targetFps else 0

    val usable: Boolean get() = enabled

    fun writeTo(intent: Intent): Intent {
        intent.putExtra(EXTRA_ENABLED, enabled)
        intent.putExtra(EXTRA_MULTIPLIER, multiplier)
        intent.putExtra(EXTRA_FLOW_SIDE, flowSide)
        intent.putExtra(EXTRA_DRIVER, driverName)
        intent.putExtra(EXTRA_PRESENT_MODE, presentMode)
        intent.putExtra(EXTRA_GEN_MODE, genMode)
        intent.putExtra(EXTRA_TARGET_FPS, targetFps)
        intent.putExtra(EXTRA_TARGET_MODE, targetMode)
        return intent
    }

    companion object {
        const val EXTRA_ENABLED = "fluxa"
        const val EXTRA_MULTIPLIER = "fluxa_multiplier"
        const val EXTRA_FLOW_SIDE = "fluxa_flow_side"
        const val EXTRA_DRIVER = "fluxa_driver"
        const val EXTRA_PRESENT_MODE = "fluxa_present_mode"
        const val EXTRA_GEN_MODE = "fluxa_gen_mode"
        const val EXTRA_TARGET_FPS = "fluxa_target_fps"
        const val EXTRA_TARGET_MODE = "fluxa_target_mode"

        /** Output mode: fixed xN multiplier, or adaptive target fps. */
        const val OUT_MODE_MULTIPLIER = 0
        const val OUT_MODE_TARGET = 1
        val OUT_MODE_LABELS = listOf("ตัวคูณ ×N", "Target fps")

        const val DEFAULT_TARGET_FPS = 60
        const val MIN_TARGET_FPS = 30
        const val MAX_TARGET_FPS = 240
        const val TARGET_FPS_STEP = 5

        fun clampTargetFps(value: Int): Int = value.coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS)

        fun targetFpsLabel(value: Int): String = "$value fps"

        /** Frame-generation algorithm cost: full DIS -> lighter -> lightest. */
        const val GEN_MODE_FULL = 0
        const val GEN_MODE_BALANCED = 1
        const val GEN_MODE_LITE = 2
        const val GEN_MODE_ULTRA = 3
        val GEN_MODE_LABELS = listOf("เต็ม", "สมดุล", "ประหยัด", "เบาสุด")

        fun clampGenMode(value: Int): Int = value.coerceIn(GEN_MODE_FULL, GEN_MODE_ULTRA)

        const val PRESENT_FIFO = 0
        const val PRESENT_MAILBOX = 1

        const val DEFAULT_MULTIPLIER = 2
        const val DEFAULT_FLOW_SIDE = 160
        /** Slider position 1 = "OFF": frames pass through with no generation. */
        const val MIN_MULTIPLIER = 1
        const val MAX_MULTIPLIER = 10
        const val MIN_FLOW_SIDE = 64
        const val MAX_FLOW_SIDE = 720

        /** Slider step for the optical-flow resolution (px). */
        const val FLOW_SIDE_STEP = 2

        fun multiplierLabel(value: Int): String = if (value <= 1) "OFF" else "${value}×"

        fun clampMultiplier(value: Int): Int = value.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)

        fun clampPresentMode(value: Int): Int =
            if (value == PRESENT_MAILBOX) PRESENT_MAILBOX else PRESENT_FIFO

        fun clampFlowSide(value: Int): Int = value.coerceIn(MIN_FLOW_SIDE, MAX_FLOW_SIDE)

        fun fromIntent(intent: Intent?): FluxaOptions {
            if (intent == null || !intent.getBooleanExtra(EXTRA_ENABLED, false)) {
                return FluxaOptions()
            }
            return FluxaOptions(
                enabled = true,
                multiplier = clampMultiplier(intent.getIntExtra(EXTRA_MULTIPLIER, DEFAULT_MULTIPLIER)),
                flowSide = clampFlowSide(intent.getIntExtra(EXTRA_FLOW_SIDE, DEFAULT_FLOW_SIDE)),
                driverName = intent.getStringExtra(EXTRA_DRIVER),
                presentMode = clampPresentMode(intent.getIntExtra(EXTRA_PRESENT_MODE, PRESENT_FIFO)),
                genMode = clampGenMode(intent.getIntExtra(EXTRA_GEN_MODE, GEN_MODE_LITE)),
                targetFps = clampTargetFps(intent.getIntExtra(EXTRA_TARGET_FPS, DEFAULT_TARGET_FPS)),
                targetMode = intent.getBooleanExtra(EXTRA_TARGET_MODE, false),
            )
        }
    }
}
