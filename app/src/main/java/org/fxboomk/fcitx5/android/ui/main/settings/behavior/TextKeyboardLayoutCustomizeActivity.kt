/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.UserConfigFiles
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.data.LayoutHeightPercentOverrides
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.preview.KeyboardPreviewManager
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fxboomk.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor

/**
 * 行级布局编辑页：针对某个基础布局或子模式布局，
 * 配置键盘高度（带实时预览），并可经"更多定制"直达布局设定界面编辑该行内容。
 */
class TextKeyboardLayoutCustomizeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_LAYOUT = "layout"
        const val EXTRA_SUBMODE = "submode"
        private const val FCITX_CONNECTION_NAME = "TextKeyboardLayoutCustomizeActivity"
        private const val MENU_SAVE_ID = 9002
        private const val MIN_LAYOUT_HEIGHT_PERCENT = 10
        private const val MAX_LAYOUT_HEIGHT_PERCENT = 90
    }

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private val fcitxConnection: FcitxConnection by lazy {
        FcitxDaemon.connect(FCITX_CONNECTION_NAME)
    }

    private lateinit var profileName: String
    private lateinit var layoutKey: String
    private var subModeLabel: String? = null
    private lateinit var dataManager: LayoutDataManager

    /** 实际生效的布局键：有专属子布局键时为 "layout:submode"，否则为基础布局键 */
    private lateinit var effectiveKey: String

    private var initialPortraitHeight: Int? = null
    private var initialLandscapeHeight: Int? = null
    private var portraitSeekBar: SeekBar? = null
    private var landscapeSeekBar: SeekBar? = null
    private var previewManager: KeyboardPreviewManager? = null
    private var saveMenuItem: MenuItem? = null

    private lateinit var previewContainer: LinearLayout

    private val root by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val scroll = androidx.core.widget.NestedScrollView(this@TextKeyboardLayoutCustomizeActivity).apply {
                addView(
                    buildContent(),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buildBottomBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        profileName = intent.getStringExtra(EXTRA_PROFILE)
            ?: UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE
        layoutKey = intent.getStringExtra(EXTRA_LAYOUT)
            ?: LayoutJsonUtils.DEFAULT_BASE_LAYOUT_KEY
        subModeLabel = intent.getStringExtra(EXTRA_SUBMODE)

        dataManager = LayoutDataManager(this)
        dataManager.loadFromFile(UserConfigFiles.textKeyboardLayoutJson(profileName))
        effectiveKey = if (subModeLabel != null && dataManager.entries.containsKey("$layoutKey:$subModeLabel")) {
            "$layoutKey:$subModeLabel"
        } else {
            layoutKey
        }

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = subModeLabel
            ?: LayoutJsonUtils.displayBaseLayoutName(layoutKey)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        setupPreview()
        updateSaveButtonState()
    }

    override fun onResume() {
        super.onResume()
        // 从"更多定制"的布局设定界面返回时，重新加载文件并刷新预览，
        // 使预览跟随在编辑器里做的布局改动
        dataManager.loadFromFile(UserConfigFiles.textKeyboardLayoutJson(profileName))
        previewManager?.updatePreview(layoutKey, subModeLabel, fcitxConnection)
        updatePreviewHeight()
    }

    override fun onDestroy() {
        runCatching { FcitxDaemon.disconnect(FCITX_CONNECTION_NAME) }
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val pad = dp(16)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val keyboardPrefs = AppPrefs.getInstance().keyboard
        val override = dataManager.getLayoutHeightPercentOverride(effectiveKey)
        val fileLevel = dataManager.profileHeightOverrides
        initialPortraitHeight = override?.portrait
            ?: fileLevel?.portrait
            ?: keyboardPrefs.keyboardHeightPercent.getValue()
        initialLandscapeHeight = override?.landscape
            ?: fileLevel?.landscape
            ?: keyboardPrefs.keyboardHeightPercentLandscape.getValue()

        content.addView(TextView(this).apply {
            text = getString(R.string.keyboard_height)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(
            addLayoutHeightSlider(getString(R.string.portrait), initialPortraitHeight ?: MIN_LAYOUT_HEIGHT_PERCENT) { portraitSeekBar = it }
        )
        content.addView(
            addLayoutHeightSlider(getString(R.string.landscape), initialLandscapeHeight ?: MIN_LAYOUT_HEIGHT_PERCENT) { landscapeSeekBar = it }
        )

        content.addView(TextView(this).apply {
            text = getString(R.string.text_keyboard_layout_customize_preview)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(styledColor(android.R.attr.textColorSecondary))
        })
        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        content.addView(
            previewContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return content
    }
    private fun buildBottomBar(): LinearLayout {
        val pad = dp(8)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            backgroundColor = styledColor(android.R.attr.colorBackgroundFloating)
            addView(Button(this@TextKeyboardLayoutCustomizeActivity).apply {
                text = getString(R.string.text_keyboard_layout_manage_more_customize)
                setOnClickListener { openLayoutEditor() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun addLayoutHeightSlider(
        label: String,
        initialValue: Int,
        onCreated: (SeekBar) -> Unit
    ): LinearLayout {
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
                    updatePreviewHeight()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        onCreated(seekBar)
        group.addView(valueLabel)
        group.addView(seekBar)
        return group
    }

    private fun setupPreview() {
        previewManager = KeyboardPreviewManager(
            this,
            previewContainer,
            dataManager.entries
        ) { key ->
            dataManager.getLayoutHeightPercentOverride(key) ?: dataManager.profileHeightOverrides
        }
        previewManager?.updatePreview(layoutKey, subModeLabel, fcitxConnection)
        updatePreviewHeight()
    }

    private fun updatePreviewHeight() {
        val isLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val percent = (if (isLandscape) landscapeSeekBar else portraitSeekBar)
            ?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT) ?: return
        previewManager?.updatePreviewHeight(percent)
    }

    private fun updateSaveButtonState() {
        val portrait = portraitSeekBar?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT)
        val landscape = landscapeSeekBar?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT)
        val changed = portrait != initialPortraitHeight || landscape != initialLandscapeHeight
        saveMenuItem?.isEnabled = changed
        // 与布局设定界面一致：有变更时黑色高亮，无变更置灰
        saveMenuItem?.icon?.mutate()?.setTint(if (changed) android.graphics.Color.BLACK else android.graphics.Color.GRAY)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, getString(R.string.save))
            .apply {
                setIcon(R.drawable.ic_baseline_save_24)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_SAVE_ID -> {
                saveHeights()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveHeights(): Boolean {
        val portrait = portraitSeekBar?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT)
            ?: initialPortraitHeight
        val landscape = landscapeSeekBar?.progress?.plus(MIN_LAYOUT_HEIGHT_PERCENT)
            ?: initialLandscapeHeight
        if (portrait == initialPortraitHeight && landscape == initialLandscapeHeight) {
            return true
        }
        dataManager.setLayoutHeightPercentOverride(
            effectiveKey,
            LayoutHeightPercentOverrides(portrait = portrait, landscape = landscape)
        )
        val file = UserConfigFiles.textKeyboardLayoutJson(profileName)
        val saved = file != null && dataManager.saveToFile(file)
        if (!saved) {
            toast(getString(R.string.text_keyboard_layout_manage_save_failed))
            return false
        }
        ConfigProviders.provider = ConfigProviders.provider
        initialPortraitHeight = portrait
        initialLandscapeHeight = landscape
        updateSaveButtonState()
        return true
    }

    private fun openLayoutEditor() {
        // 先保存高度，保证编辑器内预览与当前设置一致
        saveHeights()
        startActivity(Intent(this, TextKeyboardLayoutEditorActivity::class.java).apply {
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_PROFILE, profileName)
            putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_LAYOUT, layoutKey)
            subModeLabel?.let { putExtra(TextKeyboardLayoutEditorActivity.EXTRA_TARGET_SUBMODE, it) }
        })
    }
}
