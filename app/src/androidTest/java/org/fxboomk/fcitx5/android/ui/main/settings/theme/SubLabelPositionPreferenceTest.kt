/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.theme

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.ListPreference
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.UppercasePosition
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.modified.EntryEnabledListPreference
import org.fxboomk.fcitx5.android.ui.main.modified.MyPreferenceFragment
import org.fxboomk.fcitx5.android.ui.main.modified.restore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.FutureTask

class SubLabelPositionPreferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun bothPositionListsRetainEveryOptionAndDisableTheOccupiedEdge() = withHost { host ->
        for (punctuation in listOf(PunctuationPosition.Bottom, PunctuationPosition.Top,
            PunctuationPosition.TopRight)) {
            onMain { setPositions(host, punctuation, UppercasePosition.None) }
            val dialog = open(host, UPPERCASE_KEY)
            onMain {
                val preference = host.preference(UPPERCASE_KEY)
                assertTrue(preference is EntryEnabledListPreference)
                assertEquals(UppercasePosition.entries.map { it.name },
                    preference.entryValues.map { it.toString() })
                assertEnabled(dialog, preference, UppercasePosition.None.name, true)
                assertEnabled(dialog, preference, UppercasePosition.Top.name,
                    punctuation == PunctuationPosition.Bottom)
                assertEnabled(dialog, preference, UppercasePosition.Bottom.name,
                    punctuation != PunctuationPosition.Bottom)
            }
            dismiss(host)
        }

