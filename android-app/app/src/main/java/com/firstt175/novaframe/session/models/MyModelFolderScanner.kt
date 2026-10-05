package com.firstt175.novaframe.session.models

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Manages user model folders through Android's Storage Access Framework.
 *
 * The selected SAF directory is the parent location. NovaFrame creates a
 * "NovaFrameModels" directory there. Every direct child directory is treated
 * as an independent model package and is auto-detected from standard NCNN
 * RIFE/IFRNet filenames.
 */
data class UserModelFolder(
    val name: String,
    val uri: Uri,
    val engine: Int,
    val files: List<String>,
    val paramUri: Uri,
    val binUri: Uri,
)

object MyModelFolderScanner {
    const val ROOT_FOLDER_NAME = "NovaFrameModels"

    fun ensureRoot(ctx: Context, selectedTreeUri: Uri): Uri {
        val parent = DocumentFile.fromTreeUri(ctx, selectedTreeUri)
            ?: error("Cannot access selected storage folder")
        val existing = parent.findFile(ROOT_FOLDER_NAME)
        val root = existing ?: parent.createDirectory(ROOT_FOLDER_NAME)
        return root?.uri ?: error("Couldn't create $ROOT_FOLDER_NAME")
    }

    fun scan(ctx: Context, rootUri: Uri): List<UserModelFolder> {
        val root = DocumentFile.fromTreeUri(ctx, rootUri) ?: return emptyList()
        return root.listFiles()
            .filter { it.isDirectory && it.name != null }
            .mapNotNull { detect(it) }
            .sortedBy { it.name.lowercase() }
    }

    private fun detect(dir: DocumentFile): UserModelFolder? {
        val files = dir.listFiles().filter { it.isFile && it.name != null }
        val names = files.mapNotNull { it.name }.toSet()

        val rife = "flownet.param" in names && "flownet.bin" in names
        val ifr = "ifrnet.param" in names && "ifrnet.bin" in names
        if (!rife && !ifr) return null

        // RIFE takes precedence only if both contracts are present; a package
        // should normally contain exactly one engine contract.
        val engine = if (rife) 0 else 1
        val paramName = if (engine == 0) "flownet.param" else "ifrnet.param"
        val binName = if (engine == 0) "flownet.bin" else "ifrnet.bin"
        val param = files.firstOrNull { it.name == paramName }
            ?: return null
        val bin = files.firstOrNull { it.name == binName }
            ?: return null
        return UserModelFolder(
            name = dir.name ?: "Unnamed model",
            uri = dir.uri,
            engine = engine,
            files = names.sorted(),
            paramUri = param.uri,
            binUri = bin.uri,
        )
    }

    /** Copies one selected model folder into private storage for the native loader. */
    fun importToPrivate(ctx: Context, model: UserModelFolder): File {
        val safe = model.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model" }
        val out = File(ctx.filesDir, "my_models/$safe").apply { mkdirs() }
        val wanted = if (model.engine == 0) {
            listOf("flownet.param", "flownet.bin")
        } else {
            listOf("ifrnet.param", "ifrnet.bin")
        }

        val param = File(out, wanted.first())
        val bin = File(out, wanted.last())
        ctx.contentResolver.openInputStream(model.paramUri)?.use { input ->
            param.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot read ${wanted.first()}")
        ctx.contentResolver.openInputStream(model.binUri)?.use { input ->
            bin.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot read ${wanted.last()}")

        if (!param.exists() || param.length() == 0L || !bin.exists() || bin.length() == 0L) {
            throw IllegalStateException("Model folder is missing required NCNN files")
        }
        return out
    }
}
