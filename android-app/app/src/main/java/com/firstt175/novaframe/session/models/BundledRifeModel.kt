package com.firstt175.novaframe.session.models

import android.content.Context
import com.firstt175.novaframe.prefs.RifeModel
import java.io.File

/**
 * Extracts one of the small, bundled RIFE Vulkan models.
 *
 * All supported models use the standard rife-ncnn-vulkan flownet.param/.bin
 * contract expected by NcnnInterpolator. Only the lightweight v4/v4.6/v4.25
 * Lite variants are shipped; large HD/UHD/anime/legacy bundles are intentionally
 * not included.
 */
object BundledRifeModel {
    private const val PARAM_NAME = "flownet.param"
    private const val BIN_NAME = "flownet.bin"

    fun ensureExtracted(ctx: Context, model: RifeModel): Boolean {
        val outDir = modelDir(ctx, model).apply { mkdirs() }
        val paramOut = File(outDir, PARAM_NAME)
        val binOut = File(outDir, BIN_NAME)

        if (isNonEmpty(paramOut) && isNonEmpty(binOut)) return true

        return try {
            copyAsset(ctx, "models/${model.assetDir}/$PARAM_NAME", paramOut)
            copyAsset(ctx, "models/${model.assetDir}/$BIN_NAME", binOut)
            isNonEmpty(paramOut) && isNonEmpty(binOut)
        } catch (t: Throwable) {
            paramOut.delete()
            binOut.delete()
            false
        }
    }

    fun modelDir(ctx: Context, model: RifeModel): File =
        File(ctx.filesDir, "ai_model/${model.prefValue}")

    private fun isNonEmpty(f: File) = f.exists() && f.length() > 0L

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
