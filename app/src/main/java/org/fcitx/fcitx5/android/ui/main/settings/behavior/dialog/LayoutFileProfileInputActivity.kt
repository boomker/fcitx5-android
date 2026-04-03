/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class LayoutFileProfileInputActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_INITIAL_PROFILE = "initial_profile"
        const val EXTRA_SHOW_COPY_SWITCH = "show_copy_switch"
        const val EXTRA_COPY_CURRENT_DEFAULT = "copy_current_default"
        const val EXTRA_RESULT_PROFILE = "result_profile"
        const val EXTRA_RESULT_COPY_CURRENT = "result_copy_current"

        const val ACTION_CREATE = "create"
        const val ACTION_RENAME = "rename"
        private const val MENU_SAVE_ID = 9001
    }

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val root by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(content, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private lateinit var profileInput: AppCompatEditText
    private var copySwitch: SwitchCompat? = null
    private lateinit var action: String

    private val content by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)

            addView(TextView(this@LayoutFileProfileInputActivity).apply {
                text = getString(R.string.text_keyboard_layout_file_name)
                textSize = 13f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
            })

            profileInput = AppCompatEditText(this@LayoutFileProfileInputActivity).apply {
                hint = getString(R.string.text_keyboard_layout_file_name_hint)
            }
            addView(
                profileInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        action = intent.getStringExtra(EXTRA_ACTION) ?: ACTION_CREATE
        supportActionBar?.title = if (action == ACTION_RENAME) {
            getString(R.string.text_keyboard_layout_file_rename)
        } else {
            getString(R.string.text_keyboard_layout_file_create)
        }

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        val initial = intent.getStringExtra(EXTRA_INITIAL_PROFILE).orEmpty()
        if (initial.isNotBlank()) {
            profileInput.setText(initial)
            profileInput.setSelection(initial.length)
        }

        if (intent.getBooleanExtra(EXTRA_SHOW_COPY_SWITCH, false)) {
            copySwitch = SwitchCompat(this).apply {
                text = getString(R.string.text_keyboard_layout_file_copy_current)
                isChecked = intent.getBooleanExtra(EXTRA_COPY_CURRENT_DEFAULT, true)
            }
            content.addView(
                copySwitch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, getString(R.string.save))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_SAVE_ID -> {
                val raw = profileInput.text?.toString().orEmpty()
                val normalized = UserConfigFiles.normalizeTextKeyboardLayoutProfile(raw)
                if (normalized == null) {
                    Toast.makeText(this, getString(R.string.text_keyboard_layout_file_name_invalid), Toast.LENGTH_SHORT).show()
                    return true
                }
                val data = Intent().apply {
                    putExtra(EXTRA_ACTION, action)
                    putExtra(EXTRA_RESULT_PROFILE, normalized)
                    putExtra(EXTRA_RESULT_COPY_CURRENT, copySwitch?.isChecked ?: true)
                }
                setResult(RESULT_OK, data)
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
