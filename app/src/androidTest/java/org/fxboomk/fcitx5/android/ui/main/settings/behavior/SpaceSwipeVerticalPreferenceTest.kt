/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.core.view.allViews
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeIdleBehavior
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeVerticalBehavior
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceSwipeVerticalPreferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun allFiveIdleOptionsAreExclusiveAndSaveOnlyAfterConfirmation() {
        withDialog { dialog ->
            val buttons = dialog.window!!.decorView.allViews.filterIsInstance<RadioButton>()
                .filter { it.tag is SpaceSwipeIdleBehavior }.toList()
            assertEquals(5, buttons.size)
            assertTrue(radio(dialog, SpaceSwipeIdleBehavior.Default).isChecked)
            SpaceSwipeIdleBehavior.entries.forEach { behavior ->
                radio(dialog, behavior).performClick()
                assertEquals(listOf(behavior), buttons.filter { it.isChecked }.map { it.tag })
                assertEquals(SpaceSwipeIdleBehavior.Default, AppPrefs.getInstance().keyboard.spaceSwipeIdleBehavior.getValue())
            }
            radio(dialog, SpaceSwipeIdleBehavior.CursorThenSelection).performClick()
            radio(dialog, SpaceSwipeVerticalBehavior.CandidateRows).performClick()
            assertEquals(SpaceSwipeVerticalBehavior.ArrowKeys, AppPrefs.getInstance().keyboard.spaceSwipeVerticalBehavior.getValue())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
    }

    @Test
    fun cancelLeavesBothStoredSettingsUntouched() {
        withDialog(save = false) { dialog ->
            radio(dialog, SpaceSwipeIdleBehavior.SelectionOnly).performClick()
            radio(dialog, SpaceSwipeVerticalBehavior.CandidateRows).performClick()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }

    private fun withDialog(save: Boolean = true, block: (AlertDialog) -> Unit) {
        val prefs = AppPrefs.getInstance().keyboard
        val oldComposition = prefs.spaceSwipeVerticalBehavior.getValue()
        val oldIdle = prefs.spaceSwipeIdleBehavior.getValue()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var dialog: AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                prefs.spaceSwipeVerticalBehavior.setValue(SpaceSwipeVerticalBehavior.ArrowKeys)
                prefs.spaceSwipeIdleBehavior.setValue(SpaceSwipeIdleBehavior.Default)
                val created = SpaceSwipeVerticalPreference(activity).createDialog()
                dialog = created
                created.show()
                block(created)
            }
            instrumentation.waitForIdleSync()
            val expected = if (save) SpaceSwipeIdleBehavior.CursorThenSelection else SpaceSwipeIdleBehavior.Default
            assertEquals(if (save) SpaceSwipeVerticalBehavior.CandidateRows else SpaceSwipeVerticalBehavior.ArrowKeys,
                prefs.spaceSwipeVerticalBehavior.getValue())
            assertEquals(expected, prefs.spaceSwipeIdleBehavior.getValue())
            instrumentation.runOnMainSync {
                val reopened = SpaceSwipeVerticalPreference(activity).createDialog()
                dialog = reopened
                reopened.show()
                assertTrue(radio(reopened, expected).isChecked)
                assertEquals(1, reopened.window!!.decorView.allViews.filterIsInstance<RadioButton>()
                    .count { it.tag is SpaceSwipeIdleBehavior && it.isChecked })
                reopened.dismiss()
            }
        } finally {
            instrumentation.runOnMainSync {
                dialog?.dismiss()
                prefs.spaceSwipeVerticalBehavior.setValue(oldComposition)
                prefs.spaceSwipeIdleBehavior.setValue(oldIdle)
                activity.finish()
            }
        }
    }

    private fun radio(dialog: AlertDialog, behavior: Any): RadioButton =
        dialog.window!!.decorView.findViewWithTag(behavior)
}
