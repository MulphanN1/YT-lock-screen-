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
    private const val KEY_EVENTS = "diag_events"
    private const val KEY_CRASH = "diag_crash"
    private const val MAX_EVENTS = 10
    private const val MAX_EXITS = 5

    /** Once per process: the activity is also recreated inside a living process. */
    private var launchNoted = false

    /** Adds a notable event, e.g. the page process crashing or low memory, to a short history. */
    fun note(context: Context, text: String) {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_EVENTS, null)?.lines().orEmpty()
        val events = (old + "${time(System.currentTimeMillis())} $text").takeLast(MAX_EVENTS)
        prefs.edit().putString(KEY_EVENTS, events.joinToString("\n")).commit()
    }

    /** Marks a new process in the history, with the build, to tell old and new versions apart. */
    fun noteLaunch(context: Context) {
        if (launchNoted) return
        launchNoted = true
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        note(context, "запуск, версия ${version ?: "?"}")
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
            prefs.getString(KEY_EVENTS, null)?.let { "События:\n$it" },
            processExits(context)?.let { "Завершения процесса:\n$it" },
        )
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Android 11+ keeps why the previous processes ended (low memory, crash, update...). */
    private fun processExits(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        // The system returns the newest first; the events above read oldest first.
        return exits.reversed().joinToString("\n") { exit ->
            "${time(exit.timestamp)} ${reasonName(exit.reason)}" +
                (exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        }.ifEmpty { null }
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
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "приложение отключено или обновлено"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "установлено обновление"
        else -> "код $reason"
    }

    private fun time(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
}
