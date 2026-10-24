package com.bauman.pocket

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Одна цепочка будильников на всё: в каждый момент, когда что-то меняется (за 15 минут до пары,
 * начало, конец, напоминание о сроке, полночь), обновляем уведомление «сейчас», виджет и
 * ставим следующий будильник.
 */
class Ticker : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                tick(ctx.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val CH_NOW = "now"
        private const val CH_DEADLINES = "deadlines"
        private const val ID_NOW = 1

        fun tick(ctx: Context) {
            val data = Store.load(ctx)
            val at = Semester.now()
            channels(ctx)
            showNow(ctx, data, at)
            fireReminders(ctx, data, at)
            Widget.updateAll(ctx, data, at)
            schedule(ctx, Plan.nextChange(data, at))
        }

        private fun channels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_NOW, "Текущая пара", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Какая пара идёт или будет через 15 минут. Видно на экране блокировки."
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DEADLINES, "Сроки", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Накануне вечером и в день сдачи."
                },
            )
        }

        private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun openApp(ctx: Context, code: Int, tab: String? = null): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (tab != null) i.putExtra(MainActivity.EXTRA_TAB, tab)
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun showNow(ctx: Context, data: Data, at: LocalDateTime) {
            val nm = NotificationManagerCompat.from(ctx)
            val state = Plan.now(data, at)
            val show = Store.notifyNow(ctx) && canNotify(ctx) && when (state) {
                is Plan.Now.During -> true
                is Plan.Now.Next -> state.date == at.toLocalDate() && state.inMinutes <= Plan.LEAD_MIN
                Plan.Now.Nothing -> false
            }
            if (!show) {
                nm.cancel(ID_NOW)
                return
            }
            val (lesson, label, target) = when (state) {
                is Plan.Now.During -> Triple(state.lesson, "идёт · до ${Ru.hm(state.lesson.end!!)}", state.lesson.end!!)
                is Plan.Now.Next -> Triple(state.lesson, "в ${Ru.hm(state.lesson.start!!)}", state.lesson.start!!)
                Plan.Now.Nothing -> return
            }
            val title = lesson.title + (lesson.kind?.let { " · $it" } ?: "")
            val text = listOfNotNull(lesson.room ?: lesson.note, lesson.teacher).joinToString(" · ")
            val whenMs = at.toLocalDate().atTime(target).atZone(Semester.ZONE).toInstant().toEpochMilli()
            val n = NotificationCompat.Builder(ctx, CH_NOW)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(ContextCompat.getColor(ctx, R.color.accent))
                .setContentTitle(title)
                .setContentText(text.ifEmpty { label })
                .setSubText(label)
                .setWhen(whenMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openApp(ctx, 1))
                .build()
            try {
                nm.notify(ID_NOW, n)
            } catch (_: SecurityException) {
            }
        }

        private fun fireReminders(ctx: Context, data: Data, at: LocalDateTime) {
            val prefs = Store.prefs(ctx)
            val nowMs = System.currentTimeMillis()
            var last = prefs.getLong("last_tick", 0)
            // Первый запуск или телефон долго был выключен: старое не вываливаем.
            if (last == 0L || nowMs - last > TimeUnit.HOURS.toMillis(12)) last = nowMs - TimeUnit.MINUTES.toMillis(5)
            prefs.edit().putLong("last_tick", nowMs).apply()
            if (!Store.notifyDeadlines(ctx) || !canNotify(ctx)) return

            val from = at.minus(Duration.ofMillis(nowMs - last))
            val nm = NotificationManagerCompat.from(ctx)
            for (r in Plan.reminders(data)) {
                if (r.at <= from || r.at > at) continue
                val item = r.item
                val body = listOfNotNull(item.what, item.statusText.takeIf { it.isNotEmpty() }).joinToString("\n")
                val n = NotificationCompat.Builder(ctx, CH_DEADLINES)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setColor(ContextCompat.getColor(ctx, R.color.accent))
                    .setContentTitle(r.title)
                    .setContentText(item.what)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(ctx, 2, MainActivity.TAB_DEADLINES))
                    .build()
                try {
                    nm.notify(r.id, n)
                } catch (_: SecurityException) {
                }
            }
        }

        private fun schedule(ctx: Context, next: LocalDateTime) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(ctx, Ticker::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val ms = next.atZone(Semester.ZONE).toInstant().toEpochMilli()
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            try {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        }

        /** Запустить цепочку будильников и фоновое обновление данных. */
        fun start(ctx: Context) {
            thread { tick(ctx.applicationContext) }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        // Ошибки покажут настройки; следующая попытка — по расписанию, без повторов вдогонку.
        if (Store.token(applicationContext).isNotBlank()) Store.syncCatching(applicationContext)
        if (LksClient.connected(applicationContext)) LksClient.syncCatching(applicationContext)
        Ticker.tick(applicationContext)
        return Result.success()
    }
}
