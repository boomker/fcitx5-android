/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function

/**
 * 只给设置页用的占位原生实现：没有任何网络与音频能力。
 *
 * 为什么必须有：前奏里的 __native 对象在求值那一刻就把 __n_locale、__n_wsConnect 等名字全部
 * 解引用了一遍，缺一个就是 ReferenceError（真机上的表现是「读取插件设置失败」）。
 */
internal object JsStubNatives {
    fun install(js: QuickJs, locale: String) {
        val binary = JsBinary()
        fun decode(value: String) = binary.decode(value)
        js.function("__n_locale") { locale }
        js.function("__n_wsConnect") { throw IllegalStateException("network is not available here") }
        js.function("__n_wsWaitOpen") { null }
        js.function("__n_wsSend") { null }
        js.function("__n_wsSendBinary") { null }
        js.function("__n_wsSendGzipFrame") { null }
        js.function("__n_wsClose") { null }
        js.function("__n_httpPost") { throw IllegalStateException("network is not available here") }
        js.function("__n_hmacSha1Base64") { throw IllegalStateException("signing is not available here") }
        js.function("__n_concatBase64") { args -> binary.concatenate(text(args, 0)) }
        js.function("__n_wavBase64") { args ->
            encode(JsHostHelpers.wavBytes(decode(text(args, 0)), text(args, 1).toInt()))
        }
        js.function("__n_utf8Base64") { args -> binary.encodeUtf8(text(args, 0)) }
        js.function("__n_base64Utf8") { args -> String(decode(text(args, 0)), Charsets.UTF_8) }
        js.function("__n_byteLength") { args -> decode(text(args, 0)).size }
        js.function("__n_sliceBase64") { args ->
            val data = decode(text(args, 0))
            val offset = text(args, 1).toInt().coerceIn(0, data.size)
            val length = text(args, 2).toInt().coerceIn(0, data.size - offset)
            encode(data.copyOfRange(offset, offset + length))
        }
        js.function("__n_readUInt8") { args -> decode(text(args, 0))[text(args, 1).toInt()].toInt() and 0xff }
        js.function("__n_readInt32BE") { args ->
            JsHostHelpers.readInt32BE(decode(text(args, 0)), text(args, 1).toInt())
        }
        js.function("__n_gunzipUtf8") { args -> String(JsHostHelpers.gunzip(decode(text(args, 0))), Charsets.UTF_8) }
    }

    private fun text(args: Array<Any?>, index: Int): String = args.getOrNull(index)?.toString().orEmpty()

    private fun encode(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}

