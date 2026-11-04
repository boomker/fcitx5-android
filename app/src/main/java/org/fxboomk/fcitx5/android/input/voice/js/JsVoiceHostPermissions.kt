/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.content.Context
import okhttp3.HttpUrl
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.appContext

/** A plugin's own hosts declaration is a limit, never evidence of user consent. */
internal object JsVoiceHostPermissions {
    private const val PREFS = "js_voice_host_permissions"
    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun approvedHosts(pluginId: String): Set<String> =
        prefs().getStringSet(pluginId, emptySet()).orEmpty().toSet()

    fun isApproved(pluginId: String, hosts: Set<String>): Boolean =
        hosts.isNotEmpty() && approvedHosts(pluginId).containsAll(hosts)

    /** Called only after the user confirms the displayed, complete hosts list. */
    fun approve(pluginId: String, hosts: Set<String>) {
        require(hosts.isNotEmpty() && hosts.all { JsVoicePluginMeta.normalizeHost(it) == it })
        check(prefs().edit().putStringSet(pluginId, hosts.toSet()).commit())
    }

    fun revoke(pluginId: String) {
        check(prefs().edit().remove(pluginId).commit())
    }
}

/** Re-read consent on each operation, including sends on an already opened socket. */
internal class JsVoiceNetworkPolicy(
    private val pluginId: String,
    private val declaredHosts: Set<String>,
) {
    fun requireApproval() {
        check(JsVoiceHostPermissions.isApproved(pluginId, declaredHosts)) {
            appContext.getString(R.string.js_voice_network_not_approved)
        }
    }

    fun checkUrl(url: String): HttpUrl {
        val parsed = JsVoiceHelpers.secureUrl(url)
        require(parsed != null && parsed.host.trimEnd('.') in declaredHosts) {
            "Host is not declared by the plugin"
        }
        requireApproval()
        return parsed
    }
}
