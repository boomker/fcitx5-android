/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.common.preference

import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask

class PreferenceResetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun editTextResetConsultsTheListenerBeforeChangingTheValue() = onMain {
        for (accepted in listOf(true, false)) {
            val preference = EditTextPreference(themedContext()).apply {
                isPersistent = false
                setDefaultValue(DEFAULT_TEXT)
                text = CHANGED_TEXT
            }
            val valuesSeenByListener = mutableListOf<String?>()
            preference.setOnPreferenceChangeListener { _, value ->
                assertEquals(DEFAULT_TEXT, value)
                valuesSeenByListener.add(preference.text)
                accepted
            }

            preference.resetToDefault()

            assertEquals(listOf(CHANGED_TEXT), valuesSeenByListener)
            assertEquals(if (accepted) DEFAULT_TEXT else CHANGED_TEXT, preference.text)
        }
    }

    @Test
    fun switchResetConsultsTheListenerBeforeChangingEitherBooleanValue() = onMain {
        for (defaultValue in listOf(true, false)) {
            for (accepted in listOf(true, false)) {
                val preference = ResettableSwitchPreference(themedContext()).apply {
                    isPersistent = false
                    setDefaultValue(defaultValue)
                    isChecked = !defaultValue
                }
                val valuesSeenByListener = mutableListOf<Boolean>()
                preference.setOnPreferenceChangeListener { _, value ->
                    assertEquals(defaultValue, value)
                    valuesSeenByListener.add(preference.isChecked)
                    accepted
                }

                preference.resetToDefault()

                assertEquals(listOf(!defaultValue), valuesSeenByListener)
                assertEquals(if (accepted) defaultValue else !defaultValue, preference.isChecked)
            }
        }
    }

    @Test
    fun preferencesWithoutDefaultsKeepTheirValuesAndDoNotNotifyListeners() = onMain {
        val text = EditTextPreference(themedContext()).apply {
            isPersistent = false
            this.text = CHANGED_TEXT
            setOnPreferenceChangeListener { _, _ -> error("No default to restore") }
        }
        val switch = ResettableSwitchPreference(themedContext()).apply {
            isPersistent = false
            isChecked = true
            setOnPreferenceChangeListener { _, _ -> error("No default to restore") }
        }

        text.resetToDefault()
        switch.resetToDefault()

        assertEquals(CHANGED_TEXT, text.text)
        assertTrue(switch.isChecked)
    }

    @Test
    fun editTextDialogResetsTheValueAndCannotBeOpenedTwice() = withHost { host ->
        onMain { assertEquals(CHANGED_TEXT, host.preference().text) }
        val fragment = open(host)
        val dialog = onMain { fragment.requireDialog() as AlertDialog }
        onMain {
            host.onDisplayPreferenceDialog(host.preference())
            host.parentFragmentManager.executePendingTransactions()
            assertSame(fragment, host.parentFragmentManager.findFragmentByTag(host.javaClass.name))
            assertEquals(1, host.parentFragmentManager.fragments.count { it is DialogFragment })
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(DEFAULT_TEXT, host.preference().text)
            assertFalse(dialog.isShowing)
        }
    }

    @Test
    fun recreatedEditTextDialogStillResetsItsTargetPreference() = withHost { host ->
        onMain { assertEquals(CHANGED_TEXT, host.preference().text) }
        val original = open(host)
        val recreated = onMain {
            val manager = host.parentFragmentManager
            val state = requireNotNull(manager.saveFragmentInstanceState(original))
            val arguments = Bundle(original.requireArguments())
            original.dismissNow()
            (manager.fragmentFactory.instantiate(
                requireNotNull(original.javaClass.classLoader), original.javaClass.name
            ) as DialogFragment).apply {
                this.arguments = arguments
                setInitialSavedState(state)
                @Suppress("DEPRECATION")
                setTargetFragment(host, 0)
                showNow(manager, host.javaClass.name)
            }
        }
        instrumentation.waitForIdleSync()
        onMain {
            val dialog = recreated.requireDialog() as AlertDialog
            assertEquals(CHANGED_TEXT, host.preference().text)
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals(DEFAULT_TEXT, host.preference().text) }
    }

    private fun themedContext() = ContextThemeWrapper(
        instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat
    )

    private fun open(host: PreferenceHostFragment): DialogFragment {
        val fragment = onMain {
            host.onDisplayPreferenceDialog(host.preference())
            host.parentFragmentManager.executePendingTransactions()
            host.parentFragmentManager.findFragmentByTag(host.javaClass.name) as DialogFragment
        }
        instrumentation.waitForIdleSync()
        return fragment
    }

    private fun withHost(block: (PreferenceHostFragment) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val host = PreferenceHostFragment()
        try {
            onMain {
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, host, "preference_reset_test_host")
                    .commitNow()
            }
            instrumentation.waitForIdleSync()
            block(host)
        } finally {
            onMain {
                (activity.supportFragmentManager.findFragmentByTag(host.javaClass.name)
                    as? DialogFragment)?.dismissNow()
                if (host.isAdded) {
                    activity.supportFragmentManager.beginTransaction().remove(host).commitNow()
                }
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    class PreferenceHostFragment : ResettablePreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                addPreference(EditTextPreference(preferenceManager.context).apply {
                    key = TEXT_KEY
                    title = "Resettable text"
                    isPersistent = false
                    setDefaultValue(DEFAULT_TEXT)
                })
            }
            // Attaching the preference applies its default, so set the test value afterwards.
            preference().text = CHANGED_TEXT
        }

        fun preference(): EditTextPreference = requireNotNull(findPreference(TEXT_KEY))
    }

    companion object {
        private const val TEXT_KEY = "preference_reset_test_text"
        private const val DEFAULT_TEXT = "Default text"
        private const val CHANGED_TEXT = "Changed text"
    }
}
