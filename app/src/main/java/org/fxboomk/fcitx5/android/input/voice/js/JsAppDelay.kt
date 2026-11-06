/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.delay

/** QuickJS has no browser timers. A bounded coroutine delay remains cancellable with the app. */
internal fun installJsAppDelay(js: QuickJs, ensureCurrent: suspend () -> Unit) {
    js.asyncFunction("__n_appSleep") { args ->
        ensureCurrent()
        val milliseconds = (args.getOrNull(0) as? Number)?.toDouble()
        require(milliseconds != null && milliseconds.isFinite() &&
            milliseconds in 0.0..10_000.0 && milliseconds % 1.0 == 0.0) { "Invalid App delay" }
        delay(milliseconds.toLong())
        ensureCurrent()
        null
    }
}
