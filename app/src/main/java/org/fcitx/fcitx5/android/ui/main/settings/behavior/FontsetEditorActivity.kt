/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.candidates.CandidateAutoScaleTextView
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import org.fcitx.fcitx5.android.utils.toast
import java.io.File
import org.fcitx.fcitx5.android.daemon.FcitxDaemon

class FontsetEditorActivity : AppCompatActivity() {

    private data class FontEntry(
        val key: String,
        @StringRes val titleRes: Int,
        val sample: String
    )

    private data class FontRowViews(
        val preview: TextView,
        val value: TextView
    )

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val fontsetFile by lazy {
        org.fcitx.fcitx5.android.input.config.ConfigProviders.provider.fontsetFile()
            ?: File(File(filesDir, "fonts"), "fontset.json")
    }
    private val fontsDir by lazy {
        fontsetFile.parentFile ?: File(filesDir, "fonts")
    }

    private val entries = listOf(
        FontEntry("font", R.string.fontset_entry_font, "Aa 中文 123"),
        FontEntry("key_main_font", R.string.fontset_entry_key_main, "QWER 你好"),
        FontEntry("key_alt_font", R.string.fontset_entry_key_alt, "!@#（）"),
        FontEntry("cand_font", R.string.fontset_entry_candidate, "候选词 Example"),
        FontEntry("popup_key_font", R.string.fontset_entry_popup_key, "Popup 键"),
        FontEntry("preedit_font", R.string.fontset_entry_preedit, "预编辑 Preedit")
    )

    private val selectedFonts: MutableMap<String, MutableList<String>> = mutableMapOf()
    private val rowViews: MutableMap<String, FontRowViews> = mutableMapOf()
    private var availableFonts: List<String> = emptyList()

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
                LinearLayout.LayoutParams(matchParent, 0).apply {
                    weight = 1f
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(ui)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.edit_fontset)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        loadState()
        buildRows()
        refreshRows()
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
            saveFontset()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun loadState() {
        if (!fontsDir.exists()) fontsDir.mkdirs()
        availableFonts = fontsDir.listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.filter {
                val l = it.lowercase()
                l.endsWith(".ttf") || l.endsWith(".otf") || l.endsWith(".ttc") || l.endsWith(".otc")
            }
            ?.sorted()
            .orEmpty()

        val parsed = org.fcitx.fcitx5.android.input.config.ConfigProviders
            .readFontsetPathMapSnapshot()
            .fold(
                onSuccess = { it?.value ?: emptyMap() },
                onFailure = {
                    toast(it)
                    emptyMap()
                }
            )
        entries.forEach { entry ->
            selectedFonts[entry.key] = parsed[entry.key]?.toMutableList() ?: mutableListOf()
        }
    }

    private fun buildRows() {
        listContainer.removeAllViews()
        entries.forEachIndexed { index, entry ->

            val openPicker = { openFontPicker(entry) }

            val title = TextView(this).apply {
                text = getString(entry.titleRes)
                setTextColor(styledColor(android.R.attr.textColorPrimary))
                textSize = 15f
                setOnClickListener { openPicker() }
            }
            listContainer.addView(title)

            val preview = TextView(this).apply {
                text = entry.sample
                textSize = 20f
                setPadding(0, dp(4), 0, 0)
                setOnClickListener { openPicker() }
            }
            listContainer.addView(preview)

            val value = TextView(this).apply {
                textSize = 13f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(4), 0, dp(12))
                setOnClickListener { openPicker() }
            }
            listContainer.addView(value)

            val divider = View(this).apply {
                setBackgroundColor(
                    runCatching { styledColor(android.R.attr.colorControlNormal) }
                        .getOrDefault(0x33000000)
                )
                alpha = 0.35f
            }
            listContainer.addView(
                divider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply {}
            )
            rowViews[entry.key] = FontRowViews(preview, value)
        }

