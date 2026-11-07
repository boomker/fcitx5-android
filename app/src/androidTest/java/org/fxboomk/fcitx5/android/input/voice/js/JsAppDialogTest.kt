/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
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

    private fun bundledAssistantDialog(result: String = ""): JsonObject = runBlocking {
        val script = instrumentation.targetContext.assets.open("bundled/js-apps/llm-assistant.js")
            .bufferedReader().use { it.readText() }
        val js = JsVoiceRuntime.create()
        try {
            JsVoiceStubNatives.install(js, "zh-CN")
            JsVoiceRuntime.initialize(js, JsVoicePrelude.SOURCE + "\n" + script)
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
