/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.keyboardtest

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ScrollView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import splitties.resources.styledColor

/** The host owns dismissal on stop; reopening always creates a fresh test editor. */
internal class KeyboardTestDialog(context: Context) : BottomSheetDialog(context) {
    val panel = KeyboardTestPanel(context, ::dismiss)

    init {
        setContentView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit
                    else minOf(limit, View.MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isSaveEnabled = false
            addView(panel, ViewGroup.LayoutParams(-1, -2))
        }, ViewGroup.LayoutParams(-1, -2))
        behavior.isDraggable = false
        behavior.skipCollapsed = true
        setOnShowListener {
            findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.backgroundTintList = ColorStateList.valueOf(
                    context.styledColor(com.google.android.material.R.attr.colorSurface)
                )
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            @Suppress("DEPRECATION") // Keep IME resizing compatible with Android 10 and earlier.
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            panel.input.requestFocus()
            panel.input.post {
                if (isShowing && panel.input.isAttachedToWindow) window?.let { host ->
                    WindowCompat.getInsetsController(host, panel.input).show(WindowInsetsCompat.Type.ime())
                }
            }
        }
    }

    override fun dismiss() {
        window?.let { WindowCompat.getInsetsController(it, panel.input).hide(WindowInsetsCompat.Type.ime()) }
        panel.clear()
        super.dismiss()
    }
}
