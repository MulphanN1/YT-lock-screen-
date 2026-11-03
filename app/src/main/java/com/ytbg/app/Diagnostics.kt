package com.ytbg.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

/**
 * Traces why the app disappeared. It survives the process dying, so the reason can be read
 * on the next launch (Settings → Crash log).
 */
object Diagnostics {
    private const val KEY_EVENT = "diag_event"
    private const val KEY_CRASH = "diag_crash"

    /** Remembers the latest notable event, e.g. the page process crashing or low memory. */
    fun note(context: Context, text: String) {
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_EVENT, "${time(System.currentTimeMillis())} $text")
            .commit()
    }

    /** Stores uncaught exceptions before the process goes down, then lets the system handle them. */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                app.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CRASH, "${time(System.currentTimeMillis())} ${error.javaClass.simpleName}: ${error.message}")
                    .commit()
            } catch (e: Exception) {
                // Nothing more can be done while crashing.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A multi-line summary for the dialog, or null when there is nothing to show. */
    fun report(context: Context): String? {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val lines = listOfNotNull(
            prefs.getString(KEY_CRASH, null)?.let { context.getString(R.string.diag_crash, it) },
            prefs.getString(KEY_EVENT, null)?.let { context.getString(R.string.diag_event, it) },
            lastProcessExit(context)?.let { context.getString(R.string.diag_process, it) },
        )
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Android 11+ keeps the reason the previous process ended (low memory, crash, killed...). */
    private fun lastProcessExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return "${time(exit.timestamp)} ${reasonName(context, exit.reason)}" +
            (exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    private fun reasonName(context: Context, reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> context.getString(R.string.exit_self)
        ApplicationExitInfo.REASON_SIGNALED -> context.getString(R.string.exit_signaled)
        ApplicationExitInfo.REASON_LOW_MEMORY -> context.getString(R.string.exit_low_memory)
        ApplicationExitInfo.REASON_CRASH -> context.getString(R.string.exit_crash)
        ApplicationExitInfo.REASON_CRASH_NATIVE -> context.getString(R.string.exit_crash_native)
        ApplicationExitInfo.REASON_ANR -> context.getString(R.string.exit_anr)
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> context.getString(R.string.exit_init_failure)
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> context.getString(R.string.exit_permission_change)
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> context.getString(R.string.exit_excessive_resources)
        ApplicationExitInfo.REASON_USER_REQUESTED -> context.getString(R.string.exit_user_requested)
        ApplicationExitInfo.REASON_USER_STOPPED -> context.getString(R.string.exit_user_stopped)
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> context.getString(R.string.exit_dependency_died)
        ApplicationExitInfo.REASON_OTHER -> context.getString(R.string.exit_other)
        else -> context.getString(R.string.exit_code, reason)
    }

    private fun time(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}