        val hint = TextView(this).apply {
            text = getString(R.string.fontset_editor_hint) + "\n" + fontsetFile.absolutePath
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(8), 0, 0)
        }
        listContainer.addView(hint)
    }

    private fun refreshRows() {
        entries.forEach { entry ->
            val selected = selectedFonts[entry.key].orEmpty()
            val row = rowViews[entry.key] ?: return@forEach
            row.value.text = if (selected.isEmpty()) {
                ""
            } else {
                selected.joinToString(", ")
            }
            row.preview.typeface = buildTypeface(selected)
        }
    }

    private fun openFontPicker(entry: FontEntry) {
        if (availableFonts.isEmpty()) {
            toast(getString(R.string.no_fonts_found))
            return
        }
        val current = selectedFonts[entry.key].orEmpty().filter { it in availableFonts }
        val selectedOrder = current.toMutableList()
        val allOrdered = mutableListOf<String>().apply {
            addAll(selectedOrder)
            addAll(availableFonts.filter { it !in selectedOrder })
        }

        class FontItemHolder(
            row: LinearLayout,
            val order: TextView,
            val checkbox: CheckBox,
            val hint: TextView
        ) : RecyclerView.ViewHolder(row)

        val adapter = object : RecyclerView.Adapter<FontItemHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FontItemHolder {
                val row = LinearLayout(this@FontsetEditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pv = dp(4)
                    setPadding(0, pv, 0, pv)
                }
                val orderLabel = TextView(this@FontsetEditorActivity).apply {
                    width = dp(24)
                }
                val checkBox = CheckBox(this@FontsetEditorActivity)
                val dragHint = TextView(this@FontsetEditorActivity).apply {
                    text = "☰"
                    textSize = 18f
                    alpha = 0.5f
                    width = dp(28)
                    gravity = android.view.Gravity.CENTER
                }
                row.addView(orderLabel)
                row.addView(
                    checkBox,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        weight = 1f
                    }
                )
                row.addView(dragHint)

                return FontItemHolder(row, orderLabel, checkBox, dragHint)
            }

            override fun getItemCount(): Int = allOrdered.size

            override fun onBindViewHolder(holder: FontItemHolder, position: Int) {
                val fontName = allOrdered[position]
                val selectedIndex = selectedOrder.indexOf(fontName)
                val selected = selectedIndex >= 0

                holder.order.text = if (selected) "${selectedIndex + 1}." else ""
                holder.hint.alpha = if (selected) 0.8f else 0.25f

                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.text = fontName
                holder.checkbox.isChecked = selected
                holder.checkbox.setOnCheckedChangeListener { _, checked ->
                    val adapterPos = holder.bindingAdapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                    val name = allOrdered[adapterPos]
                    val currentlySelected = name in selectedOrder
                    if (checked) {
                        if (!currentlySelected) {
                            allOrdered.removeAt(adapterPos)
                            val insertPos = selectedOrder.size
                            selectedOrder.add(name)
                            allOrdered.add(insertPos, name)
                        }
                    } else {
                        if (currentlySelected) {
                            selectedOrder.remove(name)
                            allOrdered.removeAt(adapterPos)
                            allOrdered.add(name)
                        }
                    }
                    notifyDataSetChanged()
                }
            }
        }

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@FontsetEditorActivity)
            this.adapter = adapter
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false

                val selectedCount = selectedOrder.size
                if (from >= selectedCount || to >= selectedCount) return false

                val moving = allOrdered.removeAt(from)
                allOrdered.add(to, moving)

                selectedOrder.clear()
                selectedOrder.addAll(allOrdered.take(selectedCount))

                adapter.notifyItemMoved(from, to)
                adapter.notifyItemRangeChanged(0, allOrdered.size)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        itemTouchHelper.attachToRecyclerView(recycler)

        AlertDialog.Builder(this)
            .setTitle(entry.titleRes)
            .setView(recycler)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedFonts[entry.key] = selectedOrder.toMutableList()
                refreshRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveFontset() {
        val nonEmptyMap = entries
            .associate { entry -> entry.key to selectedFonts[entry.key].orEmpty() }
            .filterValues { it.isNotEmpty() }
        // allow saving empty fontset (means use system default fonts)
        org.fcitx.fcitx5.android.input.config.ConfigProviders.provider.writeFontsetPathMap(nonEmptyMap)
            .onSuccess { file ->
            org.fcitx.fcitx5.android.input.font.FontProviders.clearCache()
            AutoScaleTextView.refreshAllFontTypeFaces()
            CandidateAutoScaleTextView.refreshAllFontTypeFaces()
            // notify fcitx to reload config so input windows (keyboard) refresh style
            FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                reloadConfig()
            }
            toast(getString(R.string.fontset_saved_at, file.absolutePath))
            finish()
        }.onFailure {
            toast(it)
        }
    }

    private fun buildTypeface(fontNames: List<String>): Typeface? {
        val files = fontNames.map { File(fontsDir, it) }.filter { it.exists() }
        if (files.isEmpty()) return null

        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val firstFont = Font.Builder(files.first()).build()
                var builder = Typeface.CustomFallbackBuilder(
                    FontFamily.Builder(firstFont).build()
                )
                files.drop(1).forEach { file ->
                    val family = FontFamily.Builder(Font.Builder(file).build()).build()
                    builder = builder.addCustomFallback(family)
                }
                builder.build()
            } else {
                Typeface.createFromFile(files.first())
            }
        }.getOrNull()
    }

    companion object {
        private const val MENU_SAVE_ID = 1001
    }
}
