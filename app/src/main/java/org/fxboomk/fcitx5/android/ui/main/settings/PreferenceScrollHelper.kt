/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.ui.main.MainViewModel
import splitties.resources.styledColor
import kotlin.math.roundToInt

object PreferenceScrollHelper {

    fun scrollToPendingPreference(
        fragment: PreferenceFragmentCompat,
        viewModel: MainViewModel
    ) {
        val key = viewModel.peekPendingPreferenceScrollKey() ?: return
        fragment.findPreference<Preference>(key) ?: return
        fragment.listView.post {
            val listView = fragment.listView
            val position = (listView.adapter as? PreferenceGroup.PreferencePositionCallback)
                ?.getPreferenceAdapterPosition(key)
                ?: RecyclerView.NO_POSITION
            if (position == RecyclerView.NO_POSITION) return@post
            listView.scrollToPosition(position)
            highlightWhenBound(listView, position) {
                viewModel.consumePendingPreferenceScrollKey(key)
            }
        }
    }

    private fun highlightWhenBound(
        listView: RecyclerView,
        position: Int,
        attempt: Int = 0,
        onFinished: () -> Unit
    ) {
        listView.postOnAnimation {
            val itemView = listView.findViewHolderForAdapterPosition(position)?.itemView
            if (itemView != null) {
                listView.postDelayed({
                    val boundView = listView.findViewHolderForAdapterPosition(position)?.itemView
                    if (boundView?.isShown == true) {
                        highlight(boundView)
                    }
                    onFinished()
                }, HIGHLIGHT_START_DELAY_MS)
            } else if (attempt < MAX_BIND_ATTEMPTS) {
                highlightWhenBound(listView, position, attempt + 1, onFinished)
            } else {
                onFinished()
            }
        }
    }

    private fun highlight(view: View) {
        val density = view.resources.displayMetrics.density
        val horizontalInset = (HORIZONTAL_INSET_DP * density).roundToInt()
        val verticalInset = (VERTICAL_INSET_DP * density).roundToInt()
        val highlight = GradientDrawable().apply {
            cornerRadius = CORNER_RADIUS_DP * density
            setColor(view.context.styledColor(android.R.attr.colorControlHighlight))
            setStroke(
                (STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1),
                view.context.styledColor(android.R.attr.colorAccent)
            )
            setBounds(
                horizontalInset,
                verticalInset,
                view.width - horizontalInset,
                view.height - verticalInset
            )
            alpha = 0
        }
        view.overlay.add(highlight)
        ValueAnimator.ofInt(0, 255, 255, 0).apply {
            duration = HIGHLIGHT_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                highlight.alpha = it.animatedValue as Int
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.overlay.remove(highlight)
                }
            })
            start()
        }
    }

    private const val MAX_BIND_ATTEMPTS = 12
    private const val HIGHLIGHT_START_DELAY_MS = 300L
    private const val HIGHLIGHT_DURATION_MS = 1400L
    private const val HORIZONTAL_INSET_DP = 8
    private const val VERTICAL_INSET_DP = 2
    private const val CORNER_RADIUS_DP = 8f
    private const val STROKE_WIDTH_DP = 2
}
