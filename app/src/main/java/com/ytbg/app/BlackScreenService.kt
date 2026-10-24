package com.ytbg.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive while [BlackScreen] is shown and offers a "turn off"
 * notification as a way out that does not depend on the overlay itself.
 */
class BlackScreenService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            BlackScreen.hide(this)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dark)
            .setContentTitle(getString(R.string.black_notif_title))
            .setContentText(getString(R.string.black_notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_close,
                getString(R.string.action_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, BlackScreenService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!BlackScreen.isShowing) stopSelf()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.black_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "black_screen"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "com.ytbg.app.BLACK_STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BlackScreenService::class.java))
            } catch (e: IllegalStateException) {
                // Background start refused: the overlay still works, just without the notification.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlackScreenService::class.java))
        }
    }
}
