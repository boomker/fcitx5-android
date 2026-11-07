/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import android.content.Context
import org.fxboomk.fcitx5.android.utils.appContext

internal object JsAppPermissions {
    private fun prefs() = appContext.getSharedPreferences("js_app_permissions", Context.MODE_PRIVATE)
    fun granted(plugin: String, app: String): Set<String> = prefs().getStringSet("$plugin/$app", emptySet()).orEmpty().toSet()
    fun grant(plugin: String, app: String, permissions: Set<String>) {
        check(prefs().edit().putStringSet("$plugin/$app", permissions).commit())
    }
    fun revoke(plugin: String) {
        val edit = prefs().edit()
        prefs().all.keys.filter { it.startsWith("$plugin/") }.forEach(edit::remove)
        check(edit.commit())
    }
}

