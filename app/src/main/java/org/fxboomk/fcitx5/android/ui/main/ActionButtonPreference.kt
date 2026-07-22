/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.fxboomk.fcitx5.android.R

class ActionButtonPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    var actionText: CharSequence = ""
        set(value) {
            field = value
            notifyChanged()
        }

    var actionEnabled: Boolean = true
        set(value) {
            field = value
            notifyChanged()
        }

    var actionVisible: Boolean = true
        set(value) {
            field = value
            notifyChanged()
        }

    var actionBadgeVisible: Boolean = false
        set(value) {
            field = value
            notifyChanged()
        }

    var onActionClick: (() -> Unit)? = null

    init {
        widgetLayoutResource = R.layout.preference_action_button
        isIconSpaceReserved = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val button = holder.findViewById(R.id.preference_action_button) as? Button ?: return
        val badge = holder.findViewById(R.id.preference_action_badge_dot)
        val visibility = if (actionVisible) View.VISIBLE else View.GONE
        button.visibility = visibility
        button.text = actionText
        button.isEnabled = actionEnabled && actionVisible
        button.setOnClickListener { onActionClick?.invoke() }
        badge?.visibility = if (actionVisible && actionBadgeVisible) View.VISIBLE else View.GONE
    }
}
