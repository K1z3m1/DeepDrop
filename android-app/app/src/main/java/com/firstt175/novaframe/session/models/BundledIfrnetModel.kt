package com.firstt175.novaframe.session.models

import android.content.Context
import com.firstt175.novaframe.prefs.IfrnetModel
import java.io.File

/**
 * Extracts one of the bundled lightweight IFRNet_S Vulkan models.
 * Only the smallest S variants are shipped.
 */
object BundledIfrnetModel {
    private const val PARAM_NAME = "ifrnet.param"
    private const val BIN_NAME = "ifrnet.bin"

    fun ensureExtracted(ctx: Context, model: IfrnetModel): Boolean {
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

    fun modelDir(ctx: Context, model: IfrnetModel): File =
        File(ctx.filesDir, "ai_model_ifrnet/${model.prefValue}")

    private fun isNonEmpty(f: File) = f.exists() && f.length() > 0L

    private fun copyAsset(ctx: Context, assetPath: String, dest: File) {
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
