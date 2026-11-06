/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import okhttp3.HttpUrl

/** Configured endpoints add exact hostnames, not blanket permission to access arbitrary URLs. */
internal class JsAppEndpointPolicy(declaredHosts: Set<String>, settings: Map<String, String>) {
    private val allowedHosts = declaredHosts + settings.values.mapNotNull { value ->
        value.trim().takeIf { it.startsWith("https://", true) }
            ?.let(JsVoiceHelpers::secureUrl)?.host?.trimEnd('.')
    }

    /** Authorization is checked separately, after this validates the destination and before secrets. */
    fun checkUrl(url: String): HttpUrl {
        require(url.startsWith("https://", true)) { "HTTPS is required" }
        val parsed = requireNotNull(JsVoiceHelpers.secureUrl(url)) { "Invalid HTTPS endpoint" }
        require(parsed.host.trimEnd('.') in allowedHosts) {
            "Endpoint must be declared or configured in plugin settings"
        }
        return parsed
    }
}
