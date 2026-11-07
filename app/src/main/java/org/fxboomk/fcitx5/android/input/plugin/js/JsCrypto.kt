/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Resolve credentials inside the host; scripts receive the signature, never the key. */
internal object JsCrypto {
    fun hmacSha1(secretField: String, text: String, secretFor: (String) -> String?): ByteArray {
        val secret = secretFor(secretField)?.takeIf { it.isNotBlank() }
            ?: throw JsSecretMissingException(secretField)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(text.toByteArray(Charsets.UTF_8))
    }
}
