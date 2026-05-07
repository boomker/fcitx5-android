package org.fxboomk.fcitx5.android.input.predict

import kotlin.math.max
import kotlin.math.roundToInt

internal data class AiSuggestionPanelPlacement(
    val x: Float,
    val y: Float,
)

internal data class AiSuggestionPanelLayoutRequest(
    val containerWidth: Float,
    val containerHeight: Float,
    val panelWidth: Float,
    val panelHeight: Float,
    val edgeGap: Float,
    val fallbackTop: Float,
    val keyboardLeft: Float,
    val keyboardWidth: Float,
    val keyboardBarHeight: Float,
    val anchor: AiSuggestionStripComponent.CursorAnchorState?,
)

internal fun resolveAiSuggestionPanelWidth(
    containerWidth: Int,
    edgeGap: Int,
    minPanelWidth: Int,
    maxPanelWidth: Int,
    keyboardWidth: Int?,
): Int {
    val safeMaxWidth = (containerWidth - edgeGap * 2).coerceAtLeast(minPanelWidth)
    return keyboardWidth
        ?.coerceAtMost(safeMaxWidth)
        ?.coerceAtLeast(minPanelWidth)
        ?: minOf(maxPanelWidth, safeMaxWidth)
}

internal fun resolveAiSuggestionPanelExpandedHeight(
    containerHeight: Int,
    edgeGap: Int,
): Int {
    val safeMaxHeight = (containerHeight - edgeGap * 2).coerceAtLeast(0)
    return (containerHeight * 0.75f).roundToInt().coerceAtMost(safeMaxHeight)
}

internal fun computeAiSuggestionPanelPlacement(
    request: AiSuggestionPanelLayoutRequest,
): AiSuggestionPanelPlacement {
    val safeXMin = request.edgeGap
    val safeXMax = max(safeXMin, request.containerWidth - request.panelWidth - request.edgeGap)
    val safeYMin = request.edgeGap
    val safeYMax = max(safeYMin, request.containerHeight - request.panelHeight - request.edgeGap)
    val keyboardBarTop = request.fallbackTop - request.keyboardBarHeight
    val minTop = max(request.fallbackTop + request.edgeGap, safeYMin)
    val fallbackBelowKeyboard = minTop.coerceIn(safeYMin, safeYMax)

    val anchor = request.anchor
    val x = if (request.keyboardWidth > 0f) {
        request.keyboardLeft.coerceIn(safeXMin, safeXMax)
    } else if (anchor != null) {
        (anchor.horizontal - request.panelWidth / 2f).coerceIn(safeXMin, safeXMax)
    } else {
        (request.containerWidth - request.panelWidth - request.edgeGap).coerceIn(safeXMin, safeXMax)
    }
    val preferredBelow = anchor?.let { max(it.bottom + request.edgeGap, minTop) } ?: minTop
    val fallbackAbove = anchor?.let { it.top - request.panelHeight - request.edgeGap }
        ?: (keyboardBarTop - request.panelHeight - request.edgeGap)
    val maxAboveTop = keyboardBarTop - request.panelHeight - request.edgeGap
    val y = when {
        preferredBelow <= safeYMax -> preferredBelow
        fallbackAbove in safeYMin..maxAboveTop -> fallbackAbove
        else -> safeYMax
    }
    return AiSuggestionPanelPlacement(x = x, y = y)
}
