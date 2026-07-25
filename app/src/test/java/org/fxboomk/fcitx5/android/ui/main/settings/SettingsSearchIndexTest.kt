/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings

import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.Const
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchIndexTest {

    @Test
    fun toolbarMenuSearchSpecsIncludeTopLevelOverflowItems() {
        val specs = SettingsSearchIndex.toolbarMenuSearchSpecs.associateBy { it.title }

        assertEquals(Const.faqUrl, specs.getValue(R.string.faq).externalUri)
        assertEquals(SettingsRoute.Developer, specs.getValue(R.string.developer).route)
        assertEquals(SettingsRoute.About, specs.getValue(R.string.about).route)
    }

    @Test
    fun toolbarMenuSearchSpecsIncludeAboutAndDeveloperSubItems() {
        val specs = SettingsSearchIndex.toolbarMenuSearchSpecs.associateBy { it.title }

        assertEquals(SettingsRoute.Developer, specs.getValue(R.string.real_time_logs).route)
        assertEquals(SettingsRoute.Developer, specs.getValue(R.string.restart_fcitx_instance).route)
        assertEquals(SettingsRoute.License, specs.getValue(R.string.open_source_licenses).route)
        assertEquals(SettingsRoute.About, specs.getValue(R.string.upgrade_latest).route)
        assertEquals(SettingsRoute.About, specs.getValue(R.string.build_git_hash).route)
    }

    @Test
    fun toolbarMenuSearchSpecsIncludePluginManagementSubItems() {
        val pluginMenuTitles = SettingsSearchIndex.toolbarMenuSearchSpecs
            .filter { it.parent == R.string.plugins || it.title == R.string.manage_plugins }
            .mapTo(mutableSetOf()) { it.title }

        assertTrue(R.string.manage_plugins in pluginMenuTitles)
        assertTrue(R.string.uninstall_selected_plugins in pluginMenuTitles)
        assertTrue(R.string.upgrade_selected_plugins in pluginMenuTitles)
    }

    @Test
    fun titleContainsSearchTermOnlySearchesSettingTitle() {
        val result = SettingsSearchResult(
            title = "Candidate Window",
            path = listOf("Android"),
            summary = "Theme candidate options"
        )

        assertTrue(result.titleContainsSearchTerm("theme candidate"))
        assertFalse(result.titleContainsSearchTerm("theme"))
        assertTrue(result.matches("theme"))
    }

    @Test
    fun sortedForSettingsSearchPrioritizesTitleMatches() {
        val pathMatch = SettingsSearchResult(
            title = "Theme",
            path = listOf("Candidate"),
            summary = "Display options"
        )
        val summaryMatch = SettingsSearchResult(
            title = "Appearance",
            path = listOf("Z"),
            summary = "Candidate colors"
        )
        val titleMatch = SettingsSearchResult(
            title = "Candidate Window",
            path = listOf("Z")
        )

        val sorted = listOf(pathMatch, summaryMatch, titleMatch)
            .filter { it.matches("candidate") }
            .sortedForSettingsSearch("candidate")

        assertEquals(listOf(titleMatch, pathMatch, summaryMatch), sorted)
    }
}
