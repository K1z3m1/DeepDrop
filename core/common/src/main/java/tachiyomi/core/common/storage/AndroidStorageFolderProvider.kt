package tachiyomi.core.common.storage

import android.os.Environment
import androidx.core.net.toUri
import java.io.File

class AndroidStorageFolderProvider : FolderProvider {

    override fun directory(): File {
        // Fixed folder name (not the app name) so renaming the app never moves the default storage
        // location away from users' existing downloads/backups.
        return File(Environment.getExternalStorageDirectory().absolutePath + File.separator + DEFAULT_FOLDER_NAME)
    }

    override fun path(): String {
        return directory().toUri().toString()
    }

    private companion object {
        const val DEFAULT_FOLDER_NAME = "Noctis"
    }
}
