/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardSearchCategory
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSearchRefreshTest {

    @Test
    fun openingPhrasesSearchLoadsFavoritesAndDefaultOpeningRestoresLocal() =
        withOverlay { overlay, _ ->
            overlay.open(ClipboardSearchCategory.Favorites)
            val request = searchJob(overlay)
            assertNotNull(request)
            assertEquals(ClipboardSearchCategory.Favorites, selectedCategory(overlay))
            clickCategory(overlay, R.string.clipboard_category_favorites)
            assertSame(request, searchJob(overlay))
            overlay.close()
            overlay.open()
            assertEquals(ClipboardSearchCategory.Local, selectedCategory(overlay))
            assertEquals(null, searchJob(overlay))
        }

    @Test
    fun rapidCategorySwitchesKeepPresentedResultsVisible() = withOverlay { overlay, ui ->
        val adapter = ui.recyclerView.adapter as ClipboardSearchAdapter
        val entries = listOf(ClipboardEntry(id = 1, text = "previous result"))
        // Reset the differ so seeding is synchronous even after open() submitted [].
        adapter.submitList(null)
        adapter.submitList(entries)
        assertEquals(entries, adapter.currentList)
        ui.showResults("1 result")
        val originalPadding = ui.recyclerView.paddingTop

        repeat(10) {
            for (category in listOf(
                R.string.clipboard_category_all,
                R.string.clipboard_category_favorites,
                R.string.clipboard_search_category_media,
                R.string.clipboard_search_category_remote,
                R.string.clipboard_category_local
            )) {
                clickCategory(overlay, category)
                assertTrue("Pending category must not hide the list", ui.recyclerView.isVisible)
                assertEquals(originalPadding, ui.recyclerView.paddingTop)
                assertEquals(entries, adapter.currentList)
            }
        }
    }

    @Test
    fun firstExplicitLocalSelectionSearchesButRepeatedClickDoesNotRestart() =
        withOverlay { overlay, _ ->
            assertEquals(null, searchJob(overlay))
            clickCategory(overlay, R.string.clipboard_category_local)
            val firstRequest = searchJob(overlay)
            assertNotNull("The initially selected Local tab must still load on first click", firstRequest)
            assertTrue(firstRequest!!.isActive)
            clickCategory(overlay, R.string.clipboard_category_local)
            assertSame(firstRequest, searchJob(overlay))
            assertTrue(firstRequest.isActive)

            clickCategory(overlay, R.string.clipboard_category_all)
            assertTrue(firstRequest.isCancelled)
            assertTrue(searchJob(overlay)!!.isActive)
        }

    @Test
    fun editingQueryKeepsResultsUntilReplacementAndClearingRestoresInitialState() =
        withOverlay { overlay, ui ->
            overlay.commit("first")
            val adapter = ui.recyclerView.adapter as ClipboardSearchAdapter
            adapter.submitList(null)
            adapter.submitList(listOf(ClipboardEntry(id = 1, text = "first result")))
            assertEquals(1, adapter.itemCount)
            ui.showResults("1 result")
            val firstRequest = searchJob(overlay)!!
            overlay.commit(" query")
            assertTrue(firstRequest.isCancelled)
            assertTrue(ui.recyclerView.isVisible)
            val pendingRequest = searchJob(overlay)!!
            ui.clearButton.performClick()
            assertFalse(ui.recyclerView.isVisible)
            assertTrue(pendingRequest.isCancelled)
        }

    @Test
    fun closingDuringRefreshCancelsPendingSearchAndClearsResults() = withOverlay { overlay, ui ->
        clickCategory(overlay, R.string.clipboard_category_all)
        val request = searchJob(overlay)!!
        overlay.close()
        assertTrue(request.isCancelled)
        assertEquals(null, searchJob(overlay))
        assertEquals(0, ui.recyclerView.adapter!!.itemCount)
    }

    private fun clickCategory(overlay: ClipboardSearchOverlay, textRes: Int) {
        val text = context.getString(textRes)
        descendants(overlay.root).filterIsInstance<TextView>().single { it.text == text }.performClick()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun searchJob(overlay: ClipboardSearchOverlay): Job? =
        ClipboardSearchOverlay::class.java.getDeclaredField("searchJob").run {
            isAccessible = true
            get(overlay) as Job?
        }

    private fun selectedCategory(overlay: ClipboardSearchOverlay): ClipboardSearchCategory =
        ClipboardSearchOverlay::class.java.getDeclaredField("selectedCategory").run {
            isAccessible = true
            get(overlay) as ClipboardSearchCategory
        }

    private fun withOverlay(block: (ClipboardSearchOverlay, ClipboardSearchUi) -> Unit) =
        instrumentation.runOnMainSync {
            // Execute clicks and assertions in one main-loop turn, before the debounce
            // resumes. These tests neither query nor mutate the real clipboard database.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val overlay = ClipboardSearchOverlay(
                context, ThemePreset.MaterialLight, 0f, false, scope,
                onClose = {}, onCursorPositioned = {}, onEntryClick = { _, _ -> }
            )
            try {
                overlay.open()
                val ui = ClipboardSearchOverlay::class.java.getDeclaredField("ui").run {
                    isAccessible = true
                    get(overlay) as ClipboardSearchUi
                }
                block(overlay, ui)
            } finally {
                overlay.close()
                scope.cancel()
            }
        }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
    }
}
