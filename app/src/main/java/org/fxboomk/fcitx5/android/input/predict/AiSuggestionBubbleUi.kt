package org.fxboomk.fcitx5.android.input.predict

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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

    fun updateContent(count: Int, errorMessage: String? = null) {
        if (errorMessage != null) {
            label.text = errorMessage
            label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            label.maxLines = 2
            label.ellipsize = TextUtils.TruncateAt.END
            contentDescription = errorMessage
        } else {
            label.text = if (count > 0) "AI $count" else "AI"
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            label.ellipsize = null
            contentDescription = label.text
        }
    }

    fun setContentMaxWidth(maxWidth: Int): Boolean {
        val constrainedWidth = maxWidth.coerceAtLeast(1)
        if (label.maxWidth == constrainedWidth) return false
        label.maxWidth = constrainedWidth
        return true
    }
}
