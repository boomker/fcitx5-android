/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSuggestionWindowTest {

    @Test
    fun presentationStateReplacesSuggestionsAndCallback() {
        val state = AiSuggestionWindow.PresentationState()
        val received = mutableListOf<String>()

        state.update(listOf("旧候选")) { suggestion ->
            received += "old:$suggestion"
        }
        state.update(listOf("新候选1", "新候选2")) { suggestion ->
            received += "new:$suggestion"
        }
        state.dispatchSelection("新候选2")

        assertEquals(listOf("新候选1", "新候选2"), state.snapshot())
        assertEquals(listOf("new:新候选2"), received)
    }
}
