package com.vyze.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash log file that writes timestamped entries to internal storage.
 *
 * Unlike Room-based error logging, this works even for native crashes (SIGSEGV/SIGABRT)
 * because the file is flushed synchronously after each write.
 *
 * After each inference, [exportToDownloads] copies the log to the public Downloads
 * directory so it can be pulled via `adb pull /sdcard/Download/vyze_crash.log`.
 */
object CrashLogFile {

    private const val FILE_NAME = "vyze_crash.log"
    private const val EXPORT_FILE_NAME = "vyze_crash.log"
    private const val MAX_ENTRIES = 200

    private var logFile: File? = null
    private val entries = mutableListOf<String>()
    private val lock = Any()

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
        synchronized(lock) {
            entries.clear()
            logFile?.writeText("--- Crash log started at ${dateFormat.format(Date())} ---\n")
        }
        Log.d("CrashLogFile", "Initialized: ${logFile?.absolutePath}")
    }

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "$timestamp | $tag | $message"
        synchronized(lock) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            appendToFile(entry)
        }
        Log.d(tag, message)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val trace = throwable?.let {
            "\n  ${it.javaClass.simpleName}: ${it.message}\n  ${it.stackTrace.take(8).joinToString("\n  at ") { s -> s.toString() }}"
        } ?: ""
        val entry = "$timestamp | $tag | ERROR: $message$trace"
        synchronized(lock) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }
            appendToFile(entry)
        }
        Log.e(tag, message, throwable)
    }

    fun flush() {
        synchronized(lock) {
            logFile?.let { file ->
                try {
                    FileWriter(file, true).use { writer ->
                        writer.write("--- FLUSH at ${dateFormat.format(Date())} ---\n")
                        writer.flush()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Export the crash log to the public Downloads directory so it can be pulled
     * via `adb pull /sdcard/Download/vyze_crash.log`.
     *
     * Call this after each successful inference so the log is always fresh.
     * If storage permission is not granted, this silently does nothing.
     */
    fun exportToDownloads(context: Context) {
        try {
            val source = File(context.filesDir, FILE_NAME)
            if (!source.exists()) return

            // Check storage access
            val hasAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
            }
            if (!hasAccess) return

            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ) ?: return

            val target = File(downloadsDir, EXPORT_FILE_NAME)
            source.copyTo(target, overwrite = true)
            Log.d("CrashLogFile", "Exported to ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Throwable) {
            Log.w("CrashLogFile", "Export failed: ${e.message}")
        }
    }

    fun read(context: Context): String {
        return try {
            File(context.filesDir, FILE_NAME).readText()
        } catch (_: Exception) {
            "No crash log found"
        }
    }

    private fun appendToFile(entry: String) {
        logFile?.let { file ->
            try {
                FileWriter(file, true).use { writer ->
                    writer.write("$entry\n")
                    writer.flush()
                }
            } catch (_: Exception) {
            }
        }
    }
}
