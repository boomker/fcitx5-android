/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinLayoutVariants
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TextKeyboardLayoutEditorActivityTest {
    @Test fun compactBrowsingDoesNotCreateADedicatedLayout() = withEditor { activity, file ->
        set(activity, "selectedLayoutVariant", ShuangpinKeyboardLayout.Compact18)
        selectDraft(activity, "shuangpin-xiaohe", null)
        repeat(2) { invoke(activity, "updateSaveButtonState") }
        assertFalse(invoke(activity, "hasChanges") as Boolean)
        assertFalse(manager(activity).entries.containsKey(ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe")))
        assertEquals(18, rows(activity).flatten().count { it["type"] == "ShuangpinGroupKey" })
        assertFalse(LayoutDataManager(activity).apply { loadFromFile(file) }.entries.keys.any(ShuangpinLayoutVariants::isCompactEntry))
    }

    @Test fun sixColumnInheritedEditRoundTripsWithoutChangingParentOrFullLayout() = withEditor { activity, file ->
        val data = manager(activity)
        val parentKey = ShuangpinLayoutVariants.entryKey("shuangpin")
        val ownKey = ShuangpinLayoutVariants.entryKey("shuangpin-xiaohe")
        data.entries[parentKey] = data.compactDefaultRows(sixColumns = true)
        assertTrue(invoke(activity, "saveLayout") as Boolean)
        val parentBefore = data.copyLayout(data.entries.getValue(parentKey))
        val fullBefore = data.copyLayout(data.entries.getValue("default"))
        set(activity, "selectedLayoutVariant", ShuangpinKeyboardLayout.Compact18)
        selectDraft(activity, "shuangpin-xiaohe", null)
        assertEquals(listOf(6, 6, 6), rows(activity).take(3).map { it.size })
        assertFalse(invoke(activity, "hasChanges") as Boolean)
        val edited = rows(activity)
        val we = edited[0][1]
        edited[0][1] = edited[2][4]
        edited[2][4] = we
        edited[2][4]["displayText"] = "我的分组"
        assertTrue(invoke(activity, "saveLayout") as Boolean)
        val reloaded = LayoutDataManager(activity).apply { loadFromFile(file) }
        assertEquals("we", reloaded.entries.getValue(ownKey)[2][4]["groupId"])
        assertEquals("我的分组", reloaded.entries.getValue(ownKey)[2][4]["displayText"])
        assertEquals(listOf(6, 6, 6), reloaded.entries.getValue(ownKey).take(3).map { it.size })
        assertEquals(parentBefore, data.entries[parentKey])
        assertEquals(fullBefore, data.entries["default"])
    }

    @Test fun missingBaseIsVisibleAndBrowsingDoesNotMaterializeIt() = withEditor { activity, file ->
        set(activity, "allImesFromJson", arrayOf(InputMethodEntry("test-ime", "Test IME", "", "", "", "zh", "test-ime", false)))
        assertTrue((invoke(activity, "visibleBaseLayoutKeys") as List<*>).contains("test-ime"))
        selectDraft(activity, "test-ime", null)
        repeat(2) { invoke(activity, "updateSaveButtonState") }
        assertFalse(invoke(activity, "hasChanges") as Boolean)
        assertFalse(manager(activity).entries.containsKey("test-ime"))
        assertFalse(LayoutDataManager(activity).apply { loadFromFile(file) }.entries.containsKey("test-ime"))
    }

    @Test fun dropdownHidesDisabledPresetsAndKeepsCustomizedLayoutsWithoutWriting() = withEditor { activity, file ->
        // Exercise the picker, independently of the device's active IME and
        // the direct-parent editing mode that deliberately hides this spinner.
        set(activity, "targetForceBase", false)
        set(activity, "currentLayout", "default")
        val data = manager(activity)
        val originalFile = file.readText()
        for (base in listOf("pinyin", "wubi", "custom-keys", "custom-height")) {
            data.entries[base] = data.copyLayout(data.entries.getValue("default"))
        }
        data.entries.getValue("custom-keys")[0][0]["main"] = "changed"
        data.setLayoutHeightPercentOverride("custom-height",
            LayoutHeightPercentOverrides(43, 29))
        data.entries["child-only:child"] = data.copyLayout(data.entries.getValue("default"))
        val originalData = data.exportCurrentJsonString()
        val english = InputMethodEntry("keyboard-us", "English", "", "", "", "en", "androidkeyboard", false)
        set(activity, "allImesFromJson", arrayOf(english))
        val visible = invoke(activity, "visibleBaseLayoutKeys") as List<*>
        assertEquals(listOf("default", "custom-keys", "custom-height", "child-only"), visible)
        assertTrue("pinyin" in data.baseLayoutNames()) // Source lookup still sees stored, hidden groups.
        invoke(activity, "buildSpinner")
        // layoutSpinner 是 `by lazy` 属性，其 JVM 字段名带 $delegate 后缀，
        // 需通过生成的 getLayoutSpinner() 访问器读取，而不是反射字段。
        val spinner = invoke(activity, "getLayoutSpinner") as android.widget.Spinner
        val items = (0 until spinner.adapter.count).map { spinner.adapter.getItem(it).toString() }
        assertEquals(listOf("English", "custom-keys", "custom-height", "child-only"), items)
        assertEquals(originalData, data.exportCurrentJsonString())
        assertEquals(originalFile, file.readText())
    }

    @Test fun shuangpinParentIsOmittedFromLayoutSpinner() = withEditor { activity, _ ->
        set(activity, "targetForceBase", false)
        set(activity, "currentLayout", "default")
        val data = manager(activity)
        data.entries["shuangpin"] = data.copyLayout(data.entries.getValue("default"))
        set(activity, "allImesFromJson", arrayOf(
            InputMethodEntry("keyboard-us", "English", "", "", "", "en", "androidkeyboard", false),
            InputMethodEntry("shuangpin-xiaohe", "小鹤双拼", "", "", "", "zh", "pinyin", false),
            InputMethodEntry("shuangpin-ms", "微软双拼", "", "", "", "zh", "pinyin", false)
        ))

        invoke(activity, "buildSpinner")
        val spinner = invoke(activity, "getLayoutSpinner") as android.widget.Spinner
        val items = (0 until spinner.adapter.count).map { spinner.adapter.getItem(it).toString() }

        assertEquals(listOf("English", "小鹤双拼", "微软双拼"), items)
        assertFalse("双拼" in items)
    }

    @Test fun directShuangpinParentEditKeepsTargetWithoutShowingCategorySpinner() = withEditor { activity, _ ->
        set(activity, "targetForceBase", true)
        set(activity, "currentLayout", "shuangpin")

        invoke(activity, "buildSpinner")

        assertEquals("shuangpin", get(activity, "currentLayout"))
        val spinnerContainer = invoke(activity, "getSpinnerContainer") as android.view.View
        assertEquals(android.view.View.GONE, spinnerContainer.visibility)
    }

    @Test fun menuRefreshDoesNotLoseLaterBaseOrSubmodeEdits() = withEditor { activity, file ->
        // Exercise both virtual bases and inherited non-Rime submodes through the same draft lifecycle.
        for ((base, submode) in listOf(
            "test-ime" to null,
            "default" to "test-mode",
            "child-only-ime" to "child"
        )) {
            val key = if (submode == null) base else "$base:$submode"
            val originalBase = manager(activity).copyLayout(manager(activity).entries.getValue("default"))
            selectDraft(activity, base, submode)
            repeat(2) { invoke(activity, "updateSaveButtonState") }
            assertFalse("Menu preparation must not make the file dirty", invoke(activity, "hasChanges") as Boolean)
            assertEquals(key, get(activity, "bufferedLayoutKey"))
            rows(activity)[0][0]["main"] = "x"
            assertTrue("A later edit must still register the draft", invoke(activity, "hasChanges") as Boolean)
            assertEquals(originalBase, manager(activity).entries.getValue("default"))
            assertEquals("x", manager(activity).entries.getValue(key)[0][0]["main"])
            assertTrue(invoke(activity, "saveLayout") as Boolean)
            val reloaded = LayoutDataManager(activity).apply { loadFromFile(file) }
            assertEquals("x", reloaded.entries.getValue(key)[0][0]["main"])
            if (base == "child-only-ime") assertFalse(reloaded.entries.containsKey(base))
        }
    }

    @Test fun invalidInheritedRowsRecoverWithoutMaterializingTheAbandonedDraft() = withEditor { activity, _ ->
        val data = manager(activity)
        data.entries["healthy"] = data.copyLayout(data.entries.getValue("default"))
        data.entries["default"] = mutableListOf()
        // 「双拼」父级输入法布局缺失有效行 → 其下属方案（小鹤双拼）继承不到任何行。
        // 注意：未定制布局的输入法会回退出厂 26 键预设，因此要让继承链真正为空，
        // 必须让父级条目存在但内容为空（而不是让条目缺失）。
        data.entries["shuangpin"] = mutableListOf()
        // "healthy" 的行与出厂预设一致，只有作为已启用输入法才可见，才能成为回退目标。
        set(activity, "allImesFromJson", arrayOf(InputMethodEntry("healthy", "Healthy", "", "", "", "en", "test", false)))
        selectDraft(activity, "shuangpin-xiaohe", null)
        // 继承到无效行时回退到第一个有效父级输入法布局，且不把被放弃的草稿落盘
        assertEquals("healthy", get(activity, "currentLayout"))
        assertNull(get(activity, "bufferedLayoutKey"))
        assertFalse(data.entries.containsKey("shuangpin-xiaohe"))
    }

    @Test fun emptyChildRecoversToBaseWithoutReselectingTheEmptyChild() = withEditor { activity, _ ->
        val data = manager(activity)
        data.entries["healthy"] = data.copyLayout(data.entries.getValue("default"))
        data.entries["default"] = mutableListOf()
        data.entries["healthy:bad"] = mutableListOf()
        set(activity, "targetForceBase", false)
        selectDraft(activity, "healthy", "bad")
        assertEquals("healthy", get(activity, "currentLayout"))
        assertNull(get(activity, "previewSubModeLabel"))
        assertTrue(data.entries.getValue("healthy:bad").isEmpty())
    }

    @Test fun profileReloadDiscardsDraftWithoutLeakingItIntoNextProfile() = withEditor { activity, _ ->
        val secondProfile = "layout-editor-test-${UUID.randomUUID()}"
        val secondFile = requireNotNull(UserConfigFiles.textKeyboardLayoutJson(secondProfile))
        try {
            secondFile.writeText(manager(activity).exportCurrentJsonString())
            selectDraft(activity, "default", "discarded-mode")
            rows(activity)[0][0]["main"] = "discard-me"
            val switch = activity.javaClass.getDeclaredMethod("switchLayoutProfile", String::class.java)
            switch.isAccessible = true
            switch.invoke(activity, secondProfile)
            assertFalse(manager(activity).entries.containsKey("default:discarded-mode"))
            assertFalse(invoke(activity, "hasChanges") as Boolean)
        } finally {
            removeTestFile(secondFile)
        }
    }

    @Test fun firstEditOfShuangpinSchemeCarriesOverInheritedMacroKeyAndFrozenHeight() = withEditor { activity, file ->
        val data = manager(activity)
        // 定制「双拼」父级输入法布局：把一个字母键换成宏按键，并设置高度覆写
        val macroKey = LayoutJsonUtils.parseLayoutRows(
            Json.parseToJsonElement(
                """[[{"type":"MacroKey","label":"宏","tap":{"macro":[{"type":"text","text":"hi"}]}}]]"""
            ).jsonArray
        ).first().first().toMutableMap()
        val base = data.copyLayout(data.entries.getValue("default"))
        val rowIndex = base.indexOfFirst { row -> row.any { it["type"] == "AlphabetKey" } }
        val keyIndex = base[rowIndex].indexOfFirst { it["type"] == "AlphabetKey" }
        base[rowIndex][keyIndex] = macroKey
        data.entries["shuangpin"] = base
        data.setLayoutHeightPercentOverride("shuangpin", LayoutHeightPercentOverrides(43, 29))

        // 首次编辑「小鹤双拼」：草稿整份继承父级输入法布局，宏按键一并带过来
        selectDraft(activity, "shuangpin-xiaohe", null)
        assertEquals("shuangpin-xiaohe", get(activity, "bufferedLayoutKey"))
        assertEquals("MacroKey", rows(activity)[rowIndex][keyIndex]["type"])
        rows(activity)[rowIndex][keyIndex]["label"] = "改"
        assertTrue(invoke(activity, "hasChanges") as Boolean)
        assertTrue(invoke(activity, "saveLayout") as Boolean)

        val reloaded = LayoutDataManager(activity).apply { loadFromFile(file) }
        // 首次编辑后，方案专属布局沿用了继承来的宏设定（不只是高度）
        val schemeMacro = reloaded.entries.getValue("shuangpin-xiaohe").flatten()
            .first { it["type"] == "MacroKey" }
        assertEquals("改", schemeMacro["label"])
        assertNotNull(schemeMacro["tap"])
        // 继承到的高度被一并固化，不再仅靠「双拼」父级输入法布局兜底
        assertEquals(
            LayoutHeightPercentOverrides(43, 29),
            reloaded.getLayoutHeightPercentOverride("shuangpin-xiaohe")
        )
        // 父级输入法布局本身未被方案的编辑改动
        val baseMacro = reloaded.entries.getValue("shuangpin").flatten()
            .first { it["type"] == "MacroKey" }
        assertEquals("宏", baseMacro["label"])
    }

    private fun selectDraft(activity: TextKeyboardLayoutEditorActivity, base: String, submode: String?) {
        set(activity, "currentLayout", base)
        set(activity, "previewSubModeLabel", submode)
        invoke(activity, "buildRows")
        assertTrue(rows(activity).isNotEmpty())
    }

    private fun withEditor(block: (TextKeyboardLayoutEditorActivity, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profile = "layout-editor-test-${UUID.randomUUID()}"
        val file = requireNotNull(UserConfigFiles.textKeyboardLayoutJson(profile))
        val data = LayoutDataManager(instrumentation.targetContext).apply { loadFromFile(null) }
        file.parentFile?.mkdirs()
        file.writeText(data.exportCurrentJsonString())
        var activity: TextKeyboardLayoutEditorActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                TextKeyboardLayoutEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_PROFILE, profile)
                putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_LAYOUT, "default")
            }) as TextKeyboardLayoutEditorActivity
            instrumentation.waitForIdleSync()
            val editor = activity
            instrumentation.runOnMainSync { block(editor, file) }
        } finally {
            activity?.let { editor -> instrumentation.runOnMainSync { editor.finish() } }
            instrumentation.waitForIdleSync()
            removeTestFile(file)
        }
    }

    private fun removeTestFile(file: File) {
        file.delete()
        file.parentFile?.listFiles { candidate -> candidate.name.startsWith("${file.nameWithoutExtension}_backup_") }
            ?.forEach { it.delete() }
    }

    private fun manager(activity: TextKeyboardLayoutEditorActivity) = get(activity, "dataManager") as LayoutDataManager

    @Suppress("UNCHECKED_CAST")
    private fun rows(activity: TextKeyboardLayoutEditorActivity) =
        get(activity, "currentRowsRef") as MutableList<MutableList<MutableMap<String, Any?>>>

    private fun get(activity: TextKeyboardLayoutEditorActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun set(activity: TextKeyboardLayoutEditorActivity, name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private fun invoke(activity: TextKeyboardLayoutEditorActivity, name: String): Any? =
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
}
