/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.dialog

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.voice.js.JsVoicePluginStore
import org.fxboomk.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fxboomk.fcitx5.android.ui.main.settings.voice.JsVoicePluginSettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPickerDialogTest {

    @Test
    fun doubaoSettingsButtonOpensTheSelectedPluginConfiguration() {
        assertPluginConfiguration(JsVoiceProviders.DOUBAO_ASR_ID)
    }

    @Test
    fun settingsButtonOpensEachInstalledJsVoicePluginConfiguration() {
        // Users can uninstall bundled plugins; only installed providers can be selected.
        val installed = JsVoiceProviders.voiceInputSpecs()
        assertTrue("No installed JS voice plugins", installed.isNotEmpty())
        installed.forEach { assertPluginConfiguration(it.id) }
    }

    @Test
    fun settingsButtonDoesNothingForDefaultSystemOrExternalVoiceEngines() {
        for (providerId in listOf("", "org.example.voice/.VoiceIme", "plugin:org.example.voice/.VoiceInputProviderService")) {
            assertTrue("Unexpected activity for $providerId", clickSettings(providerId).isEmpty())
        }
    }

    @Test
    fun settingsButtonDoesNothingForAnUnavailableJsPlugin() {
        val unavailableIds = setOf("js:missing-plugin") +
            JsVoicePluginStore.disabledBuiltInIds().map { "js:$it" }
        unavailableIds.forEach { providerId ->
            assertTrue("Unexpected activity for unavailable $providerId", clickSettings(providerId).isEmpty())
        }
    }

    private fun assertPluginConfiguration(pluginId: String) {
        val started = clickSettings(pluginId)
        assertEquals("Expected one configuration activity for $pluginId", 1, started.size)
        val intent = started.single()
        assertEquals(JsVoicePluginSettingsActivity::class.java.name, intent.component?.className)
        assertEquals(pluginId, intent.getStringExtra(JsVoicePluginSettingsActivity.EXTRA_PLUGIN_ID))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private fun clickSettings(providerId: String): List<Intent> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = RecordingContext(instrumentation.targetContext)
        val preferredVoiceInput = AppPrefs.getInstance().keyboard.preferredVoiceInput
        val original = preferredVoiceInput.getValue()
        var dialog: AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                preferredVoiceInput.setValue(providerId)
                dialog = VoiceInputPickerDialog.build(context) {}.also {
                    // Create the real button and its listener without attaching an IME window.
                    it.create()
                    assertTrue(it.getButton(AlertDialog.BUTTON_NEUTRAL).performClick())
                }
            }
            // AlertDialog dispatches button listeners through its main-thread handler.
            instrumentation.waitForIdleSync()
            return context.startedActivities.toList()
        } finally {
            instrumentation.runOnMainSync {
                dialog?.dismiss()
                preferredVoiceInput.setValue(original)
            }
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val startedActivities = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            startedActivities += intent
        }
    }
}
