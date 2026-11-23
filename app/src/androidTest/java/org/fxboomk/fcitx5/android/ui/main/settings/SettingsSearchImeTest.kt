/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceFragmentCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.testing.RequireDefaultImeRule
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Test
import java.io.File

/** Verify a real IME keeps composing text and candidates while settings results refresh. */
class SettingsSearchImeTest {
    companion object {
        @ClassRule
        @JvmField
        val defaultIme = RequireDefaultImeRule()
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun currentCodeRemainsComposingInSettingsSearch() =
        verifySearchComposition("Composing pinyin", PreeditStyleOption.COMPOSING)

    @Test
    fun highlightedCandidateRemainsComposingInSettingsSearch() =
        verifySearchComposition("Commit preview", PreeditStyleOption.COMMIT_PREVIEW)

    private fun verifySearchComposition(mode: String, style: String) {
        val prefs = AppPrefs.getInstance()
        val scopes = prefs.internal.preeditStyleScopes
        val compositionStyle = prefs.keyboard.compositionAreaStyle
        val notifications = prefs.internal.needNotifications
        val originalScopes = scopes.getValue().toSet()
        val originalStyle = compositionStyle.getValue()
        val originalNotifications = notifications.getValue()
        val hadScopes = scopes.sharedPreferences.contains(scopes.key)
        val hadStyle = compositionStyle.sharedPreferences.contains(compositionStyle.key)
        val hadNotifications = notifications.sharedPreferences.contains(notifications.key)
        val storage = context.createDeviceProtectedStorageContext()
        val root = storage.getExternalFilesDir(null) ?: storage.filesDir
        val files = listOf(
            File(root, "config/profile"),
            File(root, "config/conf/pinyin.conf"),
            File(root, "config/conf/pinyin/pinyin.conf")
        ).associateWith { it.takeIf(File::exists)?.readBytes() }
        var activity: MainActivity? = null
        var service: FcitxInputMethodService? = null
        try {
            onMain { notifications.setValue(false) }
            val host = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            activity = host
            lateinit var fragment: PreferenceFragmentCompat
            lateinit var editor: EditText
            onMain {
                val navHost = host.supportFragmentManager
                    .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                fragment = navHost.childFragmentManager.fragments
                    .filterIsInstance<PreferenceFragmentCompat>()
                    .first { it.findPreference<SettingsSearchPreference>(SettingsSearchPreference.KEY) != null }
                assertTrue(fragment.listView.findViewById<View>(R.id.settings_search_placeholder).performClick())
                editor = fragment.listView.findViewById<SearchView>(R.id.settings_search_view)
                    .findViewById(androidx.appcompat.R.id.search_src_text)
            }
            var nextShow = 0L
            await("The settings search did not receive the real keyboard", 25_000) {
                service = runningService()
                val ready = service?.inputView?.isShown == true &&
                    service?.currentInputEditorInfo?.fieldId == editor.id
                if (!ready && editor.hasWindowFocus() && SystemClock.uptimeMillis() >= nextShow) {
                    host.getSystemService(InputMethodManager::class.java)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
                    nextShow = SystemClock.uptimeMillis() + 500
                }
                ready
            }
            val ime = requireNotNull(service)
            val (originalEnabled, originalIme, originalConfig) = runFcitxJob(ime) {
                Triple(enabledIme().map { it.uniqueName }, currentIme().uniqueName,
                    getImConfig("pinyin")["cfg"].deepCopy())
            }
            val originalFlags = CapabilityFlags.fromEditorInfo(requireNotNull(ime.currentInputEditorInfo))
            try {
                onMain { PreeditStyleScopes.write("im:pinyin", style) }
                runFcitxJob(ime) {
                    setEnabledIme((listOf("keyboard-us") + originalEnabled + "pinyin").distinct().toTypedArray())
                    activateIme("pinyin")
                    assertEquals("pinyin", currentIme().uniqueName)
                    setImConfig("pinyin", originalConfig.deepCopy().apply {
                        getOrCreate("PreeditMode").value = mode
                    })
                    // Keep this input from being learned in the device's user dictionary.
                    setCapFlags(CapabilityFlags(originalFlags.flags or CapabilityFlag.Sensitive.flag))
                    PreeditStyleResolver.applyActive(this)
                    reset()
                }
                var code = ""
                for (key in "nihao") {
                    code += key
                    val preview = runFcitxJob(ime) {
                        // Soft keyboard letter actions send a virtual key press only.
                        sendKey(key.toString(), states = KeyStates.Virtual.states)
                        clientPreeditCached.toString()
                    }
                    try {
                        assertTrue("$mode lost native preedit after $code", preview.isNotEmpty())
                        await("$mode did not deliver composing text after $code") {
                            editor.text.toString() == preview &&
                                BaseInputConnection.getComposingSpanStart(editor.text) >= 0 &&
                                ime.hasVisibleNativeCandidates()
                        }
                    } catch (failure: AssertionError) {
                        val nativeState = runCatching {
                            runFcitxJob(ime) {
                                "ime=${currentIme().uniqueName}, nativeClient='$clientPreeditCached', " +
                                    "nativePanel='${inputPanelCached.preedit}', " +
                                    "nativeCandidates=${getCandidates(0, 16).size}, " +
                                    "actualPreeditMode='${getImConfig("pinyin")["cfg"]["PreeditMode"].value}'"
                            }
                        }.getOrElse { "native diagnostics failed: $it" }
                        var editorState = ""
                        onMain {
                            val liveEditor = fragment.listView
                                .findViewById<SearchView>(R.id.settings_search_view)
                                ?.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
                            val composingStart = BaseInputConnection.getComposingSpanStart(editor.text)
                            val composingEnd = BaseInputConnection.getComposingSpanEnd(editor.text)
                            editorState = "editor='${editor.text}', composing=$composingStart..$composingEnd, " +
                                "matchesPreview=${editor.text.toString() == preview}, " +
                                "hasFocus=${editor.hasFocus()}, attached=${editor.isAttachedToWindow}, " +
                                "visibleNativeCandidates=${ime.hasVisibleNativeCandidates()}, " +
                                "editorFieldId=${editor.id}, currentFieldId=${ime.currentInputEditorInfo?.fieldId}, " +
                                "currentPackage=${ime.currentInputEditorInfo?.packageName}, " +
                                "inputViewShown=${ime.inputView?.isShown}, " +
                                "sameLiveEditor=${liveEditor === editor}, liveEditor='${liveEditor?.text}'"
                        }
                        val diagnostics = "$mode after $code, originalPreview='$preview'; $editorState; $nativeState"
                        Log.e("SettingsSearchImeTest", diagnostics, failure)
                        throw AssertionError(diagnostics, failure)
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(300)
                    onMain {
                        assertEquals("$mode rewrote the preview after $code", preview, editor.text.toString())
                        assertTrue("$mode finished composing after $code",
                            BaseInputConnection.getComposingSpanStart(editor.text) >= 0)
                        assertTrue("$mode moved focus away after $code", editor.hasFocus())
                        assertTrue("$mode removed the candidate bar after $code", ime.hasVisibleNativeCandidates())
                    }
                    val candidates = runFcitxJob(ime) {
                        assertTrue("$mode cleared native preedit after $code", clientPreeditCached.isNotEmpty())
                        getCandidates(0, 16)
                    }
                    assertTrue("$mode cleared native candidates after $code", candidates.isNotEmpty())
                    Log.i("SettingsSearchImeTest", "$mode code=$code preview=$preview candidates=${candidates.size}")
                }
                val expected = runFcitxJob(ime) { getCandidates(0, 16).first().text }
                assertTrue(runFcitxJob(ime) { select(0) })
                await("$mode could not explicitly commit the first candidate") {
                    editor.text.toString() == expected &&
                        BaseInputConnection.getComposingSpanStart(editor.text) == -1
                }
                assertTrue(runFcitxJob(ime) { clientPreeditCached.isEmpty() })
            } finally {
                runFcitxJob(ime) {
                    try {
                        reset()
                        setImConfig("pinyin", originalConfig)
                        setEnabledIme(originalEnabled.toTypedArray())
                        if (originalIme in originalEnabled) activateIme(originalIme)
                    } finally {
                        setCapFlags(originalFlags)
                    }
                }
            }
        } finally {
            try {
                activity?.let { host -> onMain { host.finish() } }
                instrumentation.waitForIdleSync()
            } finally {
                try {
                    files.forEach { (file, bytes) ->
                        if (bytes != null) file.writeBytes(bytes)
                        else if (file.exists()) assertTrue("Could not restore absent config $file", file.delete())
                    }
                    service?.let { runFcitxJob(it) { reloadConfig() } }
                } finally {
                    onMain {
                        if (hadScopes) scopes.setValue(originalScopes)
                        else scopes.sharedPreferences.edit().remove(scopes.key).apply()
                        if (hadStyle) compositionStyle.setValue(originalStyle)
                        else compositionStyle.sharedPreferences.edit().remove(compositionStyle.key).apply()
                        prefs.keyboard.fireChange(compositionStyle.key)
                        if (hadNotifications) notifications.setValue(originalNotifications)
                        else notifications.sharedPreferences.edit().remove(notifications.key).apply()
                    }
                }
            }
        }
    }

    private fun RawConfig.deepCopy(): RawConfig =
        copy(subItems = subItems?.map { it.deepCopy() }?.toTypedArray())

    private fun <T> runFcitxJob(service: FcitxInputMethodService, block: suspend FcitxAPI.() -> T): T {
        val result = CompletableDeferred<T>()
        onMain {
            service.postFcitxJob {
                try { result.complete(block()) }
                catch (error: Throwable) { result.completeExceptionally(error) }
            }
        }
        return runBlocking { withTimeout(10_000) { result.await() } }
    }

    private fun runningService(): FcitxInputMethodService? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }
            .get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun await(message: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        var ready = false
        onMain { ready = condition() }
        assertTrue(message, ready)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
