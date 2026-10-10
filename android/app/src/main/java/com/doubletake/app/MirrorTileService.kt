package com.doubletake.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: stops a running session, or reconnects to the last receiver. */
class MirrorTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        when (val s = Ui.session) {
            is Session.Mirroring -> tile.set(Tile.STATE_ACTIVE, s.name)
            is Session.Connecting -> tile.set(Tile.STATE_ACTIVE, "Connecting…")
            else -> tile.set(Tile.STATE_INACTIVE, MainActivity.lastReceiver(this)?.name)
        }
    }

    private fun Tile.set(state: Int, subtitle: String?) {
        this.state = state
        this.subtitle = subtitle
        updateTile()
    }

    override fun onClick() {
        if (Ui.session is Session.Mirroring || Ui.session is Session.Connecting) {
            startService(Intent(this, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
            return
        }
        // Screen-capture consent needs an activity, so reconnecting goes through the app.
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_QUICK_CONNECT, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }

    companion object {
        /** Ask the system to refresh the tile after the session changes. */
        fun refresh(context: Context) =
            requestListeningState(context, ComponentName(context, MirrorTileService::class.java))
    }
}
