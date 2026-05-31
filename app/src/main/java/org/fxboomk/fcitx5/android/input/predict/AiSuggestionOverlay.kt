package org.fxboomk.fcitx5.android.input.predict

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarComponent
import splitties.dimensions.dp
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class AiSuggestionOverlay(
    context: Context,
    theme: Theme,
) : FrameLayout(context) {

    var onBubbleClick: (() -> Unit)? = null
    var onDismissRequest: (() -> Unit)? = null
    var onSuggestionClick: ((String) -> Unit)? = null
    var onCollapseClick: (() -> Unit)? = null
    var onQuestionAnswerClick: (() -> Unit)? = null
    var onThinkingClick: (() -> Unit)? = null
    var onTranslateClick: (() -> Unit)? = null
    var onLongFormClick: (() -> Unit)? = null

    private val edgeGap = context.dp(12).toFloat()
    private val keyboardBarHeight = context.dp(KawaiiBarComponent.HEIGHT).toFloat()
    private val minPanelWidth = context.dp(220)
    private val maxPanelWidth = context.dp(320)

    private var keyboardLeft = 0f
    private var keyboardWidth = 0f
    private var fallbackTop = 0f
    private var panelExpanded = false
    private var manualPanelPlacement: AiSuggestionPanelPlacement? = null
    private var draggingPanel = false
    private var panelDragOffsetX = 0f
    private var panelDragOffsetY = 0f
    private var currentState = AiSuggestionStripComponent.PresentationState(
        mode = AiSuggestionStripComponent.PresentationMode.Hidden,
        suggestions = emptyList(),
        anchor = null,
        panelSuggestions = emptyList(),
        isPanelOpen = false,
        isLongFormEnabled = false,
        isSingleTextMode = false,
        isLoading = false,
        loadingLabel = null,
        isQuestionAnswerEnabled = false,
        isThinkingEnabled = false,
        isTranslateEnabled = false,
    )

    private val bubbleUi = AiSuggestionBubbleUi(context, theme).apply {
        visibility = GONE
        setOnClickListener { onBubbleClick?.invoke() }
    }

    private val panelUi = AiSuggestionPanelUi(
        context = context,
        theme = theme,
        onSuggestionClick = { suggestion -> onSuggestionClick?.invoke(suggestion) },
        onCollapseClick = { onCollapseClick?.invoke() ?: onDismissRequest?.invoke() },
        onResizeToggleRequest = { togglePanelExpanded() },
        onHeaderDragStart = { rawX, rawY -> beginPanelDrag(rawX, rawY) },
        onHeaderDragMove = { rawX, rawY -> updatePanelDrag(rawX, rawY) },
        onHeaderDragEnd = { endPanelDrag() },
        onQuestionAnswerClick = { onQuestionAnswerClick?.invoke() },
        onThinkingClick = { onThinkingClick?.invoke() },
        onTranslateClick = { onTranslateClick?.invoke() },
        onLongFormClick = { onLongFormClick?.invoke() },
    ).apply {
        visibility = GONE
    }

    init {
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        addView(
            bubbleUi,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
        addView(
            panelUi,
            LayoutParams(
                minPanelWidth,
                LayoutParams.WRAP_CONTENT,
            )
        )
    }

    fun render(state: AiSuggestionStripComponent.PresentationState) {
        if (state.mode != AiSuggestionStripComponent.PresentationMode.PanelVisible) {
            draggingPanel = false
            manualPanelPlacement = null
        }
        if (state.mode == AiSuggestionStripComponent.PresentationMode.Hidden) {
            panelExpanded = false
        }
        currentState = state
        bubbleUi.updateCount(state.suggestions.size)
        panelUi.updateContent(
            values = state.panelSuggestions,
            isLongFormEnabled = state.isLongFormEnabled,
            isSingleTextMode = state.isSingleTextMode,
            isLoading = state.isLoading,
            loadingLabel = state.loadingLabel,
            isQuestionAnswerEnabled = state.isQuestionAnswerEnabled,
            isThinkingEnabled = state.isThinkingEnabled,
            isTranslateEnabled = state.isTranslateEnabled,
        )

        bubbleUi.visibility = when (state.mode) {
            AiSuggestionStripComponent.PresentationMode.BubbleAnchored,
            AiSuggestionStripComponent.PresentationMode.BubbleFallback -> View.VISIBLE
            else -> View.GONE
        }
        panelUi.visibility = if (state.mode == AiSuggestionStripComponent.PresentationMode.PanelVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
        visibility = if (state.mode == AiSuggestionStripComponent.PresentationMode.Hidden) {
            View.GONE
        } else {
            View.VISIBLE
        }
        post { positionChildren() }
    }

    fun updateFallbackTop(top: Float) {
        fallbackTop = top
        if (visibility == View.VISIBLE) {
            post { positionChildren() }
        }
    }

    fun updateKeyboardFrame(left: Float, width: Float) {
        keyboardLeft = left
        keyboardWidth = width
        if (visibility == View.VISIBLE) {
            post { positionChildren() }
        }
    }

    fun unionTouchableRegion(outRegion: Region) {
        if (visibility != View.VISIBLE) return
        val target = when {
            panelUi.visibility == View.VISIBLE -> panelUi
            bubbleUi.visibility == View.VISIBLE -> bubbleUi
            else -> null
        } ?: return
        val location = IntArray(2)
        target.getLocationInWindow(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + target.width,
            location[1] + target.height,
        )
        if (!rect.isEmpty) {
            outRegion.union(rect)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            currentState.mode == AiSuggestionStripComponent.PresentationMode.PanelVisible &&
            event.actionMasked == MotionEvent.ACTION_DOWN &&
            isWithinKeyboardBar(event).not()
        ) {
            val panelRect = Rect(
                panelUi.translationX.toInt(),
                panelUi.translationY.toInt(),
                (panelUi.translationX + panelUi.width).toInt(),
                (panelUi.translationY + panelUi.height).toInt(),
            )
            if (!panelRect.contains(event.x.toInt(), event.y.toInt())) {
                onDismissRequest?.invoke()
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun positionChildren() {
        if (width <= 0 || height <= 0) return

        val targetPanelWidth = resolveAiSuggestionPanelWidth(
            containerWidth = width,
            edgeGap = edgeGap.toInt(),
            minPanelWidth = minPanelWidth,
            maxPanelWidth = maxPanelWidth,
            keyboardWidth = keyboardWidth.takeIf { it > 0f }?.toInt(),
        )
        val targetPanelHeight = if (panelExpanded) {
            resolveAiSuggestionPanelExpandedHeight(
                containerHeight = height,
                edgeGap = edgeGap.roundToInt(),
            )
        } else {
            LayoutParams.WRAP_CONTENT
        }
        val widthChanged = panelUi.layoutParams.width != targetPanelWidth
        val heightChanged = panelUi.layoutParams.height != targetPanelHeight
        panelUi.setExpandedState(
            expanded = panelExpanded,
            panelHeight = targetPanelHeight.takeIf { it > 0 },
        )
        panelUi.updateLayoutParams<LayoutParams> {
            width = targetPanelWidth
            height = targetPanelHeight
        }
        if (widthChanged || heightChanged) {
            panelUi.requestLayout()
            post { positionChildren() }
        }

        when (currentState.mode) {
            AiSuggestionStripComponent.PresentationMode.Hidden -> Unit
            AiSuggestionStripComponent.PresentationMode.BubbleAnchored,
            AiSuggestionStripComponent.PresentationMode.BubbleFallback -> positionBubble()
            AiSuggestionStripComponent.PresentationMode.PanelVisible -> positionPanel()
        }
    }

    private fun positionBubble() {
        val bubbleWidth = bubbleUi.width.toFloat().takeIf { it > 0f } ?: return
        val bubbleHeight = bubbleUi.height.toFloat().takeIf { it > 0f } ?: return
        val anchor = currentState.anchor

        val x = if (currentState.mode == AiSuggestionStripComponent.PresentationMode.BubbleAnchored && anchor != null) {
            val afterCursor = anchor.horizontal + edgeGap
            val beforeCursor = anchor.horizontal - bubbleWidth - edgeGap
            if (afterCursor + bubbleWidth <= width - edgeGap) {
                afterCursor
            } else {
                beforeCursor.coerceAtLeast(edgeGap)
            }
        } else {
            width - bubbleWidth - edgeGap
        }
        val y = if (currentState.mode == AiSuggestionStripComponent.PresentationMode.BubbleAnchored && anchor != null) {
            val lineCenter = (anchor.top + anchor.bottom) / 2f
            (lineCenter - bubbleHeight / 2f).coerceIn(edgeGap, height - bubbleHeight - edgeGap)
        } else {
            (fallbackTop + edgeGap).coerceIn(edgeGap, height - bubbleHeight - edgeGap)
        }

        bubbleUi.translationX = x
        bubbleUi.translationY = y
    }

    private fun positionPanel() {
        val panelWidth = panelUi.width.toFloat().takeIf { it > 0f }
            ?: panelUi.layoutParams.width.toFloat().takeIf { it > 0f }
            ?: return
        val panelHeight = panelUi.height.toFloat().takeIf { it > 0f }
            ?: panelUi.layoutParams.height.toFloat().takeIf { it > 0f }
            ?: return
        val manualPlacement = manualPanelPlacement
        if (manualPlacement != null) {
            val clampedPlacement = clampPanelPlacement(
                placement = manualPlacement,
                panelWidth = panelWidth,
                panelHeight = panelHeight,
            )
            manualPanelPlacement = clampedPlacement
            applyPanelPlacement(clampedPlacement)
            return
        }
        val placement = computeAiSuggestionPanelPlacement(
            AiSuggestionPanelLayoutRequest(
                containerWidth = width.toFloat(),
                containerHeight = height.toFloat(),
                panelWidth = panelWidth,
                panelHeight = panelHeight,
                edgeGap = edgeGap,
                fallbackTop = fallbackTop,
                keyboardLeft = keyboardLeft,
                keyboardWidth = keyboardWidth,
                keyboardBarHeight = keyboardBarHeight,
                anchor = currentState.anchor,
            )
        )
        applyPanelPlacement(placement)
    }

    private fun isWithinKeyboardBar(event: MotionEvent): Boolean {
        if (keyboardWidth <= 0f) return false
        val x = event.x
        val y = event.y
        val left = keyboardLeft
        val right = keyboardLeft + keyboardWidth
        val top = fallbackTop - keyboardBarHeight
        val bottom = fallbackTop
        return x in left..right && y in top..bottom
    }

    private fun togglePanelExpanded() {
        if (currentState.mode != AiSuggestionStripComponent.PresentationMode.PanelVisible) return
        panelExpanded = !panelExpanded
        post { positionChildren() }
    }

    private fun beginPanelDrag(rawX: Float, rawY: Float) {
        if (currentState.mode != AiSuggestionStripComponent.PresentationMode.PanelVisible) return
        val panelWidth = panelUi.width.toFloat().takeIf { it > 0f } ?: return
        val panelHeight = panelUi.height.toFloat().takeIf { it > 0f } ?: return
        draggingPanel = true
        val currentPlacement = clampPanelPlacement(
            placement = AiSuggestionPanelPlacement(
                x = panelUi.translationX,
                y = panelUi.translationY,
            ),
            panelWidth = panelWidth,
            panelHeight = panelHeight,
        )
        manualPanelPlacement = currentPlacement
        panelDragOffsetX = rawX - currentPlacement.x
        panelDragOffsetY = rawY - currentPlacement.y
    }

    private fun updatePanelDrag(rawX: Float, rawY: Float) {
        if (!draggingPanel) return
        val panelWidth = panelUi.width.toFloat().takeIf { it > 0f } ?: return
        val panelHeight = panelUi.height.toFloat().takeIf { it > 0f } ?: return
        val placement = clampPanelPlacement(
            placement = AiSuggestionPanelPlacement(
                x = rawX - panelDragOffsetX,
                y = rawY - panelDragOffsetY,
            ),
            panelWidth = panelWidth,
            panelHeight = panelHeight,
        )
        manualPanelPlacement = placement
        applyPanelPlacement(placement)
    }

    private fun endPanelDrag() {
        draggingPanel = false
    }

    private fun clampPanelPlacement(
        placement: AiSuggestionPanelPlacement,
        panelWidth: Float,
        panelHeight: Float,
    ): AiSuggestionPanelPlacement {
        val maxX = (width - panelWidth - edgeGap).coerceAtLeast(edgeGap)
        val maxY = (height - panelHeight - edgeGap).coerceAtLeast(edgeGap)
        return AiSuggestionPanelPlacement(
            x = placement.x.coerceIn(edgeGap, maxX),
            y = placement.y.coerceIn(edgeGap, maxY),
        )
    }

    private fun applyPanelPlacement(placement: AiSuggestionPanelPlacement) {
        panelUi.translationX = placement.x
        panelUi.translationY = placement.y
    }
}
