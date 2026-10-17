package com.rafeeq.assistant.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.rafeeq.assistant.MainActivity

class RafeeqTileService : TileService() {
    companion object {
        private const val TAG = "RafeeqTileService"
    }

    override fun onCreate() {
        super.onCreate()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onStopListening() {
        super.onStopListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivityAndCollapse(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open Rafeeq from tile", e)
        }
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            tile.label = "رفيق"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "مساعدك الصوتي"
            }
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
}