package com.ytbg.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/** Home-screen player. [PlaybackService] pushes the current track into it. */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context, current))
    }

    /** What the widget shows; null when nothing is loaded. */
    data class State(val title: String, val artist: String, val playing: Boolean, val art: Bitmap?)

    companion object {
        private var current: State? = null

        fun update(context: Context, state: State?) {
            if (state == current) return
            current = state
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, PlayerWidget::class.java)
            if (manager.getAppWidgetIds(component).isEmpty()) return
            manager.updateAppWidget(component, views(context, state))
        }

        private fun views(context: Context, state: State?): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_player).apply {
                val openApp = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                setOnClickPendingIntent(R.id.widgetRoot, openApp)
                setOnClickPendingIntent(R.id.art, openApp)

                val tint = ContextCompat.getColor(context, R.color.text_primary)
                for (id in listOf(R.id.previous, R.id.playPause, R.id.next)) setInt(id, "setColorFilter", tint)

                if (state == null) {
                    setTextViewText(R.id.title, context.getString(R.string.widget_idle_title))
                    setTextViewText(R.id.artist, context.getString(R.string.widget_idle_text))
                    setImageViewResource(R.id.art, R.mipmap.ic_launcher)
                    setImageViewResource(R.id.playPause, R.drawable.ic_play)
                    // The player lives in the app: without it the buttons just open it.
                    for (id in listOf(R.id.previous, R.id.playPause, R.id.next)) setOnClickPendingIntent(id, openApp)
                    return@apply
                }

                setTextViewText(R.id.title, state.title)
                setTextViewText(R.id.artist, state.artist)
                if (state.art != null) {
                    setImageViewBitmap(R.id.art, state.art)
                } else {
                    setImageViewResource(R.id.art, R.mipmap.ic_launcher)
                }
                setImageViewResource(R.id.playPause, if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
                setContentDescription(
                    R.id.playPause,
                    context.getString(if (state.playing) R.string.action_pause else R.string.action_play),
                )
                setOnClickPendingIntent(R.id.previous, serviceIntent(context, PlaybackService.ACTION_PREVIOUS))
                setOnClickPendingIntent(
                    R.id.playPause,
                    serviceIntent(context, if (state.playing) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY),
                )
                setOnClickPendingIntent(R.id.next, serviceIntent(context, PlaybackService.ACTION_NEXT))
            }

        private fun serviceIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Widget taps may arrive while the app is in the background, where only a
            // foreground-service start is allowed.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, WIDGET_REQUEST + action.hashCode(), intent, flags)
            } else {
                PendingIntent.getService(context, WIDGET_REQUEST + action.hashCode(), intent, flags)
            }
        }

        private const val WIDGET_REQUEST = 1000
    }
}
