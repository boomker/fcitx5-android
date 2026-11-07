/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout

/** Source size, JS heap, host buffers and transport messages have independent budgets. */
internal object JsRuntime {
    const val MEMORY_BYTES = 32L * 1024 * 1024
    const val INITIALIZATION_MS = 2_000L
    const val BINARY_BYTES = 4 * 1024 * 1024
    const val RESPONSE_BYTES = 2 * 1024 * 1024
    const val WEBSOCKET_BYTES = 1024L * 1024
    const val REQUEST_BYTES = 8L * 1024 * 1024

    fun create() = QuickJs.create(Dispatchers.IO).apply {
        memoryLimit = MEMORY_BYTES
        evaluationTimeoutMillis = INITIALIZATION_MS
    }

    /** The engine interrupt bounds CPU work; this also bounds a never-settling top-level Promise. */
    suspend fun initialize(js: QuickJs, source: String) = withTimeout(INITIALIZATION_MS) {
        js.evaluate<Any?>(source)
        Unit
    }

}
