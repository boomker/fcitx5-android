/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.FrameLayout
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.fxboomk.fcitx5.android.ui.main.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 31)
class KeyboardGlassWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun unattachedAnchorNeverCreatesAWindowAndDisposeIsIdempotent() {
        onMain {
            val availability = mutableListOf<Boolean>()
            val controller = KeyboardGlassWindow(
                View(instrumentation.targetContext),
                { FloatArray(8) },
                { 24 },
                availability::add
            )
            val initial = glassWindows().size
            controller.attach()
            controller.attach()
            controller.refresh()
            controller.dispose()
            controller.dispose()
            assertEquals(initial, glassWindows().size)
            assertTrue(availability.none { it })
        }
    }

    @Test
    fun blurWindowFollowsOnlyTheAnchorAndReleasesWhenHiddenOrDetached() {
        val context = instrumentation.targetContext
        val manager = context.getSystemService(WindowManager::class.java)
        assumeTrue("Device does not currently support cross-window blur", manager.isCrossWindowBlurEnabled)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        lateinit var anchor: View
        lateinit var parent: FrameLayout
        lateinit var controller: KeyboardGlassWindow
        var anchorCreated = false
        var controllerCreated = false
        var available = false
        try {
            onMain {
                parent = activity.findViewById(android.R.id.content)
                anchor = View(activity)
                anchorCreated = true
                parent.addView(anchor, FrameLayout.LayoutParams(240, 180).apply {
                    leftMargin = 12
                    topMargin = 36
                })
                controller = KeyboardGlassWindow(
                    anchor,
                    { floatArrayOf(18f, 18f, 18f, 18f, 0f, 0f, 0f, 0f) },
                    { 24 },
                    { available = it }
                )
                controllerCreated = true
                controller.attach()
                controller.attach()
            }
            await {
                val blur = glassWindows().singleOrNull()
                available && blur != null && blur.width == anchor.width && blur.height == anchor.height
            }
            onMain {
                val window = glassWindows().single()
                val attributes = window.layoutParams as WindowManager.LayoutParams
                val position = IntArray(2)
                anchor.getLocationOnScreen(position)
                assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA, attributes.type)
                assertEquals(anchor.width, attributes.width)
                assertEquals(anchor.height, attributes.height)
                assertEquals(position[0], attributes.x)
                assertEquals(position[1], attributes.y)
                val anchorOnScreen = IntArray(2)
                val blurOnScreen = IntArray(2)
                anchor.getLocationOnScreen(anchorOnScreen)
                window.getLocationOnScreen(blurOnScreen)
                assertEquals(anchorOnScreen.toList(), blurOnScreen.toList())
                assertEquals(anchor.width, window.width)
                assertEquals(anchor.height, window.height)
                assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
                assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
                assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
                assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                assertNotNull(attributes.token)
                anchor.translationX = 23f
                anchor.translationY = 31f
            }
            await {
                val blur = glassWindows().singleOrNull()
                val attributes = blur?.layoutParams as? WindowManager.LayoutParams
                val position = IntArray(2)
                val blurPosition = IntArray(2)
                anchor.getLocationOnScreen(position)
                blur?.getLocationOnScreen(blurPosition)
                attributes?.x == position[0] && attributes.y == position[1] &&
                    blurPosition.toList() == position.toList()
            }
            onMain {
                anchor.visibility = View.INVISIBLE
                controller.refresh()
                assertFalse(available)
            }
            await { glassWindows().isEmpty() }
            onMain {
                anchor.visibility = View.VISIBLE
                controller.refresh()
            }
            await { available && glassWindows().size == 1 }
            onMain { parent.removeView(anchor) }
            await { !available && glassWindows().isEmpty() }
        } finally {
            onMain {
                if (controllerCreated) controller.dispose()
                if (anchorCreated) (anchor.parent as? FrameLayout)?.removeView(anchor)
                activity.finish()
            }
        }
    }

    private fun glassWindows(): List<View> = WindowInspector.getGlobalWindowViews().filter { view ->
        (view.layoutParams as? WindowManager.LayoutParams)?.title == KeyboardGlassWindow.WINDOW_TITLE
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var complete = false
            onMain { complete = condition() }
            if (complete) return
            SystemClock.sleep(25)
        }
        onMain { assertTrue("Keyboard blur window did not reach its expected state", condition()) }
    }
}
