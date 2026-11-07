/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.plugin.js

import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsPluginPreferenceDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun listDefaultSavesThroughTheChangeListenerAndUpdatesSelection() = withPreferences { fragment ->
        var changes = 0
        var saved = "polish"
        lateinit var preference: ListPreference
        instrumentation.runOnMainSync {
            preference = ListPreference(fragment.requireContext()).apply {
                key = "purpose"
                title = "Purpose"
                isPersistent = false
                entries = arrayOf("Short", "Polish")
                entryValues = arrayOf("short", "polish")
                value = saved
                setOnPreferenceChangeListener { target, value ->
                    changes++
                    saved = value as String
                    target.summary = saved
                    true
                }
            }
            fragment.preferenceScreen.addPreference(preference)
            val dialog = JsPluginListPreferenceDialogFragment.newInstance(preference.key, "short")
            @Suppress("DEPRECATION")
            dialog.setTargetFragment(fragment, 0)
            dialog.showNow(fragment.parentFragmentManager, "test_dialog")
            (dialog.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(1, changes)
            assertEquals("short", saved)
            assertEquals("short", preference.value)
            assertEquals("short", preference.summary)
            assertEquals(0, preference.findIndexOfValue(preference.value))
            val reopened = JsPluginListPreferenceDialogFragment.newInstance(preference.key, "short")
            @Suppress("DEPRECATION")
            reopened.setTargetFragment(fragment, 0)
            reopened.showNow(fragment.parentFragmentManager, "reopened_dialog")
            assertEquals(0, (reopened.requireDialog() as AlertDialog).listView.checkedItemPosition)
            reopened.dismissNow()
        }
    }

    @Test
    fun textDefaultsSaveDeclaredValuesIncludingEmptyStrings() = withPreferences { fragment ->
        listOf("https://api.deepseek.com", "4096", "").forEachIndexed { index, reset ->
            var saved = "custom"
            lateinit var preference: EditTextPreference
            instrumentation.runOnMainSync {
                preference = EditTextPreference(fragment.requireContext()).apply {
                    key = "text_$index"
                    title = "Text"
                    isPersistent = false
                    text = saved
                    setOnPreferenceChangeListener { target, value ->
                        saved = value as String
                        target.summary = saved
                        true
                    }
                }
                fragment.preferenceScreen.addPreference(preference)
                val dialog = JsPluginEditTextPreferenceDialogFragment.newInstance(preference.key, reset)
                @Suppress("DEPRECATION")
                dialog.setTargetFragment(fragment, 0)
                dialog.showNow(fragment.parentFragmentManager, "test_dialog_$index")
                (dialog.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(reset, saved)
                assertEquals(reset, preference.text)
                assertEquals(reset, preference.summary)
            }
        }
    }

    @Test
    fun textDialogWithoutAResetValueHasNoDefaultButton() = withPreferences { fragment ->
        instrumentation.runOnMainSync {
            val preference = EditTextPreference(fragment.requireContext()).apply {
                key = "model"
                title = "Model"
                isPersistent = false
                text = "chosen-model"
            }
            fragment.preferenceScreen.addPreference(preference)
            val dialog = JsPluginEditTextPreferenceDialogFragment.newInstance(preference.key, null)
            @Suppress("DEPRECATION")
            dialog.setTargetFragment(fragment, 0)
            dialog.showNow(fragment.parentFragmentManager, "test_dialog")
            val button = (dialog.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL)
            assertFalse(button?.visibility == View.VISIBLE)
            dialog.dismissNow()
            assertEquals("chosen-model", preference.text)
        }
    }

    @Test
    fun unchangedMaskedSecretPreservesTheExistingCredential() = checkSecretEdit(null)

    @Test
    fun editingAMaskedSecretReplacesTheCredential() = checkSecretEdit("replacement-test-key")

    @Test
    fun deletingAMaskedSecretClearsTheCredential() = checkSecretEdit("")

    @Test
    fun appendingToTheMaskSavesOnlyTheNewCredential() = checkSecretEdit("replacement-test-key") { editor ->
        editor.setSelection(editor.text.length)
        editor.append("replacement-test-key")
    }

    @Test
    fun deletingPartOfTheMaskClearsTheCredential() = checkSecretEdit("") { editor ->
        editor.text.delete(0, 1)
    }

    private fun checkSecretEdit(replacement: String?, edit: ((EditText) -> Unit)? = null) = withPreferences { fragment ->
        var savedSecret: String? = "original-test-key"
        val updates = mutableListOf<String>()
        lateinit var preference: JsPluginSecretPreference
        instrumentation.runOnMainSync {
            preference = JsPluginSecretPreference(fragment.requireContext()) { savedSecret != null }.apply {
                key = "secret"
                title = "API key"
                isPersistent = false
                text = ""
                setOnPreferenceChangeListener { _, value ->
                    val entered = value as String
                    updates += entered
                    savedSecret = entered.ifBlank { null }
                    false
                }
            }
            fragment.preferenceScreen.addPreference(preference)
            val dialog = JsPluginEditTextPreferenceDialogFragment.newInstance(preference.key, null)
            @Suppress("DEPRECATION")
            dialog.setTargetFragment(fragment, 0)
            dialog.showNow(fragment.parentFragmentManager, "test_secret_dialog")
            val alert = dialog.requireDialog() as AlertDialog
            val editor = alert.findViewById<EditText>(android.R.id.edit)!!
            assertEquals("••••••••", editor.text.toString())
            assertTrue(editor.transformationMethod is PasswordTransformationMethod)
            assertEquals(0, editor.selectionStart)
            assertEquals(8, editor.selectionEnd)
            assertFalse(alert.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility == View.VISIBLE)
            if (edit != null) edit(editor) else if (replacement != null) editor.setText(replacement)
            if (replacement != null) assertEquals(replacement, editor.text.toString())
            alert.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(if (replacement == null) emptyList<String>() else listOf(replacement), updates)
            assertEquals(if (replacement == null) "original-test-key" else replacement.ifBlank { null }, savedSecret)
            assertEquals("", preference.text)
        }
    }

    private fun withPreferences(block: (TestPreferencesFragment) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            val fragment = TestPreferencesFragment()
            instrumentation.runOnMainSync {
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, fragment, "test_preferences").commitNow()
            }
            block(fragment)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    class TestPreferencesFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        }
    }
}
