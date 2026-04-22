package org.fxboomk.fcitx5.android.input.predict

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp

object AiSuggestionWindow : InputWindow.ExtendedInputWindow<AiSuggestionWindow>() {

    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()
    private val aiSuggestionStrip: AiSuggestionStripComponent by manager.must()

    private val adapter = SuggestionAdapter { suggestion ->
        aiSuggestionStrip.commitSuggestionFromWindow(suggestion)
    }

    override val title: String
        get() = context.getString(R.string.ai_clip_title)

    override fun onCreateView(): View {
        val spanCount = if (
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            3
        } else {
            2
        }
        val recyclerView = RecyclerView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            layoutManager = GridLayoutManager(context, spanCount)
            adapter = this@AiSuggestionWindow.adapter
        }
        return FrameLayout(context).apply {
            setBackgroundColor(theme.keyboardColor)
            addView(
                recyclerView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
        }
    }

    override fun onAttached() {
        val suggestions = aiSuggestionStrip.currentSuggestionsSnapshot()
        if (suggestions.isEmpty()) {
            windowManager.attachWindow(KeyboardWindow)
            return
        }
        adapter.submitList(suggestions)
    }

    override fun onDetached() = Unit

    private class SuggestionAdapter(
        private val onSuggestionClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SuggestionViewHolder>() {
        private var suggestions: List<String> = emptyList()

        fun submitList(values: List<String>) {
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
                setTextColor(AiSuggestionWindow.theme.candidateTextColor)
                setPadding(context.dp(12), context.dp(14), context.dp(12), context.dp(14))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = context.dp(12).toFloat()
                    setColor(AiSuggestionWindow.theme.keyBackgroundColor)
                    setStroke(context.dp(1), AiSuggestionWindow.theme.dividerColor)
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
            val suggestion = suggestions[position]
            holder.textView.text = suggestion
            holder.textView.setOnClickListener {
                onSuggestionClick(suggestion)
            }
        }

        override fun getItemCount(): Int = suggestions.size
    }

    private class SuggestionViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
}