        for (uppercase in listOf(UppercasePosition.Top, UppercasePosition.Bottom)) {
            onMain { setPositions(host, PunctuationPosition.None, uppercase) }
            val dialog = open(host, PUNCTUATION_KEY)
            onMain {
                val preference = host.preference(PUNCTUATION_KEY)
                assertTrue(preference is EntryEnabledListPreference)
                assertEquals(PunctuationPosition.entries.map { it.name },
                    preference.entryValues.map { it.toString() })
                assertEnabled(dialog, preference, PunctuationPosition.None.name, true)
                assertEnabled(dialog, preference, PunctuationPosition.Bottom.name,
                    uppercase != UppercasePosition.Bottom)
                assertEnabled(dialog, preference, PunctuationPosition.Top.name,
                    uppercase != UppercasePosition.Top)
                assertEnabled(dialog, preference, PunctuationPosition.TopRight.name,
                    uppercase != UppercasePosition.Top)
            }
            dismiss(host)
        }
    }

    @Test
    fun aDisabledChoiceCannotSaveOrDismissTheDialog() = withHost { host ->
        onMain { setPositions(host, PunctuationPosition.Bottom, UppercasePosition.None) }
        val dialog = open(host, UPPERCASE_KEY)
        onMain {
            val preference = host.preference(UPPERCASE_KEY)
            click(dialog, preference, UppercasePosition.Bottom.name)
            assertTrue(dialog.isShowing)
            assertEquals(UppercasePosition.None, ThemeManager.prefs.uppercasePosition.getValue())
            assertEquals(UppercasePosition.None.name, preference.value)
            assertFalse(preference.callChangeListener(UppercasePosition.Bottom.name))

            click(dialog, preference, UppercasePosition.Top.name)
            assertEquals(UppercasePosition.Top, ThemeManager.prefs.uppercasePosition.getValue())
            assertEquals(UppercasePosition.Top.name, preference.value)
        }
        instrumentation.waitForIdleSync()
        assertFalse(onMain { dialog.isShowing })
    }

    @Test
    fun reopeningTheSamePreferenceRefreshesRestrictionsAndNoneReleasesBothEdges() = withHost { host ->
        onMain { setPositions(host, PunctuationPosition.Bottom, UppercasePosition.None) }
        val preference = onMain { host.preference(UPPERCASE_KEY) }
        var dialog = open(host, UPPERCASE_KEY)
        onMain { assertEnabled(dialog, preference, UppercasePosition.Bottom.name, false) }
        dismiss(host)

        onMain { setPositions(host, PunctuationPosition.TopRight, UppercasePosition.None) }
        dialog = open(host, UPPERCASE_KEY)
        onMain {
            assertTrue(preference === host.preference(UPPERCASE_KEY))
            assertEnabled(dialog, preference, UppercasePosition.Bottom.name, true)
            assertEnabled(dialog, preference, UppercasePosition.Top.name, false)
        }
        dismiss(host)

        onMain { setPositions(host, PunctuationPosition.None, UppercasePosition.None) }
        dialog = open(host, UPPERCASE_KEY)
        onMain {
            UppercasePosition.entries.forEach { assertEnabled(dialog, preference, it.name, true) }
            click(dialog, preference, UppercasePosition.Bottom.name)
            assertEquals(UppercasePosition.Bottom, ThemeManager.prefs.uppercasePosition.getValue())
        }
        dismiss(host)

        dialog = open(host, PUNCTUATION_KEY)
        onMain {
            assertEnabled(dialog, host.preference(PUNCTUATION_KEY), PunctuationPosition.Bottom.name, false)
        }
        dismiss(host)

        onMain { setPositions(host, PunctuationPosition.None, UppercasePosition.None) }
        dialog = open(host, PUNCTUATION_KEY)
        onMain {
            PunctuationPosition.entries.forEach {
                assertEnabled(dialog, host.preference(PUNCTUATION_KEY), it.name, true)
            }
        }
    }

    @Test
    fun restoringTheDefaultCannotBypassAnOccupiedPosition() = withHost { host ->
        onMain { setPositions(host, PunctuationPosition.None, UppercasePosition.Bottom) }
        var dialog = open(host, PUNCTUATION_KEY)
        onMain {
            val preference = host.preference(PUNCTUATION_KEY)
            assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            preference.restore()
            assertEquals(PunctuationPosition.None, ThemeManager.prefs.punctuationPosition.getValue())
            assertEquals(PunctuationPosition.None.name, preference.value)
        }
        dismiss(host)

        onMain { setPositions(host, PunctuationPosition.None, UppercasePosition.None) }
        dialog = open(host, PUNCTUATION_KEY)
        onMain {
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals(PunctuationPosition.Bottom, ThemeManager.prefs.punctuationPosition.getValue())
        }
    }

    @Test
    fun ordinaryListsStillSaveTheirSelectionAndRestoreTheirDefault() = withHost { host ->
        val preference = onMain { host.preference(PLAIN_LIST_KEY) }
        assertFalse(preference is EntryEnabledListPreference)
        var dialog = open(host, PLAIN_LIST_KEY)
        onMain {
            assertEnabled(dialog, preference, "first", true)
            assertEnabled(dialog, preference, "second", true)
            click(dialog, preference, "second")
        }
        instrumentation.waitForIdleSync()
        onMain { assertEquals("second", preference.value) }
        dismiss(host)

        dialog = open(host, PLAIN_LIST_KEY)
        onMain {
            assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertEquals("first", preference.value)
        }
    }

    @Test
    fun positionDialogsMatchOrdinaryListTypographyAndDimensions() = withHost { host ->
        for (key in listOf(PUNCTUATION_KEY, UPPERCASE_KEY)) {
            onMain {
                val position = host.preference(key)
                host.preference(PLAIN_LIST_KEY).apply {
                    title = position.title
                    dialogTitle = position.dialogTitle
                    entries = position.entries
                    entryValues = position.entryValues
                    value = position.value
                }
            }
            val ordinary = open(host, PLAIN_LIST_KEY)
            val expected = onMain { dialogMetrics(ordinary) }
            dismiss(host)
            val position = open(host, key)
            onMain { assertEquals("Different list styling for $key", expected, dialogMetrics(position)) }
            dismiss(host)
        }
    }

    private data class DialogMetrics(
        val textSize: Float,
        val typeface: Typeface?,
        val rowHeight: Int,
        val paddingStart: Int,
        val paddingEnd: Int,
        val drawablePadding: Int,
        val dialogWidth: Int,
        val dialogHeight: Int,
    )

    private fun dialogMetrics(dialog: AlertDialog): DialogMetrics {
        val row = requireNotNull(dialog.listView.getChildAt(0))
        val text = requireNotNull(row.findViewById<TextView>(android.R.id.text1))
        val decor = requireNotNull(dialog.window).decorView
        return DialogMetrics(text.textSize, text.typeface, row.height,
            text.paddingStart, text.paddingEnd, text.compoundDrawablePadding, decor.width, decor.height)
    }

    private fun setPositions(
        host: PreferenceHostFragment,
        punctuation: PunctuationPosition,
        uppercase: UppercasePosition,
    ) {
        ThemeManager.prefs.punctuationPosition.setValue(punctuation)
        ThemeManager.prefs.uppercasePosition.setValue(uppercase)
        // Programmatic preference changes do not update ListPreference's cached value.
        host.preference(PUNCTUATION_KEY).value = punctuation.name
        host.preference(UPPERCASE_KEY).value = uppercase.name
    }

    private fun assertEnabled(
        dialog: AlertDialog,
        preference: ListPreference,
        value: String,
        enabled: Boolean,
    ) {
        val index = preference.findIndexOfValue(value)
        assertTrue("Missing option $value", index >= 0)
        val list = dialog.listView
        assertEquals("Wrong enabled state for ${preference.key}:$value", enabled,
            list.adapter.isEnabled(index))
        val row = list.adapter.getView(index, null, list)
        assertEquals("Option row is not greyed out for ${preference.key}:$value", enabled, row.isEnabled)
    }

    private fun click(dialog: AlertDialog, preference: ListPreference, value: String) {
        val index = preference.findIndexOfValue(value)
        assertTrue("Missing option $value", index >= 0)
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(index, null, list), index,
            list.adapter.getItemId(index))
    }

    private fun open(host: PreferenceHostFragment, key: String): AlertDialog {
        val dialog = onMain {
            host.onDisplayPreferenceDialog(host.preference(key))
            host.parentFragmentManager.executePendingTransactions()
            (host.parentFragmentManager.findFragmentByTag(host.javaClass.name) as DialogFragment)
                .requireDialog() as AlertDialog
        }
        instrumentation.waitForIdleSync()
        return dialog
    }

    private fun dismiss(host: PreferenceHostFragment) {
        onMain {
            (host.parentFragmentManager.findFragmentByTag(host.javaClass.name) as? DialogFragment)
                ?.dismissNow()
            host.parentFragmentManager.executePendingTransactions()
        }
        instrumentation.waitForIdleSync()
    }

    private fun withHost(block: (PreferenceHostFragment) -> Unit) {
        val prefs = ThemeManager.prefs
        val oldPunctuation = prefs.punctuationPosition.getValue()
        val oldUppercase = prefs.uppercasePosition.getValue()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val host = PreferenceHostFragment()
        try {
            onMain {
                prefs.punctuationPosition.setValue(PunctuationPosition.Bottom)
                prefs.uppercasePosition.setValue(UppercasePosition.None)
                activity.supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, host, "sub_label_position_test_host")
                    .commitNow()
            }
            instrumentation.waitForIdleSync()
            block(host)
        } finally {
            if (host.isAdded) dismiss(host)
            onMain {
                if (host.isAdded) activity.supportFragmentManager.beginTransaction().remove(host).commitNow()
                prefs.punctuationPosition.setValue(oldPunctuation)
                prefs.uppercasePosition.setValue(oldUppercase)
                activity.finish()
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    class PreferenceHostFragment : MyPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
                for (key in listOf(PUNCTUATION_KEY, UPPERCASE_KEY)) {
                    addPreference(ThemeManager.prefs.managedPreferencesUi.single { it.key == key }
                        .createUi(preferenceManager.context))
                }
                addPreference(ListPreference(preferenceManager.context).apply {
                    key = PLAIN_LIST_KEY
                    title = "Ordinary list"
                    entries = arrayOf("First", "Second")
                    entryValues = arrayOf("first", "second")
                    isPersistent = false
                    setDefaultValue("first")
                })
            }
        }

        fun preference(key: String): ListPreference = requireNotNull(findPreference(key))
    }

    companion object {
        private const val PUNCTUATION_KEY = "punctuation_position"
        private const val UPPERCASE_KEY = "uppercase_position"
        private const val PLAIN_LIST_KEY = "sub_label_position_test_plain_list"
    }
}
