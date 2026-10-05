package com.firstt175.novaframe.session

import com.firstt175.novaframe.session.diagnostics.CrashReporter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logging wrapper that mirrors calls to both logcat and the shared
 * `filesDir/diagnostics/lsfg.log` file used by the crash reporter. Use
 * instead of android.util.Log for any message that should survive a remote
 * test session.
 *
 * Each line records timestamp, level, thread name, and tag — the thread
 * name plus tag together identify exactly which subsystem produced the
 * line (capture, fps, service, etc. each run on their own HandlerThread),
 * which is what actually lets a crash be traced back to the part of the
 * app that broke.
 *
 * Logging can be turned off entirely via [setEnabled] (persisted, off
 * reduces background work — the file write + logcat call on every line is
 * the main cost, not the file itself). Errors ([e]) are always written
 * regardless of the toggle, since they're low-volume and are what crash
 * diagnosis actually depends on.
 *
 * Initialised lazily from [init] (called by NovaApplication). If init was
 * never reached, we silently fall through to logcat-only.
 */
object NovaLog {

    private const val PREFS_NAME = "lsfg_log_prefs"
    private const val KEY_ENABLED = "logging_enabled"

    private var file: File? = null
    private var prefs: SharedPreferences? = null

    // Cached rather than re-read from SharedPreferences on every call (this
    // fires from hot capture/render loops) — kept in sync by setEnabled().
    @Volatile
    private var enabled: Boolean = true

    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // One BufferedWriter kept open for the process lifetime instead of a
    // fresh FileWriter per call. The previous version opened+wrote+closed a
    // new file handle (two syscalls plus object allocation) on every single
    // i()/w()/e() call across ~85 call sites in service/capture code — this
    // mirrors the persistent-fd approach the native ring logger already uses
    // for the same lsfg.log file. writeLock also confines timestampFormat
    // (a SimpleDateFormat, which is documented as not thread-safe) to one
    // critical section: NovaLog is called from several different
    // HandlerThreads concurrently (capture, fps, service), so the shared
    // formatter was previously a latent race that could corrupt timestamps
    // or throw.
    private val writeLock = Any()
    private var writer: BufferedWriter? = null

    fun init(ctx: Context) {
        val appCtx = ctx.applicationContext
        file = CrashReporter.logFile(appCtx)
        val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        enabled = p.getBoolean(KEY_ENABLED, true)
    }

    fun isEnabled(): Boolean = enabled

    /** Persist and apply the toggle immediately. Safe to call from the UI thread. */
    fun setEnabled(value: Boolean) {
        enabled = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        if (!value) {
            // Drop the open writer so a disabled session doesn't keep a
            // stale fd around; the next enabled append() reopens fresh.
            synchronized(writeLock) {
                runCatching { writer?.flush() }
                writer = null
            }
        }
    }

    /** Verbose/debug detail — gated by the toggle. */
    fun d(tag: String, msg: String) {
        if (!enabled) return
        Log.d(tag, msg)
        append('D', tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        if (!enabled) return
        Log.i(tag, msg)
        append('I', tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (!enabled) return
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        append('W', tag, msg, tr)
    }

    /** Always written, even when logging is toggled off — this is what crash diagnosis relies on. */
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        append('E', tag, msg, tr)
    }

    private fun append(level: Char, tag: String, msg: String, tr: Throwable?) {
        val f = file ?: return
        synchronized(writeLock) {
            try {
                val w = writer ?: BufferedWriter(FileWriter(f, true)).also { writer = it }
                val ts = timestampFormat.format(Date())
                val threadName = Thread.currentThread().name
                w.write(ts); w.write(" "); w.write(level.toString()); w.write("/")
                w.write(tag); w.write(" ["); w.write(threadName); w.write("]: ")
                w.write(msg); w.newLine()
                if (tr != null) {
                    val sw = StringWriter()
                    tr.printStackTrace(PrintWriter(sw))
                    w.write(sw.toString().trimEnd()); w.newLine()
                }
                // Flush (cheap) rather than close (expensive): keeps the
                // on-disk copy current for crash forensics without paying
                // open/close syscalls on every line.
                w.flush()
            } catch (_: Throwable) {
                // Best-effort: never let logging crash the app. Drop the
                // writer so the next call retries a fresh open rather than
                // reusing a handle that may be in a bad state.
                writer = null
            }
        }
    }
}
