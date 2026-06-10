package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class AiSuggestionPanelUi(
    context: Context,
    private val theme: Theme,
    private val onSuggestionClick: (String) -> Unit,
    private val onCollapseClick: () -> Unit,
    private val onResizeToggleRequest: () -> Unit,
    private val onHeaderDragStart: (rawX: Float, rawY: Float) -> Unit,
    private val onHeaderDragMove: (rawX: Float, rawY: Float) -> Unit,
    private val onHeaderDragEnd: () -> Unit,
    private val onQuestionAnswerClick: () -> Unit,
    private val onThinkingClick: () -> Unit,
    private val onTranslateClick: () -> Unit,
    private val onLongFormClick: () -> Unit,
) : LinearLayout(context) {

    private data class PanelItem(
        val text: String,
        val enabled: Boolean,
    )

    private val collapseButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_baseline_arrow_back_24)
        contentDescription = context.getString(R.string.ai_clip_collapse)
        setColorFilter(theme.altKeyTextColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12).toFloat()
            setColor(theme.keyBackgroundColor)
            setStroke(context.dp(1), theme.dividerColor)
        }
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        setOnClickListener { onCollapseClick() }
        setOnLongClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onResizeToggleRequest()
            true
        }
    }

    private val titleView = TextView(context).apply {
        text = context.getString(R.string.ai_clip_title)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(theme.candidateTextColor)
    }

    private val thinkingChip = createActionChip(context.getString(R.string.ai_clip_thinking)) {
        onThinkingClick()
    }

    private val questionAnswerChip = createActionChip(context.getString(R.string.ai_clip_question_answer)) {
        onQuestionAnswerClick()
    }

    private val translateChip = createActionChip(context.getString(R.string.ai_clip_translate)) {
        onTranslateClick()
    }

    private val longFormChip = createActionChip(context.getString(R.string.ai_clip_long_form)) {
        onLongFormClick()
    }

    private val headerView = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(8))
        addView(
            collapseButton,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
        addView(
            titleView,
            LayoutParams(
                0,
                LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                marginStart = context.dp(10)
                marginEnd = context.dp(10)
            }
        )
        addView(
            thinkingChip,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = context.dp(8)
            }
        )
        addView(
            translateChip,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = context.dp(8)
            }
        )
        addView(
            questionAnswerChip,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = context.dp(8)
            }
        )
        addView(
            longFormChip,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
    }

    private val adapter = SuggestionAdapter(theme, onSuggestionClick)

    private val recyclerView = RecyclerView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(context.dp(10), 0, context.dp(10), context.dp(10))
        layoutManager = GridLayoutManager(context, spanCountFor(context.resources.configuration))
        adapter = this@AiSuggestionPanelUi.adapter
    }

    private val singleTextView = TextView(context).apply {
        textSize = 16f
        gravity = Gravity.START or Gravity.TOP
        setTextColor(theme.candidateTextColor)
        setPadding(context.dp(12), context.dp(14), context.dp(12), context.dp(14))
    }

    private val singleTextScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(context.dp(10), 0, context.dp(10), context.dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12).toFloat()
            setColor(theme.keyBackgroundColor)
            setStroke(context.dp(1), theme.dividerColor)
        }
        addView(
            singleTextView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
    }

    private var headerDragActive = false
    private var lastHeaderDragRawX = 0f
    private var lastHeaderDragRawY = 0f

    init {
        orientation = VERTICAL
        clipToPadding = false
        elevation = context.dp(10).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(16).toFloat()
            setColor(theme.keyboardColor)
            setStroke(context.dp(1), theme.dividerColor)
        }
        addView(
            headerView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
        addView(
            recyclerView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
        addView(
            singleTextScrollView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                val margin = context.dp(6)
                setMargins(margin, margin, margin, margin)
            }
        )
        titleView.setOnLongClickListener { view ->
            headerDragActive = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            view.parent?.requestDisallowInterceptTouchEvent(true)
            onHeaderDragStart(lastHeaderDragRawX, lastHeaderDragRawY)
            true
        }
        titleView.setOnTouchListener { view, event ->
            lastHeaderDragRawX = event.rawX
            lastHeaderDragRawY = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    headerDragActive = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!headerDragActive) {
                        false
                    } else {
                        onHeaderDragMove(event.rawX, event.rawY)
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!headerDragActive) {
                        false
                    } else {
                        headerDragActive = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        onHeaderDragEnd()
                        true
                    }
                }
                else -> false
            }
        }
    }

    fun updateContent(
        values: List<String>,
        singleTextCommitValue: String?,
        isLongFormEnabled: Boolean,
        isSingleTextMode: Boolean,
        isLoading: Boolean,
        loadingLabel: String?,
        isQuestionAnswerEnabled: Boolean,
        isThinkingEnabled: Boolean,
        isTranslateEnabled: Boolean,
    ) {
        updateActionChip(questionAnswerChip, active = isQuestionAnswerEnabled)
        updateActionChip(thinkingChip, active = isThinkingEnabled)
        updateActionChip(translateChip, active = isTranslateEnabled)
        updateActionChip(longFormChip, active = isLongFormEnabled)
        val items = if (isLoading && values.isEmpty()) {
            listOf(
                PanelItem(
                    loadingLabel.orEmpty(),
                    false,
                )
            )
        } else {
            values.map { PanelItem(it, !isLoading) }
        }
        val singleItem = items.firstOrNull()
        if (isSingleTextMode) {
            recyclerView.visibility = View.GONE
            singleTextScrollView.visibility = View.VISIBLE
            singleTextView.text = singleItem?.text.orEmpty()
            val singleTextClickValue = singleTextCommitValue
                ?.takeIf(String::isNotBlank)
                ?: singleItem?.text
            val singleTextClickListener = if (singleItem?.enabled == true && !singleTextClickValue.isNullOrBlank()) {
                View.OnClickListener { onSuggestionClick(singleTextClickValue) }
            } else {
                null
            }
            singleTextScrollView.setOnClickListener(singleTextClickListener)
            singleTextScrollView.isClickable = singleTextClickListener != null
            singleTextScrollView.isFocusable = singleTextClickListener != null
            singleTextView.setOnClickListener(singleTextClickListener)
            singleTextView.isClickable = singleTextClickListener != null
            singleTextView.isFocusable = singleTextClickListener != null
            val singleTextAlpha = if (singleItem?.enabled == false) 0.7f else 1f
            singleTextScrollView.alpha = singleTextAlpha
            singleTextView.alpha = singleTextAlpha
        } else {
            singleTextScrollView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            singleTextScrollView.setOnClickListener(null)
            singleTextScrollView.isClickable = false
            singleTextScrollView.isFocusable = false
            singleTextView.setOnClickListener(null)
            singleTextView.isClickable = false
            singleTextView.isFocusable = false
            singleTextScrollView.alpha = 1f
            singleTextView.alpha = 1f
            adapter.submitList(items)
            (recyclerView.layoutManager as? GridLayoutManager)?.spanCount =
                spanCountFor(resources.configuration)
        }
    }

    fun setExpandedState(expanded: Boolean, panelHeight: Int? = null) {
        val contentHeight = if (expanded) {
            resolveExpandedContentHeight(panelHeight)
        } else {
            LayoutParams.WRAP_CONTENT
        }
        recyclerView.updateLayoutParams<LayoutParams> {
            height = contentHeight
            weight = 0f
        }
        singleTextScrollView.updateLayoutParams<LayoutParams> {
            height = contentHeight
            weight = 0f
        }
        if (expanded && resolveHeaderHeight() == 0) {
            post { setExpandedState(true, panelHeight) }
        }
    }

    private fun resolveExpandedContentHeight(panelHeight: Int?): Int {
        val targetPanelHeight = panelHeight ?: return LayoutParams.WRAP_CONTENT
        val headerHeight = resolveHeaderHeight()
        val minContentHeight = context.dp(160)
        return (targetPanelHeight - headerHeight).coerceAtLeast(minContentHeight)
    }

    private fun resolveHeaderHeight(): Int {
        return headerView.height.takeIf { it > 0 }
            ?: headerView.measuredHeight.takeIf { it > 0 }
            ?: 0
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount =
            spanCountFor(newConfig)
    }

    private fun createActionChip(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        setTextColor(theme.altKeyTextColor)
        background = chipBackground(active = false)
        setOnClickListener { onClick() }
    }

    private fun updateActionChip(view: TextView, active: Boolean) {
        view.background = chipBackground(active = active)
        view.setTextColor(if (active) theme.keyTextColor else theme.altKeyTextColor)
    }

    private fun chipBackground(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(14).toFloat()
        setColor(if (active) theme.accentKeyBackgroundColor else theme.keyBackgroundColor)
        setStroke(context.dp(1), if (active) theme.accentKeyBackgroundColor else theme.dividerColor)
    }

    private fun spanCountFor(configuration: Configuration): Int {
        return if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
    }

    private class SuggestionAdapter(
        private val theme: Theme,
        private val onSuggestionClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SuggestionViewHolder>() {
        private var suggestions: List<PanelItem> = emptyList()

        fun submitList(values: List<PanelItem>) {
            suggestions = values.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
            val context = parent.context
            val textView = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.candidateTextColor)
                setPadding(context.dp(12), context.dp(14), context.dp(12), context.dp(14))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = context.dp(12).toFloat()
                    setColor(theme.keyBackgroundColor)
                    setStroke(context.dp(1), theme.dividerColor)
                }
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                ).apply {
                    val margin = context.dp(6)
                    setMargins(margin, margin, margin, margin)
                }
            }
            return SuggestionViewHolder(textView)
        }

        override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
            val item = suggestions[position]
            holder.textView.text = item.text
            holder.textView.maxLines = 3
            holder.textView.gravity = Gravity.CENTER
            holder.textView.alpha = if (item.enabled) 1f else 0.7f
            holder.textView.setOnClickListener(
                if (item.enabled) {
                    View.OnClickListener { onSuggestionClick(item.text) }
                } else {
                    null
                }
            )
        }

        override fun getItemCount(): Int = suggestions.size
    }

    private class SuggestionViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
