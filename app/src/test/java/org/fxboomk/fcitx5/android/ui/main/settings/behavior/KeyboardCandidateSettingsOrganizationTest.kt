/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardCandidateSettingsOrganizationTest {

    @Test
    fun candidateSettingsAreGroupedByDisplayModeAndConfiguredObject() {
        assertEquals(
            listOf(
                "preedit_style",
                "horizontal_candidate_style",
                "expanded_candidate_style",
                "expanded_candidate_grid_span_count_portrait"
            ),
            KeyboardSettingsSupport.horizontalCandidateKeys
        )
        assertEquals(
            listOf(
                "candidates_window_orientation",
                "virtual_keyboard_candidates_position",
                "candidates_window_padding",
                "candidates_window_radius",
                "candidates_window_min_width"
            ),
            KeyboardSettingsSupport.candidateWindowKeys
        )
        assertEquals(
            listOf(
                "candidates_item_padding_vertical",
                "candidates_window_font_size",
                "candidate_highlight_radius"
            ),
            KeyboardSettingsSupport.candidateItemKeys
        )
    }

    @Test
    fun languageSwitchVisibilityIsInBasicBehaviorAndCapsBehaviorPrecedesLanguageSwitchBehavior() {
        assertTrue("show_lang_switch_key" in KeyboardSettingsSupport.basicBehaviorKeys)
        assertFalse("show_lang_switch_key" in KeyboardSettingsSupport.keyAndGestureKeys)
        assertEquals(
            KeyboardSettingsSupport.keyAndGestureKeys.indexOf("caps_key_behavior") + 1,
            KeyboardSettingsSupport.keyAndGestureKeys.indexOf("lang_switch_key_behavior")
        )
    }

}
