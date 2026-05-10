/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroEditorActivityTest {

    @Test
    fun findCurrentStepPositionTracksRemainingStepAfterEarlierDeletion() {
        val shortcutStep = MacroEditorActivity.MacroStepData(type = "shortcut")
        val downStep = MacroEditorActivity.MacroStepData(type = "down")
        val upStep = MacroEditorActivity.MacroStepData(type = "up")

        val steps = mutableListOf(shortcutStep, downStep, upStep)
        steps.removeAt(1)

        assertEquals(1, steps.findCurrentStepPosition(upStep))
    }

    @Test
    fun findCurrentStepPositionReturnsMissingWhenStepAlreadyDeleted() {
        val shortcutStep = MacroEditorActivity.MacroStepData(type = "shortcut")
        val downStep = MacroEditorActivity.MacroStepData(type = "down")

        val steps = mutableListOf(shortcutStep)

        assertEquals(-1, steps.findCurrentStepPosition(downStep))
    }
}
