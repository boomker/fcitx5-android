/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.prefs

import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import java.util.concurrent.FutureTask
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateBarHeightSettingsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun switchingSchemesAndPlacementRestoresIndependentUserChoices() = withSettings { fcitx ->
        refresh(fcitx)
        assertProfile(adaptive = false, height = 72, top = false)
        fcitx.configs["height-a"] = config(top = true)
        refresh(fcitx)
        assertProfile(adaptive = true, height = 72, top = true)

        onMain {
            AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(false)
            ThemeManager.prefs.toolbarHeight.setValue(48)
            fcitx.entry = entry("height-b")
        }
        refresh(fcitx)
        assertProfile(adaptive = false, height = 72, top = false)
        onMain {
            AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(true)
            ThemeManager.prefs.toolbarHeight.setValue(60)
            fcitx.entry = entry("height-a")
        }
        refresh(fcitx)
        assertProfile(adaptive = false, height = 48, top = true)

        fcitx.configs["height-a"] = config(top = false)
        refresh(fcitx)
        assertProfile(adaptive = false, height = 72, top = false)
        fcitx.configs["height-a"] = config(top = true)
        refresh(fcitx)
        assertProfile(adaptive = false, height = 48, top = true)
        onMain { fcitx.entry = entry("height-b") }
        refresh(fcitx)
        assertProfile(adaptive = true, height = 60, top = false)
    }

    @Test
    fun changingAnnotationModeNotifiesLayoutWithoutChangingTheSavedToggle() = withSettings { fcitx ->
        refresh(fcitx)
        onMain { AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(true) }
        fcitx.configs["height-a"] = config(top = true)
        refresh(fcitx)
        val preference = AppPrefs.getInstance().keyboard.toolbarDynamicHeight
        var notifications = 0
        val listener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> notifications++ }
        onMain { preference.registerOnChangeListener(listener) }
        try {
            fcitx.configs["height-a"] = config(top = true, enabled = false)
            refresh(fcitx)
            assertProfile(adaptive = true, height = 72, top = false)
            onMain { assertEquals(1, notifications) }
            fcitx.configs["height-a"] = config(top = true)
            refresh(fcitx)
            assertProfile(adaptive = true, height = 72, top = true)
            onMain { assertEquals(2, notifications) }
        } finally {
            onMain { preference.unregisterOnChangeListener(listener) }
        }
    }

    @Test
    fun hiddenAnnotationsRestoreNormalSettingsWithoutOverwritingTheTopChoice() = withSettings { fcitx ->
        refresh(fcitx)
        fcitx.configs["height-a"] = config(top = true)
        refresh(fcitx)
        onMain { ThemeManager.prefs.toolbarHeight.setValue(48) }
        assertProfile(adaptive = true, height = 48, top = true)

        fcitx.configs["height-a"] = config(top = true, enabled = false)
        refresh(fcitx)
        assertProfile(adaptive = false, height = 72, top = false)
        onMain {
            AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(true)
            ThemeManager.prefs.toolbarHeight.setValue(60)
            fcitx.entry = entry("height-b")
        }
        refresh(fcitx)
        assertProfile(adaptive = false, height = 72, top = false)
        onMain { fcitx.entry = entry("height-a") }
        refresh(fcitx)
        assertProfile(adaptive = true, height = 60, top = false)

        fcitx.configs["height-a"] = config(top = true)
        refresh(fcitx)
        assertProfile(adaptive = true, height = 48, top = true)
        onMain { AppPrefs.getInstance().keyboard.toolbarDynamicHeight.setValue(false) }
        fcitx.configs["height-a"] = config(top = true, enabled = false)
        refresh(fcitx)
        assertProfile(adaptive = true, height = 60, top = false)
        fcitx.configs["height-a"] = config(top = true)
        refresh(fcitx)
        assertProfile(adaptive = false, height = 48, top = true)
        fcitx.configs["height-a"] = config(top = false)
        refresh(fcitx)
        assertProfile(adaptive = true, height = 60, top = false)
    }

    @Test
    fun aSuspendedOldSchemeConfigurationCannotOverwriteTheNewScheme() = withSettings { fcitx ->
        fcitx.holdConfig = true
        lateinit var request: Job
        onMain {
            request = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                CandidateBarHeightSettings.refresh(fcitx.api)
            }
            assertFalse(request.isCompleted)
            fcitx.entry = entry("height-b")
            CandidateBarHeightSettings.apply(fcitx.entry)
            requireNotNull(fcitx.pending).resume(config(top = true))
            fcitx.pending = null
        }
        runBlocking { request.join() }
        assertProfile(adaptive = false, height = 72, top = false)
    }

    private class FakeFcitx {
        var entry = entry("height-a")
        val configs = mutableMapOf("height-a" to config(top = false), "height-b" to config(top = false))
        var holdConfig = false
        var pending: Continuation<RawConfig>? = null
        val api: FcitxAPI = Proxy.newProxyInstance(
            FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getInputMethodEntryCached" -> entry
                "getImConfig" -> if (holdConfig) {
                    @Suppress("UNCHECKED_CAST")
                    pending = args!!.last() as Continuation<RawConfig>
                    COROUTINE_SUSPENDED
                } else configs.getValue(args!![0] as String)
                else -> error("Unexpected Fcitx API call: "+method.name)
            }
        } as FcitxAPI
    }

    private fun refresh(fcitx: FakeFcitx) {
        onMain { runBlocking { CandidateBarHeightSettings.refresh(fcitx.api) } }
        instrumentation.waitForIdleSync()
    }

    private fun assertProfile(adaptive: Boolean, height: Int, top: Boolean) = onMain {
        assertEquals(adaptive, AppPrefs.getInstance().keyboard.toolbarDynamicHeight.getValue())
        assertEquals(height, ThemeManager.prefs.toolbarHeight.getValue())
        assertEquals(top, CandidateBarHeightSettings.annotationOnTop)
    }

    private fun withSettings(block: (FakeFcitx) -> Unit) {
        val connection = FcitxDaemon.connect(javaClass.name)
        val originalEntry = runBlocking { connection.runOnReady { inputMethodEntryCached } }
        val adaptive = AppPrefs.getInstance().keyboard.toolbarDynamicHeight
        val height = ThemeManager.prefs.toolbarHeight
        val profiles = AppPrefs.getInstance().internal.candidateBarHeightProfiles
        val saved = onMain {
            listOf(adaptive.key, height.key, profiles.key).associateWith { adaptive.sharedPreferences.all[it] }
        }
        try {
            onMain {
                adaptive.setValue(false)
                height.setValue(72)
                profiles.setValue(Json.encodeToString(CandidateBarHeightProfiles(CandidateBarHeightProfile(false, 72))))
            }
            block(FakeFcitx())
        } finally {
            onMain {
                CandidateBarHeightSettings.apply(originalEntry)
                val editor = adaptive.sharedPreferences.edit()
                saved.filterKeys { it != profiles.key }.forEach { (key, value) ->
                    when (value) {
                        null -> editor.remove(key)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                    }
                }
                assertTrue(editor.commit())
                val profileEditor = profiles.sharedPreferences.edit()
                val profileValue = saved[profiles.key] as String?
                if (profileValue == null) profileEditor.remove(profiles.key)
                else profileEditor.putString(profiles.key, profileValue)
                assertTrue(profileEditor.commit())
            }
            FcitxDaemon.disconnect(javaClass.name)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    companion object {
        private fun entry(name: String) = InputMethodEntry(name, name, "", "", name, "zh", "pinyin", true)

        private fun config(top: Boolean, enabled: Boolean = true) = RawConfig(subItems = arrayOf(
            RawConfig("cfg", subItems = arrayOf(
                RawConfig("PinyinAnnotationPlacement", if (top) "Top" else "Right"),
                RawConfig("PinyinAnnotation", if (enabled) "Only rare characters" else "Do not show"),
            ))
        ))
    }
}

