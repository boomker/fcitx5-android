/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import kotlinx.serialization.json.JsonPrimitive

/** Loads a plugin script in a throwaway QuickJS instance, e.g. to render its settings screen. */
internal object JsScript {
    suspend fun describe(script: String, locale: String): List<JsPluginField> {
        val js = JsRuntime.create()
        return try {
            JsStubNatives.install(js, locale)
            JsRuntime.initialize(js, JsPrelude.SOURCE + "\n" + script)
            JsPluginFields.parse(js.evaluate<String>("__describe(" + JsonPrimitive(locale) + ")"))
        } finally {
            runCatching { js.close() }
        }
    }
}

