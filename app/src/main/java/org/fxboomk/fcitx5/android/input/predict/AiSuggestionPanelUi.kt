package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
    private val onQuestionAnswerClick: () -> Unit,
    private val onThinkingClick: () -> Unit,
    private val onLongFormClick: () -> Unit,
) : LinearLayout(context) {

    private data class PanelItem(
        val text: String,
        val enabled: Boolean,
    )

    private val collapseButton = ImageView(context).apply {
        setImageResource(R.drawable.ic_baseline_expand_less_24)
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
        layoutManager = GridLayoutManager(context, spanCountFor(context.resources.configuration, false))
        adapter = this@AiSuggestionPanelUi.adapter
    }

    private var singleTextMode = false

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
    }

    fun updateContent(
        values: List<String>,
        isLongFormEnabled: Boolean,
        isSingleTextMode: Boolean,
        isSingleTextLoading: Boolean,
        isQuestionAnswerEnabled: Boolean,
        isThinkingEnabled: Boolean,
    ) {
        singleTextMode = isSingleTextMode
        updateActionChip(questionAnswerChip, active = isQuestionAnswerEnabled)
        updateActionChip(thinkingChip, active = isThinkingEnabled)
        updateActionChip(longFormChip, active = isLongFormEnabled)
        val items = if (isSingleTextLoading && values.isEmpty()) {
            listOf(
                PanelItem(
                    if (isQuestionAnswerEnabled) {
                        context.getString(R.string.ai_clip_answer_loading)
                    } else {
                        context.getString(R.string.ai_clip_long_form_loading)
                    },
                    false,
                )
            )
        } else {
            values.map { PanelItem(it, !isSingleTextLoading) }
        }
        adapter.submitList(items, isSingleTextMode)
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount =
            spanCountFor(resources.configuration, isSingleTextMode)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount =
            spanCountFor(newConfig, singleTextMode)
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

    private fun spanCountFor(configuration: Configuration, isLongForm: Boolean): Int {
        if (isLongForm) return 1
        return if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
    }

    private class SuggestionAdapter(
        private val theme: Theme,
        private val onSuggestionClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SuggestionViewHolder>() {
        private var suggestions: List<PanelItem> = emptyList()
        private var singleColumn = false

        fun submitList(values: List<PanelItem>, singleColumn: Boolean) {
            suggestions = values.toList()
            this.singleColumn = singleColumn
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
            holder.textView.maxLines = if (singleColumn) 6 else 3
            holder.textView.gravity = if (singleColumn) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
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
