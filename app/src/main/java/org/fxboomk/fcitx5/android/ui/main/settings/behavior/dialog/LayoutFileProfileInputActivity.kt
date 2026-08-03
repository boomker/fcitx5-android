/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.SeekBar
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
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
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
        const val EXTRA_RESULT_HEIGHT_PERCENT_PORTRAIT = "result_height_percent_portrait"
        const val EXTRA_RESULT_HEIGHT_PERCENT_LANDSCAPE = "result_height_percent_landscape"
        const val EXTRA_INITIAL_HEIGHT_PERCENT_PORTRAIT = "initial_height_percent_portrait"
        const val EXTRA_INITIAL_HEIGHT_PERCENT_LANDSCAPE = "initial_height_percent_landscape"

        const val ACTION_CREATE = "create"
        const val ACTION_RENAME = "rename"
        private const val MENU_SAVE_ID = 9001
        private const val MIN_LAYOUT_HEIGHT_PERCENT = 10
        private const val MAX_LAYOUT_HEIGHT_PERCENT = 90
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
    private var portraitHeightSeekBar: SeekBar? = null
    private var landscapeHeightSeekBar: SeekBar? = null
    private var saveMenuItem: MenuItem? = null
    private lateinit var action: String
    private var initialProfile: String = ""
    private var initialCopyCurrent: Boolean = true

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

        initialProfile = intent.getStringExtra(EXTRA_INITIAL_PROFILE).orEmpty()
        if (initialProfile.isNotBlank()) {
            profileInput.setText(initialProfile)
            profileInput.setSelection(initialProfile.length)
        }
        profileInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        if (intent.getBooleanExtra(EXTRA_SHOW_COPY_SWITCH, false)) {
            initialCopyCurrent = intent.getBooleanExtra(EXTRA_COPY_CURRENT_DEFAULT, true)
            copySwitch = SwitchCompat(this).apply {
                text = getString(R.string.text_keyboard_layout_file_copy_current)
                isChecked = initialCopyCurrent
                setOnCheckedChangeListener { _, _ -> updateSaveButtonState() }
            }
            content.addView(
                copySwitch,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.keyboard_height)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        })
        val keyboardPrefs = AppPrefs.getInstance().keyboard
        portraitHeightSeekBar = addLayoutHeightSlider(
            getString(R.string.portrait),
            intent.getIntExtra(
                EXTRA_INITIAL_HEIGHT_PERCENT_PORTRAIT,
                keyboardPrefs.keyboardHeightPercent.getValue()
            )
        )
        landscapeHeightSeekBar = addLayoutHeightSlider(
            getString(R.string.landscape),
            intent.getIntExtra(
                EXTRA_INITIAL_HEIGHT_PERCENT_LANDSCAPE,
                keyboardPrefs.keyboardHeightPercentLandscape.getValue()
            )
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, getString(R.string.save))
            .apply {
                setIcon(R.drawable.ic_baseline_save_24)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        updateSaveButtonState()
        return true
    }

    private fun updateSaveButtonState() {
        val normalized = UserConfigFiles.normalizeTextKeyboardLayoutProfile(profileInput.text?.toString().orEmpty())
        val changed = normalized != null && (
            normalized != initialProfile || (copySwitch?.isChecked ?: true) != initialCopyCurrent ||
                portraitHeightSeekBar?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT) !=
                    intent.getIntExtra(
                        EXTRA_INITIAL_HEIGHT_PERCENT_PORTRAIT,
                        AppPrefs.getInstance().keyboard.keyboardHeightPercent.getValue()
                    ) ||
                landscapeHeightSeekBar?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT) !=
                    intent.getIntExtra(
                        EXTRA_INITIAL_HEIGHT_PERCENT_LANDSCAPE,
                        AppPrefs.getInstance().keyboard.keyboardHeightPercentLandscape.getValue()
                    )
        )
        saveMenuItem?.isEnabled = changed
        saveMenuItem?.icon?.mutate()?.setTint(if (changed) Color.BLACK else Color.GRAY)
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
                    portraitHeightSeekBar?.let {
                        putExtra(EXTRA_RESULT_HEIGHT_PERCENT_PORTRAIT, it.progress + MIN_LAYOUT_HEIGHT_PERCENT)
                    }
                    landscapeHeightSeekBar?.let {
                        putExtra(EXTRA_RESULT_HEIGHT_PERCENT_LANDSCAPE, it.progress + MIN_LAYOUT_HEIGHT_PERCENT)
                    }
                }
                setResult(RESULT_OK, data)
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun addLayoutHeightSlider(label: String, initialValue: Int): SeekBar {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val initial = initialValue.coerceIn(MIN_LAYOUT_HEIGHT_PERCENT, MAX_LAYOUT_HEIGHT_PERCENT)
        val valueLabel = TextView(this).apply {
            text = "$label: $initial%"
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }
        val seekBar = SeekBar(this).apply {
            max = MAX_LAYOUT_HEIGHT_PERCENT - MIN_LAYOUT_HEIGHT_PERCENT
            progress = initial - MIN_LAYOUT_HEIGHT_PERCENT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    valueLabel.text = "$label: ${progress + MIN_LAYOUT_HEIGHT_PERCENT}%"
                    updateSaveButtonState()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        group.addView(valueLabel)
        group.addView(seekBar)
        content.addView(group)
        return seekBar
    }
}
