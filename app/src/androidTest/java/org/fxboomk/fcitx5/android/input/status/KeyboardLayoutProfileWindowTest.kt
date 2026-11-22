/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.status

import android.content.Intent
import android.os.SystemClock
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.RadioButton
import android.widget.ScrollView
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.DefaultConfigProvider
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.input.editing.GestureEditingTestActivity
import org.fxboomk.fcitx5.android.input.keyboard.AltTextKeyView
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.fxboomk.fcitx5.android.input.keyboard.KeyView
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayout
import org.fxboomk.fcitx5.android.input.keyboard.ShuangpinKeyboardLayouts
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.TextKeyboardLayoutProfileManagerActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.TextKeyboardLayoutProfileOrder
import org.fxboomk.fcitx5.android.testing.RequireDefaultImeRule
import org.junit.Assert.*
import org.junit.ClassRule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class KeyboardLayoutProfileWindowTest {
    companion object {
        @ClassRule
        @JvmField
        val defaultIme = RequireDefaultImeRule()
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun keyboardLayoutShowsTwoColumnsSwitchesProfilesAndOpensTheExpandedActiveProfile() {
        val prefs = AppPrefs.getInstance()
        val originalArrangement = prefs.internal.compactShuangpinInputMethods.getValue()
        val originalProfile = prefs.keyboard.textKeyboardLayoutProfile.getValue()
        val originalOrder = prefs.keyboard.textKeyboardLayoutProfileOrder.getValue()
        val originalCollapsed = prefs.keyboard.textKeyboardLayoutProfileCollapsed.getValue()
        val originalProvider = ConfigProviders.provider
        val profiles = List(2) { "keyboard-profile-test-$it-${UUID.randomUUID()}" }
        val files = profiles.map { requireNotNull(UserConfigFiles.textKeyboardLayoutJson(it)) }
        val connectionName = "KeyboardLayoutProfileWindowTest"
        val connection = FcitxDaemon.connect(connectionName)
        val activity = instrumentation.startActivitySync(
            Intent(context, GestureEditingTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as GestureEditingTestActivity
        var originalIme: InputMethodEntry? = null
        var originalEnabledIme: Array<String>? = null
        var service: FcitxInputMethodService? = null
        var originalShiftLock: Boolean? = null
        try {
            val data = LayoutDataManager(context).apply { loadFromFile(null) }
            files.forEach { file ->
                file.parentFile?.mkdirs()
                file.writeText(data.exportCurrentJsonString())
            }
            val originalJson = files.map { it.readText() }
            lateinit var editor: EditText
            onMain {
                prefs.keyboard.textKeyboardLayoutProfile.setValue(profiles[0])
                prefs.keyboard.textKeyboardLayoutProfileOrder.setValue(profiles.reversed().joinToString("\n"))
                prefs.keyboard.textKeyboardLayoutProfileCollapsed.setValue(profiles.joinToString("\n"))
                ConfigProviders.provider = DefaultConfigProvider
                TextKeyboard.invalidateLayoutCache()
                editor = EditText(activity).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText("profile selection preserves editor")
                }
                activity.setContentView(editor)
                editor.requestFocus()
            }
            await { editor.isShown && editor.hasWindowFocus() }
            onMain { activity.getSystemService(InputMethodManager::class.java)
                .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
            await { runningService()?.let { it.isInputViewShown && it.currentInputEditorInfo?.fieldId == editor.id } == true }
            val activeService = requireNotNull(runningService()).also { service = it }
            originalShiftLock = activeService.isVirtualShiftLockOn()
            originalIme = connection.runImmediately { inputMethodEntryCached }
            val enabled = connection.runImmediately { enabledIme() }
            originalEnabledIme = enabled.map { it.uniqueName }.toTypedArray()
            val target = connection.runImmediately { availableIme() }.first { it.uniqueName == "shuangpin-xiaohe" }
            if (enabled.none { it.uniqueName == target.uniqueName }) {
                connection.runImmediately { setEnabledIme(enabled.map { it.uniqueName }.toTypedArray() + target.uniqueName) }
            }
            connection.runImmediately { activateIme(target.uniqueName) }
            await { connection.runImmediately { inputMethodEntryCached.uniqueName } == target.uniqueName }
            val wm = windowManager(activeService)
            onMain { activeService.setVirtualShiftLockState(false) }
            val cases = listOf(ShuangpinKeyboardLayout.Compact18 to profiles[1],
                ShuangpinKeyboardLayout.Full to profiles[0])
            for ((variant, selectedProfile) in cases) {
                onMain {
                    ShuangpinKeyboardLayouts.write(target.uniqueName, variant)
                    (wm.getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards()
                }
                if (variant == ShuangpinKeyboardLayout.Compact18) {
                    openProfileWindowFromMenu(activeService)
                } else {
                    onMain { activeService.inputView!!.executeButtonAction("keyboard_layout") }
                }
                await { wm.currentWindowOrNull() is KeyboardLayoutProfileWindow }
                val window = wm.currentWindowOrNull() as KeyboardLayoutProfileWindow
                await { window.onCreateView().isShown && profileButtons(window).size >= 3 &&
                    profileButtons(window).first().width > 0 }
                lateinit var keysAfterSelection: List<KeyView>
                onMain {
                    val current = prefs.keyboard.textKeyboardLayoutProfile.getValue()
                    val scroll = window.onCreateView() as ScrollView
                    assertTrue("Profile selection must occupy the keyboard's expanded area",
                        activeService.inputView!!.allViews.any { it === scroll })
                    val grid = scroll.getChildAt(0) as GridLayout
                    assertEquals(2, grid.columnCount)
                    val buttons = profileButtons(window)
                    assertEquals(TextKeyboardLayoutProfileOrder.ordered(current), buttons.map { it.tag as String })
                    assertEquals(listOf(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE) + profiles.reversed(),
                        buttons.take(3).map { it.tag as String })
                    assertEquals(context.getString(R.string.default_), buttons.first().text.toString())
                    assertEquals(listOf(current), buttons.filter { it.isChecked }.map { it.tag as String })
                    assertEquals(buttons[0].top, buttons[1].top)
                    assertTrue("Profiles must occupy two physical columns",
                        buttons[0].right <= buttons[1].left || buttons[1].right <= buttons[0].left)
                    assertTrue("The third profile must start a second row", buttons[2].top > buttons[0].top)
                    assertTrue(buttons.single { it.tag == selectedProfile }.performClick())
                    assertTrue("Selecting a profile must immediately return to the keyboard",
                        wm.currentWindowOrNull() is KeyboardWindow)
                    assertEquals(selectedProfile, prefs.keyboard.textKeyboardLayoutProfile.getValue())
                    assertEquals(UserConfigFiles.textKeyboardLayoutJson(selectedProfile), ConfigProviders.provider.textKeyboardLayoutFile())
                    assertEquals(variant, ShuangpinKeyboardLayouts.read(target.uniqueName))
                    assertEquals("profile selection preserves editor", editor.text.toString())
                    keysAfterSelection = activeService.inputView!!.allViews.filterIsInstance<KeyView>().toList()
                }
                instrumentation.waitForIdleSync()
                onMain {
                    assertEquals("A completed profile selection must not rebuild the keyboard again",
                        keysAfterSelection, activeService.inputView!!.allViews.filterIsInstance<KeyView>().toList())
                }
                await { hasGroupKeys(activeService) == (variant == ShuangpinKeyboardLayout.Compact18) }
            }

            instrumentation.waitForIdleSync()
            lateinit var keysBeforeReselection: List<KeyView>
            onMain { keysBeforeReselection = activeService.inputView!!.allViews.filterIsInstance<KeyView>().toList() }
            openProfileWindowFromMenu(activeService)
            onMain {
                val window = wm.currentWindowOrNull() as KeyboardLayoutProfileWindow
                assertTrue(profileButtons(window).single { it.tag == profiles[0] }.performClick())
                assertTrue(wm.currentWindowOrNull() is KeyboardWindow)
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertEquals("Reselecting the current profile must reuse its existing key views",
                    keysBeforeReselection, activeService.inputView!!.allViews.filterIsInstance<KeyView>().toList())
            }

            // A profile preference changed by another screen still updates the open panel.
            openProfileWindowFromMenu(activeService)
            val externallyUpdatedWindow = wm.currentWindowOrNull() as KeyboardLayoutProfileWindow
            onMain { prefs.keyboard.textKeyboardLayoutProfile.setValue(profiles[1]) }
            await { profileButtons(externallyUpdatedWindow).single { it.isChecked }.tag == profiles[1] }
            onMain { assertTrue(profileButtons(externallyUpdatedWindow).single { it.tag == profiles[0] }.performClick()) }
            instrumentation.waitForIdleSync()

            // Real file writes must still invalidate the layout, even with the same timestamp.
            lateinit var keysBeforeFileWrite: List<KeyView>
            onMain { keysBeforeFileWrite = activeService.inputView!!.allViews.filterIsInstance<KeyView>().toList() }
            val activeFile = files[0]
            val beforeWrite = activeFile.readText()
            val beforeModified = activeFile.lastModified()
            try {
                activeFile.writeText("$beforeWrite\n")
                assertTrue(activeFile.setLastModified(beforeModified))
                await { keysBeforeFileWrite != activeService.inputView!!.allViews.filterIsInstance<KeyView>().toList() }
            } finally {
                activeFile.writeText(beforeWrite)
                assertTrue(activeFile.setLastModified(beforeModified))
                instrumentation.waitForIdleSync()
            }

            val samples = InstrumentationRegistry.getArguments().getString("profileSwitchSamples")?.toIntOrNull() ?: 0
            for (variant in listOf(ShuangpinKeyboardLayout.Compact18, ShuangpinKeyboardLayout.Full)) {
                if (samples <= 0) break
                onMain {
                    ShuangpinKeyboardLayouts.write(target.uniqueName, variant)
                    (wm.getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards()
                }
                repeat(samples) { sample ->
                    val current = prefs.keyboard.textKeyboardLayoutProfile.getValue()
                    val selected = profiles.first { it != current }
                    measureProfileSelection(activeService, selected, variant, "changed", sample)
                    measureProfileSelection(activeService, selected, variant, "same", sample)
                }
            }

            onMain { prefs.keyboard.textKeyboardLayoutProfile.setValue(profiles[0]) }
            openProfileWindowFromMenu(activeService)
            await { wm.currentWindowOrNull() is KeyboardLayoutProfileWindow }
            val window = wm.currentWindowOrNull() as KeyboardLayoutProfileWindow
            val monitor = instrumentation.addMonitor(TextKeyboardLayoutProfileManagerActivity::class.java.name, null, false)
            var managerActivity: TextKeyboardLayoutProfileManagerActivity? = null
            try {
                onMain {
                    val button = window.onCreateBarExtension().allViews.single {
                        it.contentDescription == context.getString(R.string.text_keyboard_layout_manage_title)
                    }
                    assertTrue("The manage button must be mounted in the visible title bar", button.isShown)
                    assertTrue(button.performClick())
                }
                managerActivity = monitor.waitForActivityWithTimeout(8000) as? TextKeyboardLayoutProfileManagerActivity
                assertNotNull("The title button did not open layout management", managerActivity)
                instrumentation.waitForIdleSync()
                val opened = requireNotNull(managerActivity)
                onMain {
                    assertEquals(profiles[0], opened.intent.getStringExtra(TextKeyboardLayoutProfileManagerActivity.EXTRA_TARGET_PROFILE))
                    assertTrue(opened.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                    assertEquals(profiles[0], prefs.keyboard.textKeyboardLayoutProfile.getValue())
                    val collapsed = prefs.keyboard.textKeyboardLayoutProfileCollapsed.getValue().split('\n')
                    assertFalse("The active profile must open expanded", profiles[0] in collapsed)
                    assertTrue("Opening management must preserve other collapsed profiles", profiles[1] in collapsed)
                    val header = opened.window.decorView.allViews.single { it.tag == profiles[0] }
                    assertTrue("The active profile header must be visible", header.isShown)
                    assertTrue("The active profile row must expose its expanded state", header.allViews.any {
                        it.contentDescription == context.getString(R.string.text_keyboard_layout_manage_collapse)
                    })
                    assertEquals(ShuangpinKeyboardLayout.Full, ShuangpinKeyboardLayouts.read(target.uniqueName))
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                onMain { managerActivity?.finish() }
                instrumentation.waitForIdleSync()
            }
            assertEquals("Switching or opening management must not edit profiles", originalJson, files.map { it.readText() })
        } finally {
            originalEnabledIme?.let { entries -> connection.runImmediately { setEnabledIme(entries) } }
            originalIme?.let { entry -> connection.runImmediately { activateIme(entry.uniqueName) } }
            onMain {
                prefs.internal.compactShuangpinInputMethods.setValue(originalArrangement)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(originalProfile)
                prefs.keyboard.textKeyboardLayoutProfileOrder.setValue(originalOrder)
                prefs.keyboard.textKeyboardLayoutProfileCollapsed.setValue(originalCollapsed)
                ConfigProviders.provider = originalProvider
                TextKeyboard.invalidateLayoutCache()
                originalShiftLock?.let { locked -> service?.setVirtualShiftLockState(locked) }
                service?.let { (windowManager(it).getEssentialWindow(KeyboardWindow) as KeyboardWindow).refreshAllKeyboards() }
                activity.finish()
            }
            FcitxDaemon.disconnect(connectionName)
            instrumentation.waitForIdleSync()
            files.forEach { file ->
                if (file.exists()) assertTrue("Could not remove the temporary profile", file.delete())
                file.parentFile?.listFiles { candidate -> candidate.name.startsWith("${file.nameWithoutExtension}_backup_") }
                    ?.forEach { assertTrue("Could not remove the temporary profile backup", it.delete()) }
            }
        }
    }

    private fun openProfileWindowFromMenu(service: FcitxInputMethodService) {
        val wm = windowManager(service)
        onMain { service.inputView!!.executeButtonAction("more") }
        await { (wm.currentWindowOrNull() as? StatusAreaWindow)?.view?.adapter?.let {
            (it as StatusAreaAdapter).entries.any { entry ->
                entry is StatusAreaEntry.ActionEntry && entry.buttonAction.id == "keyboard_layout"
            }
        } == true }
        val status = wm.currentWindowOrNull() as StatusAreaWindow
        val adapter = status.view.adapter as StatusAreaAdapter
        val entry = adapter.entries.filterIsInstance<StatusAreaEntry.ActionEntry>().single {
            it.buttonAction.id == "keyboard_layout"
        }
        onMain {
            adapter.onItemClick(status.view, entry)
            assertTrue("The menu dispatcher must preserve the profile panel after the click",
                wm.currentWindowOrNull() is KeyboardLayoutProfileWindow)
        }
        instrumentation.waitForIdleSync()
    }

    private fun measureProfileSelection(
        service: FcitxInputMethodService,
        profile: String,
        variant: ShuangpinKeyboardLayout,
        kind: String,
        sample: Int
    ) {
        openProfileWindowFromMenu(service)
        val wm = windowManager(service)
        val window = wm.currentWindowOrNull() as KeyboardLayoutProfileWindow
        await { window.onCreateView().isShown && profileButtons(window).all { it.width > 0 } }
        val frame = CountDownLatch(1)
        val frameNanos = AtomicLong()
        var start = 0L
        var clickNanos = 0L
        onMain {
            val root = service.inputView!!
            assertTrue("Frame measurements require hardware acceleration", root.isHardwareAccelerated)
            assertTrue("Frame measurements require Android 10 or newer", Build.VERSION.SDK_INT >= 29)
            root.viewTreeObserver.registerFrameCommitCallback {
                frameNanos.set(SystemClock.elapsedRealtimeNanos())
                frame.countDown()
            }
            start = SystemClock.elapsedRealtimeNanos()
            assertTrue(profileButtons(window).single { it.tag == profile }.performClick())
            clickNanos = SystemClock.elapsedRealtimeNanos() - start
            assertTrue(wm.currentWindowOrNull() is KeyboardWindow)
            root.invalidate()
        }
        assertTrue("The selected keyboard did not submit a frame", frame.await(8, TimeUnit.SECONDS))
        onMain {
            assertEquals(profile, AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile.getValue())
            assertEquals(variant == ShuangpinKeyboardLayout.Compact18, hasGroupKeys(service))
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "PROFILE_SWITCH variant=$variant kind=$kind sample=$sample " +
                "click_ms=${clickNanos / 1_000_000.0} frame_ms=${(frameNanos.get() - start) / 1_000_000.0}\n")
        })
        instrumentation.waitForIdleSync()
    }

    private fun profileButtons(window: KeyboardLayoutProfileWindow): List<RadioButton> =
        ((window.onCreateView() as ScrollView).getChildAt(0) as GridLayout)
            .children.filterIsInstance<RadioButton>().toList()

    private fun windowManager(service: FcitxInputMethodService): InputWindowManager =
        service.inputView!!.javaClass.getDeclaredField("windowManager").apply { isAccessible = true }
            .get(service.inputView) as InputWindowManager

    private fun runningService(): FcitxInputMethodService? {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val services = threadClass.getDeclaredField("mServices").apply { isAccessible = true }.get(thread) as Map<*, *>
        return services.values.filterIsInstance<FcitxInputMethodService>().firstOrNull()
    }

    private fun hasGroupKeys(service: FcitxInputMethodService): Boolean =
        service.inputView!!.allViews.filterIsInstance<AltTextKeyView>().any {
            (it.def as? KeyDef.Appearance.AltText)?.isLetterGroup == true
        }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var result = false
            onMain { result = condition() }
            if (result) return
            SystemClock.sleep(50)
        }
        onMain { assertTrue("Timed out waiting for keyboard profile UI", condition()) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
