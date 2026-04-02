package org.fcitx.fcitx5.android.plugin.clipboard_sync.service

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.plugin.clipboard_sync.MainService
import org.fcitx.fcitx5.android.plugin.clipboard_sync.R

class QuickSyncTileService : TileService() {

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_QUICK_SYNC) {
            refreshTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshTile()
    }

    override fun onStopListening() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val enabled = prefs.getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)
        val newValue = !enabled
        prefs.edit().putBoolean(PREF_QUICK_SYNC, newValue).apply()
        if (newValue) {
            MainService.startSyncService(this, "quick-tile-enabled", forceEnableSync = true)
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean(PREF_QUICK_SYNC, DEFAULT_QUICK_SYNC_ENABLED)
        tile.label = getString(R.string.quick_sync_tile_label)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    companion object {
        private const val PREF_QUICK_SYNC = "quick_sync"
        private const val DEFAULT_QUICK_SYNC_ENABLED = false

        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            requestListeningState(
                context,
                ComponentName(context, QuickSyncTileService::class.java)
            )
        }
    }
}
