package org.fxboomk.fcitx5.android.plugin.clipboard_sync

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.fxboomk.fcitx5.android.plugin.clipboard_sync.R

object SyncNetworkPolicy {
    const val PREF_WIFI_ONLY = "sync_network_wifi_only"
    const val PREF_ALLOWED_WIFI_ONLY = "sync_network_allowed_wifi_only"
    const val PREF_ALLOWED_SSIDS = "sync_network_allowed_ssids"

    data class State(
        val wifiOnly: Boolean,
        val allowedWifiOnly: Boolean,
        val allowedSsids: Set<String>
    )

    enum class Reason {
        Allowed,
        NonWifiNetwork,
        NoAllowedWifiSelected,
        UnknownWifiSsid,
        WifiNotAllowed
    }

    data class Decision(
        val allowed: Boolean,
        val reason: Reason,
        val currentSsid: String? = null
    )

    fun ensureDefaults(prefs: SharedPreferences) {
        if (
            prefs.contains(PREF_WIFI_ONLY) &&
            prefs.contains(PREF_ALLOWED_WIFI_ONLY) &&
            prefs.contains(PREF_ALLOWED_SSIDS)
        ) {
            return
        }
        prefs.edit()
            .putBoolean(PREF_WIFI_ONLY, prefs.getBoolean(PREF_WIFI_ONLY, false))
            .putBoolean(PREF_ALLOWED_WIFI_ONLY, prefs.getBoolean(PREF_ALLOWED_WIFI_ONLY, false))
            .putStringSet(PREF_ALLOWED_SSIDS, prefs.getStringSet(PREF_ALLOWED_SSIDS, emptySet()) ?: emptySet())
            .apply()
    }

    fun loadState(prefs: SharedPreferences): State {
        return State(
            wifiOnly = prefs.getBoolean(PREF_WIFI_ONLY, false),
            allowedWifiOnly = prefs.getBoolean(PREF_ALLOWED_WIFI_ONLY, false),
            allowedSsids = prefs.getStringSet(PREF_ALLOWED_SSIDS, emptySet())
                ?.mapNotNull(::normalizeSsid)
                ?.toSet()
                .orEmpty()
        )
    }

    fun buildSummary(context: Context, prefs: SharedPreferences): String {
        val state = loadState(prefs)
        return when {
            state.allowedWifiOnly && state.allowedSsids.isNotEmpty() ->
                context.getString(R.string.sync_network_summary_allowed_count, state.allowedSsids.size)

            state.allowedWifiOnly ->
                context.getString(R.string.sync_network_summary_allowed_empty)

            state.wifiOnly ->
                context.getString(R.string.sync_network_summary_wifi_only)

            else ->
                context.getString(R.string.sync_network_summary_any)
        }
    }

    fun buildSelectedWifiSummary(context: Context, prefs: SharedPreferences): String {
        val count = loadState(prefs).allowedSsids.size
        return if (count > 0) {
            context.getString(R.string.sync_network_selected_wifi_summary_count, count)
        } else {
            context.getString(R.string.sync_network_selected_wifi_summary_empty)
        }
    }

    fun requiredRuntimePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasRequiredRuntimePermissions(context: Context): Boolean {
        return requiredRuntimePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    @Suppress("DEPRECATION")
    fun availableScanSsids(context: Context): List<String> {
        if (!hasRequiredRuntimePermissions(context) || !isLocationEnabled(context)) {
            return emptyList()
        }
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        return runCatching { wifiManager.scanResults }
            .getOrDefault(emptyList())
            .mapNotNull { result -> normalizeSsid(result.SSID) }
            .distinct()
            .sorted()
    }

    @Suppress("DEPRECATION")
    fun currentConnectedWifiSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
        val wifiInfoSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        normalizeSsid(wifiInfoSsid)?.let { return it }

        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        val transportSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (capabilities.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        return normalizeSsid(transportSsid)
    }

    fun isWifiConnected(context: Context): Boolean {
        currentConnectedWifiSsid(context)?.let { return true }
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun evaluate(context: Context, prefs: SharedPreferences): Decision {
        val state = loadState(prefs)
        if (!state.wifiOnly && !state.allowedWifiOnly) {
            return Decision(allowed = true, reason = Reason.Allowed)
        }

        if (!isWifiConnected(context)) {
            return Decision(allowed = false, reason = Reason.NonWifiNetwork)
        }
        if (!state.allowedWifiOnly) {
            return Decision(allowed = true, reason = Reason.Allowed)
        }
        if (state.allowedSsids.isEmpty()) {
            return Decision(allowed = false, reason = Reason.NoAllowedWifiSelected)
        }
        val currentSsid = currentConnectedWifiSsid(context)
            ?: return Decision(allowed = false, reason = Reason.UnknownWifiSsid)
        return if (currentSsid in state.allowedSsids) {
            Decision(allowed = true, reason = Reason.Allowed, currentSsid = currentSsid)
        } else {
            Decision(allowed = false, reason = Reason.WifiNotAllowed, currentSsid = currentSsid)
        }
    }

    fun normalizeSsid(raw: String?): String? {
        val trimmed = raw?.trim()?.removeSurrounding("\"").orEmpty()
        return trimmed.takeIf {
            it.isNotBlank() &&
                !it.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true) &&
                !it.equals("<unknown ssid>", ignoreCase = true)
        }
    }
}
