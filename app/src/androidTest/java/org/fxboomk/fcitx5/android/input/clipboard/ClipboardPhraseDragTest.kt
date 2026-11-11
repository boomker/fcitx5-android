/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.clipboard

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.dimensions.dp

class ClipboardPhraseDragTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun completeFavoriteListCanMoveAcrossTheClipboardPageBoundary() = withSession { session ->
        onMain {
            session.favorites.beginDrag()
            assertTrue(session.favorites.move(23, 0))

            val expected = listOf(24) + (1..23)
            assertEquals(24, session.favorites.itemCount)
            assertEquals(expected, session.favorites.changedOrder())
            assertEquals(24, requireNotNull(session.favorites.getEntryAt(0)).id)
            assertEquals(23, requireNotNull(session.favorites.getEntryAt(23)).id)
        }
    }

    @Test
    fun databaseRefreshCannotReplaceTheListWhileADragIsActive() = withSession { session ->
        onMain {
            session.favorites.beginDrag()
            assertTrue(session.favorites.move(0, 23))
            val expected = (2..24).toList() + 1
            val refreshed = session.entries.reversed() + ClipboardEntry(id = 25, text = "new phrase", pinned = true)

            session.favorites.submitEntries(refreshed)

            assertEquals(expected, session.favorites.changedOrder())
            assertEquals(24, session.favorites.itemCount)

            session.favorites.finishDrag(refreshed)

            assertNull(session.favorites.changedOrder())
            assertEquals(25, session.favorites.itemCount)
            assertEquals(24, requireNotNull(session.favorites.getEntryAt(0)).id)
            assertEquals(25, requireNotNull(session.favorites.getEntryAt(24)).id)
        }
    }

    @Test
    fun invalidOrUnchangedMovesDoNotRequestAnOrderSave() = withSession { session ->
        onMain {
            assertFalse(session.favorites.move(0, 1))
            session.favorites.beginDrag()
            assertFalse(session.favorites.move(-1, 0))
            assertFalse(session.favorites.move(0, 24))
            assertFalse(session.favorites.move(0, 0))
            assertNull(session.favorites.changedOrder())
            assertEquals((1..24).toList(), (0 until 24).map { requireNotNull(session.favorites.getEntryAt(it)).id })
        }
    }

    @Test
    fun touchingTheHandleStartsDragWithoutPastingThePhrase() = withSession { session ->
        val point = session.handleCenter(0)
        session.send(MotionEvent.ACTION_DOWN, point)
        session.send(MotionEvent.ACTION_UP, point)

        onMain {
            assertEquals(listOf(1), session.adapter.dragStarts)
            assertTrue(session.adapter.pasted.isEmpty())
            assertNull(session.adapter.currentMenu())
            assertTrue(session.holder(0).itemView.performClick())
            assertEquals(listOf(1), session.adapter.pasted)
        }
    }

    @Test
    fun longPressingThePhraseStillShowsItsExistingActionMenu() = withSession { session ->
        onMain {
            assertTrue(session.holder(0).itemView.performLongClick())
            val popup = requireNotNull(session.adapter.currentMenu())
            val titles = (0 until popup.menu.size()).map { popup.menu.getItem(it).title.toString() }

            listOf(R.string.copy, R.string.remove_from_favorites, R.string.edit, R.string.share, R.string.delete).forEach {
                assertTrue("Existing phrase action must remain available", session.activity.getString(it) in titles)
            }
            assertTrue(session.adapter.dragStarts.isEmpty())
            assertTrue(session.adapter.pasted.isEmpty())
        }
    }

    @Test
    fun draggingTheHandleMovesTheVisibleItemAndKeepsItsBoundPhrase() = withSession { session ->
        val start = session.handleCenter(0)
        val target = session.itemCenter(2)
        session.send(MotionEvent.ACTION_DOWN, start)
        repeat(12) { step ->
            val fraction = (step + 1) / 12f
            session.send(MotionEvent.ACTION_MOVE, start.first to start.second + (target.second - start.second) * fraction)
        }

        onMain {
            val dragged = session.adapter.dragStarts.single()
            val position = (0 until session.favorites.itemCount).first {
                session.favorites.getEntryAt(it)?.id == dragged
            }
            assertTrue("A real drag must change the displayed order", position > 0)
            val holder = session.holder(position)
            assertEquals(dragged.toLong(), holder.itemId)
            assertEquals("phrase $dragged", holder.entryUi.textView.text.toString())
            assertTrue(session.adapter.pasted.isEmpty())
            assertNull(session.adapter.currentMenu())
        }
        session.send(MotionEvent.ACTION_UP, start.first to target.second)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun withSession(block: (Session) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var session: Session? = null
        try {
            onMain {
                val created = Session(activity)
                session = created
                activity.setContentView(FrameLayout(activity).apply {
                    addView(created.recyclerView, FrameLayout.LayoutParams(created.width, created.height))
                })
            }
            instrumentation.waitForIdleSync()
            val current = requireNotNull(session)
            onMain {
                current.layout()
                assertTrue(current.recyclerView.isAttachedToWindow)
                assertEquals(View.VISIBLE, current.holder(0).entryUi.dragHandle.visibility)
                assertEquals(current.holder(0).itemView.left, current.holder(1).itemView.left)
                assertEquals(current.width, current.holder(0).itemView.width)
            }
            block(current)
        } finally {
            onMain {
                session?.adapter?.onDetached()
                activity.finish()
            }
        }
    }

    private inner class Session(val activity: MainActivity) {
        val width = activity.dp(320)
        val height = activity.dp(400)
        val entries = List(24) { ClipboardEntry(id = it + 1, text = "phrase ${it + 1}", pinned = true) }
        val adapter = RecordingAdapter(activity)
        val favorites = adapter.FavoritesAdapter()
        val recyclerView = RecyclerView(activity).apply {
            layoutManager = StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL)
            itemAnimator = null
            adapter = favorites
        }
        private var downTime = 0L
        private var elapsed = 0L
        private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = favorites.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) favorites.beginDrag()
                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                val current = (0 until favorites.itemCount).map { requireNotNull(favorites.getEntryAt(it)) }
                favorites.finishDrag(current)
                super.clearView(recyclerView, viewHolder)
            }
        })

        init {
            favorites.submitEntries(entries)
            touchHelper.attachToRecyclerView(recyclerView)
            adapter.startDrag = touchHelper::startDrag
        }

        fun layout() {
            recyclerView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            recyclerView.layout(0, 0, width, height)
        }

        fun holder(position: Int): ClipboardAdapter.ViewHolder =
            requireNotNull(recyclerView.findViewHolderForAdapterPosition(position)) as ClipboardAdapter.ViewHolder

        fun handleCenter(position: Int): Pair<Float, Float> {
            var point = 0f to 0f
            onMain {
                val handle = holder(position).entryUi.dragHandle
                val handleLocation = IntArray(2).also(handle::getLocationOnScreen)
                val listLocation = IntArray(2).also(recyclerView::getLocationOnScreen)
                point = (handleLocation[0] - listLocation[0] + handle.width / 2f) to
                    (handleLocation[1] - listLocation[1] + handle.height / 2f)
            }
            return point
        }

        fun itemCenter(position: Int): Pair<Float, Float> {
            var point = 0f to 0f
            onMain {
                val view = holder(position).itemView
                point = (view.left + view.width / 2f) to (view.top + view.height / 2f)
            }
            return point
        }

        fun send(action: Int, point: Pair<Float, Float>) {
            onMain {
                if (action == MotionEvent.ACTION_DOWN) {
                    downTime = SystemClock.uptimeMillis()
                    elapsed = 0
                }
                elapsed += 16
                val event = MotionEvent.obtain(downTime, downTime + elapsed, action, point.first, point.second, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try {
                    recyclerView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            instrumentation.waitForIdleSync()
        }
    }

    private class RecordingAdapter(activity: MainActivity) : ClipboardAdapter(
        ThemePreset.MaterialLight, activity.dp(8).toFloat(), false
    ) {
        val pasted = mutableListOf<Int>()
        val dragStarts = mutableListOf<Int>()
        var startDrag: (ViewHolder) -> Unit = {}

        override fun onPaste(entry: ClipboardEntry) { pasted += entry.id }
        override fun onStartDrag(holder: ViewHolder) {
            dragStarts += holder.itemId.toInt()
            startDrag(holder)
        }

        override fun onCopy(entry: ClipboardEntry) = Unit
        override fun onPin(id: Int) = Unit
        override fun onUnpin(id: Int) = Unit
        override fun onEdit(id: Int) = Unit
        override fun onShare(entry: ClipboardEntry) = Unit
        override fun onSplitText(text: String) = Unit
        override fun onSearch(query: String) = Unit
        override fun onDial(number: String) = Unit
        override fun onPasteContent(entry: ClipboardEntry) = false
        override fun onOpenFile(uri: Uri) = Unit
        override fun onOpenLink(uri: Uri) = Unit
        override fun onViewImage(uri: Uri) = Unit
        override fun onDelete(id: Int) = Unit

        fun currentMenu(): PopupMenu? = ClipboardAdapter::class.java.getDeclaredField("popupMenu").run {
            isAccessible = true
            get(this@RecordingAdapter) as PopupMenu?
        }
    }
}
