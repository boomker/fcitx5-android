/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.Action
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import splitties.resources.styledColor
import java.io.File
import java.util.UUID

class TextKeyboardLayoutProfileManagerActivityTest {
    @Test
    fun activeLayoutRefreshesWhenReturningAfterImeSwitch() = withProfiles { profiles, files ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connectionName = "LayoutProfileManagerTest"
        val connection = FcitxDaemon.connect(connectionName)
        var originalIme: InputMethodEntry? = null
        try {
            withManager { activity ->
                val original = connection.runImmediately { currentIme() }
                val originalActions = connection.runImmediately { statusArea() }
                originalIme = original
                // currentIme() only resolves while the fcitx5 IME is the OS-active
                // input method with a live input context. Otherwise the daemon
                // reports its "not available" placeholder (empty uniqueName) and
                // activateIme() cannot produce an observable IMChangeEvent.
                assumeTrue(
                    "Needs fcitx5 as the active IME with a live input context",
                    original.uniqueName.isNotEmpty()
                )
                val other = connection.runImmediately { enabledIme() }.firstOrNull { it.uniqueName != original.uniqueName }
                assumeTrue("Needs two enabled IMEs to exercise a real switch", other != null)
                val target = requireNotNull(other)
                val manager = LayoutDataManager(activity).apply {
                    loadFromFile(files.first())
                    for (ime in listOf(original, target)) {
                        entries[ime.uniqueName] = defaultPresetSnapshot().map { row ->
                            row.map { it.toMutableMap() }.toMutableList()
                        }.toMutableList()
                    }
                }
                files.first().writeText(manager.exportCurrentJsonString())
                instrumentation.callActivityOnPause(activity)
                instrumentation.callActivityOnResume(activity)
                assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text.toString() == expectedChain(activity, profiles.first(), original, originalActions)
                })
                instrumentation.callActivityOnPause(activity)
                connection.runImmediately { activateIme(target.uniqueName) }
                val current = connection.runImmediately { currentIme() }
                val currentActions = connection.runImmediately { statusArea() }
                assertEquals(target.uniqueName, current.uniqueName)
                instrumentation.callActivityOnResume(activity)
                assertTrue("Resuming must show the new active IME", descendants(activity.window.decorView)
                    .filterIsInstance<TextView>().any { it.text.toString() == expectedChain(activity, profiles.first(), current, currentActions) })
            }
        } finally {
            originalIme?.let { original -> connection.runImmediately { activateIme(original.uniqueName) } }
            FcitxDaemon.disconnect(connectionName)
        }
    }

    @Test
    fun childOnlyAndUnmatchedSiblingSummariesBothUseActiveImeName() = withProfiles { profiles, _ ->
        withManager { activity ->
            val ime = InputMethodEntry("child-only", "Child Only", "", "", "", "en", "test", false,
                "custom", "custom", "")
            setPrivateField(activity, "allImes", arrayOf(ime))
            setPrivateField(activity, "allAvailableImes", arrayOf(ime))
            setPrivateField(activity, "activeIme", ime)
            val manager = invokePrivate(activity, "managerFor", profiles.first()) as LayoutDataManager
            manager.entries["child-only:custom"] = manager.copyLayout(manager.entries.getValue("default"))
            assertEquals("${profiles.first()}-Child Only", invokePrivate(activity, "activeLayoutChain"))
            setPrivateField(activity, "activeIme", ime.copy(subMode = ime.subMode.copy(label = "sibling")))
            assertEquals("${profiles.first()}-Child Only", invokePrivate(activity, "activeLayoutChain"))
            assertFalse(manager.entries.containsKey("child-only"))
        }
    }

    @Test
    fun activeIdentityAndRimeSchemaAreShownWithoutCustomLayouts() = withProfiles { profiles, files ->
        val originals = files.associateWith { it.readText() }
        withManager { activity ->
            val manager = invokePrivate(activity, "managerFor", profiles.first()) as LayoutDataManager
            val before = manager.exportCurrentJsonString()
            val shuangpin = InputMethodEntry("shuangpin", "双拼", "", "", "", "zh", "pinyin", false)
            setPrivateField(activity, "activeIme", shuangpin)
            assertEquals("${profiles.first()}-双拼-${variantLabel(activity, "shuangpin")}", invokePrivate(activity, "activeLayoutChain"))
            // A scheme is a child of the 「双拼」 parent layout, so the parent shows up too.
            setPrivateField(activity, "activeIme",
                InputMethodEntry("shuangpin-xiaohe", "小鹤双拼", "", "", "", "zh", "pinyin", false))
            assertEquals("${profiles.first()}-双拼-小鹤双拼-${variantLabel(activity, "shuangpin-xiaohe")}", invokePrivate(activity, "activeLayoutChain"))
            val rime = InputMethodEntry("rime", "中州韵", "", "", "", "zh", "rime", false,
                "Latin Mode", "abc", "")
            setPrivateField(activity, "activeStatusActions", arrayOf(
                Action(1, false, false, false, "fcitx-rime-im", "", "A", "朙月拼音", null)
            ))
            setPrivateField(activity, "activeIme", rime)
            assertEquals("${profiles.first()}-中州韵-朙月拼音", invokePrivate(activity, "activeLayoutChain"))
            invokePrivate(activity, "updateProfileSummary")
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == "${profiles.first()}-中州韵-朙月拼音" && it.visibility == View.VISIBLE
            })
            setPrivateField(activity, "activeIme", null)
            invokePrivate(activity, "updateProfileSummary")
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == "${profiles.first()}-中州韵-朙月拼音" && it.visibility == View.GONE
            })
            assertEquals(before, manager.exportCurrentJsonString())
        }
        originals.forEach { (file, original) -> assertEquals(original, file.readText()) }
    }

    @Test
    fun openingAndFilteringProfilesPreservesDefaultFallback() = withProfiles { profiles, files ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = LayoutDataManager(instrumentation.targetContext).apply {
            loadFromFile(null)
            entries.getValue("default")[0][0]["main"] = "z"
            setLayoutHeightPercentOverride("default", LayoutHeightPercentOverrides(43, 29))
        }
        files.first().writeText(manager.exportCurrentJsonString())
        val original = files.first().readText()
        withManager { activity -> filterButton(activity, profiles.first()).performClick() }
        withManager { activity -> filterButton(activity, profiles.first()).performClick() }
        assertEquals("Viewing a profile must not materialize IME-specific layouts", original, files.first().readText())
    }

    @Test
    fun enabledImeWithoutStoredBaseIsFilterableWithoutMaterializingLayout() = withProfiles { profiles, files ->
        val baseLayout = "missing-base-${UUID.randomUUID()}"
        val displayName = "Missing Base ${UUID.randomUUID()}"
        val ime = InputMethodEntry(baseLayout, displayName, "", "", "", "en", "test", false)
        val originals = files.associateWith { it.readText() }
        withManager { activity ->
            setPrivateField(activity, "allImes", arrayOf(ime))
            setPrivateField(activity, "allAvailableImes", arrayOf(ime))
            invokePrivate(activity, "render")

            val expected = activity.getString(
                R.string.text_keyboard_layout_manage_submode_uncustomized,
                displayName
            )
            assertTrue(expected in profileTexts(activity, profiles.first()))
            filterButton(activity, profiles.first()).performClick()
            assertFalse(expected in profileTexts(activity, profiles.first()))
        }
        originals.forEach { (file, original) ->
            assertEquals("Rendering and filtering a missing base must not persist it", original, file.readText())
        }
    }

    @Test
    fun disabledPresetsAreHiddenButCustomizationsAndChildrenRemain() = withProfiles { profiles, files ->
        val originals = files.associateWith { it.readText() }
        withManager { activity ->
            val english = InputMethodEntry("keyboard-us", "English", "", "", "", "en", "androidkeyboard", false)
            setPrivateField(activity, "allImes", arrayOf(english))
            setPrivateField(activity, "allAvailableImes", arrayOf(english))
            invokePrivate(activity, "render")
            val texts = profileTexts(activity, profiles.first())
            val englishLabel = activity.getString(R.string.text_keyboard_layout_manage_submode_uncustomized, "English")
            assertEquals(1, texts.count { it == englishLabel })
            for (base in listOf("test-factory", "pinyin", "wubi")) {
                assertFalse(activity.getString(R.string.text_keyboard_layout_manage_submode_uncustomized, base) in texts)
            }
            assertTrue("test-height" in texts)
            assertTrue("• child" in texts)
        }
        originals.forEach { (file, original) -> assertEquals(original, file.readText()) }
    }

    @Test
    fun profileFilterPreservesCustomizationsAndSurvivesReopening() = withProfiles { profiles, _ ->
        val first = profiles.first()
        val second = profiles.last()
        withManager { activity ->
            val factoryIme = InputMethodEntry("test-factory", "test-factory", "", "", "", "en", "test", false)
            setPrivateField(activity, "allImes", arrayOf(factoryIme))
            invokePrivate(activity, "render")
            val factoryLabel = activity.getString(R.string.text_keyboard_layout_manage_submode_uncustomized, "test-factory")
            assertTrue(factoryLabel in profileTexts(activity, first))
            val filter = filterButton(activity, first)
            val row = filter.parent as ViewGroup
            val moveUp = descendants(row).filterIsInstance<ImageButton>().single {
                it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_move_up)
            }
            assertEquals(row.indexOfChild(moveUp) + 1, row.indexOfChild(filter))
            filter.performClick()
            assertFalse(factoryLabel in profileTexts(activity, first))
            assertTrue(factoryLabel in profileTexts(activity, second))
            assertTrue("test-height" in profileTexts(activity, first))
            assertTrue("• child" in profileTexts(activity, first))
            val heightRow = profileViews(activity, first).single { view ->
                descendants(view).filterIsInstance<TextView>().any { it.text.toString() == "test-height" }
            }
            assertTrue(descendants(heightRow).filterIsInstance<ImageButton>().any {
                it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_base_reset_action)
            })
            assertTrue(filterButton(activity, first).isActivated)
        }
        withManager { activity ->
            val factoryIme = InputMethodEntry("test-factory", "test-factory", "", "", "", "en", "test", false)
            setPrivateField(activity, "allImes", arrayOf(factoryIme))
            invokePrivate(activity, "render")
            val factoryLabel = activity.getString(R.string.text_keyboard_layout_manage_submode_uncustomized, "test-factory")
            assertFalse(factoryLabel in profileTexts(activity, first))
            assertTrue(factoryLabel in profileTexts(activity, second))
            filterButton(activity, first).performClick()
            assertTrue(factoryLabel in profileTexts(activity, first))
            assertFalse(filterButton(activity, first).isActivated)
        }
    }

    @Test
    fun activeFilterUsesContrastingAccentTintInBothThemes() = withProfiles { profiles, _ ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = AppCompatDelegate.getDefaultNightMode()
        try {
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setDefaultNightMode(mode)
                    AppPrefs.getInstance().keyboard.textKeyboardLayoutProfileFiltered.setValue(profiles.first())
                }
                withManager { activity ->
                    val filter = filterButton(activity, profiles.first())
                    val tint = requireNotNull(filter.imageTintList).defaultColor
                    val accent = activity.styledColor(android.R.attr.colorAccent)
                    val background = activity.styledColor(android.R.attr.colorBackground)
                    assertTrue(filter.isActivated)
                    assertEquals(accent, tint)
                    assertTrue(
                        "Active filter tint must remain distinguishable from the activity background",
                        ColorUtils.calculateContrast(tint, background) >= 3.0
                    )
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(originalMode) }
        }
    }

    @Test
    fun rimeFallbackRowsRetainIdentityAndFactoryStateWhenEngineLabelsAreEmpty() {
        val profile = "rime-fallback-${UUID.randomUUID()}"
        val baseLayout = "rime"
        val schema = "test-schema"
        val key = "$baseLayout:$schema"
        withManager { activity ->
            val manager = LayoutDataManager(activity).apply {
                loadFromFile(null)
                entries[key] = defaultPresetSnapshot().map { row ->
                    row.map { it.toMutableMap() }.toMutableList()
                }.toMutableList()
            }
            rimeSchemaLabelsCache(activity)["$profile:$baseLayout"] = emptyList()

            val rows = invokePrivate(
                activity, "subLayoutRows", profile, manager, baseLayout, childLayoutKind("RIME")
            ) as List<*>
            val row = requireNotNull(rows.single())
            assertEquals(schema, privateField(row, "displayName"))
            assertEquals(key, privateField(row, "storedKey"))
            assertEquals("UNCUSTOMIZED", privateField(row, "state").toString())
            assertEquals("RIME", privateField(row, "kind").toString())
        }
    }

    /** 反射获取私有的 ChildLayoutKind 枚举常量，用于直接调用私有 subLayoutRows。 */
    private fun childLayoutKind(name: String): Any =
        Class.forName(
            "org.fxboomk.fcitx5.android.ui.main.settings.behavior" +
                ".TextKeyboardLayoutProfileManagerActivity\$ChildLayoutKind"
        ).enumConstants.orEmpty().first { it.toString() == name }

    @Test
    fun shuangpinChildrenOnlyListSchemesEnabledInInputMethodList() = withManager { activity ->
        val manager = LayoutDataManager(activity).apply { loadFromFile(null) }
        val enabled = arrayOf(
            InputMethodEntry("shuangpin-xiaohe", "小鹤双拼", "", "", "", "zh", "pinyin", false)
        )
        // 未启用（仅可用）的内置双拼不应出现在双拼节点下
        val available = enabled +
            InputMethodEntry("shuangpin-ms", "微软双拼", "", "", "", "zh", "pinyin", false)
        setPrivateField(activity, "allImes", enabled)
        setPrivateField(activity, "allAvailableImes", available)

        val rows = invokePrivate(activity, "shuangpinSubLayoutRows", manager) as List<*>
        val schemeKeys = rows.map { privateField(requireNotNull(it), "editLayoutKey") }
        assertEquals(listOf("shuangpin-xiaohe"), schemeKeys)
        assertFalse(schemeKeys.contains("shuangpin-ms"))
    }

    @Test
    fun parentLayoutNodesCollapseAndExpandTheirChildren() = withProfiles { profiles, _ ->
        withManager { activity ->
            val profile = profiles.first()
            val prefs = AppPrefs.getInstance().keyboard
            // 夹具中唯一带专属子布局的父级输入法布局是 test-parent
            assertTrue("Child row visible initially",
                profileTexts(activity, profile).any { it == "• child" })

            baseLayoutToggleButton(activity, profile, "test-parent", collapsed = false).performClick()

            assertFalse("Collapsing a parent layout must hide its child rows",
                profileTexts(activity, profile).any { it == "• child" })
            assertEquals("$profile\ttest-parent", prefs.textKeyboardLayoutBaseCollapsed.getValue())

            baseLayoutToggleButton(activity, profile, "test-parent", collapsed = true).performClick()

            assertTrue("Expanding it again must bring the child rows back",
                profileTexts(activity, profile).any { it == "• child" })
            assertEquals("", prefs.textKeyboardLayoutBaseCollapsed.getValue())
        }
    }

    @Test
    fun resettingBaseLayoutKeepsChildLayoutsAndHeights() = withManager { activity ->
        val manager = LayoutDataManager(activity).apply {
            loadFromFile(null)
            entries["shuangpin"] = copyLayout(entries.getValue("default"))
            entries["shuangpin-xiaohe"] = mutableListOf(
                mutableListOf(mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to "x"))
            )
            entries["rime:custom"] = mutableListOf(
                mutableListOf(mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to "y"))
            )
            setLayoutHeightPercentOverride(
                "shuangpin-xiaohe", LayoutHeightPercentOverrides(portrait = 55, landscape = 56)
            )
            setLayoutHeightPercentOverride("rime:custom", LayoutHeightPercentOverrides(portrait = 61))
        }
        val xiaoheRows = manager.entries.getValue("shuangpin-xiaohe").map { it.map { it.toMap() } }
        val rimeRows = manager.entries.getValue("rime:custom").map { it.map { it.toMap() } }

        manager.deleteBaseLayoutTree("shuangpin")
        manager.deleteBaseLayoutTree("rime")

        // 子布局内容与高度覆写均不受父级输入法布局重置影响
        assertEquals(xiaoheRows, manager.entries.getValue("shuangpin-xiaohe").map { it.map { it.toMap() } })
        assertEquals(rimeRows, manager.entries.getValue("rime:custom").map { it.map { it.toMap() } })
        assertEquals(
            LayoutHeightPercentOverrides(portrait = 55, landscape = 56),
            manager.getLayoutHeightPercentOverride("shuangpin-xiaohe")
        )
        assertEquals(
            LayoutHeightPercentOverrides(portrait = 61),
            manager.getLayoutHeightPercentOverride("rime:custom")
        )
        // 仅父级输入法布局本身被重置为出厂 26 键
        assertEquals(null, manager.getLayoutHeightPercentOverride("shuangpin"))
        assertEquals(null, manager.getLayoutHeightPercentOverride("rime"))
        assertEquals(
            manager.defaultPresetSnapshot(),
            manager.entries.getValue("shuangpin").map { it.map { it.toMap() } }
        )
        assertEquals(
            manager.defaultPresetSnapshot(),
            manager.entries.getValue("rime").map { it.map { it.toMap() } }
        )
    }

    private fun withProfiles(block: (List<String>, List<File>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefs = AppPrefs.getInstance().keyboard
        val preferences = listOf(prefs.textKeyboardLayoutProfile, prefs.textKeyboardLayoutProfileCollapsed,
            prefs.textKeyboardLayoutProfileOrder, prefs.textKeyboardLayoutProfileFiltered,
            prefs.textKeyboardLayoutBaseCollapsed)
        val originalValues = preferences.map { it.getValue() }
        val profiles = List(2) { "layout-filter-test-${UUID.randomUUID()}" }
        val files = profiles.map { requireNotNull(UserConfigFiles.textKeyboardLayoutJson(it)) }
        try {
            val manager = LayoutDataManager(instrumentation.targetContext).apply {
                loadFromFile(null)
                for (key in listOf("test-factory", "test-parent", "test-height", "test-parent:child")) {
                    entries[key] = defaultPresetSnapshot().map { row -> row.map { it.toMutableMap() }.toMutableList() }.toMutableList()
                }
                entries.getValue("test-parent:child")[0][0]["main"] = "z"
                setLayoutHeightPercentOverride("test-height", LayoutHeightPercentOverrides(43, 29))
            }
            files.forEach { it.parentFile?.mkdirs(); it.writeText(manager.exportCurrentJsonString()) }
            instrumentation.runOnMainSync {
                prefs.textKeyboardLayoutProfile.setValue(profiles.first())
                prefs.textKeyboardLayoutProfileCollapsed.setValue("")
                prefs.textKeyboardLayoutProfileOrder.setValue(profiles.joinToString("\n"))
                prefs.textKeyboardLayoutProfileFiltered.setValue("")
                prefs.textKeyboardLayoutBaseCollapsed.setValue("")
            }
            block(profiles, files)
        } finally {
            instrumentation.runOnMainSync {
                preferences.zip(originalValues).forEach { (preference, value) -> preference.setValue(value) }
            }
            files.forEach { file ->
                file.delete()
                file.parentFile?.listFiles { candidate -> candidate.name.startsWith("${file.nameWithoutExtension}_backup_") }
                    ?.forEach { it.delete() }
            }
        }
    }

    private fun withManager(block: (TextKeyboardLayoutProfileManagerActivity) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            TextKeyboardLayoutProfileManagerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            as TextKeyboardLayoutProfileManagerActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { block(activity) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun profileRow(activity: TextKeyboardLayoutProfileManagerActivity, profile: String): ViewGroup =
        descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == profile }.parent as ViewGroup

    private fun filterButton(activity: TextKeyboardLayoutProfileManagerActivity, profile: String): ImageButton =
        descendants(profileRow(activity, profile)).filterIsInstance<ImageButton>().single {
            it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_show_uncustomized) ||
                it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_hide_uncustomized)
        }

    /**
     * 与 activity 内部的激活链渲染完全同源的期望值：除 [formatActiveLayoutChain] 外，
     * 还带上它传入的本地化父级内部输入法布局名（双拼方案才会用上）。
     */
    private fun expectedChain(
        activity: TextKeyboardLayoutProfileManagerActivity,
        profile: String,
        ime: InputMethodEntry,
        actions: Array<Action>
    ): String? = formatActiveLayoutChain(
        profile, ime, actions,
        invokePrivate(activity, "baseLayoutDisplayName", ShuangpinLayoutKeys.BASE_LAYOUT_KEY) as String,
        ime.uniqueName.takeIf(ShuangpinKeyboardLayouts::supports)?.let { variantLabel(activity, it) },
    )

    private fun variantLabel(activity: TextKeyboardLayoutProfileManagerActivity, uniqueName: String): String =
        activity.getString(if (ShuangpinKeyboardLayouts.read(uniqueName) == ShuangpinKeyboardLayout.Compact18) {
            R.string.shuangpin_keyboard_layout_compact18
        } else R.string.shuangpin_keyboard_layout_full)

    /** 某父级输入法布局行右侧的展开/折叠按钮（[base] 为其中的可辨识文本片段）。 */
    private fun baseLayoutToggleButton(
        activity: TextKeyboardLayoutProfileManagerActivity,
        profile: String,
        base: String,
        collapsed: Boolean
    ): ImageButton {
        val contentDescription = activity.getString(
            if (collapsed) R.string.text_keyboard_layout_manage_expand
            else R.string.text_keyboard_layout_manage_collapse
        )
        val row = profileViews(activity, profile).first { view ->
            descendants(view).filterIsInstance<TextView>().any { it.text.contains(base) }
        }
        return descendants(row).filterIsInstance<ImageButton>()
            .single { it.contentDescription == contentDescription }
    }

    private fun profileViews(activity: TextKeyboardLayoutProfileManagerActivity, profile: String): List<View> {
        val row = profileRow(activity, profile)
        val container = row.parent as ViewGroup
        return (container.indexOfChild(row) + 1 until container.childCount).map { container.getChildAt(it) }
            .takeWhile { view -> descendants(view).filterIsInstance<ImageButton>().none {
                it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_set_default)
            } }
    }

    private fun profileTexts(activity: TextKeyboardLayoutProfileManagerActivity, profile: String): List<String> =
        profileViews(activity, profile).flatMap { descendants(it).filterIsInstance<TextView>().map { text -> text.text.toString() }.toList() }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun privateField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun invokePrivate(target: Any, name: String, vararg arguments: Any?): Any? =
        target.javaClass.declaredMethods.single { method ->
            method.name == name && method.parameterTypes.size == arguments.size
        }.apply { isAccessible = true }.invoke(target, *arguments)

    @Suppress("UNCHECKED_CAST")
    private fun rimeSchemaLabelsCache(activity: TextKeyboardLayoutProfileManagerActivity) =
        privateField(activity, "rimeSchemaLabelsCache") as MutableMap<String, List<String>>

    @Test
    fun deleteButtonsUseDestructiveColorInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalMode = AppCompatDelegate.getDefaultNightMode()
        try {
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(mode) }
                val intent = Intent(
                    instrumentation.targetContext,
                    TextKeyboardLayoutProfileManagerActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = instrumentation.startActivitySync(intent) as TextKeyboardLayoutProfileManagerActivity
                try {
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        val buttons = descendants(activity.window.decorView).filterIsInstance<ImageButton>().toList()
                        val deleteProfile = buttons.single {
                            it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_delete_profile)
                        }
                        val tint = requireNotNull(deleteProfile.imageTintList)
                        val destructive = activity.styledColor(androidx.appcompat.R.attr.colorError)
                        assertEquals(destructive, tint.getColorForState(intArrayOf(android.R.attr.state_enabled), 0))
                        assertEquals(
                            activity.styledColor(android.R.attr.textColorHint),
                            tint.getColorForState(intArrayOf(-android.R.attr.state_enabled), 0)
                        )
                        buttons.filter {
                            it.contentDescription == activity.getString(R.string.text_keyboard_layout_manage_delete)
                        }.forEach {
                            assertEquals(destructive, requireNotNull(it.imageTintList).defaultColor)
                        }
                    }
                } finally {
                    instrumentation.runOnMainSync { activity.finish() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(originalMode) }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
