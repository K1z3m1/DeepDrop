package com.firstt175.novaframe.session.diagnostics

import com.firstt175.novaframe.session.NativeBridge

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based crash capture, kept entirely on-device. No network access and
 * no share/export intent:
 *   - Java/Kotlin uncaught exceptions → write a text report to filesDir/last_crash.txt
 *     plus a line into the native ring log.
 *   - Native fatal signals → handled by crash_reporter.cpp, same file.
 */
object CrashReporter {

    private const val TAG = "NovaCrash"
    const val CRASH_FILE = "last_crash.txt"
    // File the dialog has already been shown for. Kept on disk so the share
    // button can still attach the most recent crash even after the user has
    // dismissed the one-shot dialog, but it never re-triggers the dialog.
    const val CRASH_FILE_SEEN = "last_crash_seen.txt"
    const val LOG_FILE = "lsfg.log"

    // Diagnostics files live in their own subdirectory (not the filesDir root)
    // to keep them separate from other app-private storage.
    private const val DIAGNOSTICS_DIR = "diagnostics"

    private fun diagnosticsDir(ctx: Context): File =
        File(ctx.filesDir, DIAGNOSTICS_DIR).apply { mkdirs() }

    /** Absolute path used by both sides to locate the crash file. */
    fun crashFile(ctx: Context): File = File(diagnosticsDir(ctx), CRASH_FILE)
    fun seenCrashFile(ctx: Context): File = File(diagnosticsDir(ctx), CRASH_FILE_SEEN)
    fun logFile(ctx: Context): File = File(diagnosticsDir(ctx), LOG_FILE)

    /**
     * Install both halves. Safe to call multiple times; the native side is
     * idempotent and we only set the Java handler once.
     */
    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val crashPath = crashFile(appCtx).absolutePath
        val logPath = logFile(appCtx).absolutePath

        runCatching { NativeBridge.initCrashReporter(crashPath, logPath) }
            .onFailure { Log.w(TAG, "initCrashReporter failed", it) }

        if (installed) return
        installed = true

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeJavaCrash(appCtx, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "writeJavaCrash failed", t)
            }
            prev?.uncaughtException(thread, throwable)
                ?: kotlin.system.exitProcess(10)
        }
    }

    private var installed = false

    private fun writeJavaCrash(ctx: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== LSFG java crash ===")
            pw.println("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("thread: ${thread.name}")
            appendDeviceInfo(pw)
            pw.println()
            pw.println("--- stack ---")
            throwable.printStackTrace(pw)
            pw.println("=== end ===")
        }
        crashFile(ctx).writeText(sw.toString())
    }

    private fun appendDeviceInfo(pw: PrintWriter) {
        pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("product: ${Build.PRODUCT}")
        pw.println("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        pw.println("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        pw.println("hardware: ${Build.HARDWARE} board=${Build.BOARD}")
        pw.println("soc: ${safeSoc()}")
    }

    private fun safeSoc(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            "n/a"
        }
    } catch (_: Throwable) {
        "n/a"
    }

    /** True if a crash report is waiting to be seen. */
    fun hasPendingCrash(ctx: Context): Boolean {
        val f = crashFile(ctx)
        return f.exists() && f.length() > 0
    }

    /**
     * Mark the pending crash as seen by moving it aside. After this call
     * [hasPendingCrash] returns false, but the file is still readable by
     * [readCrashSummary] so the in-app log viewer keeps showing it.
     * Called as soon as the one-shot dialog is shown — this prevents the
     * dialog from re-appearing on every launch when the user dismisses by
     * swiping the app away rather than tapping a button.
     */
    fun markPendingCrashSeen(ctx: Context) {
        val src = crashFile(ctx)
        if (!src.exists()) return
        val dst = seenCrashFile(ctx)
        // Best-effort rename. If the rename fails (e.g. dst already exists on
        // some FS implementations) fall back to copy + delete; if both fail,
        // delete the source so we still don't loop the dialog.
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            try {
                dst.writeBytes(src.readBytes())
            } catch (t: Throwable) {
                Log.w(TAG, "markPendingCrashSeen copy failed", t)
            }
            src.delete()
        }
    }

    /** Delete both the pending and seen crash files; the rolling log is kept. */
    fun clearPendingCrash(ctx: Context) {
        crashFile(ctx).delete()
        seenCrashFile(ctx).delete()
    }

    /** Read the crash file, up to [maxBytes] from the end. */
    fun readCrashSummary(ctx: Context, maxBytes: Int = 64 * 1024): String {
        val f = if (crashFile(ctx).exists()) crashFile(ctx) else seenCrashFile(ctx)
        if (!f.exists()) return ""
        val bytes = f.readBytes()
        val start = (bytes.size - maxBytes).coerceAtLeast(0)
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }
}
