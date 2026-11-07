/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js.app

import org.fxboomk.fcitx5.android.input.plugin.js.JsPrelude
import org.fxboomk.fcitx5.android.input.plugin.js.JsRuntime
import org.fxboomk.fcitx5.android.input.plugin.js.JsStubNatives
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.widget.Button
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.slider.Slider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsAppDialogTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun assistantReplyGrowsWithWrappedTextAndShrinksAgainWithinItsHeightLimit() {
        val spec = bundledAssistantDialog("单行回复")
        instrumentation.runOnMainSync {
            val service = service()
            val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { null })
            try {
                dialog.render(spec)
                val root = panel(dialog) as ViewGroup
                val width = root.measuredWidth
                fun layoutReply() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(service.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                }
                layoutReply()
                val reply = descendants(root).filterIsInstance<EditText>()
                    .single { it.text.toString() == "单行回复" }
                assertNull(reply.keyListener)
                val oneLineHeight = reply.height
                assertEquals(1, reply.lineCount)
                val replyBox = reply.parent.parent as View
                assertTrue("The reply container must not retain a fixed empty area", replyBox.height <=
                    oneLineHeight + (8 * service.resources.displayMetrics.density).toInt())
                assertTrue("A one-line reply must not reserve two lines", oneLineHeight <
                    reply.lineHeight * 2 + reply.compoundPaddingTop + reply.compoundPaddingBottom)
                val oneLinePanelHeight = root.height
                val oneLineWindowHeight = requireNotNull(dialog.window).attributes.height

                dialog.setValue("result", "第一行\n第二行\n第三行")
                assertTrue("Updating the reply must resize the window before the next traversal",
                    requireNotNull(dialog.window).attributes.height > oneLineWindowHeight)
                layoutReply()
                assertEquals(3, reply.lineCount)
                assertTrue(reply.height > oneLineHeight)
                assertTrue(root.height > oneLinePanelHeight)

                val wrappedText = "自动换行的回复内容".repeat(100)
                dialog.setValue("result", wrappedText)
                layoutReply()
                assertTrue("Soft-wrapped text must count toward the height", reply.lineCount > 3)
                assertEquals(wrappedText, reply.text.toString())
                assertTrue("Long replies need a bounded viewport", reply.height <=
                    (96 * service.resources.displayMetrics.density).toInt())
                assertTrue(reply.isVerticalScrollBarEnabled)

                dialog.setValue("result", "短回复")
                assertEquals(oneLineWindowHeight, requireNotNull(dialog.window).attributes.height)
                layoutReply()
                assertEquals(1, reply.lineCount)
                assertEquals(oneLineHeight, reply.height)
                assertEquals(oneLinePanelHeight, root.height)
            } finally {
                dialog.dismiss()
                service.jsAppInputConnection = null
            }
        }
    }

    @Test
    fun assistantModelAndThinkingShareARowAboveTheSliderAndTwoLineEditor() {
        val spec = bundledAssistantDialog()
        instrumentation.runOnMainSync {
            val service = service()
            val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { null })
            try {
                dialog.render(spec)
                val root = panel(dialog) as ViewGroup
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val views = descendants(root)
                val slider = views.filterIsInstance<Slider>().single()
                val model = views.filterIsInstance<TextView>().single { it.text.startsWith("模型：") }
                val thinking = views.filterIsInstance<TextView>().single { it.text.startsWith("思考档位") }
                assertTrue(model.parent === thinking.parent)
                assertEquals(LinearLayout.HORIZONTAL, (model.parent as LinearLayout).orientation)
                val row = Rect(0, 0, model.width, model.height)
                root.offsetDescendantRectToMyCoords(model, row)
                val sliderRow = slider.parent as ViewGroup
                val track = Rect(0, 0, sliderRow.width, sliderRow.height)
                root.offsetDescendantRectToMyCoords(sliderRow, track)
                assertTrue("The slider needs its own row", track.top >= row.bottom)
                assertEquals((40 * service.resources.displayMetrics.density).toInt(), sliderRow.height)
                slider.value = 3f
                assertEquals("思考档位 · 中", thinking.text.toString())

                val editor = views.filterIsInstance<EditText>().single { it !is AutoCompleteTextView && it.keyListener != null }
                assertEquals(2, editor.minLines)
                assertEquals(2, editor.maxLines)
                val text = "第一行\n第二行\n第三行"
                editor.setText(text)
                editor.setSelection(0)
                root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTrue("Two text lines must fit", editor.height >= editor.lineHeight * 2)
                assertTrue("The input should be shorter than three lines", editor.height <
                    editor.lineHeight * 3 + editor.compoundPaddingTop + editor.compoundPaddingBottom)
                assertEquals(text, editor.text.toString())
            } finally {
                dialog.dismiss()
                service.jsAppInputConnection = null
            }
        }
    }

    @Test
    fun sliderKeepsItsValueLocallyUntilSendAndCanBeUpdatedByThePlugin() = instrumentation.runOnMainSync {
        val service = service()
        val events = mutableListOf<Pair<String, JsonObject>>()
        val dialog = JsAppDialog(service, { name, values -> events += name to values }, {}, keyboardView = { null })
        try {
            dialog.render(sliderDialog())
            val views = descendants(panel(dialog))
            val slider = views.filterIsInstance<Slider>().single()
            assertEquals(0f, slider.valueFrom, 0f)
            assertEquals(3f, slider.valueTo, 0f)
            assertEquals(1f, slider.stepSize, 0f)
            assertEquals("none", dialog.values()["reasoning"]?.toString()?.trim('"'))

            slider.value = 2f
            assertTrue(events.isEmpty())
            assertEquals("medium", dialog.values()["reasoning"]?.toString()?.trim('"'))
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "思考档位 · 中" })
            dialog.setValue("reasoning", "high")
            assertEquals(3f, slider.value, 0f)
            dialog.setBusy(true)
            assertFalse(slider.isEnabled)
            dialog.setBusy(false)
            assertTrue(slider.isEnabled)
            assertTrue(views.filterIsInstance<Button>().single().performClick())
            assertEquals("send", events.single().first)
            assertEquals("high", events.single().second["reasoning"]?.toString()?.trim('"'))
        } finally {
            dialog.dismiss()
            service.jsAppInputConnection = null
        }
    }

    @Test
    fun slidingAnOptionDoesNotStartDraggingThePanel() = instrumentation.runOnMainSync {
        val service = service()
        val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { null })
        try {
            dialog.render(sliderDialog())
            val root = panel(dialog) as JsAppPanelLayout
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val slider = descendants(root).filterIsInstance<Slider>().single()
            val area = Rect(0, 0, slider.width, slider.height)
            root.offsetDescendantRectToMyCoords(slider, area)
            val density = service.resources.displayMetrics.density
            for ((action, x) in listOf(MotionEvent.ACTION_DOWN to area.centerX().toFloat(),
                    MotionEvent.ACTION_MOVE to area.centerX() + 32f * density)) {
                val event = MotionEvent.obtain(0, 20, action, x, area.centerY().toFloat(), 0)
                try {
                    assertFalse("Slider gestures must stay in the control", root.onInterceptTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        } finally {
            dialog.dismiss()
            service.jsAppInputConnection = null
        }
    }

    @Test
    fun keyboardLayoutRequestsDoNotHideAnAlreadyRevealedPanel() = instrumentation.runOnMainSync {
        val service = service()
        val pendingKeyboard = View(service).apply { requestLayout() }
        var keyboard: View? = pendingKeyboard
        val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { keyboard })
        try {
            val window = requireNotNull(dialog.window)
            assertEquals(0f, window.attributes.alpha, 0f)
            dialog.render(editorDialog())
            val panel = panel(dialog)
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            assertFalse(panel.viewTreeObserver.dispatchOnPreDraw())
            assertEquals("The first frame must wait for the keyboard", 0f, window.attributes.alpha, 0f)

            keyboard = null
            assertFalse(panel.viewTreeObserver.dispatchOnPreDraw())
            assertPanelVisible(dialog)

            keyboard = pendingKeyboard
            assertTrue(pendingKeyboard.isLayoutRequested)
            repeat(3) {
                assertFalse("Keyboard layout must not cancel panel drawing",
                    panel.viewTreeObserver.dispatchOnPreDraw())
                assertPanelVisible(dialog)
            }
        } finally {
            dialog.dismiss()
            service.jsAppInputConnection = null
        }
    }

    @Test
    fun compositionAndCandidateCommitKeepTheRevealedPanelVisible() = instrumentation.runOnMainSync {
        val service = service()
        var keyboard: View? = null
        val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { keyboard })
        try {
            dialog.render(editorDialog())
            val panel = panel(dialog)
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            assertFalse(panel.viewTreeObserver.dispatchOnPreDraw())
            assertPanelVisible(dialog)
            val editor = descendants(panel).filterIsInstance<EditText>().single()
            val connection = JsAppInputConnection(editor)
            keyboard = View(service).apply { requestLayout() }

            connection.updateComposition("ni", 2)
            assertEquals("ni", editor.text.toString())
            assertEquals(0, BaseInputConnection.getComposingSpanStart(editor.text))
            editor.requestLayout()
            assertTrue(editor.isLayoutRequested)
            assertFalse(panel.viewTreeObserver.dispatchOnPreDraw())
            assertPanelVisible(dialog)

            // Space commits the selected candidate through this local input connection.
            assertTrue(connection.commitText("你", 1))
            assertEquals("你", editor.text.toString())
            assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
            editor.requestLayout()
            assertFalse(panel.viewTreeObserver.dispatchOnPreDraw())
            assertPanelVisible(dialog)
        } finally {
            dialog.dismiss()
            service.jsAppInputConnection = null
        }
    }

    @Test
    fun assistantDropdownOptionsHaveCompactRowsWithoutClippingText() {
        val spec = bundledAssistantDialog()
        instrumentation.runOnMainSync {
            val service = service()
            val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { null })
            try {
                dialog.render(spec)
                val selects = descendants(requireNotNull(dialog.window).decorView)
                    .filterIsInstance<AutoCompleteTextView>()
                assertEquals(2, selects.size)
                val density = service.resources.displayMetrics.density
                for (select in selects) {
                    val list = ListView(select.context).apply { adapter = select.adapter }
                    list.measure(
                        View.MeasureSpec.makeMeasureSpec((200 * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.AT_MOST)
                    )
                    list.layout(0, 0, list.measuredWidth, list.measuredHeight)
                    assertTrue(list.childCount > 1)
                    for (index in 0 until list.childCount) {
                        val row = list.getChildAt(index) as TextView
                        assertTrue("Option ${row.text} is too tall: ${row.height / density}dp",
                            row.height <= 44 * density)
                        assertTrue("Option ${row.text} clips its text",
                            row.height >= row.lineHeight + row.paddingTop + row.paddingBottom)
                    }
                }
            } finally {
                dialog.dismiss()
                service.jsAppInputConnection = null
            }
        }
    }

    @Test
    fun bundledAssistantHeaderRendersAndClosesWhileBusy() {
        val spec = bundledAssistantDialog()
        instrumentation.runOnMainSync {
            val service = service()
            var closed = 0
            lateinit var dialog: JsAppDialog
            dialog = JsAppDialog(service, { name, _ ->
                if (name == "close") dialog.dismiss()
            }, { closed++ }, keyboardView = { null })
            try {
                dialog.render(spec)
                dialog.setBusy(true)
                val views = descendants(requireNotNull(dialog.window).decorView)
                val heading = views.filterIsInstance<TextView>().first { it.text.toString() == "AI 助手" }
                val header = heading.parent as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL, header.orientation)
                assertEquals(1f, (heading.layoutParams as LinearLayout.LayoutParams).weight, 0f)
                val form = header.parent as LinearLayout
                val actions = form.getChildAt(form.childCount - 1) as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL, actions.orientation)
                assertEquals(Gravity.CENTER_VERTICAL or Gravity.END, actions.gravity)
                assertEquals(2, descendants(actions).filterIsInstance<Button>().size)
                val close = descendants(header).filterIsInstance<Button>().single()
                assertTrue(close.isEnabled)
                assertTrue(close.performClick())
                assertEquals(1, closed)
            } finally {
                dialog.dismiss()
                service.jsAppInputConnection = null
            }
            assertEquals(1, closed)
        }
    }

    @Test
    fun bundledAssistantResultRendersReadOnlyOutputAndConfirmationActions() {
        val spec = bundledAssistantDialog("generated answer")
        instrumentation.runOnMainSync {
            val service = service()
            val dialog = JsAppDialog(service, { _, _ -> }, {}, keyboardView = { null })
            try {
                dialog.render(spec)
                val views = descendants(requireNotNull(dialog.window).decorView)
                val result = views.filterIsInstance<EditText>().single { it.text.toString() == "generated answer" }
                assertNull(result.keyListener)
                val heading = views.filterIsInstance<TextView>().first { it.text.toString() == "AI 助手" }
                val form = heading.parent.parent as LinearLayout
                val actions = form.getChildAt(form.childCount - 1) as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL, actions.orientation)
                assertEquals(3, descendants(actions).filterIsInstance<Button>().size)
            } finally {
                dialog.dismiss()
                service.jsAppInputConnection = null
            }
        }
    }

    @Test
    fun failedInitialLayoutDismissesInsteadOfLeavingAnErrorOnlyPanel() = instrumentation.runOnMainSync {
        val service = service()
        var closed = 0
        val dialog = JsAppDialog(service, { _, _ -> }, { closed++ }, keyboardView = { null })
        try {
            val failure = runCatching {
                dialog.render(Json.parseToJsonElement("""{"layout":{"type":"unsupported"}}""").jsonObject)
            }.exceptionOrNull()
            assertEquals("Unsupported layout node", failure?.message)
            dialog.showError(requireNotNull(failure).message.orEmpty())
            assertEquals(1, closed)
            assertFalse(dialog.isShowing)
            dialog.showError("A late error must not revive the dismissed panel")
            assertEquals(1, closed)
        } finally {
            dialog.dismiss()
            service.jsAppInputConnection = null
        }
    }

    @Test
    fun initializationFailureDismissesAnEmptyDialog() = instrumentation.runOnMainSync {
        val service = service()
        var closed = 0
        val dialog = JsAppDialog(service, { _, _ -> }, { closed++ }, keyboardView = { null })
        try {
            dialog.showError("Plugin initialization failed")
            assertEquals(1, closed)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
        }
    }

    @Test
    fun errorInRenderedDialogKeepsTheCloseControlAvailable() = instrumentation.runOnMainSync {
        val service = service()
        var closed = 0
        lateinit var dialog: JsAppDialog
        dialog = JsAppDialog(service, { name, _ ->
            if (name == "close") dialog.dismiss()
        }, { closed++ }, keyboardView = { null })
        try {
            dialog.render(Json.parseToJsonElement("""{
                "layout":{"type":"row","children":[{"type":"button","id":"close","label":"Close"}]}
            }""").jsonObject)
            dialog.showError("Request failed")
            assertEquals(0, closed)
            val views = descendants(requireNotNull(dialog.window).decorView)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Request failed" })
            assertTrue(views.filterIsInstance<Button>().single().performClick())
            assertEquals(1, closed)
        } finally {
            dialog.dismiss()
            service.jsAppInputConnection = null
        }
    }

    private fun editorDialog() = Json.parseToJsonElement("""{
        "layout":{"type":"input","id":"text","multiline":true}
    }""").jsonObject

    private fun sliderDialog() = Json.parseToJsonElement("""{
        "layout":{"type":"column","children":[
            {"type":"slider","id":"reasoning","label":"思考档位","value":"none","options":[
                {"value":"none","label":"关闭"},{"value":"low","label":"低"},
                {"value":"medium","label":"中"},{"value":"high","label":"高"}
            ]},
            {"type":"button","id":"send","label":"发送"}
        ]}
    }""").jsonObject

    private fun panel(dialog: JsAppDialog): View = requireNotNull(dialog.window).decorView
        .findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun assertPanelVisible(dialog: JsAppDialog) {
        val attributes = requireNotNull(dialog.window).attributes
        assertEquals("The panel must remain opaque", 1f, attributes.alpha, 0f)
        assertEquals("The panel must remain touchable", 0,
            attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    private fun bundledAssistantDialog(result: String = ""): JsonObject = runBlocking {
        val script = instrumentation.targetContext.assets.open("bundled/js-apps/llm-assistant.js")
            .bufferedReader().use { it.readText() }
        val js = JsRuntime.create()
        try {
            JsStubNatives.install(js, "zh-CN")
            JsRuntime.initialize(js, JsPrelude.SOURCE + "\n" + script)
            js.evaluate<Any?>("lastResult = " + JsonPrimitive(result))
            Json.parseToJsonElement(js.evaluate<String>(
                "JSON.stringify(dialogSpec(stringsFor('zh-CN'), 'AI 助手', 'short', 'selected text'))"
            )).jsonObject
        } finally {
            js.close()
        }
    }

    private fun service() = FcitxInputMethodService().apply {
        // Render without starting a second system IME or changing the user's selected keyboard.
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(this, instrumentation.targetContext)
    }

    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(descendants(view.getChildAt(index)))
    }
}
