/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.addon

import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import org.fxboomk.fcitx5.android.core.RawConfig
import org.fxboomk.fcitx5.android.ui.common.preference.resetToDefault
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.fxboomk.fcitx5.android.ui.main.settings.PreferenceScreenFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the generated preferences and their real change/save lifecycle. */
class PinyinAnnotationPlacementPreferenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun disabledAnnotationStartsGrayAndCannotRequestAPlacementDialog() = withHost { activity ->
        for (scope in Scope.entries) {
            for (saved in listOf(TOP, RIGHT)) {
                val fixture = onMain { fixture(activity, scope, NONE, saved) }
                onMain {
                    assertFalse(fixture.placement.isEnabled)
                    assertEquals(saved, fixture.placement.value)
                    val row = bind(fixture.placement)
                    assertFalse(row.findViewById<TextView>(android.R.id.title).isEnabled)
                    assertFalse(row.isEnabled)
                    row.performClick()
                    assertEquals(0, fixture.dialogs.get())
                    assertEquals(saved, fixture.cfg[PLACEMENT].value)
                    assertEquals(0, fixture.saves.get())
                }
            }
        }
    }

    @Test
    fun allAnnotationModesRefreshEnabledStateWithoutLosingEitherPlacement() = withHost { activity ->
        for (scope in Scope.entries) {
            for (saved in listOf(TOP, RIGHT)) {
                val fixture = onMain { fixture(activity, scope, NONE, saved) }
                for ((index, mode) in listOf(RARE, POLYPHONIC, ALL, NONE, RARE).withIndex()) {
                    change(fixture.annotation, mode)
                    onMain {
                        assertEquals(mode != NONE, fixture.placement.isEnabled)
                        assertEquals(saved, fixture.placement.value)
                        assertEquals(saved, fixture.cfg[PLACEMENT].value)
                        assertEquals(mode, fixture.cfg[ANNOTATION].value)
                        assertEquals(index + 1, fixture.saves.get())
                        val row = bind(fixture.placement)
                        assertEquals(mode != NONE, row.findViewById<TextView>(android.R.id.title).isEnabled)
                    }
                }
                onMain {
                    bind(fixture.placement).performClick()
                    assertEquals(1, fixture.dialogs.get())
                }
            }
        }
    }

    @Test
    fun resettingAnnotationModeReenablesPlacementAndRetainsItsSavedValue() = withHost { activity ->
        for (scope in Scope.entries) {
            for (saved in listOf(TOP, RIGHT)) {
                val fixture = onMain { fixture(activity, scope, ALL, saved) }
                change(fixture.annotation, NONE)
                onMain { assertFalse(fixture.placement.isEnabled) }

                onMain { fixture.annotation.resetToDefault() }
                instrumentation.waitForIdleSync()
                onMain {
                    assertEquals(RARE, fixture.annotation.value)
                    assertTrue(fixture.placement.isEnabled)
                    assertEquals(saved, fixture.placement.value)
                    assertEquals(saved, fixture.cfg[PLACEMENT].value)
                    assertEquals(2, fixture.saves.get())
                }
            }
        }
    }

    @Test
    fun enabledPlacementChangesStillSaveAndSurviveDisablingAnnotation() = withHost { activity ->
        val fixture = onMain { fixture(activity, Scope.INPUT_METHOD, RARE, RIGHT) }
        change(fixture.placement, TOP)
        onMain {
            assertEquals(TOP, fixture.cfg[PLACEMENT].value)
            assertTrue(fixture.placement.isEnabled)
            assertEquals(1, fixture.saves.get())
        }
        change(fixture.annotation, NONE)
        onMain {
            assertFalse(fixture.placement.isEnabled)
            assertEquals(TOP, fixture.placement.value)
            bind(fixture.placement).performClick()
            assertEquals(0, fixture.dialogs.get())
        }
        change(fixture.annotation, POLYPHONIC)
        onMain {
            assertTrue(fixture.placement.isEnabled)
            assertEquals(TOP, fixture.placement.value)
            assertEquals(3, fixture.saves.get())
        }
    }

    @Test
    fun aRootCategoryPageWithoutTheOptionsKeepsItsNavigationUsable() = withHost { activity ->
        onMain {
            val context = ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat)
            val manager = PreferenceManager(context)
            val raw = raw(NONE, TOP)
            var selected = -1
            var saves = 0
            val screen = PreferenceScreenFactory.create(
                manager,
                activity.supportFragmentManager,
                raw,
                save = { saves++ },
                layout = requireNotNull(AddonConfigLayouts.get(context, "pinyin")),
                onSectionClick = { selected = it },
            )
            assertNull(screen.findPreference<Preference>(ANNOTATION))
            assertNull(screen.findPreference<Preference>(PLACEMENT))
            val candidates = requireNotNull(screen.findPreference<Preference>("config_section_1"))
            bind(candidates).performClick()
            assertEquals(1, selected)
            assertEquals(0, saves)
            assertEquals(TOP, raw["cfg"][PLACEMENT].value)
        }
    }

    private enum class Scope { FLAT, GLOBAL_ADDON, INPUT_METHOD }

    private data class Fixture(
        val cfg: RawConfig,
        val screen: PreferenceScreen,
        val annotation: ListPreference,
        val placement: ListPreference,
        val saves: AtomicInteger,
        val dialogs: AtomicInteger,
    )

    private fun fixture(activity: FragmentActivity, scope: Scope, mode: String, placement: String): Fixture {
        val context = ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat)
        val manager = PreferenceManager(context)
        val raw = raw(mode, placement)
        val saves = AtomicInteger()
        val dialogs = AtomicInteger()
        manager.setOnDisplayPreferenceDialogListener { dialogs.incrementAndGet() }
        val layout = when (scope) {
            Scope.FLAT -> null
            Scope.GLOBAL_ADDON -> requireNotNull(AddonConfigLayouts.get(context, "pinyin"))
            Scope.INPUT_METHOD -> requireNotNull(AddonConfigLayouts.getForInputMethod(context, "shuangpin-xiaohe"))
        }
        val screen = PreferenceScreenFactory.create(
            manager,
            activity.supportFragmentManager,
            raw,
            save = { saves.incrementAndGet() },
            layout = layout,
            sectionIndex = if (scope == Scope.FLAT) null else 1,
        )
        return Fixture(
            raw["cfg"], screen,
            requireNotNull(screen.findPreference<ListPreference>(ANNOTATION)),
            requireNotNull(screen.findPreference<ListPreference>(PLACEMENT)), saves, dialogs,
        )
    }

    private fun raw(mode: String, placement: String) = RawConfig(subItems = arrayOf(
        RawConfig("cfg", subItems = arrayOf(RawConfig(ANNOTATION, mode), RawConfig(PLACEMENT, placement))),
        RawConfig("desc", subItems = arrayOf(RawConfig("PinyinEngineConfig", subItems = arrayOf(
            enumDescriptor(ANNOTATION, RARE, listOf(NONE, RARE, POLYPHONIC, ALL)),
            enumDescriptor(PLACEMENT, RIGHT, listOf(TOP, RIGHT)),
        )))),
    ))

    private fun enumDescriptor(name: String, default: String, values: List<String>) = RawConfig(
        name, subItems = arrayOf(
            RawConfig("Type", "Enum"),
            RawConfig("Description", name),
            RawConfig("DefaultValue", default),
            RawConfig("Enum", subItems = values.mapIndexed { index, value -> RawConfig(index.toString(), value) }.toTypedArray()),
        ),
    )

    private fun change(preference: ListPreference, value: String) {
        onMain {
            assertTrue(preference.callChangeListener(value))
            preference.value = value
        }
        instrumentation.waitForIdleSync()
    }

    private fun bind(preference: Preference): View {
        val context = preference.context
        val parent = FrameLayout(context)
        val inflater = LayoutInflater.from(context)
        val row = inflater.inflate(preference.layoutResource, parent, false)
        if (preference.widgetLayoutResource != 0) {
            row.findViewById<ViewGroup>(android.R.id.widget_frame)?.let {
                inflater.inflate(preference.widgetLayoutResource, it)
            }
        }
        parent.addView(row)
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row))
        return row
    }

    private fun withHost(block: (FragmentActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            block(activity)
        } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        instrumentation.runOnMainSync(task)
        return task.get()
    }

    private companion object {
        const val ANNOTATION = "PinyinAnnotation"
        const val PLACEMENT = "PinyinAnnotationPlacement"
        const val NONE = "Do not show"
        const val RARE = "Only rare characters"
        const val POLYPHONIC = "Automatic"
        const val ALL = "Always show"
        const val TOP = "Top"
        const val RIGHT = "Right"
    }
}
