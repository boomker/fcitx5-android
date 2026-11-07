/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 插件密钥的落盘加密。
 *
 * 密钥材料只存在于 AndroidKeyStore（TEE/StrongBox 视设备而定），应用只拿得到
 * [SecretKey] 句柄；密文与随机 IV 一起以 Base64 存 SharedPreferences。
 * 这样即使设备被 root、SharedPreferences 被直接读走，也拿不到明文 API key。
 *
 * 不引入第三方加密库：AES/GCM 在 API 23 起就是平台能力（minSdk = 23）。
 */
internal object JsSecretCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "js_voice_plugin_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(payload: String): String? = runCatching {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) return null
        val iv = raw.copyOfRange(0, IV_LENGTH)
        val ciphertext = raw.copyOfRange(IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
