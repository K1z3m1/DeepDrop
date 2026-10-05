package com.firstt175.novaframe.session.models

import android.content.Context
import java.io.File

/** Discovers every NCNN model directory shipped under assets/models/. */
data class AssetModelInfo(
    val dir: String,
    val label: String,
    val engine: Int,
    val paramName: String,
    val binName: String,
    /** Combined size of paramName + binName, in MB (2 decimal places). 0f if it couldn't be read. */
    val sizeMb: Float = 0f,
)

object AssetModelScanner {
    fun scan(ctx: Context): List<AssetModelInfo> {
        val roots = runCatching { ctx.assets.list("models")?.toList().orEmpty() }.getOrDefault(emptyList())
        return roots.mapNotNull { dir ->
            val files = runCatching { ctx.assets.list("models/$dir")?.toSet().orEmpty() }.getOrDefault(emptySet())
            when {
                "flownet.param" in files && "flownet.bin" in files ->
                    AssetModelInfo(
                        dir, pretty(dir), 0, "flownet.param", "flownet.bin",
                        sizeMb = assetSizeMb(ctx, "models/$dir/flownet.param") + assetSizeMb(ctx, "models/$dir/flownet.bin"),
                    )
                "ifrnet.param" in files && "ifrnet.bin" in files ->
                    AssetModelInfo(
                        dir, pretty(dir), 1, "ifrnet.param", "ifrnet.bin",
                        sizeMb = assetSizeMb(ctx, "models/$dir/ifrnet.param") + assetSizeMb(ctx, "models/$dir/ifrnet.bin"),
                    )
                else -> null
            }
        }.sortedBy { it.label.lowercase() }
    }

    /** Size of a single asset file in MB. Assets can be compressed in the APK, so this opens
     *  an fd (uncompressed assets only) and falls back to reading the stream length otherwise. */
    private fun assetSizeMb(ctx: Context, asset: String): Float {
        val bytes = runCatching { ctx.assets.openFd(asset).use { it.length } }
            .recoverCatching { ctx.assets.open(asset).use { it.available().toLong() } }
            .getOrDefault(0L)
        return bytes / (1024f * 1024f)
    }

    fun extract(ctx: Context, model: AssetModelInfo): File {
        val safe = model.dir.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(ctx.filesDir, "ai_asset_models/$safe").apply { mkdirs() }
        val param = File(out, model.paramName)
        val bin = File(out, model.binName)
        if (!param.exists() || param.length() == 0L) copy(ctx, "models/${model.dir}/${model.paramName}", param)
        if (!bin.exists() || bin.length() == 0L) copy(ctx, "models/${model.dir}/${model.binName}", bin)
        return out
    }

    private fun copy(ctx: Context, asset: String, out: File) {
        ctx.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
    }

    private fun pretty(s: String): String = s
        .replace('_', ' ')
        .replace(Regex("(?i)ensemblefalse"), "")
        .replace(Regex("(?i)ensembletrue"), " ensemble")
        .replace(Regex("(?i)fasttrue"), " fast")
        .replace(Regex("\\s+"), " ")
        .trim()
}
