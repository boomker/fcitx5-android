package org.fcitx.fcitx5.android.plugin.clipboard_sync.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R

class QuickSyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(PREF_QUICK_SYNC, true)
        prefs.edit().putBoolean(PREF_QUICK_SYNC, !enabled).apply()
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_QUICK_SYNC, true)
        tile.label = getString(R.string.quick_sync_tile_label)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    private companion object {
        const val PREF_QUICK_SYNC = "quick_sync"
    }
}
