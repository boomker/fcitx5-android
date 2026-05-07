package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Test

class AiSuggestionOverlayLayoutTest {

    @Test
    fun expandedHeightUsesThreeQuartersOfScreenHeight() {
        val expandedHeight = resolveAiSuggestionPanelExpandedHeight(
            containerHeight = 1200,
            edgeGap = 12,
        )

        assertEquals(900, expandedHeight)
    }

    @Test
    fun placementFollowsKeyboardAlignmentAndPrefersBelowAnchor() {
        val anchor = AiSuggestionStripComponent.CursorAnchorState(
            horizontal = 280f,
            bottom = 340f,
            top = 300f,
            parentWidth = 1080f,
            parentHeight = 1600f,
        )

        val placement = computeAiSuggestionPanelPlacement(
            AiSuggestionPanelLayoutRequest(
                containerWidth = 1080f,
                containerHeight = 1600f,
                panelWidth = 360f,
                panelHeight = 240f,
                edgeGap = 12f,
                fallbackTop = 328f,
                keyboardLeft = 96f,
                keyboardWidth = 880f,
                keyboardBarHeight = 56f,
                anchor = anchor,
            )
        )

        assertEquals(96f, placement.x, 0.01f)
        assertEquals(anchor.bottom + 12f, placement.y, 0.01f)
    }

    @Test
    fun placementFallsBackToBottomVisibleSlot() {
        val anchor = AiSuggestionStripComponent.CursorAnchorState(
            horizontal = 520f,
            bottom = 900f,
            top = 860f,
            parentWidth = 1080f,
            parentHeight = 1100f,
        )

        val placement = computeAiSuggestionPanelPlacement(
            AiSuggestionPanelLayoutRequest(
                containerWidth = 1080f,
                containerHeight = 1100f,
                panelWidth = 420f,
                panelHeight = 260f,
                edgeGap = 12f,
                fallbackTop = 900f,
                keyboardLeft = 120f,
                keyboardWidth = 840f,
                keyboardBarHeight = 56f,
                anchor = anchor,
            )
        )

        assertEquals(120f, placement.x, 0.01f)
        assertEquals(828f, placement.y, 0.01f)
    }
}
