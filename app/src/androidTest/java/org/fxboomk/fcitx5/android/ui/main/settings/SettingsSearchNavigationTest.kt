/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.data.pinyin.CustomShuangpinSchemeStore
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.addon.AddonConfigLayouts
import org.fxboomk.fcitx5.android.ui.main.settings.im.CustomShuangpinFragment
import org.fxboomk.fcitx5.android.ui.main.settings.im.CustomShuangpinMappingFragment
import org.fxboomk.fcitx5.android.utils.CollapsiblePreferenceCategory
import org.fxboomk.fcitx5.android.utils.navigateWithAnim
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import splitties.resources.styledColor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On device verification of the three settings navigation fixes:
 *
 * 1. the settings search must be able to find the entries of the custom shuangpin page,
 * 2. jumping to an entry of a collapsed group must expand that group,
 * 3. the highlight must really be painted on the target row.
 */
class SettingsSearchNavigationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fcitxConnection by lazy { FcitxDaemon.connect(CLIENT_NAME) }

    @After
    fun tearDown() {
        FcitxDaemon.disconnect(CLIENT_NAME)
    }

    // -------------------------------------------------------------------------------------
    // Bug 1: the custom shuangpin entries must show up in the settings search
    // -------------------------------------------------------------------------------------

    @Test
    fun searchIndexContainsTheCustomShuangpinEntries() = runBlocking {
        withCustomShuangpinEnabled {
            val items = fcitxConnection.runOnReady {
                SettingsSearchIndex.fcitxItems(context, this)
            }

            val mapping = items.filter { it.tab == CustomShuangpinFragment.TAB_MAPPING }
            assertTrue(
                "no initials group entry in the search index",
                mapping.any {
                    it.preferenceKey == CustomShuangpinMappingFragment.KEY_INITIALS_GROUP
                }
            )
            assertTrue(
                "no finals group entry in the search index",
                mapping.any {
                    it.preferenceKey == CustomShuangpinMappingFragment.KEY_FINALS_GROUP
                }
            )
            assertTrue(
                "no syllables entry in the search index",
                mapping.any { it.preferenceKey == CustomShuangpinMappingFragment.KEY_SYLLABLES }
            )
            assertTrue(
                "no per initial entry in the search index",
                mapping.any {
                    it.preferenceKey == CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b"
                }
            )
            assertTrue(
                "no per final entry in the search index",
                mapping.any {
                    it.preferenceKey == CustomShuangpinMappingFragment.KEY_FINAL_PREFIX + "ai"
                }
            )
            assertTrue(
                "custom shuangpin entries are not searchable",
                mapping.first {
                    it.preferenceKey == CustomShuangpinMappingFragment.KEY_INITIALS_GROUP
                }.matches(context.getString(R.string.custom_shuangpin_initials))
            )
            assertTrue(
                "custom shuangpin entries are not searchable by their own key",
                mapping.first {
                    it.preferenceKey == CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b"
                }.matches("custom_shuangpin_initial_b")
            )
            assertTrue(
                "custom shuangpin entries must all target the custom shuangpin page",
                items.filter {
                    it.route == SettingsRoute.CustomShuangpin && it.preferenceKey != null
                }.all {
                    it.tab == CustomShuangpinFragment.TAB_MAPPING ||
                        it.tab == CustomShuangpinFragment.TAB_PREFERENCES
                }
            )

            // The preferences tab of the custom shuangpin page shows the configuration of
            // the pinyin addon, it is only indexed when that addon provides a description.
            val hasDescription = fcitxConnection.runOnReady {
                getImConfig(CustomShuangpinSchemeStore.currentUniqueName())
                    .findByName("desc") != null
            }
            if (hasDescription) {
                assertTrue(
                    "no entry for the custom shuangpin preferences tab",
                    items.any {
                        it.route == SettingsRoute.CustomShuangpin &&
                            it.tab == CustomShuangpinFragment.TAB_PREFERENCES
                    }
                )
            }
        }
    }

    @Test
    fun searchingForACustomShuangpinEntryShowsItInTheResults() = runBlocking {
        withCustomShuangpinEnabled {
            withActivity { activity ->
                val mainFragment = mainFragment(activity)
                val query = context.getString(R.string.custom_shuangpin_initials)
                val displayName = CustomShuangpinSchemeStore.displayName()

                onMain {
                    val placeholder = mainFragment.listView
                        .findViewById<View>(R.id.settings_search_placeholder)
                    assertNotNull("the search placeholder is not bound", placeholder)
                    placeholder!!.performClick()
                    val searchView = mainFragment.listView
                        .findViewById<SearchView>(R.id.settings_search_view)
                    assertNotNull("the search view was not created", searchView)
                    searchView!!.setQuery(query, true)
                }

                val found = waitUntil(timeoutMs = 20_000) {
                    onMain {
                        searchResultTitles(mainFragment).any {
                            it.first == query && it.second.contains(displayName)
                        }
                    }
                }
                assertTrue(
                    "the custom shuangpin entry is missing from the search results",
                    found
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Bug 2: jumping to an entry of a collapsed group must expand the group
    // -------------------------------------------------------------------------------------

    @Test
    fun jumpingToAnEntryOfACollapsedGroupExpandsIt() {
        withCollapsedInitialsGroup {
            withActivity { activity ->
                activity.viewModel.requestPreferenceScroll(
                    CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b",
                    CustomShuangpinFragment.TAB_MAPPING
                )
                navigateToCustomShuangpin(activity)

                val opened = waitUntil(timeoutMs = 20_000) {
                    onMain { mappingFragment(activity) != null }
                }
                assertTrue("the custom shuangpin mapping page was not opened", opened)
                val mapping = onMain { mappingFragment(activity)!! }

                val expanded = waitUntil(timeoutMs = 10_000) {
                    onMain {
                        val group = mapping.findPreference<CollapsiblePreferenceCategory>(
                            CustomShuangpinMappingFragment.KEY_INITIALS_GROUP
                        )
                        group != null && !group.isCollapsed
                    }
                }
                assertTrue("the collapsed group was not expanded", expanded)

                val visible = waitUntil(timeoutMs = 10_000) {
                    onMain {
                        val target = mapping.findPreference<Preference>(
                            CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b"
                        )
                        target != null && target.isVisible
                    }
                }
                assertTrue("the target entry is still invisible", visible)

                onMain {
                    val target = mapping.findPreference<Preference>(
                        CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b"
                    )
                    assertNotNull(target)
                    assertTrue(target!!.isVisible)
                    assertEquals(
                        CustomShuangpinMappingFragment.KEY_INITIALS_GROUP,
                        target.parent?.key
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Bug 3: the highlight must be painted after the jump
    // -------------------------------------------------------------------------------------

    @Test
    fun jumpingToAnEntryPaintsTheHighlight() {
        withCollapsedInitialsGroup {
            withActivity { activity ->
                activity.viewModel.requestPreferenceScroll(
                    CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b",
                    CustomShuangpinFragment.TAB_MAPPING
                )
                navigateToCustomShuangpin(activity)

                val opened = waitUntil(timeoutMs = 20_000) {
                    onMain { mappingFragment(activity) != null }
                }
                assertTrue("the custom shuangpin mapping page was not opened", opened)
                val mapping = onMain { mappingFragment(activity)!! }

                val accent = onMain { activity.styledColor(android.R.attr.colorAccent) }

                var highlighted: Bitmap? = null
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline && highlighted == null) {
                    val capture = captureRow(activity, mapping)
                    if (capture != null && countAccentPixels(capture, accent) > 0) {
                        highlighted = capture
                        break
                    }
                    capture?.recycle()
                    Thread.sleep(80)
                }
                assertNotNull(
                    "the highlight was never painted on the target row",
                    highlighted
                )

                val consumed = waitUntil(timeoutMs = 5_000) {
                    onMain { activity.viewModel.peekPendingPreferenceScrollKey() == null }
                }
                assertTrue("the scroll request was not consumed", consumed)

                // The highlight must not cover the entry with a translucent scrim, only its
                // outline is painted. Compare the content of the row while it is highlighted
                // with the content of the very same row once the highlight is over.
                Thread.sleep(2_000)
                val normal = captureRow(activity, mapping)
                assertNotNull("the target row could not be captured", normal)
                val darkened = countDarkenedPixels(highlighted!!, normal!!)
                highlighted.recycle()
                normal.recycle()
                assertEquals(
                    "the highlight covers the entry with a scrim",
                    0,
                    darkened
                )
            }
        }
    }

    @Test
    fun shuangpinInitialFuzzyRowDisablesForItsOwnCompactLayout() {
        val inputMethod = "shuangpin-xiaohe"
        val otherInputMethod = "shuangpin-ms"
        val layouts = AppPrefs.getInstance().internal.compactShuangpinInputMethods
        val original = layouts.getValue()
        val hadSavedLayouts = layouts.sharedPreferences.contains(layouts.key)
        try {
            val fullLayout = original - inputMethod + otherInputMethod
            onMain { layouts.setValue(fullLayout) }
            withActivity { activity ->
                val layout = requireNotNull(AddonConfigLayouts.getForInputMethod(context, inputMethod))
                val section = layout.sections.indexOfFirst { it.title == R.string.pinyin_config_section_input }
                val option = "ShuangpinInitialFuzzy"
                onMain {
                    requireNotNull(navHost(activity)).navController.navigateWithAnim(
                        SettingsRoute.ConfigSection("Xiaohe", inputMethod, false, section,
                            context.getString(R.string.pinyin_config_section_input))
                    )
                }
                fun fragment() = navHost(activity)?.childFragmentManager
                    ?.primaryNavigationFragment as? ConfigSectionFragment
                fun rowTitle(): View? {
                    val list = fragment()?.listView ?: return null
                    val position = (list.adapter as? PreferenceGroup.PreferencePositionCallback)
                        ?.getPreferenceAdapterPosition(option) ?: return null
                    return list.findViewHolderForAdapterPosition(position)?.itemView
                        ?.findViewById(android.R.id.title)
                }
                assertTrue("A different scheme's 18-key layout must leave the row enabled", waitUntil {
                    onMain { rowTitle()?.isEnabled == true }
                })
                val checked = onMain { requireNotNull(fragment()?.findPreference<SwitchPreference>(option)).isChecked }

                onMain { layouts.setValue(fullLayout + inputMethod) }
                assertTrue("The 18-key layout must disable the visible row", waitUntil {
                    onMain {
                        fragment()?.findPreference<SwitchPreference>(option)?.isEnabled == false &&
                            rowTitle()?.isEnabled == false
                    }
                })
                onMain { layouts.setValue(fullLayout) }
                assertTrue("The 26-key layout must re-enable the row", waitUntil {
                    onMain { rowTitle()?.isEnabled == true }
                })
                onMain { assertEquals(checked, fragment()?.findPreference<SwitchPreference>(option)?.isChecked) }
            }
        } finally {
            onMain {
                if (hadSavedLayouts) layouts.setValue(original)
                else layouts.sharedPreferences.edit().remove(layouts.key).apply()
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    /**
     * Run [block] with the custom shuangpin input method enabled, and restore the original
     * list of enabled input methods afterwards.
     */
    private suspend fun withCustomShuangpinEnabled(block: suspend () -> Unit) {
        val customName = CustomShuangpinSchemeStore.currentUniqueName()
        val original = withTimeoutOrNull(FCITX_TIMEOUT_MS) {
            fcitxConnection.runOnReady { enabledIme().map { it.uniqueName } }
        } ?: emptyList()
        try {
            if (customName !in original) {
                withTimeoutOrNull(FCITX_TIMEOUT_MS) {
                    fcitxConnection.runOnReady {
                        setEnabledIme((original + customName).toTypedArray())
                    }
                }
            }
            block()
        } finally {
            withTimeoutOrNull(FCITX_TIMEOUT_MS) {
                runCatching {
                    fcitxConnection.runOnReady { setEnabledIme(original.toTypedArray()) }
                }
            }
        }
    }

    /**
     * Force the initials group of the custom shuangpin page to be collapsed, and restore the
     * previous state afterwards.
     */
    private fun withCollapsedInitialsGroup(block: () -> Unit) {
        val prefs = context.getSharedPreferences(COLLAPSIBLE_PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getBoolean(COLLAPSED_INITIALS, false)
        prefs.edit().putBoolean(COLLAPSED_INITIALS, true).commit()
        try {
            block()
        } finally {
            prefs.edit().putBoolean(COLLAPSED_INITIALS, previous).commit()
        }
    }

    private fun withActivity(block: (MainActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        try {
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun navigateToCustomShuangpin(activity: MainActivity) {
        onMain {
            val navHost = navHost(activity)
            assertNotNull("the nav host is missing", navHost)
            navHost!!.navController.navigateWithAnim(SettingsRoute.CustomShuangpin)
        }
        instrumentation.waitForIdleSync()
    }

    private fun navHost(activity: MainActivity): NavHostFragment? =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

    private fun mainFragment(activity: MainActivity): PreferenceFragmentCompat {
        val navHost = navHost(activity)
        assertNotNull("the nav host is missing", navHost)
        val fragment = navHost!!.childFragmentManager.fragments
            .filterIsInstance<PreferenceFragmentCompat>()
            .firstOrNull { it.findPreference<Preference>(SettingsSearchPreference.KEY) != null }
        assertNotNull("the main settings fragment is missing", fragment)
        return fragment!!
    }

    private fun customShuangpinFragment(activity: MainActivity): CustomShuangpinFragment? =
        navHost(activity)?.childFragmentManager?.fragments
            ?.filterIsInstance<CustomShuangpinFragment>()
            ?.firstOrNull()

    private fun mappingFragment(activity: MainActivity): CustomShuangpinMappingFragment? =
        customShuangpinFragment(activity)?.childFragmentManager?.fragments
            ?.filterIsInstance<CustomShuangpinMappingFragment>()
            ?.firstOrNull()

    /**
     * Titles and summaries of the preferences currently shown by the main fragment.
     */
    private fun searchResultTitles(fragment: PreferenceFragmentCompat): List<Pair<String, String>> {
        val screen = fragment.preferenceScreen ?: return emptyList()
        val results = mutableListOf<Pair<String, String>>()
        fun collect(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val preference = group.getPreference(i)
                if (preference is PreferenceGroup) {
                    collect(preference)
                } else {
                    results.add(
                        preference.title?.toString().orEmpty() to
                            preference.summary?.toString().orEmpty()
                    )
                }
            }
        }
        collect(screen)
        return results
    }

    /**
     * Capture the pixels of the target row. The highlight is drawn on the overlay of the row,
     * so the window itself has to be captured.
     */
    private fun captureRow(
        activity: MainActivity,
        mapping: CustomShuangpinMappingFragment
    ): Bitmap? {
        val location = IntArray(2)
        val itemView = onMain {
            val listView = mapping.listView
            val position = (listView.adapter as? PreferenceGroup.PreferencePositionCallback)
                ?.getPreferenceAdapterPosition(
                    CustomShuangpinMappingFragment.KEY_INITIAL_PREFIX + "b"
                ) ?: RecyclerView.NO_POSITION
            if (position == RecyclerView.NO_POSITION) {
                null
            } else {
                listView.findViewHolderForAdapterPosition(position)?.itemView?.also {
                    it.getLocationInWindow(location)
                }
            }
        } ?: return null
        val width = onMain { itemView.width }
        val height = onMain { itemView.height }
        if (width <= 0 || height <= 0) return null
        val bounds = onMain {
            val decor = activity.window.decorView
            Rect(0, 0, decor.width, decor.height)
        }
        val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
        if (!rect.intersect(bounds) || rect.isEmpty) return null
        val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        val status = intArrayOf(PixelCopy.ERROR_UNKNOWN)
        PixelCopy.request(
            activity.window,
            rect,
            bitmap,
            {
                status[0] = it
                latch.countDown()
            },
            mainHandler
        )
        latch.await(3, TimeUnit.SECONDS)
        if (status[0] != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /**
     * Count the pixels of the capture that use the accent color, that is the outline of the
     * highlight.
     */
    private fun countAccentPixels(bitmap: Bitmap, accent: Int): Int {
        val red = Color.red(accent)
        val green = Color.green(accent)
        val blue = Color.blue(accent)
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) == red &&
                    Color.green(pixel) == green &&
                    Color.blue(pixel) == blue
                ) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * Count the pixels of the interior of the row that are darker while the entry is
     * highlighted than they are once the highlight is over. A translucent scrim painted over
     * the entry would darken all of them.
     */
    private fun countDarkenedPixels(highlighted: Bitmap, normal: Bitmap): Int {
        if (highlighted.width != normal.width || highlighted.height != normal.height) return 0
        val density = context.resources.displayMetrics.density
        // Keep away from the outline, it is painted on purpose.
        val marginX = (20 * density).toInt()
        val marginY = (12 * density).toInt()
        var count = 0
        for (y in marginY until highlighted.height - marginY) {
            for (x in marginX until highlighted.width - marginX) {
                if (luminance(highlighted.getPixel(x, y)) < luminance(normal.getPixel(x, y)) - 2) {
                    count++
                }
            }
        }
        return count
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private fun <T> onMain(block: () -> T): T {
        val holder = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            instrumentation.waitForIdleSync()
            Thread.sleep(50)
        }
        return condition()
    }

    companion object {
        private const val CLIENT_NAME = "settings-search-navigation-test"
        private const val COLLAPSIBLE_PREFS = "collapsible_preference_state"
        private const val COLLAPSED_INITIALS = "custom_shuangpin_state_initials"
        private const val FCITX_TIMEOUT_MS = 120_000L
    }
}
