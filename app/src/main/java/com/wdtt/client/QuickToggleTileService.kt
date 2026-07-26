package com.wdtt.client

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickToggleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelManager.enabled.value) {
            TunnelControl.stop(applicationContext)
            updateTile(false)
            return
        }

        openVpnPermissionActivity()
    }

    private fun updateTileState() {
        updateTile(TunnelManager.enabled.value)
    }

    private fun updateTile(enabled: Boolean) {
        val tile = qsTile ?: return
        if (enabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Hoplet: Вкл"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Включен"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Hoplet: Выкл"
            if (Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = "Выключен"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    TileService.requestListeningState(
                        context,
                        ComponentName(context, QuickToggleTileService::class.java)
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openVpnPermissionActivity() {
        val launchIntent = VpnPermissionActivity.createLaunchIntent(
            applicationContext,
            VpnPermissionActivity.SOURCE_TILE,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            val pendingIntent = VpnPermissionActivity.createPendingIntent(
                applicationContext,
                VpnPermissionActivity.SOURCE_TILE,
                1001,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
