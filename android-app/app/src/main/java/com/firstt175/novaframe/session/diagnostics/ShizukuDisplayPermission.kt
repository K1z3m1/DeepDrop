package com.firstt175.novaframe.session.diagnostics

import com.firstt175.novaframe.session.NovaLog

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Small, isolated bridge for privileged shell operations through Shizuku
 * (WRITE_SECURE_SETTINGS grant, `wm size/density`, refresh-rate and other
 * display/performance controls). Not related to screen capture, which is
 * MediaProjection-only.
 */
object ShizukuDisplayPermission {
    const val REQUEST_CODE = 17501
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val TAG = "ShizukuDisplayPermission"

    fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun needsShizukuAppPermission(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizukuPermission() {
        runCatching {
            if (!Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure { NovaLog.e(TAG, "requestShizukuPermission failed", it) }
    }

    fun hasWriteSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Grants WRITE_SECURE_SETTINGS to this app using the Shizuku shell UID. */
    suspend fun grantWriteSecureSettings(ctx: Context): Boolean {
        if (hasWriteSecureSettings(ctx)) return true
        if (!isShizukuAvailable()) return false

        val command = arrayOf(
            "pm",
            "grant",
            ctx.packageName,
            WRITE_SECURE_SETTINGS,
        )

        return withContext(Dispatchers.IO) {
            val result = withTimeoutOrNull(6_000L) {
                runCatching {
                    val process = newProcessCompat(command)
                    val stdout = process.inputStream.bufferedReader().use { it.readText() }
                    val stderr = process.errorStream.bufferedReader().use { it.readText() }
                    val exit = process.waitFor()
                    NovaLog.i(TAG, "pm grant exit=$exit stdout=${stdout.take(300)} stderr=${stderr.take(300)}")
                    exit == 0
                }.getOrElse {
                    NovaLog.e(TAG, "pm grant failed", it)
                    false
                }
            }
            (result == true) && hasWriteSecureSettings(ctx)
        }
    }

    /** Executes a shell command with Shizuku's privileged user-service process. */
    suspend fun exec(command: String, timeoutMs: Long = 6_000L): CommandResult? {
        if (!isShizukuAvailable()) return null
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    val process = newProcessCompat(arrayOf("sh", "-c", command))
                    val stdout = process.inputStream.bufferedReader().use { it.readText() }
                    val stderr = process.errorStream.bufferedReader().use { it.readText() }
                    val exit = process.waitFor()
                    CommandResult(exit, stdout.trim(), stderr.trim())
                }.onFailure {
                    NovaLog.e(TAG, "Shizuku command failed: $command", it)
                }.getOrNull()
            }
        }
    }

    /**
     * Shizuku 13.1.x keeps newProcess hidden from the public Kotlin surface.
     * Keep the command bridge compatible without depending on an internal
     * compile-time API. The returned process is still the Shizuku shell
     * process, not a local Runtime.exec() process.
     */
    private fun newProcessCompat(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }
}
