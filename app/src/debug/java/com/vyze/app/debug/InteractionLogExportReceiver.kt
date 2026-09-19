package com.vyze.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY receiver (src/debug source set — never compiled into release).
 *
 * adb trigger (from a development machine with the debug build installed):
 *
 *     adb shell am broadcast \
 *         -n com.vyze.app/.debug.InteractionLogExportReceiver
 *
 * Exported=true is intentional and safe: this component EXISTS ONLY in
 * debug builds, and its sole effect is writing a JSONL transcript file to
 * this app's own external-files directory (local storage, no network).
 *
 * [goAsync] keeps the process alive for the short DB read + file write
 * (well inside the ~10s allowance); finish() always runs.
 */
class InteractionLogExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = InteractionLogExporter.export(appContext)
                Log.i(
                    TAG,
                    if (file != null) "Export complete: ${file.absolutePath}"
                    else "Export produced no file (empty stores or write failure)",
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Export crashed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "InteractionLogExport"
    }
}
