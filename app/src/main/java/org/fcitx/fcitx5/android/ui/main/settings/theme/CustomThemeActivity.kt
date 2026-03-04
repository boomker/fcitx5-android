/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
// android.view.ViewGroup (imported earlier)
import android.webkit.MimeTypeMap
import android.widget.SeekBar
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropContract
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropOption
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropResult
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.parcelable
import splitties.dimensions.dp
import splitties.resources.color
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.appcompat.switch
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.packed
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.verticalLayout
import android.graphics.Color
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.graphics.drawable.GradientDrawable
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.lParams
import android.widget.GridLayout
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar.OnSeekBarChangeListener
import android.widget.SeekBar as AndroidSeekBar
import android.widget.TextView
import splitties.views.dsl.core.seekBar
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView
import splitties.views.gravityVerticalCenter
import splitties.views.horizontalPadding
import splitties.views.textAppearance
import splitties.views.topPadding
import java.io.File

class CustomThemeActivity : AppCompatActivity() {
    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Theme.Custom?, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Theme.Custom?): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private val toolbar by lazy {
        view(::Toolbar) {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var previewUi: KeyboardPreviewUi

    private fun createTextView(@StringRes string: Int? = null, ripple: Boolean = false) = textView {
        if (string != null) {
            setText(string)
        }
        gravity = gravityVerticalCenter
        textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        horizontalPadding = dp(16)
        if (ripple) {
            background = styledDrawable(android.R.attr.selectableItemBackground)
        }
    }

    private fun formatArgbHex(color: Int): String = String.format(
        "#%02X%02X%02X%02X",
        Color.alpha(color),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun parseArgbHex(input: String): Int? {
        val normalized = input.trim().removePrefix("#")
        val hex = when (normalized.length) {
            6 -> "FF$normalized"
            8 -> normalized
            else -> return null
        }
        return try {
            java.lang.Long.parseLong(hex, 16).toInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun currentBackgroundDrawable(themeForBackground: Theme.Custom): BitmapDrawable? {
        return if (themeForBackground.backgroundImage != null)
            runCatching { backgroundStates.filteredDrawable }.getOrNull()
        else null
    }

    private lateinit var candidateTextPreview: TextView
    private lateinit var candidateLabelPreview: TextView
    private lateinit var candidateCommentPreview: TextView
    private lateinit var popupPreview: TextView
    private lateinit var dividerPreview: View
    private lateinit var clipboardPreview: TextView
    private lateinit var genericActivePreview: TextView

    private fun updateSupplementColorPreview(themeForPreview: Theme.Custom) {
        candidateTextPreview.setTextColor(themeForPreview.candidateTextColor)
        candidateLabelPreview.setTextColor(themeForPreview.candidateLabelColor)
        candidateCommentPreview.setTextColor(themeForPreview.candidateCommentColor)
        popupPreview.setTextColor(themeForPreview.popupTextColor)
        popupPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f)
            setColor(themeForPreview.popupBackgroundColor)
        }
        dividerPreview.setBackgroundColor(themeForPreview.dividerColor)
        clipboardPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f)
            setColor(themeForPreview.clipboardEntryColor)
        }
        genericActivePreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f)
            setColor(themeForPreview.genericActiveBackgroundColor)
        }
        genericActivePreview.setTextColor(themeForPreview.genericActiveForegroundColor)
    }

    private fun applyThemePreview(themeForPreview: Theme.Custom, background: BitmapDrawable? = currentBackgroundDrawable(themeForPreview)) {
        previewUi.setTheme(themeForPreview, background)
        updateSupplementColorPreview(themeForPreview)
    }

    private fun findInlineEditorIndex(parent: ViewGroup): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).tag == INLINE_COLOR_EDITOR_TAG) return i
        }
        return -1
    }

    private fun createCheckerboardDrawable(tileSize: Int): BitmapDrawable {
        val size = tileSize * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = 0xFFCCCCCC.toInt()
        canvas.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), paint)
        canvas.drawRect(tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), size.toFloat(), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(tileSize.toFloat(), 0f, size.toFloat(), tileSize.toFloat(), paint)
        canvas.drawRect(0f, tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), paint)
        return BitmapDrawable(resources, bitmap).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }

    private fun createInlineColorEditor(
        initialColor: Int,
        onPreview: (Int) -> Unit,
        onConfirm: (Int) -> Unit,
        onCancel: () -> Unit
    ): View {
        var editingColor = initialColor
        return android.widget.LinearLayout(this).apply {
            tag = INLINE_COLOR_EDITOR_TAG
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = dp(8)
            setPadding(pad, pad, pad, pad)

            val colorPreview = View(this@CustomThemeActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f)
                    setColor(editingColor)
                }
                minimumHeight = dp(36)
            }
            val checkerPreviewContainer = android.widget.FrameLayout(this@CustomThemeActivity).apply {
                background = createCheckerboardDrawable(dp(6))
                val checkerPadding = dp(4)
                setPadding(checkerPadding, checkerPadding, checkerPadding, checkerPadding)
                addView(
                    colorPreview,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(36)
                    )
                )
            }
            addView(
                checkerPreviewContainer,
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            )

            val hexInput = EditText(this@CustomThemeActivity).apply {
                setText(formatArgbHex(editingColor))
                isSingleLine = true
                setSelectAllOnFocus(true)
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                horizontalPadding = dp(6)
            }
            addView(
                hexInput,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val aSeek = AndroidSeekBar(this@CustomThemeActivity).apply {
                max = 255
                progress = Color.alpha(editingColor)
            }
            val rSeek = AndroidSeekBar(this@CustomThemeActivity).apply {
                max = 255
                progress = Color.red(editingColor)
            }
            val gSeek = AndroidSeekBar(this@CustomThemeActivity).apply {
                max = 255
                progress = Color.green(editingColor)
            }
            val bSeek = AndroidSeekBar(this@CustomThemeActivity).apply {
                max = 255
                progress = Color.blue(editingColor)
            }

            fun addRow(labelText: String, seek: AndroidSeekBar) {
                val row = android.widget.LinearLayout(this@CustomThemeActivity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    val lbl = TextView(this@CustomThemeActivity).apply {
                        text = labelText
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            dp(36),
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(lbl)
                    val sp = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { weight = 1f }
                    seek.layoutParams = sp
                    addView(seek)
                    val innerPad = dp(6)
                    setPadding(0, innerPad, 0, innerPad)
                    minimumHeight = dp(40)
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                addView(row, lp)
            }

            addRow("A", aSeek)
            addRow("R", rSeek)
            addRow("G", gSeek)
            addRow("B", bSeek)

            val btnRow = android.widget.LinearLayout(this@CustomThemeActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            val ok = Button(this@CustomThemeActivity).apply { text = getString(android.R.string.ok) }
            val cancel = Button(this@CustomThemeActivity).apply { text = getString(android.R.string.cancel) }
            val spacer = View(this@CustomThemeActivity)
            val spLp = android.widget.LinearLayout.LayoutParams(
                0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { weight = 1f }
            btnRow.addView(spacer, spLp)
            btnRow.addView(cancel)
            btnRow.addView(ok)
            addView(
                btnRow,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            var suppressTextChange = false
            val liveListener = object : OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: AndroidSeekBar?, progress: Int, fromUser: Boolean) {
                    editingColor = Color.argb(aSeek.progress, rSeek.progress, gSeek.progress, bSeek.progress)
                    (colorPreview.background as GradientDrawable).setColor(editingColor)
                    if (!suppressTextChange) {
                        suppressTextChange = true
                        val hex = formatArgbHex(editingColor)
                        hexInput.setText(hex)
                        hexInput.setSelection(hex.length)
                        suppressTextChange = false
                    }
                    onPreview(editingColor)
                }

                override fun onStartTrackingTouch(seekBar: AndroidSeekBar?) {}
                override fun onStopTrackingTouch(seekBar: AndroidSeekBar?) {}
            }

            aSeek.setOnSeekBarChangeListener(liveListener)
            rSeek.setOnSeekBarChangeListener(liveListener)
            gSeek.setOnSeekBarChangeListener(liveListener)
            bSeek.setOnSeekBarChangeListener(liveListener)

            hexInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (suppressTextChange) return
                    val parsed = parseArgbHex((s ?: "").toString()) ?: return
                    suppressTextChange = true
                    aSeek.progress = Color.alpha(parsed)
                    rSeek.progress = Color.red(parsed)
                    gSeek.progress = Color.green(parsed)
                    bSeek.progress = Color.blue(parsed)
                    suppressTextChange = false
                    editingColor = parsed
                    (colorPreview.background as GradientDrawable).setColor(editingColor)
                    onPreview(editingColor)
                }

                override fun afterTextChanged(s: Editable?) {}
            })

            ok.setOnClickListener {
                onConfirm(editingColor)
                (parent as? ViewGroup)?.removeView(this)
            }

            cancel.setOnClickListener {
                onCancel()
                (parent as? ViewGroup)?.removeView(this)
            }
        }
    }

    private val variantLabel by lazy {
        createTextView(R.string.dark_keys, ripple = true)
    }
    private val variantSwitch by lazy {
        switch {
            // Use dark keys by default
            isChecked = false
        }
    }

    private val brightnessLabel by lazy {
        createTextView(R.string.brightness)
    }
    private val brightnessValue by lazy {
        createTextView()
    }
    private val brightnessSeekBar by lazy {
        seekBar {
            max = 100
        }
    }

    private val cropLabel by lazy {
        createTextView(R.string.recrop_image, ripple = true)
    }

    private val supplementPreview by lazy {
        verticalLayout {
            val padH = dp(30)
            val padV = dp(4)
            setPadding(padH, padV, padH, padV)

            val oneLineRow = android.widget.LinearLayout(this@CustomThemeActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL

                fun addGap(widthDp: Int = 10) {
                    addView(
                        View(this@CustomThemeActivity),
                        android.widget.LinearLayout.LayoutParams(dp(widthDp), 1)
                    )
                }

                candidateTextPreview = TextView(this@CustomThemeActivity).apply { text = "候选" }
                candidateLabelPreview = TextView(this@CustomThemeActivity).apply { text = "标签" }
                candidateCommentPreview = TextView(this@CustomThemeActivity).apply { text = "注释" }
                popupPreview = TextView(this@CustomThemeActivity).apply {
                    text = "弹出"
                    val hp = dp(10)
                    val vp = dp(3)
                    setPadding(hp, vp, hp, vp)
                }
                dividerPreview = View(this@CustomThemeActivity)
                clipboardPreview = TextView(this@CustomThemeActivity).apply {
                    text = "剪贴板"
                    val hp = dp(8)
                    val vp = dp(3)
                    setPadding(hp, vp, hp, vp)
                }
                genericActivePreview = TextView(this@CustomThemeActivity).apply {
                    text = "激活"
                    val hp = dp(8)
                    val vp = dp(3)
                    setPadding(hp, vp, hp, vp)
                }

                addView(candidateTextPreview)
                addGap()
                addView(candidateLabelPreview)
                addGap()
                addView(candidateCommentPreview)
                addGap()
                addView(popupPreview)
                addGap(8)
                addView(dividerPreview, android.widget.LinearLayout.LayoutParams(dp(1), dp(18)))
                addGap(8)
                addView(clipboardPreview)
                addGap()
                addView(genericActivePreview)
            }

            val scroll = android.widget.HorizontalScrollView(this@CustomThemeActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    oneLineRow,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(scroll, android.widget.LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private val scrollView by lazy {
        val lineHeight = dp(48)
        val itemMargin = dp(30)
        // colorsContainer will hold editable color rows for Theme fields
        val colorsContainer = verticalLayout {
            val lineHeight = dp(48)
            val colorItems = listOf(
                Triple("Background", { t: Theme.Custom -> t.backgroundColor }, { t: Theme.Custom, c: Int -> t.copy(backgroundColor = c) }),
                Triple("Bar", { t: Theme.Custom -> t.barColor }, { t: Theme.Custom, c: Int -> t.copy(barColor = c) }),
                Triple("Keyboard", { t: Theme.Custom -> t.keyboardColor }, { t: Theme.Custom, c: Int -> t.copy(keyboardColor = c) }),
                Triple("Key Background", { t: Theme.Custom -> t.keyBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(keyBackgroundColor = c) }),
                Triple("Key Text", { t: Theme.Custom -> t.keyTextColor }, { t: Theme.Custom, c: Int -> t.copy(keyTextColor = c) }),
                Triple("Alt Key Text", { t: Theme.Custom -> t.altKeyTextColor }, { t: Theme.Custom, c: Int -> t.copy(altKeyTextColor = c) }),
                Triple("Accent Key Background", { t: Theme.Custom -> t.accentKeyBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(accentKeyBackgroundColor = c) }),
                Triple("Accent Key Text", { t: Theme.Custom -> t.accentKeyTextColor }, { t: Theme.Custom, c: Int -> t.copy(accentKeyTextColor = c) }),
                Triple("Candidate Text", { t: Theme.Custom -> t.candidateTextColor }, { t: Theme.Custom, c: Int -> t.copy(candidateTextColor = c) }),
                Triple("Candidate Label", { t: Theme.Custom -> t.candidateLabelColor }, { t: Theme.Custom, c: Int -> t.copy(candidateLabelColor = c) }),
                Triple("Candidate Comment", { t: Theme.Custom -> t.candidateCommentColor }, { t: Theme.Custom, c: Int -> t.copy(candidateCommentColor = c) }),
                Triple("Key Press Highlight", { t: Theme.Custom -> t.keyPressHighlightColor }, { t: Theme.Custom, c: Int -> t.copy(keyPressHighlightColor = c) }),
                Triple("Key Shadow", { t: Theme.Custom -> t.keyShadowColor }, { t: Theme.Custom, c: Int -> t.copy(keyShadowColor = c) }),
                Triple("Popup Background", { t: Theme.Custom -> t.popupBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(popupBackgroundColor = c) }),
                Triple("Popup Text", { t: Theme.Custom -> t.popupTextColor }, { t: Theme.Custom, c: Int -> t.copy(popupTextColor = c) }),
                Triple("Space Bar", { t: Theme.Custom -> t.spaceBarColor }, { t: Theme.Custom, c: Int -> t.copy(spaceBarColor = c) }),
                Triple("Divider", { t: Theme.Custom -> t.dividerColor }, { t: Theme.Custom, c: Int -> t.copy(dividerColor = c) }),
                Triple("Clipboard Entry", { t: Theme.Custom -> t.clipboardEntryColor }, { t: Theme.Custom, c: Int -> t.copy(clipboardEntryColor = c) }),
                Triple("Generic Active Background", { t: Theme.Custom -> t.genericActiveBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(genericActiveBackgroundColor = c) }),
                Triple("Generic Active Foreground", { t: Theme.Custom -> t.genericActiveForegroundColor }, { t: Theme.Custom, c: Int -> t.copy(genericActiveForegroundColor = c) })
            )

            for (item in colorItems) {
                val label = createTextView(null, ripple = true).apply {
                    text = item.first
                }
                val preview = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f)
                    setSize(dp(28), dp(28))
                    setColor(item.second(theme))
                }
                label.setCompoundDrawablesWithIntrinsicBounds(null, null, preview, null)
                label.compoundDrawablePadding = dp(12)
                label.setOnClickListener {
                    val parent = label.parent as ViewGroup
                    var insertIndex = parent.indexOfChild(label) + 1
                    val existingEditorIndex = findInlineEditorIndex(parent)
                    if (existingEditorIndex >= 0) {
                        if (existingEditorIndex == insertIndex) {
                            parent.removeViewAt(existingEditorIndex)
                            applyThemePreview(theme)
                            return@setOnClickListener
                        }
                        parent.removeViewAt(existingEditorIndex)
                        applyThemePreview(theme)
                        insertIndex = parent.indexOfChild(label) + 1
                    }

                    val originalTheme = theme
                    val editor = createInlineColorEditor(
                        initialColor = item.second(originalTheme),
                        onPreview = { c ->
                            val tmpTheme = item.third(originalTheme, c)
                            applyThemePreview(tmpTheme, currentBackgroundDrawable(originalTheme))
                        },
                        onConfirm = { c ->
                            theme = item.third(originalTheme, c)
                            preview.setColor(c)
                            applyThemePreview(theme)
                        },
                        onCancel = {
                            applyThemePreview(theme)
                        }
                    )
                    parent.addView(editor, insertIndex)
                }
                add(label, lParams(matchParent, lineHeight))
            }
        }

        constraintLayout {
            bottomPadding = dp(24)
            add(cropLabel, lParams(matchConstraints, lineHeight) {
                topOfParent()
                centerHorizontally(itemMargin)
                above(variantLabel)
            })
            add(variantLabel, lParams(matchConstraints, lineHeight) {
                below(cropLabel)
                startOfParent(itemMargin)
                before(variantSwitch)
                above(brightnessLabel)
            })
            add(variantSwitch, lParams(wrapContent, lineHeight) {
                topToTopOf(variantLabel)
                endOfParent(itemMargin)
            })
            add(brightnessLabel, lParams(matchConstraints, lineHeight) {
                below(variantLabel)
                startOfParent(itemMargin)
                before(brightnessValue)
                above(brightnessSeekBar)
            })
            add(brightnessValue, lParams(wrapContent, lineHeight) {
                topToTopOf(brightnessLabel)
                endOfParent(itemMargin)
            })
            add(brightnessSeekBar, lParams(matchConstraints, wrapContent) {
                below(brightnessLabel)
                centerHorizontally(itemMargin)
                above(colorsContainer)
            })
            // add the colors container below brightness controls
            add(colorsContainer, lParams(matchConstraints, wrapContent) {
                below(brightnessSeekBar)
                centerHorizontally(itemMargin)
                bottomOfParent()
            })
        }.wrapInScrollView {
            isFillViewport = true
        }
    }

    private val ui by lazy {
        constraintLayout {
            add(toolbar, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            add(previewUi.root, lParams(wrapContent, wrapContent) {
                below(toolbar)
                centerHorizontally()
            })
            add(supplementPreview, lParams(matchConstraints, wrapContent) {
                below(previewUi.root)
                centerHorizontally()
            })
            add(scrollView, lParams {
                below(supplementPreview)
                centerHorizontally()
                bottomOfParent()
                topMargin = dp(8)
            })
        }
    }

    private var newCreated = true

    private lateinit var theme: Theme.Custom

    private class BackgroundStates {
        lateinit var launcher: ActivityResultLauncher<CropOption>
        var srcImageExtension: String? = null
        var srcImageBuffer: ByteArray? = null
        var cropRect: Rect? = null
        var cropRotation: Int = 0
        lateinit var croppedBitmap: Bitmap
        lateinit var filteredDrawable: BitmapDrawable
        lateinit var srcImageFile: File
        lateinit var croppedImageFile: File
    }

    private val backgroundStates by lazy { BackgroundStates() }

    private inline fun whenHasBackground(
        block: BackgroundStates.(Theme.Custom.CustomBackground) -> Unit,
    ) {
        if (theme.backgroundImage != null)
            block(backgroundStates, theme.backgroundImage!!)
    }

    private fun BackgroundStates.setKeyVariant(
        background: Theme.Custom.CustomBackground,
        darkKeys: Boolean
    ) {
        val template = if (darkKeys) ThemePreset.TransparentLight else ThemePreset.TransparentDark
        theme = template.deriveCustomBackground(
            theme.name,
            background.croppedFilePath,
            background.srcFilePath,
            brightnessSeekBar.progress,
            background.cropRect,
            background.cropRotation
        )
        applyThemePreview(theme, filteredDrawable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recover from bundle
        val originTheme = intent?.parcelable<Theme.Custom>(ORIGIN_THEME)?.also { t ->
            theme = t
            whenHasBackground {
                croppedImageFile = File(it.croppedFilePath)
                srcImageFile = File(it.srcFilePath)
                cropRect = it.cropRect
                cropRotation = it.cropRotation
                croppedBitmap = BitmapFactory.decodeFile(it.croppedFilePath)
                filteredDrawable = BitmapDrawable(resources, croppedBitmap)
            }
            newCreated = false
        }
        // create new
        if (originTheme == null) {
            val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
            backgroundStates.apply {
                croppedImageFile = c
                srcImageFile = s
            }
            // Use dark keys by default
            theme = ThemePreset.TransparentDark.deriveCustomBackground(n, c.path, s.path)
        }
        previewUi = KeyboardPreviewUi(this, theme)
        if (theme.backgroundImage == null) {
            brightnessLabel.visibility = View.GONE
            cropLabel.visibility = View.GONE
            variantLabel.visibility = View.GONE
            variantSwitch.visibility = View.GONE
            brightnessSeekBar.visibility = View.GONE
        }
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(ui) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            ui.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            toolbar.topPadding = statusBars.top
            scrollView.bottomPadding = navBars.bottom
            windowInsets
        }
        // show Activity label on toolbar
        setSupportActionBar(toolbar)
        // show back button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        setContentView(ui)
        applyThemePreview(theme)
        whenHasBackground { background ->
            brightnessSeekBar.progress = background.brightness
            variantSwitch.isChecked = !theme.isDark
            launcher = registerForActivityResult(CropContract()) {
                when (it) {
                    CropResult.Fail -> {
                        if (newCreated) {
                            cancel()
                        }
                    }
                    is CropResult.Success -> {
                        if (newCreated) {
                            srcImageExtension = MimeTypeMap.getSingleton()
                                .getExtensionFromMimeType(contentResolver.getType(it.srcUri))
                            srcImageBuffer =
                                contentResolver.openInputStream(it.srcUri)!!
                                    .use { x -> x.readBytes() }
                        }
                        cropRect = it.rect
                        cropRotation = it.rotation
                        croppedBitmap = it.bitmap
                        filteredDrawable = BitmapDrawable(resources, croppedBitmap)
                        updateState()
                    }
                }
            }
            cropLabel.setOnClickListener {
                launchCrop(previewUi.intrinsicWidth, previewUi.intrinsicHeight)
            }
            variantLabel.setOnClickListener {
                variantSwitch.isChecked = !variantSwitch.isChecked
            }
            // attach OnCheckedChangeListener after calling setChecked (isChecked in kotlin)
            variantSwitch.setOnCheckedChangeListener { _, isChecked ->
                setKeyVariant(background, darkKeys = isChecked)
            }
            brightnessSeekBar.setOnSeekBarChangeListener(object :
                SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}

                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) updateState()
                }
            })
        }

        if (newCreated) {
            cropLabel.visibility = View.GONE
            whenHasBackground {
                previewUi.onSizeMeasured = { w, h ->
                    launchCrop(w, h)
                }
            }
        } else {
            whenHasBackground {
                updateState()
            }
        }

        onBackPressedDispatcher.addCallback {
            cancel()
        }
    }

    private fun BackgroundStates.launchCrop(w: Int, h: Int) {
        if (newCreated) {
            launcher.launch(CropOption.New(w, h))
        } else {
            launcher.launch(
                CropOption.Edit(
                    width = w,
                    height = h,
                    Uri.fromFile(srcImageFile),
                    initialRect = cropRect,
                    initialRotation = cropRotation
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun BackgroundStates.updateState() {
        val progress = brightnessSeekBar.progress
        brightnessValue.text = "$progress%"
        filteredDrawable.colorFilter = DarkenColorFilter(100 - progress)
        previewUi.setBackground(filteredDrawable)
    }

    private fun cancel() {
        setResult(
            RESULT_CANCELED,
            Intent().apply { putExtra(RESULT, null as BackgroundResult?) }
        )
        finish()
    }

    private fun done() {
        lifecycleScope.withLoadingDialog(this) {
            whenHasBackground {
                withContext(Dispatchers.IO) {
                    croppedImageFile.delete()
                    croppedImageFile.outputStream().use {
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (newCreated) {
                        if (srcImageExtension != null) {
                            srcImageFile = File("${srcImageFile.absolutePath}.$srcImageExtension")
                            theme = theme.copy(
                                backgroundImage = it.copy(
                                    srcFilePath = srcImageFile.absolutePath
                                )
                            )
                        }
                        srcImageFile.writeBytes(srcImageBuffer!!)
                    }
                }
            }
            setResult(
                RESULT_OK,
                Intent().apply {
                    var newTheme = theme
                    whenHasBackground {
                        newTheme = theme.copy(
                            backgroundImage = it.copy(
                                brightness = brightnessSeekBar.progress,
                                cropRect = cropRect,
                                cropRotation = cropRotation
                            )
                        )
                    }
                    putExtra(
                        RESULT,
                        if (newCreated)
                            BackgroundResult.Created(newTheme)
                        else
                            BackgroundResult.Updated(newTheme)
                    )
                })
            finish()
        }
    }

    private fun delete() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(RESULT, BackgroundResult.Deleted(theme.name))
            }
        )
        finish()
    }

    private fun promptDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, theme.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delete()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!newCreated) {
            val iconTint = color(R.color.red_400)
            menu.item(R.string.save, R.drawable.ic_baseline_delete_24, iconTint, true) {
                promptDelete()
            }
        }
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        menu.item(R.string.save, R.drawable.ic_baseline_check_24, iconTint, true) {
            done()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            cancel()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val INLINE_COLOR_EDITOR_TAG = "inline_color_editor"
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"
    }
}
