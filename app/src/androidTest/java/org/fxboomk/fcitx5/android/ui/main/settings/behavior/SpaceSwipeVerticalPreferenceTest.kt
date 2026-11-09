/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.widget.CheckBox
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeIdleBehavior
import org.fxboomk.fcitx5.android.input.keyboard.SpaceSwipeVerticalBehavior
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceSwipeVerticalPreferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun idleGesturesCombineAndDefaultClearsThemWithoutChangingCompositionUntilSaved() {
        withDialog { dialog ->
            val up = checkbox(dialog, SpaceSwipeIdleBehavior.UpSelect)
            val down = checkbox(dialog, SpaceSwipeIdleBehavior.DownCursor)
            val default = checkbox(dialog, SpaceSwipeIdleBehavior.Default)
            assertTrue(default.isChecked)
            up.performClick()
            down.performClick()
            assertTrue(up.isChecked)
            assertTrue(down.isChecked)
            assertFalse(default.isChecked)
            default.performClick()
            assertTrue(default.isChecked)
            assertFalse(up.isChecked)
            assertFalse(down.isChecked)
            up.performClick()
            down.performClick()
            radio(dialog, SpaceSwipeVerticalBehavior.CandidateRows).performClick()
            assertFalse(radio(dialog, SpaceSwipeVerticalBehavior.ArrowKeys).isChecked)
            assertTrue(radio(dialog, SpaceSwipeVerticalBehavior.CandidateRows).isChecked)
            assertEquals(SpaceSwipeVerticalBehavior.ArrowKeys, AppPrefs.getInstance().keyboard.spaceSwipeVerticalBehavior.getValue())
            assertEquals(emptySet<String>(), AppPrefs.getInstance().keyboard.spaceSwipeIdleBehavior.getValue())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
    }

    @Test
    fun cancelLeavesBothStoredSettingsUntouched() {
        withDialog(save = false) { dialog ->
            checkbox(dialog, SpaceSwipeIdleBehavior.UpSelect).performClick()
            radio(dialog, SpaceSwipeVerticalBehavior.CandidateRows).performClick()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
    }

    private fun withDialog(save: Boolean = true, block: (AlertDialog) -> Unit) {
        val prefs = AppPrefs.getInstance().keyboard
        val oldComposition = prefs.spaceSwipeVerticalBehavior.getValue()
        val oldIdle = prefs.spaceSwipeIdleBehavior.getValue().toSet()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var dialog: AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                prefs.spaceSwipeVerticalBehavior.setValue(SpaceSwipeVerticalBehavior.ArrowKeys)
                prefs.spaceSwipeIdleBehavior.setValue(emptySet())
                val created = SpaceSwipeVerticalPreference(activity).createDialog()
                dialog = created
                created.show()
                block(created)
            }
            instrumentation.waitForIdleSync()
            assertEquals(
                if (save) SpaceSwipeVerticalBehavior.CandidateRows else SpaceSwipeVerticalBehavior.ArrowKeys,
                prefs.spaceSwipeVerticalBehavior.getValue()
            )
            assertEquals(
                if (save) setOf("UpSelect", "DownCursor") else emptySet(),
                prefs.spaceSwipeIdleBehavior.getValue()
            )
            instrumentation.runOnMainSync {
                if (save) {
                    val reopened = SpaceSwipeVerticalPreference(activity).createDialog()
                    dialog = reopened
                    reopened.show()
                    assertTrue(checkbox(reopened, SpaceSwipeIdleBehavior.UpSelect).isChecked)
                    assertTrue(checkbox(reopened, SpaceSwipeIdleBehavior.DownCursor).isChecked)
                    assertFalse(checkbox(reopened, SpaceSwipeIdleBehavior.Default).isChecked)
                    assertTrue(radio(reopened, SpaceSwipeVerticalBehavior.CandidateRows).isChecked)
                    reopened.dismiss()
                }
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

    private fun checkbox(dialog: AlertDialog, behavior: SpaceSwipeIdleBehavior): CheckBox =
        dialog.window!!.decorView.findViewWithTag(behavior)

    private fun radio(dialog: AlertDialog, behavior: SpaceSwipeVerticalBehavior): RadioButton =
        dialog.window!!.decorView.findViewWithTag(behavior)
}
