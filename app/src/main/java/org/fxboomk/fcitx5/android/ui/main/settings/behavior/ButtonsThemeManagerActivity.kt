/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.BuildConfig
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.ui.main.keyboardtest.KeyboardTestMenu
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutThemeStore
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.DEFAULT_BUTTONS_THEME_NAME
import org.fxboomk.fcitx5.android.input.config.KeyIconStore
import org.fxboomk.fcitx5.android.input.config.KeyIconThemesConfig
import org.fxboomk.fcitx5.android.input.config.ThemeTransferCodec
import org.fxboomk.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledColor
import java.io.File

/** Manages the saved toolbar button themes and key icon themes. */
class ButtonsThemeManagerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOOLBAR_ACTIVE_CHANGED =
            "org.fxboomk.fcitx5.android.extra.TOOLBAR_ACTIVE_CHANGED"
        const val EXTRA_KEY_ICON_ACTIVE_CHANGED =
            "org.fxboomk.fcitx5.android.extra.KEY_ICON_ACTIVE_CHANGED"

        private const val STATE_COLLAPSED_GROUPS = "collapsed_groups"
        private const val STATE_TOOLBAR_ACTIVE_CHANGED = "toolbar_active_changed"
        private const val STATE_KEY_ICON_ACTIVE_CHANGED = "key_icon_active_changed"
    }

    private enum class ThemeGroup {
        Toolbar,
        KeyIcons
    }

    private val prettyJson = Json { prettyPrint = true }

    private val toolbar by lazy {
        Toolbar(this).apply {
            setBackgroundColor(styledColor(android.R.attr.colorPrimary))
            setTitleTextColor(styledColor(android.R.attr.textColorPrimary))
            elevation = dp(4f)
        }
    }

    private val content by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
    }

    private val scrollView by lazy {
        NestedScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private val root by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
    }

    private val collapsedGroups = mutableSetOf<ThemeGroup>()
    private var pendingImportGroup: ThemeGroup? = null
    private var toolbarActiveChanged = false
    private var keyIconActiveChanged = false

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val group = pendingImportGroup ?: return@registerForActivityResult
        pendingImportGroup = null
        if (uri == null) return@registerForActivityResult
        importTheme(group, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.theme_manager_title)
        KeyboardTestMenu(this, this)

        savedInstanceState?.getStringArrayList(STATE_COLLAPSED_GROUPS)?.forEach { name ->
            runCatching { ThemeGroup.valueOf(name) }.getOrNull()?.let(collapsedGroups::add)
        }
        toolbarActiveChanged = savedInstanceState?.getBoolean(STATE_TOOLBAR_ACTIVE_CHANGED) ?: false
        keyIconActiveChanged = savedInstanceState?.getBoolean(STATE_KEY_ICON_ACTIVE_CHANGED) ?: false
        if (toolbarActiveChanged || keyIconActiveChanged) syncResult()

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_COLLAPSED_GROUPS,
            ArrayList(collapsedGroups.map { it.name })
        )
        outState.putBoolean(STATE_TOOLBAR_ACTIVE_CHANGED, toolbarActiveChanged)
        outState.putBoolean(STATE_KEY_ICON_ACTIVE_CHANGED, keyIconActiveChanged)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            setText(R.string.theme_manager_hint)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(8))
        })
        val toolbarThemes = ButtonsLayoutThemeStore.load()
        addGroup(
            ThemeGroup.Toolbar,
            toolbarThemes.themes.map { it.name },
            toolbarThemes.activeTheme
        )
        val keyIconThemes = KeyIconStore.read()
        addGroup(
            ThemeGroup.KeyIcons,
            keyIconThemes.themes.map { it.name },
            keyIconThemes.activeTheme
        )
    }

    private fun addGroup(group: ThemeGroup, names: List<String>, activeName: String) {
        val collapsed = group in collapsedGroups
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = selectableBackground()
            isClickable = true
            setOnClickListener { toggleGroup(group) }
        }
        header.addView(
            TextView(this).apply {
                setText(
                    when (group) {
                        ThemeGroup.Toolbar -> R.string.toolbar_themes_group
                        ThemeGroup.KeyIcons -> R.string.key_icon_themes_group
                    }
                )
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(styledColor(android.R.attr.textColorPrimary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            iconButton(
                R.drawable.ic_baseline_file_download_24,
                getString(R.string.theme_import_action),
                tint = styledColor(androidx.appcompat.R.attr.colorAccent)
            ) { startImport(group) }
        )
        header.addView(
            ImageView(this).apply {
                setImageResource(
                    if (collapsed) R.drawable.ic_baseline_expand_more_24
                    else R.drawable.ic_baseline_expand_less_24
                )
                imageTintList = ColorStateList.valueOf(
                    styledColor(android.R.attr.textColorSecondary)
                )
                contentDescription = getString(
                    if (collapsed) R.string.text_keyboard_layout_manage_expand
                    else R.string.text_keyboard_layout_manage_collapse
                )
                setPadding(dp(4), dp(4), dp(4), dp(4))
            },
            LinearLayout.LayoutParams(dp(32), dp(32))
        )
        content.addView(header)

        if (!collapsed) {
            names.forEachIndexed { index, name ->
                content.addView(
                    buildThemeRow(
                        group = group,
                        name = name,
                        index = index,
                        active = name == activeName
                    )
                )
            }
        }
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
    }

    private fun buildThemeRow(
        group: ThemeGroup,
        name: String,
        index: Int,
        active: Boolean
    ): View {
        val protectedTheme = name == DEFAULT_BUTTONS_THEME_NAME ||
            name == KeyIconThemesConfig.DEFAULT_THEME_NAME
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(4), dp(4))
            background = selectableBackground()
            isClickable = true
            setOnClickListener { activateTheme(group, name) }

            addView(
                TextView(this@ButtonsThemeManagerActivity).apply {
                    text = displayThemeName(name)
                    textSize = 16f
                    if (active) setTypeface(typeface, Typeface.BOLD)
                    setTextColor(styledColor(android.R.attr.textColorPrimary))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            if (!protectedTheme) {
                addView(
                    iconButton(
                        R.drawable.ic_baseline_share_24,
                        getString(R.string.share)
                    ) { shareTheme(group, name) }
                )
                addView(
                    iconButton(
                        R.drawable.ic_baseline_arrow_upward_24,
                        getString(R.string.text_keyboard_layout_manage_move_up),
                        enabled = index > 1
                    ) { moveThemeUp(group, name) }
                )
                addView(
                    iconButton(
                        R.drawable.ic_baseline_edit_24,
                        getString(R.string.rename)
                    ) { showRenameDialog(group, name) }
                )
                addView(
                    iconButton(
                        R.drawable.ic_baseline_delete_24,
                        getString(R.string.delete),
                        tint = styledColor(androidx.appcompat.R.attr.colorError)
                    ) { confirmDelete(group, name) }
                )
            }
        }
    }

    private fun iconButton(
        @DrawableRes iconRes: Int,
        contentDesc: String,
        enabled: Boolean = true,
        tint: Int? = null,
        onClick: () -> Unit
    ): ImageButton {
        val borderless = TypedValue().apply {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
        }
        return ImageButton(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(
                tint ?: styledColor(android.R.attr.textColorPrimary)
            )
            contentDescription = contentDesc
            background = ContextCompat.getDrawable(context, borderless.resourceId)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.38f
            setOnClickListener { if (enabled) onClick() }
        }
    }

    private fun selectableBackground(): Drawable? {
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return ContextCompat.getDrawable(this, value.resourceId)
    }

    private fun toggleGroup(group: ThemeGroup) {
        if (!collapsedGroups.add(group)) collapsedGroups.remove(group)
        render()
    }

    private fun startImport(group: ThemeGroup) {
        pendingImportGroup = group
        importLauncher.launch(arrayOf("application/json", "text/plain"))
    }

    private fun importTheme(group: ThemeGroup, uri: Uri) {
        runCatching {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("Cannot open source")
            when (group) {
                ThemeGroup.Toolbar -> {
                    val theme = ThemeTransferCodec.decodeToolbar(text)
                    require(theme.name.isNotBlank()) { "Theme name cannot be empty" }
                    ButtonsLayoutThemeStore.save(theme.name, theme.config)
                    applyToolbarConfig(theme.config)
                }

                ThemeGroup.KeyIcons -> {
                    val theme = ThemeTransferCodec.decodeKeyIcons(text)
                    require(theme.name.isNotBlank()) { "Theme name cannot be empty" }
                    KeyIconStore.saveTheme(theme, activate = true).getOrThrow()
                }
            }
        }.onSuccess {
            markActiveChanged(group)
            toast(R.string.theme_imported)
            render()
        }.onFailure {
            toast(getString(R.string.theme_import_failed, it.message ?: ""))
        }
    }

    private fun shareTheme(group: ThemeGroup, name: String) {
        runCatching {
            val payload = when (group) {
                ThemeGroup.Toolbar -> {
                    val theme = ButtonsLayoutThemeStore.load().themes
                        .firstOrNull { it.name == name }
                        ?: error("Unknown theme: $name")
                    ThemeTransferCodec.encodeToolbar(theme)
                }

                ThemeGroup.KeyIcons -> {
                    val theme = KeyIconStore.listThemes()
                        .firstOrNull { it.name == name }
                        ?: error("Unknown theme: $name")
                    ThemeTransferCodec.encodeKeyIcons(theme)
                }
            }
            val suffix = when (group) {
                ThemeGroup.Toolbar -> ".fcitx-toolbar-buttons.json"
                ThemeGroup.KeyIcons -> ".fcitx-key-icons.json"
            }
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "${safeFileName(name)}$suffix")
            file.writeText(payload)
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.share.fileprovider",
                file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.share)
                )
            )
        }.onFailure {
            toast(getString(R.string.theme_share_failed, it.message ?: ""))
        }
    }

    private fun safeFileName(name: String): String =
        name.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .ifBlank { "theme" }

    private fun activateTheme(group: ThemeGroup, name: String) {
        if (activeThemeName(group) == name) return
        when (group) {
            ThemeGroup.Toolbar -> {
                val config = ButtonsLayoutThemeStore.activate(name) ?: return
                applyToolbarConfig(config)
            }

            ThemeGroup.KeyIcons -> {
                KeyIconStore.activate(name).onFailure {
                    toast(it)
                    return
                }
            }
        }
        markActiveChanged(group)
        render()
    }

    private fun showRenameDialog(group: ThemeGroup, name: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.theme_name_hint)
            setText(name)
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target = input.text.toString().trim()
                if (target.isEmpty()) {
                    input.error = getString(R.string.theme_name_empty)
                    return@setOnClickListener
                }
                if (target != name && themeNames(group).contains(target)) {
                    input.error = getString(R.string.theme_name_in_use)
                    return@setOnClickListener
                }
                val result = when (group) {
                    ThemeGroup.Toolbar -> ButtonsLayoutThemeStore.rename(name, target)
                    ThemeGroup.KeyIcons -> KeyIconStore.rename(name, target)
                }
                result.onSuccess {
                    if (group == ThemeGroup.KeyIcons && activeThemeName(group) == target) {
                        markActiveChanged(group)
                    }
                    toast(R.string.saved)
                    dialog.dismiss()
                    render()
                }.onFailure {
                    input.error = it.message
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(group: ThemeGroup, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, displayThemeName(name)))
            .setPositiveButton(R.string.delete) { _, _ ->
                val wasActive = activeThemeName(group) == name
                when (group) {
                    ThemeGroup.Toolbar -> {
                        ButtonsLayoutThemeStore.delete(name)
                        if (wasActive) {
                            applyToolbarConfig(ButtonsLayoutThemeStore.activeTheme().config)
                        }
                    }

                    ThemeGroup.KeyIcons -> {
                        KeyIconStore.delete(name).onFailure {
                            toast(it)
                            return@setPositiveButton
                        }
                    }
                }
                if (wasActive) markActiveChanged(group)
                toast(R.string.done)
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveThemeUp(group: ThemeGroup, name: String) {
        val result = when (group) {
            ThemeGroup.Toolbar -> ButtonsLayoutThemeStore.moveUp(name)
            ThemeGroup.KeyIcons -> KeyIconStore.moveUp(name)
        }
        result.onSuccess { render() }.onFailure { toast(it) }
    }

    private fun themeNames(group: ThemeGroup): List<String> = when (group) {
        ThemeGroup.Toolbar -> ButtonsLayoutThemeStore.load().themes.map { it.name }
        ThemeGroup.KeyIcons -> KeyIconStore.listThemes().map { it.name }
    }

    private fun activeThemeName(group: ThemeGroup): String = when (group) {
        ThemeGroup.Toolbar -> ButtonsLayoutThemeStore.load().activeTheme
        ThemeGroup.KeyIcons -> KeyIconStore.read().activeTheme
    }

    private fun displayThemeName(name: String): String =
        if (name == DEFAULT_BUTTONS_THEME_NAME || name == KeyIconThemesConfig.DEFAULT_THEME_NAME) {
            getString(R.string.default_icon)
        } else {
            name
        }

    private fun applyToolbarConfig(config: ButtonsLayoutConfig) {
        val file = ConfigProviders.provider.buttonsLayoutConfigFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val resolved = config.copy(hideKeyboardButton = config.resolvedHideKeyboardButton())
            file.writeText(prettyJson.encodeToString(resolved) + "\n")
        }.onFailure {
            toast(getString(R.string.save_failed) + ": " + (it.message ?: ""))
        }
    }

    private fun markActiveChanged(group: ThemeGroup) {
        when (group) {
            ThemeGroup.Toolbar -> toolbarActiveChanged = true
            ThemeGroup.KeyIcons -> keyIconActiveChanged = true
        }
        syncResult()
    }

    private fun syncResult() {
        if (!toolbarActiveChanged && !keyIconActiveChanged) return
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_TOOLBAR_ACTIVE_CHANGED, toolbarActiveChanged)
                putExtra(EXTRA_KEY_ICON_ACTIVE_CHANGED, keyIconActiveChanged)
            }
        )
    }
}
