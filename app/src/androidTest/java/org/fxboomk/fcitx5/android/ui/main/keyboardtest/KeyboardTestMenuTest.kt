/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.keyboardtest

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.SettingsRoute
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.ButtonsCustomizerActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.ButtonsThemeManagerActivity
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.FontsetEditorActivity
import org.junit.Assert.*
import org.junit.Test

class KeyboardTestMenuTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun allAppearanceSubpagesKeepExactlyOneIconAfterMenuRebuilds() {
        val routes = listOf(
            SettingsRoute.ThemeList,
            SettingsRoute.ThemeMode,
            SettingsRoute.ThemeKeyStyle,
            SettingsRoute.ThemeCandidateStyle,
            SettingsRoute.ThemeToolbarStyle,
            SettingsRoute.ThemeAnimation,
            SettingsRoute.ThemeOtherStyle
        )
        for (route in routes) {
            withMainActivity(route) { activity -> verifyKeyboardActionAcrossRebuilds(activity) }
        }
        for (editor in listOf(
            FontsetEditorActivity::class.java,
            ButtonsCustomizerActivity::class.java,
            ButtonsThemeManagerActivity::class.java
        )) {
            withActivity(editor) { activity -> verifyKeyboardActionAcrossRebuilds(activity) }
        }
    }

    @Test
    fun returningToAppearanceHomeRemovesTheSubpageAction() = withMainActivity(SettingsRoute.Theme) { activity ->
        instrumentation.waitForIdleSync()
        onMain {
            assertNull(toolbar(activity).menu.findItem(R.id.keyboard_test_entry))
        }
    }

    @Test
    fun buttonsAndIconsKeepsSaveThenKeyboardTestThenMoreAsIcons() =
        withActivity(ButtonsCustomizerActivity::class.java) { activity ->
            waitForKeyboardAction(activity)
            onMain {
                val toolbar = toolbar(activity)
                val rootActions = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
                assertEquals(listOf(3001, R.id.keyboard_test_entry, 3005), rootActions.map { it.itemId })
                rootActions.forEach { item ->
                    assertNotNull(item.icon)
                    val actionView = toolbar.findViewById<View>(item.itemId)
                    assertNotNull("The action must be visible in the toolbar", actionView)
                    assertTrue("Toolbar actions must use icons", (actionView as? TextView)?.text.isNullOrEmpty())
                }
                assertFalse(rootActions.any { it.itemId == 3003 || it.itemId == 3004 })
            }
        }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun bothButtonTabsUseTheSameThemeSwitchMenuAndPickerTitle() =
        withActivity(ButtonsCustomizerActivity::class.java) { activity ->
            waitForKeyboardAction(activity)
            for (tabIndex in listOf(0, 1)) {
                var opened = false
                try {
                    onMain {
                        val tabs = descendants(activity.window.decorView).filterIsInstance<TabLayout>().single()
                        requireNotNull(tabs.getTabAt(tabIndex)).select()
                        val more = requireNotNull(requireNotNull(toolbar(activity).menu.findItem(3005)).subMenu)
                        val action = requireNotNull(more.findItem(3003))
                        assertEquals(activity.getString(R.string.switch_toolbar_theme), action.title.toString())
                        assertEquals(activity.getString(R.string.switch_toolbar_theme), activity.getString(R.string.switch_icon_theme))
                        assertNotNull(action.icon)
                        assertTrue(activity.onOptionsItemSelected(action))
                        opened = true
                    }
                    instrumentation.waitForIdleSync()
                    onMain {
                        val title = WindowInspector.getGlobalWindowViews().mapNotNull {
                            it.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                        }.single()
                        assertEquals(activity.getString(R.string.switch_toolbar_theme), title.text.toString())
                    }
                } finally {
                    // Dismiss without choosing a theme or writing either theme store.
                    if (opened) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    instrumentation.waitForIdleSync()
                }
            }
        }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun themeManagerKeyboardActionOpensPanelAndFinishingClearsItsPassword() =
        withActivity(ButtonsThemeManagerActivity::class.java) { activity ->
            waitForKeyboardAction(activity)
            lateinit var panel: KeyboardTestPanel
            onMain {
                assertTrue(toolbar(activity).menu.performIdentifierAction(R.id.keyboard_test_entry, 0))
                panel = visiblePanels().single()
                panel.select(KeyboardTestPanel.Kind.PASSWORD)
                panel.input.setText("manager test password")
                activity.finish()
            }
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && onMain { panel.isAttachedToWindow }) {
                instrumentation.waitForIdleSync()
                Thread.sleep(20)
            }
            onMain {
                assertFalse(panel.isAttachedToWindow)
                assertEquals("", panel.input.text.toString())
            }
        }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun immediateCloseAndReopenStillClearsTheNewPasswordWhenThePageStops() = withMainActivity { activity ->
        lateinit var owner: PageOwner
        lateinit var provider: KeyboardTestMenu
        lateinit var action: android.view.MenuItem
        lateinit var first: KeyboardTestPanel
        lateinit var second: KeyboardTestPanel
        var ownerCreated = false
        try {
            onMain {
                owner = PageOwner().apply { moveTo(Lifecycle.State.RESUMED) }
                ownerCreated = true
                provider = KeyboardTestMenu(activity, owner)
                val menu = PopupMenu(activity, toolbar(activity)).menu
                repeat(3) { provider.onCreateMenu(menu, activity.menuInflater) }
                assertEquals(1, menu.size())
                action = requireNotNull(menu.findItem(R.id.keyboard_test_entry))
                assertEquals(activity.getString(R.string.keyboard_test_title), MenuItemCompat.getContentDescription(action))
                assertTrue(provider.onMenuItemSelected(action))
                assertTrue(provider.onMenuItemSelected(action))
                first = visiblePanels().single()
                first.select(KeyboardTestPanel.Kind.PIN)
                first.input.setText("1234")
                val close = descendants(first).single {
                    it.contentDescription == activity.getString(R.string.keyboard_test_close)
                }
                close.performClick()
                assertEquals("", first.input.text.toString())
                // Reopen before the first dialog's posted dismiss listener runs.
                assertTrue(provider.onMenuItemSelected(action))
                second = visiblePanels().single()
                assertNotSame(first, second)
                assertEquals("", second.input.text.toString())
                second.select(KeyboardTestPanel.Kind.PASSWORD)
                second.input.setText("new test password")
            }
            instrumentation.waitForIdleSync()
            onMain {
                owner.moveTo(Lifecycle.State.CREATED)
                assertEquals("", second.input.text.toString())
                assertTrue(visiblePanels().isEmpty())
            }
            instrumentation.waitForIdleSync()
            onMain {
                owner.moveTo(Lifecycle.State.RESUMED)
                assertTrue(provider.onMenuItemSelected(action))
                val reopened = visiblePanels().single()
                assertEquals("", reopened.input.text.toString())
                owner.moveTo(Lifecycle.State.DESTROYED)
                assertEquals("", reopened.input.text.toString())
                assertTrue(visiblePanels().isEmpty())
            }
        } finally {
            onMain {
                if (ownerCreated && owner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
                    owner.moveTo(Lifecycle.State.DESTROYED)
                }
            }
        }
    }

    private fun verifyKeyboardActionAcrossRebuilds(activity: AppCompatActivity) {
        repeat(3) {
            onMain { activity.invalidateMenu() }
            waitForKeyboardAction(activity)
            onMain {
                val toolbar = toolbar(activity)
                val actions = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it) }
                assertEquals(1, actions.count { it.itemId == R.id.keyboard_test_entry })
                val action = requireNotNull(toolbar.menu.findItem(R.id.keyboard_test_entry))
                assertNotNull(action.icon)
                assertEquals(activity.getString(R.string.keyboard_test_title), MenuItemCompat.getContentDescription(action))
                val view = toolbar.findViewById<View>(R.id.keyboard_test_entry)
                assertNotNull(view)
                assertTrue((view as? TextView)?.text.isNullOrEmpty())
            }
        }
    }

    private fun waitForKeyboardAction(activity: AppCompatActivity) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (onMain { toolbar(activity).findViewById<View>(R.id.keyboard_test_entry) != null }) return
            Thread.sleep(20)
        }
        assertNotNull(onMain { toolbar(activity).findViewById<View>(R.id.keyboard_test_entry) })
    }

    private fun toolbar(activity: AppCompatActivity): Toolbar =
        descendants(activity.window.decorView).filterIsInstance<Toolbar>().first()

    @RequiresApi(29)
    private fun visiblePanels(): List<KeyboardTestPanel> =
        WindowInspector.getGlobalWindowViews().flatMap { descendants(it).filterIsInstance<KeyboardTestPanel>().toList() }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            repeat(view.childCount) { yieldAll(descendants(view.getChildAt(it))) }
        }
    }

    private fun withMainActivity(route: SettingsRoute? = null, block: (MainActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        route?.let {
            intent.action = Intent.ACTION_RUN
            intent.putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, it)
        }
        withActivity(MainActivity::class.java, intent, block)
    }

    private fun <T : AppCompatActivity> withActivity(
        type: Class<T>,
        intent: Intent = Intent(instrumentation.targetContext, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        block: (T) -> Unit
    ) {
        val activity = type.cast(instrumentation.startActivitySync(intent))!!
        try {
            block(activity)
        } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { result[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    private class PageOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) { registry.currentState = state }
    }
}
