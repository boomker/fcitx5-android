/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.testing

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement

/**
 * Requires the target build's real IME, once per class, before any test setup runs.
 * Install with `companion object { @ClassRule @JvmField val defaultIme = RequireDefaultImeRule() }`.
 * A wrong default or disabled IME fails the class; it is never silently skipped.
 *
 * Opt in to temporary selection with `adb shell am instrument -w -r -e ensureDefaultIme true ...`.
 * The Gradle argument is `-Pandroid.testInstrumentationRunnerArguments.ensureDefaultIme=true`.
 * Auto mode restores both secure settings exactly, even if setup or a test fails.
 * Strict mode does not write settings. Both modes actively show a keyboard in an empty test
 * editor, retrying for up to 25 seconds, then hide it and destroy the host before test setup.
 */
class RequireDefaultImeRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val expected = ComponentName(instrumentation.targetContext.packageName,
                FcitxInputMethodService::class.java.name).flattenToShortString()
            val original = snapshot(instrumentation)
            val auto = InstrumentationRegistry.getArguments().getString("ensureDefaultIme")
                .equals("true", ignoreCase = true)
            // UiAutomation uses Runtime.exec, not a shell: these settings must be single tokens.
            if (auto) {
                original.defaultIme?.takeIf { it.isNotEmpty() }?.let(::token)
                original.enabledImes?.takeIf { it.isNotEmpty() }?.let(::token)
            }
            val userId = if (auto) shell(instrumentation, "am get-current-user").trim().toInt() else null
            val failures = mutableListOf<Throwable>()
            try {
                if (auto && !original.matches(expected)) {
                    shell(instrumentation, "ime enable ${token(expected)}")
                    shell(instrumentation, "ime set ${token(expected)}")
                }
                val current = snapshot(instrumentation)
                if (!current.matches(expected)) {
                    throw AssertionError("${description.displayName} requires this build's enabled default IME. " +
                        "Expected=$expected; current default=${current.defaultIme}; " +
                        "enabled=${current.isEnabled(expected)}. Run:\n" +
                        "adb shell ime enable $expected\n" +
                        "adb shell ime set $expected\n" +
                        "Or opt in with -e ensureDefaultIme true (restores settings after the class).")
                }
                warmUpKeyboard(instrumentation, expected, description.displayName)
                base.evaluate()
            } catch (failure: Throwable) {
                failures += failure
            } finally {
                if (auto) {
                    try {
                        restore(instrumentation, original, requireNotNull(userId))
                    } catch (failure: Throwable) {
                        failures += failure
                    }
                }
            }
            MultipleFailureException.assertEmpty(failures)
        }
    }

    private data class ImeSettings(val defaultIme: String?, val enabledImes: String?) {
        fun isEnabled(expected: String): Boolean = enabledImes.orEmpty().split(':').any {
            component(it.substringBefore(';')) == component(expected)
        }

        fun matches(expected: String): Boolean =
            component(defaultIme) == component(expected) && isEnabled(expected)
    }

    private fun snapshot(instrumentation: Instrumentation): ImeSettings {
        // Android 14+ blocks direct ENABLED_INPUT_METHODS reads for targetSdk > 33.
        // Read as the instrumentation shell without adopting or leaking permissions.
        fun read(key: String): String? = shell(instrumentation, "settings get secure $key")
            .removeSuffix("\n").removeSuffix("\r").takeUnless { it == "null" }
        return ImeSettings(read(Settings.Secure.DEFAULT_INPUT_METHOD), read(Settings.Secure.ENABLED_INPUT_METHODS))
    }

    private fun restore(instrumentation: Instrumentation, original: ImeSettings, userId: Int) {
        val failures = mutableListOf<Throwable>()
        fun attempt(block: () -> Unit) {
            try { block() } catch (failure: Throwable) { failures += failure }
        }
        if (component(original.defaultIme) != null) {
            attempt { shell(instrumentation, "ime enable ${token(requireNotNull(original.defaultIme))}") }
            attempt { shell(instrumentation, "ime set ${token(requireNotNull(original.defaultIme))}") }
        }
        // ime set may canonicalize a short component name; preserve the original raw value too.
        attempt { putSetting(instrumentation, userId, Settings.Secure.DEFAULT_INPUT_METHOD, original.defaultIme) }
        attempt { putSetting(instrumentation, userId, Settings.Secure.ENABLED_INPUT_METHODS, original.enabledImes) }
        attempt {
            val restored = snapshot(instrumentation)
            if (restored != original) {
                throw AssertionError("Failed to restore IME settings exactly: expected=$original; actual=$restored")
            }
            Log.i(TAG, "Restored default=${restored.defaultIme}; enabled list matches original")
        }
        MultipleFailureException.assertEmpty(failures)
    }

    private fun putSetting(instrumentation: Instrumentation, userId: Int, key: String, value: String?) {
        val command = when (value) {
            null -> "settings --user $userId delete secure $key"
            // Runtime.exec cannot express an empty argv token; content's typed extras can.
            "" -> "content call --uri content://settings/secure --method PUT_secure " +
                "--arg $key --extra value:s: --extra _user:i:$userId"
            else -> "settings --user $userId put secure $key ${token(value)}"
        }
        shell(instrumentation, command)
    }

    private fun warmUpKeyboard(instrumentation: Instrumentation, expected: String, className: String) {
        val started = SystemClock.uptimeMillis()
        val deadline = started + 25_000
        var activity: Activity? = null
        var attempts = 0
        var state = "serviceExists=false; inputViewExists=false; inputViewShown=false; editorMatches=false"
        var readyAfter = 0L
        val failures = mutableListOf<Throwable>()
        try {
            val host = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, GestureEditingTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ).also { activity = it }
            lateinit var editor: EditText
            instrumentation.runOnMainSync {
                editor = EditText(host).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                }
                host.setContentView(editor)
                editor.requestFocus()
            }
            var nextShow = started
            var ready = false
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val service = runningService()
                    val inputView = service?.inputView
                    val shown = inputView?.isShown == true
                    val matches = service?.currentInputEditorInfo?.fieldId == editor.id
                    state = "serviceExists=${service != null}; inputViewExists=${inputView != null}; " +
                        "inputViewShown=$shown; editorMatches=$matches"
                    ready = shown && matches
                    if (!ready && editor.isShown && editor.hasWindowFocus() &&
                        SystemClock.uptimeMillis() >= nextShow) {
                        val imm = host.getSystemService(InputMethodManager::class.java)
                        // A cold IME can lose the first bind request. Recreate the input connection
                        // before retrying, otherwise Android may coalesce every later show request.
                        if (attempts > 0 && service == null) imm.restartInput(editor)
                        imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                        attempts++
                        nextShow = SystemClock.uptimeMillis() + 500
                    }
                }
                if (ready) break
                SystemClock.sleep(50)
            }
            readyAfter = SystemClock.uptimeMillis() - started
            if (!ready) {
                throw AssertionError("Keyboard warm-up timed out after $readyAfter ms; attempts=$attempts; " +
                    "$state; expectedIme=$expected")
            }
        } catch (failure: Throwable) {
            failures += failure
        } finally {
            activity?.let { host ->
                try {
                    instrumentation.runOnMainSync {
                        try {
                            host.getSystemService(InputMethodManager::class.java)
                                .hideSoftInputFromWindow(host.window.decorView.windowToken, 0)
                        } finally {
                            host.finish()
                        }
                    }
                } catch (failure: Throwable) {
                    failures += failure
                }
                try {
                    val cleanupDeadline = SystemClock.uptimeMillis() + 5_000
                    var destroyed = false
                    do {
                        instrumentation.runOnMainSync { destroyed = host.isDestroyed }
                        if (destroyed) break
                        SystemClock.sleep(50)
                    } while (SystemClock.uptimeMillis() < cleanupDeadline)
                    if (!destroyed) throw AssertionError("Warm-up host was not destroyed within 5 s; expectedIme=$expected")
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
        }
        MultipleFailureException.assertEmpty(failures)
        Log.i(TAG, "Warm-up ready in $readyAfter ms, attempts=$attempts; class=$className; hostDestroyed=true")
    }

    private fun runningService(): FcitxInputMethodService? {
        // Same in-process service lookup as the editor UI tests; called on the main thread.
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }
            .get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun shell(instrumentation: Instrumentation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { reader ->
            reader.readText().also { Log.i(TAG, "$command: ${it.trim()}") }
        }

    private companion object {
        const val TAG = "RequireDefaultImeRule"
        fun component(value: String?): ComponentName? = value?.let(ComponentName::unflattenFromString)
        fun token(value: String): String {
            require(value.isNotEmpty() && value.none { it.isWhitespace() }) {
                "IME setting cannot be restored as a single command argument; refusing to change it: $value"
            }
            return value
        }
    }
}
