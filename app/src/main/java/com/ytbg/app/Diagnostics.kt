package com.ytbg.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

/**
 * Traces why the app disappeared. It survives the process dying, so the reason can be read
 * on the next launch (long press on the power button).
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
            prefs.getString(KEY_CRASH, null)?.let { "Сбой: $it" },
            prefs.getString(KEY_EVENT, null)?.let { "Событие: $it" },
            lastProcessExit(context)?.let { "Процесс: $it" },
        )
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** Android 11+ keeps the reason the previous process ended (low memory, crash, killed...). */
    private fun lastProcessExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return "${time(exit.timestamp)} ${reasonName(exit.reason)}" +
            (exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "приложение закрылось само"
        ApplicationExitInfo.REASON_SIGNALED -> "убито сигналом"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "нехватка памяти"
        ApplicationExitInfo.REASON_CRASH -> "сбой (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "сбой (нативный)"
        ApplicationExitInfo.REASON_ANR -> "приложение зависло"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ошибка запуска"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "смена разрешений"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "слишком много ресурсов"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "остановлено пользователем"
        ApplicationExitInfo.REASON_USER_STOPPED -> "остановлено пользователем (пользователь ОС)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "умерла зависимость"
        ApplicationExitInfo.REASON_OTHER -> "другая причина (система)"
        else -> "код $reason"
    }

    private fun time(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}
