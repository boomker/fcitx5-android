package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import org.fxboomk.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp

class AiSuggestionBubbleUi(
    context: Context,
    theme: Theme,
) : FrameLayout(context) {

    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(theme.accentKeyTextColor)
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
    }

    init {
        clipToPadding = false
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(18).toFloat()
            setColor(theme.accentKeyBackgroundColor)
            setStroke(context.dp(1), theme.dividerColor)
        }
        elevation = context.dp(8).toFloat()
        addView(
            label,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        )
    }

    fun updateCount(count: Int) {
        label.text = if (count > 0) "AI $count" else "AI"
        contentDescription = label.text
    }
}
