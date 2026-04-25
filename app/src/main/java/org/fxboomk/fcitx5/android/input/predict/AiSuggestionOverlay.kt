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
import kotlin.math.max
import kotlin.math.min

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
    var onLongFormClick: (() -> Unit)? = null

    private val edgeGap = context.dp(12).toFloat()
    private val minPanelWidth = context.dp(220)
    private val maxPanelWidth = context.dp(320)

    private var keyboardLeft = 0f
    private var keyboardWidth = 0f
    private var fallbackTop = 0f
    private var currentState = AiSuggestionStripComponent.PresentationState(
        mode = AiSuggestionStripComponent.PresentationMode.Hidden,
        suggestions = emptyList(),
        anchor = null,
        panelSuggestions = emptyList(),
        isLongFormEnabled = false,
        isSingleTextMode = false,
        isSingleTextLoading = false,
        isQuestionAnswerEnabled = false,
        isThinkingEnabled = false,
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
        onQuestionAnswerClick = { onQuestionAnswerClick?.invoke() },
        onThinkingClick = { onThinkingClick?.invoke() },
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
        currentState = state
        bubbleUi.updateCount(state.suggestions.size)
        panelUi.updateContent(
            values = state.panelSuggestions,
            isLongFormEnabled = state.isLongFormEnabled,
            isSingleTextMode = state.isSingleTextMode,
            isSingleTextLoading = state.isSingleTextLoading,
            isQuestionAnswerEnabled = state.isQuestionAnswerEnabled,
            isThinkingEnabled = state.isThinkingEnabled,
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

        val preferredKeyboardWidth = keyboardWidth.takeIf { it > 0f }?.toInt()
        val maxAllowedWidth = max(width, minPanelWidth)
        val targetPanelWidth = preferredKeyboardWidth
            ?.coerceAtMost(maxAllowedWidth)
            ?.coerceAtLeast(minPanelWidth)
            ?: min(maxPanelWidth, maxAllowedWidth)
        panelUi.updateLayoutParams<LayoutParams> {
            width = targetPanelWidth
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
        val panelWidth = panelUi.width.toFloat().takeIf { it > 0f } ?: return
        val panelHeight = panelUi.height.toFloat().takeIf { it > 0f } ?: return
        val anchor = currentState.anchor

        val x = if (keyboardWidth > 0f) {
            keyboardLeft.coerceIn(0f, width - panelWidth)
        } else if (anchor != null) {
            (anchor.horizontal - panelWidth / 2f).coerceIn(edgeGap, width - panelWidth - edgeGap)
        } else {
            width - panelWidth - edgeGap
        }
        val keyboardBarTop = fallbackTop - context.dp(KawaiiBarComponent.HEIGHT).toFloat()
        val minTop = max(fallbackTop + edgeGap, edgeGap)
        val maxTop = height - panelHeight - edgeGap
        val preferredBelow = anchor?.let { max(it.bottom + edgeGap, minTop) } ?: minTop
        val fallbackAbove = anchor?.let { it.top - panelHeight - edgeGap } ?: (keyboardBarTop - panelHeight - edgeGap)
        val maxAboveTop = keyboardBarTop - panelHeight - edgeGap
        val y = when {
            preferredBelow <= maxTop -> preferredBelow
            fallbackAbove <= maxAboveTop && fallbackAbove >= edgeGap -> fallbackAbove
            else -> minTop.coerceAtMost(maxTop.coerceAtLeast(minTop))
        }

        panelUi.translationX = x
        panelUi.translationY = y
    }

    private fun isWithinKeyboardBar(event: MotionEvent): Boolean {
        if (keyboardWidth <= 0f) return false
        val x = event.x
        val y = event.y
        val left = keyboardLeft
        val right = keyboardLeft + keyboardWidth
        val top = fallbackTop - context.dp(KawaiiBarComponent.HEIGHT).toFloat()
        val bottom = fallbackTop
        return x in left..right && y in top..bottom
    }
}
