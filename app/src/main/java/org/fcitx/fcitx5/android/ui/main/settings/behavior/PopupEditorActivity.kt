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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File

private val prettyJson = Json { prettyPrint = true }

class PopupEditorActivity : AppCompatActivity() {

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val recyclerView by lazy {
        RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PopupEditorActivity)
            isNestedScrollingEnabled = false
        }
    }

    private val addRowButton by lazy {
        TextView(this).apply {
            text = getString(R.string.popup_editor_add_mapping)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(styledColor(android.R.attr.textColorPrimary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(styledColor(android.R.attr.colorButtonNormal))
        }
    }

    private val usageHint by lazy {
        TextView(this).apply {
            text = getString(R.string.popup_editor_hint)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(12))
        }
    }

    private val mainContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            addView(addRowButton)
            addView(usageHint)
            addView(recyclerView)
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
                mainContainer,
                LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f }
            )
        }
    }

    private val provider: ConfigProvider = ConfigProviders.provider

    private val popupFile: File? by lazy { provider.popupPresetFile() }

    private val entries: MutableMap<String, MutableList<String>> = linkedMapOf()
    private var originalEntries: Map<String, List<String>> = emptyMap()
    private var saveMenuItem: MenuItem? = null
    private var adapter: PopupAdapter? = null

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
        setupRecyclerView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, "${getString(R.string.save)}")
        saveMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        updateSaveButtonState()
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
        
        // Try method reflection first
        val getterValue = runCatching {
            holderClass.getDeclaredMethod("getPopupPreset").invoke(null)
        }.onFailure { e ->
            android.util.Log.w("PopupEditor", "Failed to invoke getPopupPreset method", e)
        }.getOrNull()
        
        // Fallback to field reflection
        val fieldValue = runCatching {
            holderClass.getDeclaredField("PopupPreset").apply { 
                isAccessible = true 
            }.get(null)
        }.onFailure { e ->
            android.util.Log.w("PopupEditor", "Failed to access PopupPreset field", e)
        }.getOrNull()

        val rawMap = (getterValue ?: fieldValue) as? Map<*, *> ?: run {
            android.util.Log.w("PopupEditor", "PopupPreset returned null or is not a Map")
            return@runCatching emptyMap()
        }
        
        rawMap.mapNotNull { (key, value) ->
            val mappedKey = key as? String ?: run {
                android.util.Log.w("PopupEditor", "Invalid key type: ${key?.javaClass?.simpleName}, skipping")
                return@mapNotNull null
            }
            val mappedValues = when (value) {
                is Array<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                is List<*> -> value.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
                else -> {
                    android.util.Log.w("PopupEditor", "Invalid value type for key '$mappedKey': ${value?.javaClass?.simpleName}, expected Array or List")
                    emptyList()
                }
            }
            mappedKey to mappedValues
        }.toMap()
    }.onFailure { e ->
        android.util.Log.e("PopupEditor", "Failed to read default popup preset", e)
    }.getOrDefault(emptyMap())

    private fun setupRecyclerView() {
        adapter = PopupAdapter()
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.bottom = dp(1)
            }
        })
        addRowButton.setOnClickListener { openEditor(null) }
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

        var currentKeyValue = currentKey

        val keyEdit = TextView(this@PopupEditorActivity).apply {
            text = currentKeyValue.ifEmpty { getString(R.string.popup_editor_key_input_hint) }
            textSize = 16f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(styledColor(android.R.attr.colorPrimary))
                setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener {
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

        container.addView(keyLabel)
        container.addView(keyEdit)

        val valueLabel = TextView(this).apply {
            text = "${getString(R.string.popup_editor_values)} (${getString(R.string.popup_editor_hint_click_edit_long_press_delete)})"
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        }
        container.addView(valueLabel)

        val candidatesFlow = DraggableFlowLayout(this)
        container.addView(candidatesFlow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })

        // 构建候选词列表 - 只构建一次
        fun buildCandidates() {
            candidatesFlow.removeAllViews()
            values.forEachIndexed { index, value ->
                val candidateChip = TextView(this@PopupEditorActivity).apply {
                    text = value
                    textSize = 14f
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(styledColor(android.R.attr.colorButtonNormal))
                        setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                    // 设置固定高度确保所有候选词高度一致
                    // dp(40) 能容纳大多数特殊字符（阿拉伯文、梵文等），gravity=CENTER 确保文字垂直居中
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(40)
                    ).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    // 使用 tag 标记原始索引，用于拖拽结束后追踪
                    tag = index
                    setOnClickListener {
                        EditText(this@PopupEditorActivity).apply {
                            setText(value)
                            setSelection(text.length)
                        }.let { edit ->
                            AlertDialog.Builder(this@PopupEditorActivity)
                                .setTitle(R.string.edit)
                                .setView(edit)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    val newValue = edit.text.toString().trim()
                                    if (newValue.isNotEmpty()) {
                                        values[index] = newValue
                                        buildCandidates()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@PopupEditorActivity)
                            .setTitle(R.string.delete)
                            .setMessage(getString(R.string.popup_editor_delete_candidate_confirm, value))
                            .setPositiveButton(R.string.delete) { _, _ ->
                                values.removeAt(index)
                                buildCandidates()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        true
                    }
                }
                candidatesFlow.addView(candidateChip)
            }

            // 添加按钮 - 与候选词保持一致的高度和样式
            val addChip = TextView(this@PopupEditorActivity).apply {
                text = getString(R.string.popup_editor_add_candidate_button)
                textSize = 14f
                setPadding(dp(10), dp(5), dp(10), dp(5))  // 与候选词相同的 padding
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(styledColor(android.R.attr.colorPrimary))
                    setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                    cornerRadius = dp(4).toFloat()
                }
                // 设置固定高度与候选词一致（dp(40) 能容纳特殊字符）
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                setOnClickListener {
                    EditText(this@PopupEditorActivity).apply {
                        hint = getString(R.string.popup_editor_candidate_input_hint)
                    }.let { edit ->
                        AlertDialog.Builder(this@PopupEditorActivity)
                            .setTitle(R.string.popup_editor_add_candidate)
                            .setView(edit)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val newValue = edit.text.toString().trim()
                                if (newValue.isNotEmpty()) {
                                    values.add(newValue)
                                    buildCandidates()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            candidatesFlow.addView(addChip)
        }

        buildCandidates()

        // 设置拖拽监听器
        candidatesFlow.onDragListener = object : DraggableFlowLayout.OnDragListener {
            override fun onDragStarted(view: View, position: Int) {}

            override fun onDragPositionChanged(from: Int, to: Int) {
                // 不在这里更新数据，只在 onDragEnded 时根据最终位置更新
            }

            override fun onDragEnded(view: View, position: Int) {
                // 等待 Drag 动画完成后重建 UI
                candidatesFlow.postDelayed({
                    // 根据 FlowLayout 中视图的最终顺序更新数据
                    val newOrder = mutableListOf<String>()
                    val addButtonText = getString(R.string.popup_editor_add_candidate_button)
                    for (i in 0 until candidatesFlow.childCount) {
                        val child = candidatesFlow.getChildAt(i)
                        if (child is TextView) {
                            val text = child.text.toString()
                            // 跳过添加按钮
                            if (text != addButtonText) {
                                newOrder.add(text)
                            }
                        }
                    }
                    // 验证：新列表长度应该等于原列表长度，且内容相同（顺序可能不同）
                    if (newOrder.size == values.size && 
                        newOrder.groupingBy { it }.eachCount() == values.groupingBy { it }.eachCount()) {
                        values.clear()
                        values.addAll(newOrder)
                        updateSaveButtonState()
                    }
                    buildCandidates()
                }, 300)
            }
        }

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

                val positionChanged = originalKey != null && originalKey != newKey
                val oldPosition = if (positionChanged) {
                    entries.keys.indexOf(originalKey)
                } else {
                    entries.keys.indexOf(newKey)
                }

                if (positionChanged) {
                    entries.remove(originalKey)
                }
                entries[newKey] = values

                // 通知 adapter 数据变化
                adapter?.let { adapter ->
                    if (positionChanged && oldPosition >= 0) {
                        // Key 改变：先删除旧位置，再插入新位置
                        adapter.notifyItemRemoved(oldPosition)
                        val newPosition = entries.keys.indexOf(newKey)
                        adapter.notifyItemInserted(newPosition)
                    } else if (originalKey == null) {
                        // 新增条目
                        val newPosition = entries.keys.indexOf(newKey)
                        adapter.notifyItemInserted(newPosition)
                    } else {
                        // 仅值改变
                        adapter.notifyItemChanged(oldPosition)
                    }
                }

                updateSaveButtonState()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(key: String) {
        val position = entries.keys.indexOf(key)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.popup_editor_delete_confirm, key))
            .setPositiveButton(R.string.delete) { _, _ ->
                entries.remove(key)
                adapter?.notifyItemRemoved(position)
                updateSaveButtonState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savePopupPreset() {
        if (!hasChanges()) {
            return
        }

        val file = popupFile ?: run {
            showToast(getString(R.string.cannot_resolve_popup_preset))
            return
        }
        file.parentFile?.mkdirs()

        val jsonElement = JsonObject(entries.toSortedMap().mapValues { (_, v) ->
            JsonArray(v.map { JsonPrimitive(it) })
        })

        file.writeText(prettyJson.encodeToString(jsonElement) + "\n")
        ConfigProviders.ensureWatching()
        showToast(getString(R.string.popup_preset_saved_at, file.absolutePath))
        originalEntries = normalizedEntries()
        updateSaveButtonState()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun normalizedEntries(): Map<String, List<String>> =
        entries.toSortedMap().mapValues { (_, value) -> value.toList() }

    private fun hasChanges(): Boolean = normalizedEntries() != originalEntries

    private fun updateSaveButtonState() {
        saveMenuItem?.let { menuItem ->
            menuItem.isEnabled = hasChanges()
            menuItem.title = getString(R.string.save)
        }
    }

    /**
     * RecyclerView Adapter for popup entries
     */
    private inner class PopupAdapter : RecyclerView.Adapter<PopupAdapter.ViewHolder>() {

        private val sortedKeys: List<String>
            get() = entries.keys.toList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val container = LinearLayout(this@PopupEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val rowLayout = LinearLayout(this@PopupEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            container.addView(rowLayout)
            // 分隔线
            val divider = View(this@PopupEditorActivity).apply {
                setBackgroundColor(
                    runCatching { styledColor(android.R.attr.colorControlNormal) }
                        .getOrDefault(0x33000000)
                )
                alpha = 0.35f
            }
            container.addView(divider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ))
            return ViewHolder(container, rowLayout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val key = sortedKeys[position]
            val values = entries[key].orEmpty()

            holder.bind(key, values)
        }

        override fun getItemCount(): Int = entries.size

        inner class ViewHolder(
            private val container: LinearLayout,
            private val rowLayout: LinearLayout
        ) : RecyclerView.ViewHolder(container) {

            private val keyBadge: TextView
            private val candidatesFlow: FlowLayout

            init {
                keyBadge = TextView(rowLayout.context).apply {
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(styledColor(android.R.attr.colorPrimary))
                        setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                        cornerRadius = dp(4).toFloat()
                    }
                }

                candidatesFlow = FlowLayout(rowLayout.context).apply {
                    setPadding(dp(8), 0, 0, 0)
                }

                // keyBadge 设置固定高度与候选词一致
                rowLayout.addView(keyBadge, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setMargins(0, dp(4), dp(8), dp(4))
                })
                rowLayout.addView(candidatesFlow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    weight = 1f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })

                rowLayout.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        openEditor(sortedKeys[pos])
                    }
                }
                rowLayout.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        confirmDelete(sortedKeys[pos])
                    }
                    true
                }
            }

            fun bind(key: String, values: List<String>) {
                keyBadge.text = key
                candidatesFlow.removeAllViews()
                values.forEach { value ->
                    val candidateChip = TextView(rowLayout.context).apply {
                        text = value
                        textSize = 14f
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                        gravity = android.view.Gravity.CENTER
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(styledColor(android.R.attr.colorButtonNormal))
                            setStroke(dp(1), styledColor(android.R.attr.colorControlNormal))
                            cornerRadius = dp(4).toFloat()
                        }
                        // 设置固定高度确保所有候选词高度一致（dp(40) 能容纳特殊字符）
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            dp(40)
                        ).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                    }
                    candidatesFlow.addView(candidateChip)
                }
            }
        }
    }

    companion object {
        private const val MENU_SAVE_ID = 2001
    }
}
