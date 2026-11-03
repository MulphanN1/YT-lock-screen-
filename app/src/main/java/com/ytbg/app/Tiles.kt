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

    /** Asks the system to redraw the quick-settings tile with the current state. */
    fun refresh(context: Context) {
        try {
            TileService.requestListeningState(context, ComponentName(context, BackgroundTileService::class.java))
        } catch (e: RuntimeException) {
            // Tile not added or the launcher does not support it.
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
private fun TileService.openApp() {
    val intent = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

/** Background playback inside the app's own player. */
class BackgroundTileService : TileService() {

    override fun onStartListening() {
        render(Prefs.isEnabled(this))
    }

    override fun onClick() {
        val on = !Prefs.isEnabled(this)
        Prefs.setEnabled(this, on)
        PlayerBridge.reportBackgroundMode(on)
        // The player lives in the app, so turning the mode on brings it to the front.
        if (on) openApp()
        render(on)
    }
}
