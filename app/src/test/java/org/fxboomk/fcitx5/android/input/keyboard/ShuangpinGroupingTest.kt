/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.KeySym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuangpinGroupingTest {
    @Test
    fun regroupingCanReplaceWEWithWQAndMoveEToItsOwnKey() {
        val groups = Shuangpin18Keys.groups.map {
            when (it) { "q" -> "wq"; "we" -> "e"; else -> it }
        }
        assertTrue(ShuangpinLayoutVariants.validationErrors(rows(groups), requireSemicolon = true).isEmpty())
        assertEquals(18, groups.size)
        assertEquals("wq", Shuangpin18Keys.displayGroup("wq", false))
        assertEquals("", Shuangpin18Keys.defaultCorner("wq"))
    }

    @Test
    fun splittingAndMergingCanChangeTheNumberOfLogicalGroups() {
        val split = Shuangpin18Keys.groups.flatMap { if (it == "we") listOf("w", "e") else listOf(it) }
        val merged = Shuangpin18Keys.groups.filterNot { it == "we" || it == "rt" } + "wert"
        assertEquals(19, split.size)
        assertEquals(17, merged.size)
        for (groups in listOf(split, merged, listOf("abcdefghijklmnopqrstuvwxyz;"))) {
            assertTrue(ShuangpinLayoutVariants.validationErrors(rows(groups), requireSemicolon = true).isEmpty())
        }
    }

    @Test
    fun identicalButtonsMayRepeatButOverlappingGroupsHaveExplicitErrors() {
        assertTrue(ShuangpinLayoutVariants.validationErrors(rows(Shuangpin18Keys.groups + "we")).isEmpty())
        for (extra in listOf("wq", "ew", "w")) {
            val errors = ShuangpinLayoutVariants.validationErrors(rows(Shuangpin18Keys.groups + extra))
            assertTrue(errors.joinToString(), errors.any { it.contains(extra) && it.contains("同时属于") })
        }
    }

    @Test
    fun validationNamesMissingLettersAndRequiresSemicolonOnlyForSchemesThatUseIt() {
        val withoutWE = Shuangpin18Keys.groups.filterNot { it == "we" }
        val missing = ShuangpinLayoutVariants.validationErrors(rows(withoutWE)).joinToString()
        assertTrue(missing, missing.contains("w") && missing.contains("e"))
        val withoutSemicolon = Shuangpin18Keys.groups.map { it.replace(";", "") }
        assertTrue(ShuangpinLayoutVariants.validationErrors(rows(withoutSemicolon)).isEmpty())
        assertTrue(ShuangpinLayoutVariants.validationErrors(rows(withoutSemicolon), requireSemicolon = true)
            .any { it.contains(";") && it.contains("缺少") })
    }

    @Test
    fun malformedGroupContentIsRejectedEvenWithACompleteAlphabetElsewhere() {
        for (groupId in listOf(null, "", "WE", "ww", "w1", "w q", "中")) {
            assertFalse(Shuangpin18Keys.isValidGroup(groupId))
            val invalid = rows(Shuangpin18Keys.groups) + listOf(listOf(mapOf("type" to "ShuangpinGroupKey", "groupId" to groupId)))
            assertTrue(ShuangpinLayoutVariants.validationErrors(invalid).any { it.contains("groupId") })
        }
        assertEquals(";", Shuangpin18Keys.displayGroup(";", semicolon = false))
        assertEquals("q", Shuangpin18Keys.displayGroup("q;", semicolon = false))
    }

    @Test
    fun representativeActionsKeepIdentityAcrossReorderingAndOutsideTextCodes() {
        for (group in listOf("wq", "e", "wert", ";")) {
            val action = Shuangpin18Keys.action(group)
            assertEquals(0x0200E300 + "abcdefghijklmnopqrstuvwxyz;".indexOf(group.first()), action.sym.sym)
            assertTrue(Shuangpin18Keys.isGroupAction(action.sym))
            assertTrue(action.states.has(KeyState.Virtual))
            assertFalse(action.states.has(KeyState.Shift))
        }
        assertNotEquals(Shuangpin18Keys.action("wq"), Shuangpin18Keys.action("qw"))
        assertFalse(Shuangpin18Keys.isGroupAction(KeySym('w'.code)))
        assertFalse(Shuangpin18Keys.isGroupAction(KeySym(0x0200E31B)))
        assertFalse(Shuangpin18Keys.isGroupAction(Shuangpin18Keys.modeAction(true).sym))
        assertTrue(Shuangpin18Keys.isGroupAction(Shuangpin18Keys.action(1).sym))
    }

    @Test
    fun configurationStagesTheWholePartitionAndPreservesLetterOrder() {
        val groups = listOf("wq", "e") + Shuangpin18Keys.groups.filterNot { it == "q" || it == "we" }
        val actions = Shuangpin18Keys.configurationActions(groups + "wq")
        assertEquals(Shuangpin18Keys.configurationActions(groups), actions)
        assertEquals(listOf(0x0200E200, 0x0200E226, 0x0200E220, 0x0200E201, 0x0200E214, 0x0200E201),
            actions.take(6).map { it.sym.sym })
        assertEquals(0x0200E202, actions.last().sym.sym)
        assertEquals(27 + groups.size + 2, actions.size)
        assertTrue(actions.all { it.states.has(KeyState.Virtual) && !Shuangpin18Keys.isGroupAction(it.sym) })
    }

    @Test
    fun invalidPartitionsCannotBeSentToNative() {
        for (groups in listOf(emptyList(), listOf("wq"), Shuangpin18Keys.groups + "w", Shuangpin18Keys.groups + "WW")) {
            assertThrows(IllegalArgumentException::class.java) { Shuangpin18Keys.configurationActions(groups) }
        }
    }

    private fun rows(groups: List<String>): List<List<Map<String, Any?>>> = groups.chunked(6).map { row ->
        row.map { mapOf("type" to "ShuangpinGroupKey", "groupId" to it, "weight" to 0.15f) }
    }
}
