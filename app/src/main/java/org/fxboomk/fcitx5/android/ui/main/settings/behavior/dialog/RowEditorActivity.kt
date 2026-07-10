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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.KeyboardRowStyleUtils
import org.fxboomk.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity
import org.fxboomk.fcitx5.android.utils.serializable
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import java.util.HashMap

class RowEditorActivity : AppCompatActivity() {

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
            setSubtitleTextAppearance(context, android.R.style.TextAppearance_Small)
            setSubtitleTextColor(styledColor(android.R.attr.textColorSecondary))
        }
    }

    private val contentContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val scrollView by lazy {
        ScrollView(this).apply {
            addView(contentContainer, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private val rootView by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(scrollView, LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f })
        }
    }

    private var rowIndex: Int = -1
    private var rowStyle: KeyboardRowStyleUtils.RowStyle = KeyboardRowStyleUtils.RowStyle()
    private var initialStyle: KeyboardRowStyleUtils.RowStyle = KeyboardRowStyleUtils.RowStyle()
    private var saveMenuItem: MenuItem? = null

    private lateinit var heightMultiplierEdit: EditText
    private lateinit var subLabelValue: TextView
    private lateinit var backgroundStyleValue: TextView
    private lateinit var backgroundColorValue: TextView
    private lateinit var backgroundColorSwatch: View

    private val colorEditorLauncher =
        registerForActivityResult(ThemeColorEditorActivity.Contract()) { result ->
            result ?: return@registerForActivityResult
            rowStyle = rowStyle.copy(backgroundColor = result.color)
            bindUiState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(rootView)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rowIndex = intent.getIntExtra(EXTRA_ROW_INDEX, -1)
        rowStyle = KeyboardRowStyleUtils.rowStyleFromMeta(
            intent.serializable<HashMap<String, Any?>>(EXTRA_ROW_META)
        )
        initialStyle = rowStyle

        supportActionBar?.title = getString(R.string.text_keyboard_layout_row_editor_title)
        toolbar.subtitle = getString(R.string.text_keyboard_layout_row_editor_subtitle, rowIndex + 1)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        buildForm()
        bindUiState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, 1, getString(R.string.save)).apply {
            setIcon(R.drawable.ic_baseline_save_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateSaveButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SAVE_ID -> {
            saveAndFinish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun buildForm() {
        val uiBuilder = KeyboardEditorUiBuilder(this)
        val heightField = uiBuilder.createEditField(
            getString(R.string.text_keyboard_layout_row_height_multiplier),
            rowStyle.heightMultiplier.toString()
        )
        heightMultiplierEdit = heightField.second
        heightMultiplierEdit.hint = "1.0"
        heightMultiplierEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSaveButtonState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        contentContainer.addView(heightField.first)

        val subLabelRow = createActionRow(
            title = getString(R.string.text_keyboard_layout_row_sub_label_position)
        ) {
            showSubLabelPositionPicker()
        }
        subLabelValue = subLabelRow.second
        contentContainer.addView(subLabelRow.first)

        val backgroundStyleRow = createActionRow(
            title = getString(R.string.text_keyboard_layout_row_background_style)
        ) {
            showBackgroundStylePicker()
        }
        backgroundStyleValue = backgroundStyleRow.second
        contentContainer.addView(backgroundStyleRow.first)

        val backgroundColorRow = createColorActionRow(
            title = getString(R.string.text_keyboard_layout_row_background_color)
        ) {
            openColorEditor()
        }
        backgroundColorValue = backgroundColorRow.second
        backgroundColorSwatch = backgroundColorRow.third
        contentContainer.addView(backgroundColorRow.first)
    }

    private fun bindUiState() {
        subLabelValue.text = when (rowStyle.altTextPosition) {
            KeyboardRowStyleUtils.AltTextPosition.Top -> getString(R.string.text_keyboard_layout_row_sub_label_top)
            KeyboardRowStyleUtils.AltTextPosition.TopRight -> getString(R.string.text_keyboard_layout_row_sub_label_top_right)
            KeyboardRowStyleUtils.AltTextPosition.Bottom -> getString(R.string.text_keyboard_layout_row_sub_label_bottom)
            null -> getString(R.string.text_keyboard_layout_row_follow_theme)
        }

        backgroundStyleValue.text = when (rowStyle.backgroundStyle) {
            KeyboardRowStyleUtils.BackgroundStyle.Solid -> getString(R.string.text_keyboard_layout_row_background_style_solid)
            KeyboardRowStyleUtils.BackgroundStyle.Gradient -> getString(R.string.text_keyboard_layout_row_background_style_gradient)
            null -> getString(R.string.text_keyboard_layout_row_background_style_default)
        }

        val color = rowStyle.backgroundColor
        backgroundColorValue.text = if (color == null) {
            getString(R.string.text_keyboard_layout_row_background_not_set)
        } else {
            String.format("#%08X", color)
        }
        backgroundColorSwatch.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(color ?: ThemeManager.activeTheme.keyBackgroundColor)
        }
        backgroundColorSwatch.alpha = if (color == null) 0.4f else 1f
        backgroundColorValue.alpha = if (rowStyle.backgroundStyle == null) 0.55f else 1f
        backgroundColorSwatch.alpha = if (rowStyle.backgroundStyle == null) 0.25f else backgroundColorSwatch.alpha
        updateSaveButtonState()
    }

    private fun showSubLabelPositionPicker() {
        val options = arrayOf(
            getString(R.string.text_keyboard_layout_row_follow_theme),
            getString(R.string.text_keyboard_layout_row_sub_label_top),
            getString(R.string.text_keyboard_layout_row_sub_label_top_right),
            getString(R.string.text_keyboard_layout_row_sub_label_bottom)
        )
        val checked = when (rowStyle.altTextPosition) {
            KeyboardRowStyleUtils.AltTextPosition.Top -> 1
            KeyboardRowStyleUtils.AltTextPosition.TopRight -> 2
            KeyboardRowStyleUtils.AltTextPosition.Bottom -> 3
            null -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_row_sub_label_position)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                rowStyle = rowStyle.copy(
                    altTextPosition = when (which) {
                        1 -> KeyboardRowStyleUtils.AltTextPosition.Top
                        2 -> KeyboardRowStyleUtils.AltTextPosition.TopRight
                        3 -> KeyboardRowStyleUtils.AltTextPosition.Bottom
                        else -> null
                    }
                )
                dialog.dismiss()
                bindUiState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBackgroundStylePicker() {
        val options = arrayOf(
            getString(R.string.text_keyboard_layout_row_background_style_default),
            getString(R.string.text_keyboard_layout_row_background_style_solid),
            getString(R.string.text_keyboard_layout_row_background_style_gradient)
        )
        val checked = when (rowStyle.backgroundStyle) {
            KeyboardRowStyleUtils.BackgroundStyle.Solid -> 1
            KeyboardRowStyleUtils.BackgroundStyle.Gradient -> 2
            null -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.text_keyboard_layout_row_background_style)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                rowStyle = rowStyle.copy(
                    backgroundStyle = when (which) {
                        1 -> KeyboardRowStyleUtils.BackgroundStyle.Solid
                        2 -> KeyboardRowStyleUtils.BackgroundStyle.Gradient
                        else -> null
                    }
                )
                dialog.dismiss()
                bindUiState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openColorEditor() {
        val initialColor = rowStyle.backgroundColor ?: ThemeManager.activeTheme.keyBackgroundColor
        colorEditorLauncher.launch(
            ThemeColorEditorActivity.EditorInput(
                fieldName = "rowBackgroundColor",
                titleRes = R.string.text_keyboard_layout_row_background_color,
                initialColor = initialColor
            )
        )
    }

    private fun currentEditedStyle(): KeyboardRowStyleUtils.RowStyle? {
        val heightMultiplier = heightMultiplierEdit.text?.toString()
            ?.trim()
            ?.takeUnless { it.isEmpty() }
            ?.toFloatOrNull()
            ?: return null
        if (heightMultiplier <= 0f) return null
        if (rowStyle.backgroundStyle != null && rowStyle.backgroundColor == null) return null
        return rowStyle.copy(heightMultiplier = heightMultiplier)
    }

    private fun updateSaveButtonState() {
        val candidate = currentEditedStyle()
        val enabled = candidate != null && candidate != initialStyle
        saveMenuItem?.isEnabled = enabled
        saveMenuItem?.icon?.mutate()?.setTint(if (enabled) Color.BLACK else Color.GRAY)
    }

    private fun saveAndFinish() {
        val normalizedStyle = currentEditedStyle() ?: run {
            Toast.makeText(this, R.string.text_keyboard_layout_row_height_multiplier_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (normalizedStyle.backgroundStyle != null && normalizedStyle.backgroundColor == null) {
            Toast.makeText(this, R.string.text_keyboard_layout_row_background_color_required, Toast.LENGTH_SHORT).show()
            return
        }
        val result = Intent().apply {
            putExtra(EXTRA_RESULT_ACTION, RESULT_ACTION_SAVE)
            putExtra(EXTRA_ROW_INDEX, rowIndex)
            putExtra(EXTRA_RESULT_ROW_META, HashMap(KeyboardRowStyleUtils.buildMeta(normalizedStyle)))
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun createActionRow(
        title: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val valueView = TextView(this).apply {
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorPrimary))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            background = resources.getDrawable(android.R.drawable.list_selector_background, theme)
            setOnClickListener { onClick() }

            addView(
                TextView(this@RowEditorActivity).apply {
                    text = title
                    textSize = 13f
                    setTextColor(styledColor(android.R.attr.textColorSecondary))
                },
                LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                }
            )
            addView(
                valueView,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                }
            )
        }
        return row to valueView
    }

    private fun createColorActionRow(
        title: String,
        onClick: () -> Unit
    ): Triple<LinearLayout, TextView, View> {
        val (row, valueView) = createActionRow(title, onClick)
        val swatch = View(this)
        row.addView(
            swatch,
            LinearLayout.LayoutParams(dp(20), dp(20))
        )
        return Triple(row, valueView, swatch)
    }

    companion object {
        const val EXTRA_ROW_INDEX = "extra_row_index"
        const val EXTRA_ROW_META = "extra_row_meta"
        const val EXTRA_RESULT_ROW_META = "extra_result_row_meta"
        const val EXTRA_RESULT_ACTION = "extra_result_action"

        const val RESULT_ACTION_SAVE = "save"

        private const val MENU_SAVE_ID = 1
    }
}
