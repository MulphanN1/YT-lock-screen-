package com.ytbg.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

object Tiles {

    /** Asks the system to redraw both quick-settings tiles with the current state. */
    fun refresh(context: Context) {
        for (cls in listOf(BackgroundTileService::class.java, BlackScreenTileService::class.java)) {
            try {
                TileService.requestListeningState(context, ComponentName(context, cls))
            } catch (e: RuntimeException) {
                // Tile not added or the launcher does not support it.
            }
        }
    }
}

private fun TileService.render(active: Boolean) {
    val tile = qsTile ?: return
    tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
    tile.updateTile()
}

/** Opens the app and closes the notification shade. */
@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.openApp(extra: String? = null) {
    val intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (extra != null) intent.putExtra(extra, true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                extra.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

/** "YT Фон": background playback inside the app's own player. */
class BackgroundTileService : TileService() {

    override fun onStartListening() {
        render(Prefs.isEnabled(this))
    }

    override fun onClick() {
        val on = !Prefs.isEnabled(this)
        Prefs.setEnabled(this, on)
        PlayerBridge.reportBackgroundMode(on)
        if (on) {
            // The service can only start while the app is visible, and the player lives there.
            openApp()
        } else {
            PlaybackService.stop(this)
        }
        render(on)
    }
}

/** "Чёрный экран": keeps the real YouTube app playing under a black overlay. */
class BlackScreenTileService : TileService() {

    override fun onStartListening() {
        render(BlackScreen.isShowing)
    }

    override fun onClick() {
        when {
            BlackScreen.isShowing -> BlackScreen.hide(this)
            BlackScreen.canShow(this) -> BlackScreen.show(this)
            else -> openApp(MainActivity.EXTRA_BLACK_SCREEN)
        }
        render(BlackScreen.isShowing)
    }
}
