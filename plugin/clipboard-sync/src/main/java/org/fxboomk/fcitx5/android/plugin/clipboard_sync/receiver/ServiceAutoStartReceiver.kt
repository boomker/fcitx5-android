package org.fxboomk.fcitx5.android.plugin.clipboard_sync.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fxboomk.fcitx5.android.plugin.clipboard_sync.MainService

class ServiceAutoStartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FcitxClipboardSync"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (!MainService.shouldAutoStart(context)) {
            Log.d(TAG, "[AutoStart] Ignoring $action because quick sync is disabled")
            return
        }
        Log.d(TAG, "[AutoStart] Restarting clipboard sync after broadcast: $action")
        MainService.startSyncService(context, action.ifBlank { "broadcast" })
    }
}
