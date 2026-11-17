/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.candidates

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fxboomk.fcitx5.android.core.CandidateAction
import org.fxboomk.fcitx5.android.core.CandidateWord
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.FcitxEvent
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemePreset
import org.fxboomk.fcitx5.android.input.BaseInputView
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.input.candidates.floating.LabeledCandidateItemUi
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.ceil

class CandidateSymbolPopupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun longPressShowsOnlyEmojisAndReleaseUsesItsFinalPosition() {
        listOf(ThemePreset.MaterialLight, ThemePreset.MaterialDark).forEach { theme ->
            withSession(theme) { session ->
                session.open()
                session.onMain {
                    assertEquals(listOf("🔛", "👩‍💻"), session.labels().map { it.text.toString() })
                }
                session.moveTo(0)
                session.onMain {
                    assertTrue(session.labels()[0].isSelected)
                    assertFalse(session.labels()[1].isSelected)
                    assertTrue(session.connection.triggered.isEmpty())
                }
                session.releaseAt(1)
                session.onMain {
                    assertEquals(listOf(1 to 101), session.connection.triggered)
                    assertEquals(listOf("👩‍💻"), session.connection.committed)
                    assertNull(session.popup())
                    assertEquals(0, session.clicks)
                }
            }
        }
    }

    @Test
    fun holdingWithoutEnteringBubbleCommitsNothing() = withSession { session ->
        session.open()
        session.releaseAtAnchor()
        session.onMain {
            assertTrue(session.connection.triggered.isEmpty())
            assertNull(session.popup())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun cancellingHighlightedEmojiCommitsNothing() = withSession { session ->
        session.open()
        session.moveTo(1)
        session.cancel()
        session.onMain {
            assertTrue(session.connection.triggered.isEmpty())
            assertNull(session.popup())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun releasingBeforeActionsArriveCannotOpenALateBubble() = withSession { session ->
        val gate = CompletableDeferred<Unit>()
        session.connection.gate = gate
        session.open()
        session.onMain { assertNull(session.popup()) }
        session.releaseAtAnchor()
        session.onMain { gate.complete(Unit) }
        instrumentation.waitForIdleSync()
        session.onMain {
            assertNull(session.popup())
            assertTrue(session.connection.triggered.isEmpty())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun recyclingCandidateCancelsItsPendingActions() = withSession { session ->
        val gate = CompletableDeferred<Unit>()
        session.connection.gate = gate
        session.open()
        session.onMain {
            session.input.unbindCandidateGesture(session.anchor)
            gate.complete(Unit)
        }
        instrumentation.waitForIdleSync()
        session.onMain {
            assertNull(session.popup())
            assertTrue(session.connection.triggered.isEmpty())
        }
    }

    @Test
    fun rebindingCandidateCancelsThePreviousNativeRequest() = withSession { session ->
        val gate = CompletableDeferred<Unit>()
        session.connection.gate = gate
        session.open()
        session.onMain {
            session.input.bindCandidateGesture(session.anchor, 2, "空")
            gate.complete(Unit)
        }
        instrumentation.waitForIdleSync()
        session.onMain {
            assertNull(session.popup())
            assertTrue(session.connection.triggered.isEmpty())
        }
    }

    @Test
    fun delayedOrdinaryLongPressStillShowsItsMenuAfterRelease() {
        listOf(true, false).forEach { allowDirectionalActions ->
            withSession { session ->
                val gate = CompletableDeferred<Unit>()
                session.connection.gate = gate
                session.connection.actions = arrayOf(
                    CandidateAction(0, "Forget candidate", false, "", false, false),
                    CandidateAction(1, "Pin to top as custom phrase", false, "", false, false)
                )
                session.connection.candidateText = "开始"
                session.onMain {
                    session.input.bindCandidateGesture(
                        session.anchor, 1, "开始", allowDirectionalActions
                    )
                }
                session.open()
                session.releaseAtAnchor()
                session.onMain { gate.complete(Unit) }
                instrumentation.waitForIdleSync()
                session.onMain {
                    assertNull(session.popup())
                    val menu = requireNotNull(session.menu()).menu
                    val labels = (0 until menu.size()).map { menu.getItem(it).title.toString() }
                    assertTrue(labels.contains("Forget candidate"))
                    assertTrue(labels.contains("Pin to top as custom phrase"))
                    assertEquals(0, session.clicks)
                }
            }
        }
    }

    @Test
    fun pendingOrdinaryDirectionalDragsCannotBecomeALateMenu() {
        listOf(-100f, 100f).forEach { delta ->
            withSession { session ->
                val gate = CompletableDeferred<Unit>()
                session.connection.gate = gate
                session.connection.actions = arrayOf(
                    CandidateAction(0, "Forget candidate", false, "", false, false),
                    CandidateAction(1, "Pin to top as custom phrase", false, "", false, false)
                )
                session.connection.candidateText = "开始"
                session.onMain { session.input.bindCandidateGesture(session.anchor, 1, "开始") }
                session.open()
                session.moveBy(delta)
                // Returning to the anchor does not turn a recognized drag into a stationary hold.
                session.releaseAtAnchor()
                session.onMain { gate.complete(Unit) }
                instrumentation.waitForIdleSync()
                session.onMain {
                    assertNull(session.popup())
                    assertNull(session.menu())
                    assertTrue(session.connection.committed.isEmpty())
                    assertEquals(0, session.clicks)
                }
            }
        }
    }

    @Test
    fun nativeCandidateOrActionChangesCannotCommitADifferentEmojiWithoutARebind() {
        listOf(false, true).forEach { changeHead ->
            withSession { session ->
                session.open()
                session.moveTo(1)
                session.onMain {
                    if (changeHead) {
                        session.connection.candidateText = "👧"
                    } else {
                        session.connection.actions = arrayOf(
                            CandidateAction(101, "🈳", false, "folded-symbol", false, false)
                        )
                    }
                }
                session.releaseAt(1)
                session.onMain {
                    assertTrue(session.connection.committed.isEmpty())
                    assertTrue(session.connection.triggered.isEmpty())
                    assertNull(session.popup())
                }
            }
        }
    }

    @Test
    fun actionsForAChangedHeadCannotOpenABubbleOnTheOldAnchor() = withSession { session ->
        val gate = CompletableDeferred<Unit>()
        session.connection.gate = gate
        session.open()
        session.onMain {
            session.connection.candidateText = "👧"
            gate.complete(Unit)
        }
        instrumentation.waitForIdleSync()
        session.onMain {
            assertNull(session.popup())
            assertNull(session.menu())
            assertTrue(session.connection.committed.isEmpty())
        }
    }

    @Test
    fun rebindingBeforeTheLongPressTimeoutCannotActOnTheNewCandidate() = withSession { session ->
        session.beginTouch()
        session.onMain { session.input.bindCandidateGesture(session.anchor, 2, "空") }
        SystemClock.sleep(CustomGestureView.longPressDelay.toLong() + 50L)
        instrumentation.waitForIdleSync()
        session.releaseAtAnchor()
        session.onMain {
            assertNull(session.popup())
            assertNull(session.menu())
            assertEquals(0, session.connection.requests)
            assertTrue(session.connection.triggered.isEmpty())
            assertEquals(0, session.clicks)
        }
    }

    @Test
    fun ordinaryTapStillSelectsTheVisibleCandidate() = withSession { session ->
        session.tap()
        session.onMain {
            assertEquals(1, session.clicks)
            assertNull(session.popup())
            assertTrue(session.connection.triggered.isEmpty())
        }
    }

    @Test
    fun accessibilityCanChooseAnEmojiWithoutAHeldPointer() = withSession { session ->
        session.onMain { assertTrue(session.anchor.performLongClick()) }
        instrumentation.waitForIdleSync()
        session.onMain {
            assertNotNull(session.popup())
            val alternative = session.labels()[1]
            assertTrue(alternative.performClick())
            assertEquals(listOf(1 to 101), session.connection.triggered)
            assertEquals(listOf("👩‍💻"), session.connection.committed)
            assertNull(session.popup())
            alternative.performClick()
            assertEquals(listOf(1 to 101), session.connection.triggered)
        }
    }

    @Test
    fun sourceCommentsAndFoldMarkersUseTheExistingDimCommentStyleWithoutSpaces() = withSession { session ->
        session.onMain {
            val theme = ThemePreset.MaterialLight
            val candidateUi = CandidateItemUi(session.anchor.context, theme)
            val floatingUi = LabeledCandidateItemUi(session.anchor.context, theme, {}, 0f)
            listOf(
                CandidateWord("", "🈳", "(空)", false) to "🈳(空)",
                CandidateWord("", "▶️", "…", false) to "▶️…"
            ).forEach { (candidate, expected) ->
                candidateUi.updateCandidate(candidate)
                floatingUi.update(candidate, active = false)
                val floatingLabel = floatingUi.root.getChildAt(0) as TextView
                assertEquals(expected, candidateUi.text.text.toString())
                assertEquals(expected, floatingLabel.text.toString())
                assertTrue("Floating TextView must retain foreground spans", floatingLabel.text is Spanned)
                val floatingText = floatingLabel.text as Spanned
                val commentColors = floatingText.getSpans(
                    candidate.text.length, floatingText.length, ForegroundColorSpan::class.java
                )
                assertTrue(commentColors.any { it.foregroundColor == theme.candidateCommentColor })
                assertRenderedCommentColor(candidateUi.text, candidate.text, theme.candidateCommentColor)
            }
        }
    }

    private fun assertRenderedCommentColor(label: TextView, prefix: String, expectedColor: Int) {
        // AutoScaleTextView draws its own glyphs, so inspect the rendered comment instead of
        // assuming that its getText() exposes TextView's span buffer.
        val width = (ceil(label.paint.measureText(label.text.toString())).toInt() +
            label.paddingLeft + label.paddingRight).coerceAtLeast(1)
        val height = (ceil(label.textSize * 3f).toInt() +
            label.paddingTop + label.paddingBottom).coerceAtLeast(1)
        label.background = null
        label.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        label.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            label.draw(Canvas(bitmap))
            val commentStart = (label.paddingLeft + ceil(label.paint.measureText(prefix)).toInt())
                .coerceIn(0, width)
            val commentUsesExpectedColor = (commentStart until width).any { x ->
                (0 until height).any { y ->
                    val pixel = bitmap.getPixel(x, y)
                    // Transparent background keeps the glyph RGB independent of antialiasing.
                    Color.alpha(pixel) >= 32 &&
                        abs(Color.red(pixel) - Color.red(expectedColor)) <= 4 &&
                        abs(Color.green(pixel) - Color.green(expectedColor)) <= 4 &&
                        abs(Color.blue(pixel) - Color.blue(expectedColor)) <= 4
                }
            }
            assertTrue(
                "Comment glyphs in '${label.text}' must render with the configured comment color",
                commentUsesExpectedColor
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun withSession(
        theme: Theme = ThemePreset.MaterialLight,
        block: (Session) -> Unit
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        var session: Session? = null
        try {
            instrumentation.runOnMainSync {
                session = Session(activity, theme)
                activity.setContentView(requireNotNull(session).input)
            }
            instrumentation.waitForIdleSync()
            val current = requireNotNull(session)
            current.onMain { assertTrue(current.anchor.isAttachedToWindow) }
            block(current)
        } finally {
            instrumentation.runOnMainSync {
                session?.close()
                activity.finish()
            }
        }
    }

    private inner class Session(activity: MainActivity, theme: Theme) {
        val connection = FakeConnection()
        private val service = FcitxInputMethodService().apply {
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                .apply { isAccessible = true }.invoke(this, activity)
            fcitx = connection
        }
        val input = object : BaseInputView(service, connection, theme) {
            override fun onStartHandleFcitxEvent() = Unit
            override fun handleFcitxEvent(it: FcitxEvent<*>) = Unit
        }
        val anchor = CustomGestureView(activity)
        var clicks = 0
        private var downTime = 0L

        init {
            val density = activity.resources.displayMetrics.density
            val size = (48 * density).roundToInt()
            input.addView(FrameLayout(activity).apply {
                addView(anchor, FrameLayout.LayoutParams(size, size).apply {
                    leftMargin = (120 * density).roundToInt()
                    topMargin = (240 * density).roundToInt()
                })
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            anchor.setOnClickListener { clicks++ }
            input.bindCandidateGesture(anchor, 1, "▶️")
        }

        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

        fun popup(): CandidateCharacterPopup? =
            BaseInputView::class.java.getDeclaredField("candidateCharacterPopup")
                .apply { isAccessible = true }.get(input) as CandidateCharacterPopup?

        fun menu(): PopupMenu? =
            BaseInputView::class.java.getDeclaredField("candidateActionMenu")
                .apply { isAccessible = true }.get(input) as PopupMenu?

        fun labels(): List<TextView> {
            val shown = requireNotNull(popup())
            val content = CandidateCharacterPopup::class.java.getDeclaredField("content")
                .apply { isAccessible = true }.get(shown) as ViewGroup
            return (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<TextView>()
                .filter { it.text.isNotEmpty() }
        }

        fun open() {
            onMain {
                downTime = SystemClock.uptimeMillis()
                send(MotionEvent.ACTION_DOWN, anchor.width / 2f, anchor.height / 2f)
                assertTrue(anchor.performLongClick())
                // Simulate the timer's result without leaving another timer scheduled.
                CustomGestureView::class.java.getDeclaredField("longPressJob")
                    .apply { isAccessible = true }.get(anchor).let { (it as Job?)?.cancel() }
                CustomGestureView::class.java.getDeclaredField("longPressTriggered")
                    .apply { isAccessible = true }.setBoolean(anchor, true)
            }
            instrumentation.waitForIdleSync()
        }

        fun beginTouch() = onMain {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, anchor.width / 2f, anchor.height / 2f)
        }

        fun moveTo(index: Int) = onMain {
            val point = labelPoint(index)
            send(MotionEvent.ACTION_MOVE, point.first, point.second)
        }

        fun moveBy(deltaY: Float) = onMain {
            send(MotionEvent.ACTION_MOVE, anchor.width / 2f, anchor.height / 2f + deltaY)
        }

        fun releaseAt(index: Int) {
            onMain {
                val point = labelPoint(index)
                send(MotionEvent.ACTION_UP, point.first, point.second)
            }
            instrumentation.waitForIdleSync()
        }

        fun releaseAtAnchor() = onMain {
            send(MotionEvent.ACTION_UP, anchor.width / 2f, anchor.height / 2f)
        }

        fun cancel() = onMain { send(MotionEvent.ACTION_CANCEL, 0f, 0f) }

        fun tap() = onMain {
            downTime = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, anchor.width / 2f, anchor.height / 2f)
            send(MotionEvent.ACTION_UP, anchor.width / 2f, anchor.height / 2f)
        }

        private fun labelPoint(index: Int): Pair<Float, Float> {
            val label = labels()[index]
            val labelLocation = IntArray(2).also(label::getLocationOnScreen)
            val anchorLocation = IntArray(2).also(anchor::getLocationOnScreen)
            return labelLocation[0] + label.width / 2f - anchorLocation[0] to
                labelLocation[1] + label.height / 2f - anchorLocation[1]
        }

        private fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                anchor.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }

        fun close() {
            input.unbindCandidateGesture(anchor)
            anchor.cancelGestures()
            connection.lifecycleScope.cancel()
        }
    }

    private class FakeConnection : FcitxConnection {
        override val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var gate: CompletableDeferred<Unit>? = null
        val triggered = mutableListOf<Pair<Int, Int>>()
        val committed = mutableListOf<String>()
        var candidateText = "▶️"
        var requests = 0
        var actions = arrayOf(
            CandidateAction(0, "Forget candidate", false, "", false, false),
            CandidateAction(100, "🔛", false, "folded-symbol", false, false),
            CandidateAction(101, "👩‍💻", false, "folded-symbol", false, false)
        )
        private val api = Proxy.newProxyInstance(
            FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getCandidateActions" -> {
                    requests++
                    actions
                }
                "getCandidateActionsIfCurrent" -> {
                    requests++
                    if (candidateText == args[1]) actions else null
                }
                "triggerCandidateAction" -> {
                    triggered += (args[0] as Int) to (args[1] as Int)
                    actions.firstOrNull { it.id == args[1] }?.let { committed += it.text }
                    Unit
                }
                "triggerCandidateActionIfCurrent" -> {
                    val action = actions.firstOrNull {
                        it.id == args[2] && it.text == args[3] && it.icon == args[4] && !it.isSeparator
                    }
                    if (candidateText == args[1] && action != null) {
                        triggered += (args[0] as Int) to (args[2] as Int)
                        committed += action.text
                        true
                    } else {
                        false
                    }
                }
                else -> error("Unexpected native operation: ${method.name}")
            }
        } as FcitxAPI

        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(api) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T {
            gate?.await()
            return block(api)
        }
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
            lifecycleScope.launch { block(api) }
        }
    }
}
