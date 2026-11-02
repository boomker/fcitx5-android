/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainLabelPlacementTest {
    @Test
    fun singleEdgeCentersVisibleInkOnFace() {
        val geometry = computeMainLabelGeometry(
            bandTop = 210f,
            bandBottom = 360f,
            faceCenter = 275f,
            inkTop = -60f,       // b: ascender
            inkBottom = 0f,
            minGap = 5f,
        )

        val inkCenter = geometry.baseline + (-60f + 0f) * geometry.scale / 2f
        assertEquals(275f, inkCenter, 0.01f)
        assertTrue("letter must clear the top sublabel", geometry.baseline - 60f * geometry.scale >= 215f)
    }

    @Test
    fun singleEdgeKeepsTheAnchorWhenSubLabelClearanceRequiresShrinking() {
        val geometry = computeMainLabelGeometry(
            bandTop = 210f,
            bandBottom = 360f,
            faceCenter = 275f,
            inkTop = -200f,
            inkBottom = 0f,
            minGap = 5f,
        )

        assertEquals(275f, geometry.baseline + geometry.scale * -100f, 0.01f)
        assertEquals(0.6f, geometry.scale, 0.01f)
    }

    @Test
    fun twoEdgeBandsDoNotMoveTheMainLabelAwayFromTheKeyFace() {
        val geometry = computeMainLabelGeometry(
            bandTop = 220f,
            bandBottom = 340f,
            faceCenter = 275f,
            inkTop = -40f,
            inkBottom = 0f,
            minGap = 5f,
        )

        val inkCenter = geometry.baseline + (-40f + 0f) * geometry.scale / 2f
        val inkTop = geometry.baseline + geometry.scale * -40f
        val inkBottom = geometry.baseline
        assertEquals(275f, inkCenter, 0.01f)
        assertTrue("ink must clear the top sublabel", inkTop >= 225f)
        assertTrue("ink must clear the bottom sublabel", inkBottom <= 335f)
    }

    @Test
    fun sharedInputsProduceTheSameGeometry() {
        val first = computeMainLabelGeometry(210f, 360f, 275f, -60f, 0f, 5f)
        val second = computeMainLabelGeometry(210f, 360f, 275f, -60f, 0f, 5f)
        assertEquals(first, second)
    }
}
