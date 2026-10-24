package com.bauman.pocket

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import kotlin.concurrent.thread

/** Виджет: неделя, текущая или следующая пара и ближайший срок. Обновляет его [Ticker]. */
class Widget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread {
            try {
                Ticker.tick(ctx.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun updateAll(ctx: Context, data: Data, at: LocalDateTime) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, Widget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(ctx, data, at))
        }

        private fun views(ctx: Context, data: Data, at: LocalDateTime): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget)
            val today = at.toLocalDate()
            val week = Semester.weekOf(today)
            v.setTextViewText(
                R.id.w_head,
                "${Ru.dowShort(today)}, ${Ru.dayMonthShort(today)}" +
                    if (Semester.inSemester(week)) " · ${Semester.parityName(week)}" else "",
            )

            when (val s = Plan.now(data, at)) {
                is Plan.Now.During -> {
                    v.setTextViewText(R.id.w_label, "сейчас · ещё ${Ru.duration(s.left)}")
                    lesson(v, s.lesson)
                }
                is Plan.Now.Next -> {
                    val day = when (s.date) {
                        today -> ""
                        today.plusDays(1) -> "завтра "
                        else -> "${Ru.dowShort(s.date)} "
                    }
                    v.setTextViewText(R.id.w_label, "дальше · $day${Ru.hm(s.lesson.start!!)}")
                    lesson(v, s.lesson)
                }
                Plan.Now.Nothing -> {
                    v.setTextViewText(R.id.w_label, "пар нет")
                    v.setTextViewText(R.id.w_title, "Свободно")
                    v.setTextViewText(R.id.w_meta, "")
                }
            }

            val b = Plan.buckets(data, at)
            val item = b.upcoming.firstOrNull()
            if (item == null) {
                v.setViewVisibility(R.id.w_due, View.GONE)
            } else {
                v.setViewVisibility(R.id.w_due, View.VISIBLE)
                v.setTextViewText(R.id.w_due_what, "${Semester.subject(item.subj)} · ${item.what}")
                v.setTextViewText(R.id.w_due_when, Plan.until(item, at))
                val color = if (Plan.urgent(item, at)) R.color.hot else R.color.ink2
                v.setTextColor(R.id.w_due_when, ContextCompat.getColor(ctx, color))
            }

            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }

        private fun lesson(v: RemoteViews, l: Lesson) {
            v.setTextViewText(R.id.w_title, l.title)
            v.setTextViewText(R.id.w_meta, listOfNotNull(l.kind, l.room ?: l.note).joinToString(" · "))
        }
    }
}
