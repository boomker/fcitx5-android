package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class AiSuggestionExpandedUi(
    context: Context,
    private val theme: Theme,
    private val onSuggestionClick: (String) -> Unit,
    private val onQuestionAnswerClick: () -> Unit,
    private val onThinkingClick: () -> Unit,
    private val onTranslateClick: () -> Unit,
    private val onLongFormClick: () -> Unit,
) : LinearLayout(context) {

    private data class PanelItem(
        val text: String,
        val enabled: Boolean,
    )

    private val adapter = SuggestionAdapter(theme, onSuggestionClick)

    private val recyclerView = RecyclerView(context).apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        layoutManager = GridLayoutManager(context, spanCountFor(context.resources.configuration))
        adapter = this@AiSuggestionExpandedUi.adapter
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
        background = contentBackground()
        addView(
            singleTextView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            )
        )
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

    private val actionRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        weightSum = 4f
        val gap = context.dp(8)
        addView(thinkingChip, weightedChipLayoutParams(gap))
        addView(translateChip, weightedChipLayoutParams(gap))
        addView(questionAnswerChip, weightedChipLayoutParams(gap))
        addView(
            longFormChip,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        addView(
            recyclerView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )
        addView(
            singleTextScrollView,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply {
                val margin = context.dp(6)
                setMargins(margin, margin, margin, margin)
            }
        )
        addView(
            actionRow,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                val margin = context.dp(6)
                setMargins(margin, margin, margin, margin)
            }
        )
    }

    fun updateContent(
        visible: Boolean,
        values: List<String>,
        isLongFormEnabled: Boolean,
        isSingleTextMode: Boolean,
        isLoading: Boolean,
        loadingLabel: String?,
        isQuestionAnswerEnabled: Boolean,
        isThinkingEnabled: Boolean,
        isTranslateEnabled: Boolean,
    ) {
        visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        updateActionChip(questionAnswerChip, active = isQuestionAnswerEnabled)
        updateActionChip(thinkingChip, active = isThinkingEnabled)
        updateActionChip(translateChip, active = isTranslateEnabled)
        updateActionChip(longFormChip, active = isLongFormEnabled)

        val items = if (isLoading && values.isEmpty()) {
            listOf(PanelItem(loadingLabel.orEmpty(), false))
        } else {
            values.map { PanelItem(it, !isLoading) }
        }
        val singleItem = items.firstOrNull()

        if (isSingleTextMode) {
            recyclerView.visibility = View.GONE
            singleTextScrollView.visibility = View.VISIBLE
            singleTextView.text = singleItem?.text.orEmpty()
            val clickListener = if (singleItem?.enabled == true) {
                View.OnClickListener { onSuggestionClick(singleItem.text) }
            } else {
                null
            }
            singleTextScrollView.setOnClickListener(clickListener)
            singleTextView.setOnClickListener(clickListener)
            singleTextScrollView.alpha = if (singleItem?.enabled == false) 0.7f else 1f
            singleTextView.alpha = singleTextScrollView.alpha
        } else {
            singleTextScrollView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            singleTextScrollView.setOnClickListener(null)
            singleTextView.setOnClickListener(null)
            singleTextScrollView.alpha = 1f
            singleTextView.alpha = 1f
            adapter.submitList(items)
            (recyclerView.layoutManager as? GridLayoutManager)?.spanCount =
                spanCountFor(resources.configuration)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (recyclerView.layoutManager as? GridLayoutManager)?.spanCount = spanCountFor(newConfig)
    }

    fun scrollByPage(direction: Int) {
        if (direction == 0) return
        if (singleTextScrollView.visibility == View.VISIBLE) {
            singleTextScrollView.smoothScrollBy(0, direction * singleTextScrollView.height.coerceAtLeast(1))
        } else {
            recyclerView.smoothScrollBy(0, direction * recyclerView.height.coerceAtLeast(1))
        }
    }

    private fun weightedChipLayoutParams(marginEnd: Int) =
        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            this.marginEnd = marginEnd
        }

    private fun createActionChip(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        setTextColor(theme.altKeyTextColor)
        background = chipBackground(active = false)
        setOnClickListener { onClick() }
    }

    private fun updateActionChip(view: TextView, active: Boolean) {
        view.background = chipBackground(active)
        view.setTextColor(if (active) theme.keyTextColor else theme.altKeyTextColor)
    }

    private fun contentBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(12).toFloat()
        setColor(theme.keyBackgroundColor)
        setStroke(context.dp(1), theme.dividerColor)
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
