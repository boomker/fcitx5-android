/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.prefs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateBarHeightProfilesTest {
    private val baseline = CandidateBarHeightProfile(adaptive = false, manualHeightDp = 40)

    @Test
    fun firstTopCopiesThatSchemesManualHeightAndRestoresItsRightProfile() {
        val right = CandidateBarHeightProfile(adaptive = false, manualHeightDp = 72)
        val original = CandidateBarHeightProfiles(baseline).update("xiaohe", right)
        val top = original.select("xiaohe", topPlacement = true, annotationEnabled = true)

        assertEquals(right.copy(adaptive = true), top.scheme("xiaohe").selected)
        assertEquals(right, top.scheme("xiaohe").right)
        val restored = top.select("xiaohe", topPlacement = false, annotationEnabled = true)
        assertEquals(right, restored.scheme("xiaohe").selected)
    }

    @Test
    fun manualTopOverrideSurvivesChangingPlacementAndOtherSchemeSettings() {
        val right = CandidateBarHeightProfile(adaptive = false, manualHeightDp = 72)
        val disabledTop = CandidateBarHeightProfile(adaptive = false, manualHeightDp = 48)
        val state = CandidateBarHeightProfiles(baseline)
            .update("xiaohe", right)
            .select("xiaohe", topPlacement = true, annotationEnabled = true)
            .update("xiaohe", disabledTop)
            .select("ziranma", topPlacement = true, annotationEnabled = true)
            .update("ziranma", CandidateBarHeightProfile(adaptive = true, manualHeightDp = 60))
            .select("xiaohe", topPlacement = false, annotationEnabled = true)

        assertEquals(right, state.scheme("xiaohe").selected)
        val topAgain = state.select("xiaohe", topPlacement = true, annotationEnabled = true)
        assertEquals(disabledTop, topAgain.scheme("xiaohe").selected)
        assertEquals(60, topAgain.scheme("ziranma").selected.manualHeightDp)
    }

    @Test
    fun newSchemesUseTheOriginalBaselineInsteadOfTheLastActiveScheme() {
        val state = CandidateBarHeightProfiles(baseline)
            .select("xiaohe", topPlacement = true, annotationEnabled = true)
            .update("xiaohe", CandidateBarHeightProfile(adaptive = true, manualHeightDp = 72))
            .select("wubi", topPlacement = false, annotationEnabled = false)

        assertEquals(baseline, state.scheme("wubi").selected)
        assertFalse(state.scheme("wubi").topPlacement)
        assertFalse(state.scheme("wubi").annotationEnabled)
        assertEquals(baseline, state.scheme("new-scheme").selected)
    }

    @Test
    fun disablingAnnotationsRestoresRightAndReenablingRestoresTheSavedTopProfile() {
        val right = CandidateBarHeightProfile(adaptive = false, manualHeightDp = 72)
        val top = CandidateBarHeightProfile(adaptive = true, manualHeightDp = 48)
        val state = CandidateBarHeightProfiles(baseline)
            .update("xiaohe", right)
            .select("xiaohe", topPlacement = true, annotationEnabled = true)
            .update("xiaohe", top)
            .select("xiaohe", topPlacement = true, annotationEnabled = false)

        assertTrue(state.scheme("xiaohe").topPlacement)
        assertFalse(state.scheme("xiaohe").annotationEnabled)
        assertEquals(right, state.scheme("xiaohe").selected)
        assertEquals(top, state.scheme("xiaohe").top)
        val reloaded = Json.decodeFromString<CandidateBarHeightProfiles>(Json.encodeToString(state))
        assertEquals(right, reloaded.scheme("xiaohe").selected)
        assertEquals(top, reloaded.select("xiaohe", topPlacement = true, annotationEnabled = true)
            .scheme("xiaohe").selected)
    }

    @Test
    fun editingHeightWhileAnnotationsAreHiddenPreservesTheManualTopChoice() {
        val top = CandidateBarHeightProfile(adaptive = false, manualHeightDp = 72)
        val right = CandidateBarHeightProfile(adaptive = true, manualHeightDp = 60)
        val state = CandidateBarHeightProfiles(baseline)
            .select("xiaohe", topPlacement = true, annotationEnabled = true)
            .update("xiaohe", top)
            .select("xiaohe", topPlacement = true, annotationEnabled = false)
            .update("xiaohe", right)

        assertEquals(right, state.scheme("xiaohe").selected)
        assertEquals(top, state.select("xiaohe", topPlacement = true, annotationEnabled = true)
            .scheme("xiaohe").selected)
        assertEquals(right, state.select("xiaohe", topPlacement = false, annotationEnabled = true)
            .scheme("xiaohe").selected)
    }

    @Test
    fun hiddenTopPlacementUsesRightUntilAnnotationsAreFirstEnabled() {
        val state = CandidateBarHeightProfiles(baseline)
            .select("xiaohe", topPlacement = true, annotationEnabled = false)

        assertNull(state.scheme("xiaohe").top)
        assertEquals(baseline, state.scheme("xiaohe").selected)
        assertEquals(baseline.copy(adaptive = true),
            state.select("xiaohe", topPlacement = true, annotationEnabled = true)
                .scheme("xiaohe").selected)
    }

    @Test
    fun savedProfilesRestoreBothPlacementChoicesAfterReload() {
        val original = CandidateBarHeightProfiles(baseline)
            .update("xiaohe", CandidateBarHeightProfile(adaptive = true, manualHeightDp = 72))
            .select("xiaohe", topPlacement = true, annotationEnabled = true)
            .update("xiaohe", CandidateBarHeightProfile(adaptive = false, manualHeightDp = 48))
            .select("wubi", topPlacement = false, annotationEnabled = false)
        val reloaded = Json.decodeFromString<CandidateBarHeightProfiles>(Json.encodeToString(original))

        assertEquals(original, reloaded)
        assertEquals(48, reloaded.scheme("xiaohe").selected.manualHeightDp)
        assertFalse(reloaded.scheme("xiaohe").selected.adaptive)
        val right = reloaded.select("xiaohe", topPlacement = false, annotationEnabled = true)
        assertEquals(CandidateBarHeightProfile(adaptive = true, manualHeightDp = 72), right.scheme("xiaohe").selected)
    }
}

