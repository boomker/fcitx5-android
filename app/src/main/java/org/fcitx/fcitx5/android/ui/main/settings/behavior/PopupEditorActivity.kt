/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PopupEditorActivity : AppCompatActivity() {

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val listContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val scrollView by lazy {
        ScrollView(this).apply {
            isFillViewport = true
            addView(
                listContainer,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val ui by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(matchParent, wrapContent)
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
            )
        }
    }

    private val provider: ConfigProvider = ConfigProviders.provider

    private val popupFile: File? by lazy { provider.popupPresetFile() }

    private val entries: MutableMap<String, MutableList<String>> = linkedMapOf()
    private var originalEntries: Map<String, List<String>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(ui)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.edit_popup_preset)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        loadState()
        buildRows()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val item = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, "${getString(R.string.save)}")
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SAVE_ID -> {
            savePopupPreset()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadState() {
        // try reading user-provided popup preset JSON; fallback to built-in PopupPreset
        val snapshot = ConfigProviders.readPopupPreset<Map<String, List<String>>>()
        val parsed: Map<String, List<String>> = snapshot?.value
            ?: readDefaultPresetFromPopupKt()
        parsed.toSortedMap().forEach { (k, v) ->
            entries[k] = v.toMutableList()
        }
        originalEntries = normalizedEntries()
    }

    private fun readDefaultPresetFromPopupKt(): Map<String, List<String>> = runCatching {
        val holderClass = Class.forName("org.fcitx.fcitx5.android.input.popup.PopupPresetKt")
        val getterValue = runCatching {
            holderClass.getDeclaredMethod("getPopupPreset").invoke(null)
        }.getOrNull()
        val fieldValue = runCatching {
            holderClass.getDeclaredField("PopupPreset").apply { isAccessible = true }.get(null)
        }.getOrNull()

        val rawMap = (getterValue ?: fieldValue) as? Map<*, *> ?: return@runCatching emptyMap()
        rawMap.mapNotNull { (key, value) ->
            val mappedKey = key as? String ?: return@mapNotNull null
            val mappedValues = when (value) {
                is Array<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
            mappedKey to mappedValues
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun buildRows() {
        listContainer.removeAllViews()
        val addRow = TextView(this).apply {
            text = getString(R.string.popup_editor_add_mapping)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(styledColor(android.R.attr.textColorPrimary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(styledColor(android.R.attr.colorButtonNormal))
            setOnClickListener { openEditor(null) }
        }
        listContainer.addView(addRow)

        val usageHint = TextView(this).apply {
            text = getString(R.string.popup_editor_hint)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(12))
        }
        listContainer.addView(usageHint)

        entries.toSortedMap().forEach { (key, values) ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val keyBadge = TextView(this).apply {
                text = key
                setTextColor(styledColor(android.R.attr.textColorPrimary))
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                setBackgroundColor(styledColor(android.R.attr.colorButtonNormal))
                gravity = android.view.Gravity.CENTER
            }

            val horizontalScrollView = android.widget.HorizontalScrollView(this).apply {
                isFillViewport = false
                isHorizontalScrollBarEnabled = true
            }

            val candidatesContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), 0, 0, 0)
            }

            values.forEach { value ->
                val candidateChip = TextView(this).apply {
                    text = value
                    textSize = 14f
                    setTextColor(styledColor(android.R.attr.colorPrimary))
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    setBackgroundColor(styledColor(android.R.attr.colorBackgroundFloating))
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener { openEditor(key) }
                    setOnLongClickListener {
                        confirmDelete(key)
                        true
                    }
                }
                candidatesContainer.addView(candidateChip, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMarginEnd(dp(6))
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })
            }

            horizontalScrollView.addView(candidatesContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))

            rowLayout.addView(keyBadge, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            rowLayout.addView(horizontalScrollView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                weight = 1f
                gravity = android.view.Gravity.CENTER_VERTICAL
            })

            rowLayout.setOnClickListener { openEditor(key) }
            rowLayout.setOnLongClickListener {
                confirmDelete(key)
                true
            }

            listContainer.addView(rowLayout)

            val divider = View(this).apply {
                setBackgroundColor(
                    runCatching { styledColor(android.R.attr.colorControlNormal) }
                        .getOrDefault(0x33000000)
                )
                alpha = 0.35f
            }
            listContainer.addView(
                divider,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            )
        }

    }

    private fun openEditor(originalKey: String?) {
        val currentKey = originalKey.orEmpty()
        val values = entries[originalKey].orEmpty().toMutableList()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        val keyLabel = TextView(this).apply {
            text = getString(R.string.popup_editor_key)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }
        
        // Key edit as a chip with border
        val keyEditContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        
        var currentKeyValue = currentKey
        
        val keyEdit = TextView(this@PopupEditorActivity).apply {
            text = currentKeyValue.ifEmpty { getString(R.string.popup_editor_key_input_hint) }
            textSize = 16f
            setTextColor(styledColor(android.R.attr.colorPrimary))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(styledColor(android.R.attr.colorButtonNormal))
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(styledColor(android.R.attr.colorButtonNormal))
                setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener {
                // Show edit dialog for key
                val edit = EditText(this@PopupEditorActivity).apply {
                    setText(currentKeyValue)
                    setSelection(text.length)
                    hint = getString(R.string.popup_editor_key_input_hint)
                }
                AlertDialog.Builder(this@PopupEditorActivity)
                    .setTitle(R.string.popup_editor_key)
                    .setView(edit)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val newKey = edit.text.toString().trim()
                        if (newKey.isNotEmpty()) {
                            currentKeyValue = newKey
                            text = newKey
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        
        keyEditContainer.addView(keyEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val valueLabel = TextView(this).apply {
            text = "${getString(R.string.popup_editor_values)} (${getString(R.string.popup_editor_hint_click_edit_long_press_delete)})"
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }

        // Flow layout for candidate chips (auto wrap)
        val candidatesScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
        }

        val candidatesFlow = FlowLayout(this)

        candidatesScroll.addView(candidatesFlow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        container.addView(keyLabel)
        container.addView(keyEditContainer)
        container.addView(valueLabel)
        container.addView(candidatesScroll)

        // Rebuild candidates UI
        fun doRebuildCandidates() {
            candidatesFlow.removeAllViews()
            values.forEachIndexed { index, value ->
                val candidateChip = TextView(this@PopupEditorActivity).apply {
                    text = value
                    textSize = 14f
                    setTextColor(styledColor(android.R.attr.colorPrimary))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    gravity = android.view.Gravity.CENTER
                    // Add border effect using drawable
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(styledColor(android.R.attr.colorBackgroundFloating))
                        setStroke(dp(1), styledColor(android.R.attr.colorButtonNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                    setOnClickListener {
                        // Edit candidate
                        val edit = EditText(this@PopupEditorActivity).apply {
                            setText(value)
                            setSelection(text.length)
                        }
                        AlertDialog.Builder(this@PopupEditorActivity)
                            .setTitle(R.string.edit)
                            .setView(edit)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val newValue = edit.text.toString().trim()
                                if (newValue.isNotEmpty()) {
                                    values[index] = newValue
                                    doRebuildCandidates()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    setOnLongClickListener {
                        // Delete candidate
                        AlertDialog.Builder(this@PopupEditorActivity)
                            .setTitle(R.string.delete)
                            .setMessage(getString(R.string.popup_editor_delete_candidate_confirm, value))
                            .setPositiveButton(R.string.delete) { _, _ ->
                                values.removeAt(index)
                                doRebuildCandidates()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        true
                    }
                }
                candidatesFlow.addView(candidateChip, ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(4)
                    topMargin = dp(4)
                    rightMargin = dp(4)
                    bottomMargin = dp(4)
                })
            }
            // Add button
            val addChip = TextView(this@PopupEditorActivity).apply {
                text = getString(R.string.popup_editor_add_candidate_button)
                textSize = 16f
                setTextColor(styledColor(android.R.attr.colorPrimary))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(styledColor(android.R.attr.colorButtonNormal))
                    setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                    cornerRadius = dp(4).toFloat()
                }
                setOnClickListener {
                    // Add candidate
                    val edit = EditText(this@PopupEditorActivity).apply {
                        hint = getString(R.string.popup_editor_candidate_input_hint)
                    }
                    AlertDialog.Builder(this@PopupEditorActivity)
                        .setTitle(R.string.popup_editor_add_candidate)
                        .setView(edit)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            val newValue = edit.text.toString().trim()
                            if (newValue.isNotEmpty()) {
                                values.add(newValue)
                                doRebuildCandidates()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            candidatesFlow.addView(addChip, ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(4)
                topMargin = dp(4)
                rightMargin = dp(4)
                bottomMargin = dp(4)
            })
        }

        doRebuildCandidates()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (originalKey == null) R.string.add else R.string.edit)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newKey = currentKeyValue
                if (newKey.isEmpty() || newKey == getString(R.string.popup_editor_key_input_hint)) {
                    showToast(getString(R.string.popup_editor_key_empty))
                    return@setOnClickListener
                }
                if (newKey != currentKey && entries.containsKey(newKey)) {
                    showToast(getString(R.string.popup_editor_key_exists, newKey))
                    return@setOnClickListener
                }

                if (originalKey != null && originalKey != newKey) {
                    entries.remove(originalKey)
                }
                entries[newKey] = values
                buildRows()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(key: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.popup_editor_delete_confirm, key))
            .setPositiveButton(R.string.delete) { _, _ ->
                entries.remove(key)
                buildRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savePopupPreset() {
        if (!hasChanges()) {
            finish()
            return
        }

        val file = popupFile ?: run {
            showToast(getString(R.string.cannot_resolve_popup_preset))
            return
        }
        file.parentFile?.mkdirs()
        val json = JSONObject()
        entries.toSortedMap().forEach { (k, v) ->
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            json.put(k, arr)
        }
        file.writeText(json.toString(2) + "\n")
        // notify provider watcher
        ConfigProviders.ensureWatching()
        showToast(getString(R.string.popup_preset_saved_at, file.absolutePath))
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun normalizedEntries(): Map<String, List<String>> =
        entries
            .toSortedMap()
            .mapValues { (_, value) -> value.toList() }

    private fun hasChanges(): Boolean = normalizedEntries() != originalEntries

    companion object {
        private const val MENU_SAVE_ID = 2001
    }
}
