/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.picker

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.FcitxKeyMapping
import org.fxboomk.fcitx5.android.core.KeySym
import org.fxboomk.fcitx5.android.data.RecentlyUsed
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.AutoScaleTextView
import org.fxboomk.fcitx5.android.input.config.KeyIconIds
import org.fxboomk.fcitx5.android.input.config.KeyIconThemeStore
import org.fxboomk.fcitx5.android.input.font.FontProviders
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fxboomk.fcitx5.android.input.keyboard.KeyActionListener
import org.fxboomk.fcitx5.android.input.keyboard.KeyDef
import org.fxboomk.fcitx5.android.input.keyboard.ImageKeyView
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyView
import org.fxboomk.fcitx5.android.input.keyboard.TextKeyboard
import org.fxboomk.fcitx5.android.input.keyboard.borderedKeyBackgroundDrawable
import org.fxboomk.fcitx5.android.input.keyboard.insetRadiusDrawable
import org.fxboomk.fcitx5.android.input.keyboard.shadowedKeyBackgroundDrawable
import org.fxboomk.fcitx5.android.input.popup.PopupAction
import org.fxboomk.fcitx5.android.input.popup.PopupActionListener
import org.fxboomk.fcitx5.android.utils.alpha
import org.fxboomk.fcitx5.android.utils.rippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui

/**
 * 符号 / 表情 / 颜文字面板，**面板独占整个键盘区域**：
 * 面板按键是「点一下即时上屏」，不需要空格/回车那一整行，那行只会白白吃掉约 1/4 高度。
 *
 * 分组栏摆在哪由 [SymbolPanelStyle] 决定，两套风格共用同一份分组数据与交互：
 *
 * ```
 * [SymbolPanelStyle.Sidebar]（默认，新版）
 * ┌────────┬──────────────────────────────┐
 * │ 分组栏  │  符号网格（可纵向滚动）        │
 * │ (纵向)  │  每行 N 个                    │
 * │  最近   │                              │
 * │  ...   │                              │
 * ├────────┤                              │
 * │  ⌨  ⌫  │                              │
 * └────────┴──────────────────────────────┘
 *
 * [SymbolPanelStyle.TopBar]（旧版）
 * ┌──────────────────────────────────────────┐
 * │ ⌨  │ 最近 标点 数学 …（可横向拖动）  │  ⌫   │
 * ├────┴──────────────────────────────┴──────┤
 * │  符号网格（可纵向滚动），每行 N 个           │
 * └──────────────────────────────────────────┘
 * ```
 *
 * 键盘级操作只有这两枚小键，与 Foxy 的 `ly.j` / `ly.k` 一一对应：
 *
 * - **⌨** 返回文字键盘（与 Foxy `gu.java` case 8 的 `k5("default")` 同义）；
 * - **⌫** 退格删除，长按连续删除（与 Foxy case 15 的 `wz(j30.BACKSPACE, 0)` 同义）。
 *
 * **面板之间的切换不在这里**，而是和 Foxy 一样交由**布局按键**配置：
 * 在布局编辑器里给 LayoutSwitchKey 选 Symbol / Emoji / Kaomoji 目标
 * （见 `PickerWindow.Key.layerTargetNames`）。
 *
 * 「返回键盘」在工具栏最左侧还有第二个入口（见 `IdleUi.setBackToKeyboardMode`）：
 * 主键盘工具栏原样保留、只换最左图标。
 *
 * 与 Foxy 的差异（均为有意为之）：
 * - Foxy 的 `ly.c()` 一次性把整份 catalog（符号最多 4773 条）全部 `new` 成视图；
 *   这里改用 [RecyclerView] 复用视图，避免上千个子 View 常驻内存。
 * - 网格项复用本项目的 [TextKeyView]，因此自动继承主题圆角、边框、按压高亮与字体设置。
 * - 保留长按弹出（emoji 肤色）能力，Foxy 面板没有该交互。
 *
 * 列数由 [columns] 决定：符号/表情为 6 列，颜文字为 1 列（条目很长，见 Foxy 的
 * `multiLine` 语义）。
 *
 * 风格是**纯呈现层**：切换风格只重建视图结构（[applyStyle]），符号数据源、最近使用、
 * 过滤策略、上屏与长按行为全部不变。设置改完后下次面板显示即生效，不需要重启输入法。
 */
@SuppressLint("ViewConstructor")
class SymbolPanelUi(
    override val ctx: Context,
    private val theme: Theme,
    private val catalogType: SymbolCatalogType,
    private val columns: Int,
    private val textSize: Float,
    private val policy: PickerPolicy,
    private val keyActionListener: KeyActionListener,
    private val popupActionListener: PopupActionListener
) : Ui {

    private companion object {
        /** [SymbolPanelStyle.Sidebar]：左栏宽度与行高，对齐 Foxy 的 `b(84)` 与 `b(40)`。 */
        const val SIDEBAR_WIDTH_DP = 84
        const val SIDEBAR_ITEM_HEIGHT_DP = 40
        const val UTILITY_ROW_HEIGHT_DP = 40

        /** [SymbolPanelStyle.TopBar]：顶栏高度、两端功能键宽度、分组标签最小宽度与内边距。 */
        const val TOP_BAR_HEIGHT_DP = 40
        const val UTILITY_KEY_WIDTH_DP = 56
        const val TAB_MIN_WIDTH_DP = 48
        const val TAB_PADDING_H_DP = 12

        const val GRID_ITEM_HEIGHT_DP = 40

        /** 每个面板保留最近十行记录，颜文字面板也按单列计算。 */
        const val RECENT_ROWS = 10

        /** 分组标签文字大小。 */
        const val TAB_TEXT_SIZE = 13f

        /** 功能键文字大小，对齐 Foxy 的 20sp（左栏两枚键均分 84dp，每枚 42dp）。 */
        const val UTILITY_KEY_TEXT_SIZE = 20f

        /** 与 Foxy 的 `ly.j` / `ly.k` 相同的字形。 */
        const val LABEL_BACK_TO_KEYBOARD = "⌨"
        const val LABEL_BACKSPACE = "⌫"

        val DigitRange = '0'.code..'9'.code
        val FullWidthDigitRange = '０'.code..'９'.code
    }

    private val stylePref = AppPrefs.getInstance().symbols.symbolPanelStyle
    private val onKeyIconThemeChanged: () -> Unit = { applyStyle() }
    private var listeningForKeyIconTheme = false

    /** 当前已生效的风格；[applyStyle] 里从偏好重读。 */
    private var style: SymbolPanelStyle = stylePref.getValue()

    /**
     * 当前生效的 catalog。**不缓存在本地**：用户可能在设置里切换自备 catalog，
     * 故每次 [rebuild] 都重新取（[SymbolCatalogs] 内部按「种类 + 所选文件」缓存）。
     */
    private var catalog: SymbolCatalog = SymbolCatalog(emptyList(), multiLine = false)

    private val recentlyUsed = RecentlyUsed(catalogType.recentKey, columns * RECENT_ROWS)

    private var groups: List<SymbolGroup> = emptyList()
    private var selectedIndex = 0
    private var filteredGroups: List<SymbolGroup> = emptyList()
    private var filterKey: Any? = null
    private var fontGeneration = -1L
    private var appearanceConfiguration: Configuration? = null
    private var hasRecent = false

    private val gridItemHeight = ctx.dp(GRID_ITEM_HEIGHT_DP)

    private val keyAppearance = KeyDef.Appearance.Text(
        displayText = "",
        textSize = textSize,
        variant = KeyDef.Appearance.Variant.Normal,
        border = KeyDef.Appearance.Border.Default
    )

    private val gridAdapter = object : RecyclerView.Adapter<GridHolder>() {

        var items: List<String> = emptyList()
            set(value) {
                if (field == value) return
                field = value
                @Suppress("NotifyDataSetChanged")
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridHolder {
            val keyView = TextKeyView(ctx, theme, keyAppearance)
            keyView.id = View.generateViewId()
            keyView.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, gridItemHeight
            )
            return GridHolder(keyView)
        }

        override fun onBindViewHolder(holder: GridHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class GridHolder(private val keyView: TextKeyView) :
        RecyclerView.ViewHolder(keyView) {

        fun bind(raw: String) {
            val text = policy.transform(raw)
            keyView.mainText.text = text
            keyView.mainText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                FontProviders.getFontSize("key_main_font", textSize)
            )
            keyView.mainText.setFontTypeFace("key_main_font")
            // 面板靠滚动翻页，不用滑动切页手势
            keyView.swipeEnabled = false
            keyView.onGestureListener = null
            keyView.setOnClickListener { commit(raw) }
            keyView.setOnLongClickListener {
                val popup = policy.popup(raw)
                if (popup == null) {
                    false
                } else {
                    // 弹出键盘需要按键在屏幕上的实际位置
                    keyView.updateBounds()
                    popupActionListener.onPopupAction(
                        PopupAction.ShowKeyboardAction(keyView.id, popup, keyView.bounds)
                    )
                    true
                }
            }
        }
    }

    private val sidebarAdapter = object : RecyclerView.Adapter<SidebarHolder>() {

        override fun getItemCount() = groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SidebarHolder {
            val label = AutoScaleTextView(ctx).apply {
                gravity = Gravity.CENTER
                scaleMode = AutoScaleTextView.Mode.Proportional
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE)
                layoutParams = if (style == SymbolPanelStyle.Sidebar) {
                    // 纵向分组栏：整列等宽、固定行高。
                    // 文字内边距要压过按键边距，否则文字会压在高亮边框上。
                    val h = ctx.dp(TAB_PADDING_H_DP)
                    val v = ctx.dp(2)
                    setPadding(h, v, h, v)
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(SIDEBAR_ITEM_HEIGHT_DP)
                    )
                } else {
                    // 横向分组栏：宽度随文字、最小 48dp 便于点按，撑满顶栏高度
                    maxLines = 1
                    minimumWidth = ctx.dp(TAB_MIN_WIDTH_DP)
                    val h = ctx.dp(TAB_PADDING_H_DP)
                    setPadding(h, 0, h, 0)
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                isClickable = true
                isFocusable = true
                contentDescription = null
            }
            return SidebarHolder(label)
        }

        override fun onBindViewHolder(holder: SidebarHolder, position: Int) {
            holder.bind(position)
        }
    }

    private inner class SidebarHolder(private val label: AutoScaleTextView) :
        RecyclerView.ViewHolder(label) {

        fun bind(position: Int) {
            val active = position == selectedIndex
            label.text = groups[position].name
            label.setTextColor(
                if (active) theme.accentKeyTextColor else theme.keyTextColor.alpha(0.65f)
            )
            label.background = tabFill(active)
            label.setOnClickListener { selectGroup(position) }
        }
    }

    private lateinit var sidebarRecycler: RecyclerView
    private lateinit var gridRecycler: RecyclerView

    /** 与风格无关的外壳：切换风格时只替换它的子视图。 */
    private val rootContainer = FrameLayout(ctx).apply {
        setBackgroundColor(theme.keyboardColor)
    }

    override val root: View get() = rootContainer

    /**
     * 分组标签高亮的圆角（px），**等同于按键圆角**（`ThemePrefs.keyRadius`）。
     *
     * 符号面板里 ⌨ / ⌫ 两枚功能键复用 [TextKeyView]，圆角、按键边距、边框/阴影都来自
     * 按键体系；分组标签的选中高亮用同一套几何参数，看起来才和它们是同一种控件，
     * 也自然继承了「不贴边」——按键块本身就从格子里缩进了按键边距。
     */
    internal val tabCornerRadiusPx: Float
        get() = ctx.dp(ThemeManager.prefs.keyRadius.getValue().toFloat())

    /** 和按键一样的边距（px）：横向 [KeyView] 的按键间距，纵向同理，横屏取横屏那组。 */
    internal fun tabKeyMargins(): Pair<Int, Int> {
        val prefs = ThemeManager.prefs
        val landscape =
            ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val h = if (landscape) prefs.keyHorizontalMarginLandscape.getValue()
        else prefs.keyHorizontalMargin.getValue()
        val v = if (landscape) prefs.keyVerticalMarginLandscape.getValue()
        else prefs.keyVerticalMargin.getValue()
        return ctx.dp(h) to ctx.dp(v)
    }

    /**
     * 分组标签在「按键几何」下的背景下层（选中底色或按压高亮）。
     *
     * 直接复用按键的 drawable 构造器，和按键 `KeyView.applyStandardBackground` 同一条路径：
     * 按住键边框时是带描边的块，否则是带 1dp 底影的块；两种情况都从格子四边缩进按键边距，
     * 因此高亮范围与面板里的 ⌨ / ⌫ 完全一致，不会贴着格子边缘。
     */
    internal fun tabKeyBackground(@ColorInt bkgColor: Int): Drawable {
        val radius = tabCornerRadiusPx
        val (hMargin, vMargin) = tabKeyMargins()
        val shadowWidth = ctx.dp(1)
        return if (ThemeManager.prefs.keyBorderStroke.getValue()) {
            borderedKeyBackgroundDrawable(
                bkgColor, theme.keyShadowColor, radius, shadowWidth, hMargin, vMargin
            )
        } else {
            shadowedKeyBackgroundDrawable(
                bkgColor, theme.keyShadowColor, radius, shadowWidth, hMargin, vMargin
            )
        }
    }

    /** 按压/涟漪的形状：与按键高亮 mask 一致（同样缩进按键边距的圆角块）。 */
    private fun tabHighlightMask(@ColorInt color: Int): Drawable {
        val (hMargin, vMargin) = tabKeyMargins()
        return insetRadiusDrawable(hMargin, vMargin, tabCornerRadiusPx, color)
    }

    /**
     * 分组标签的背景：选中态是按键样式的底色块，未选中态只保留按压 / 涟漪反馈。
     *
     * 两种状态都套一层 [NoBackgroundPaddingDrawable]——按键几何的底色块是从格子四边缩进的
     * 层叠 drawable，它会把内缩量报成自己的 padding，而 `View` 会拿背景的 padding 顶掉自己
     * 的内边距，于是标签在「选中 / 未选中」之间切换时格子宽度会变（选中后变窄、文字被挤出
     * 高亮），包一层后格子尺寸只由 [SidebarHolder] 创建时设的内边距决定，切换状态不再抖动。
     */
    internal fun tabFill(active: Boolean): Drawable {
        val fill = when {
            active -> tabKeyBackground(theme.genericActiveBackgroundColor)
            // 涟漪用同形状的 mask 裁切，否则按下时涟漪会溢出到按键边距以外
            ThemeManager.prefs.keyRippleEffect.getValue() ->
                rippleDrawable(theme.keyPressHighlightColor, tabHighlightMask(Color.WHITE))

            else -> StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    tabHighlightMask(theme.keyPressHighlightColor)
                )
            }
        }
        return NoBackgroundPaddingDrawable(fill)
    }

    init {
        applyStyle()
        rebuild()
        attach()
    }

    /** Register theme updates while this panel is attached; repeat calls are harmless. */
    fun attach() {
        if (listeningForKeyIconTheme) return
        listeningForKeyIconTheme = true
        KeyIconThemeStore.addOnChangedListener(onKeyIconThemeChanged)
    }

    fun detach() {
        if (!listeningForKeyIconTheme) return
        listeningForKeyIconTheme = false
        KeyIconThemeStore.removeOnChangedListener(onKeyIconThemeChanged)
    }

    // ---- 视图结构（按风格） ----

    private fun buildSidebarRecycler() = RecyclerView(ctx).apply {
        id = View.generateViewId()
        layoutManager = if (style == SymbolPanelStyle.TopBar) {
            // 横向排列，分组较多时可左右拖动
            LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        } else {
            LinearLayoutManager(ctx)
        }
        adapter = sidebarAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun buildGridRecycler() = RecyclerView(ctx).apply {
        id = View.generateViewId()
        layoutManager = GridLayoutManager(ctx, columns)
        adapter = gridAdapter
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    /**
     * 构造分组栏底部（或顶栏两端）的一个小功能键。
     *
     * 复用 [TextKeyView]，因此自动继承主题的按键背景、圆角、边框、按压高亮与字体设置。
     */
    private fun createUtilityKey(
        label: String,
        descriptionRes: Int,
        action: KeyAction,
        repeat: Boolean = false
    ): TextKeyView {
        val appearance = KeyDef.Appearance.Text(
            displayText = label,
            textSize = UTILITY_KEY_TEXT_SIZE,
            variant = KeyDef.Appearance.Variant.Alternative,
            border = KeyDef.Appearance.Border.Default
        )
        return TextKeyView(ctx, theme, appearance).apply {
            id = View.generateViewId()
            mainText.text = label
            mainText.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                FontProviders.getFontSize("key_main_font", UTILITY_KEY_TEXT_SIZE)
            )
            mainText.setFontTypeFace("key_main_font")
            contentDescription = ctx.getString(descriptionRes)
            // 功能键不参与滑动切页/长按弹窗等键盘手势
            swipeEnabled = false
            onGestureListener = null
            val fire = { keyActionListener.onKeyAction(action, KeyActionListener.Source.Keyboard) }
            setOnClickListener { fire() }
            if (repeat) {
                // 退格长按连续删除，与键盘上的退格键一致
                repeatEnabled = true
                onRepeatListener = { fire() }
            }
        }
    }

    // ⌨ 返回键盘 —— 与 Foxy 的 ly.j 对应
    private fun backToKeyboardKey() = createUtilityKey(
        label = LABEL_BACK_TO_KEYBOARD,
        descriptionRes = R.string.back_to_keyboard,
        action = KeyAction.LayoutSwitchAction(TextKeyboard.Name)
    )

    // ⌫ 删除，长按连续删除 —— 与 Foxy 的 ly.k 对应
    private fun backspaceKey(): ImageKeyView {
        val appearance = KeyDef.Appearance.Image(
            src = KeyIconThemeStore.resolveDrawable(
                ctx,
                KeyIconIds.BACKSPACE,
                R.drawable.ic_baseline_backspace_24
            ),
            variant = KeyDef.Appearance.Variant.Alternative,
            border = KeyDef.Appearance.Border.Default,
            viewId = View.generateViewId()
        )
        return ImageKeyView(ctx, theme, appearance).apply {
            contentDescription = ctx.getString(R.string.backspace)
            swipeEnabled = false
            onGestureListener = null
            val action = SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))
            val fire = { keyActionListener.onKeyAction(action, KeyActionListener.Source.Keyboard) }
            setOnClickListener { fire() }
            repeatEnabled = true
            onRepeatListener = { fire() }
        }
    }

    /** 左：分组栏（纵向）+ 底部两枚功能键（均分 84dp）；右：符号网格。 */
    private fun buildSidebarLayout(): View {
        val utilityRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val gap = ctx.dp(1)
        listOf(backToKeyboardKey(), backspaceKey()).forEachIndexed { index, key ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) lp.marginStart = gap
            utilityRow.addView(key, lp)
        }

        val sidebarColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                sidebarRecycler,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(
                utilityRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(UTILITY_ROW_HEIGHT_DP)
                )
            )
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                sidebarColumn,
                LinearLayout.LayoutParams(
                    ctx.dp(SIDEBAR_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                gridRecycler,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
    }

    /** 上：顶栏 = ⌨ · 分组栏（横向，可拖动） · ⌫；下：符号网格。 */
    private fun buildTopBarLayout(): View {
        val keyLp = {
            LinearLayout.LayoutParams(
                ctx.dp(UTILITY_KEY_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(backToKeyboardKey(), keyLp())
            addView(
                sidebarRecycler,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            addView(backspaceKey(), keyLp())
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                topBar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(TOP_BAR_HEIGHT_DP)
                )
            )
            addView(
                gridRecycler,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
    }

    /**
     * 按当前偏好重建视图结构。
     *
     * 视图对象整体换新（而不是只改布局参数）：分组标签在两种风格下的外层布局参数
     * 差别太大（`MATCH_PARENT × 40dp` 对 `WRAP_CONTENT × MATCH_PARENT`），
     * 复用旧 holder 反而要逐项修补，且回收池里的陈旧布局会漏改。
     */
    private fun applyStyle() {
        style = stylePref.getValue()
        rootContainer.removeAllViews()
        sidebarRecycler = buildSidebarRecycler()
        gridRecycler = buildGridRecycler()
        val content = when (style) {
            SymbolPanelStyle.Sidebar -> buildSidebarLayout()
            SymbolPanelStyle.TopBar -> buildTopBarLayout()
        }
        rootContainer.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 风格切换后分组列表没变，但网格内容要重新挂到新建的 recycler 上
        if (groups.isNotEmpty()) applySelectedGroup()
    }

    /**
     * 重新构建分组列表（语言、过滤偏好、所选 catalog 或最近使用变化后调用）。
     */
    fun rebuild() {
        val nextCatalog = SymbolCatalogs.get(catalogType)
        val nextKey = policy.invalidateKey()
        val nextFont = FontProviders.fontGeneration
        val configuration = ctx.resources.configuration
        val appearanceChanged = nextFont != fontGeneration || appearanceConfiguration != configuration
        val filterChanged = catalog !== nextCatalog || filterKey != nextKey || appearanceChanged
        val oldCatalogIndex = selectedIndex - if (hasRecent) 1 else 0
        if (filterChanged) {
            catalog = nextCatalog
            filterKey = nextKey
            filteredGroups = catalog.groups.mapNotNull { group ->
                val filtered = group.symbols.filter(policy::filter)
                if (filtered.isEmpty()) null else SymbolGroup(group.name, filtered)
            }
        }
        val recent = recentlyUsed.items.filter(policy::filter)
        val nextGroups = if (recent.isEmpty()) filteredGroups else
            listOf(SymbolGroup(ctx.getString(R.string.symbol_panel_recent), recent)) + filteredGroups
        val nextHasRecent = recent.isNotEmpty()
        if (groups != nextGroups) {
            val wasEmpty = groups.isEmpty()
            groups = nextGroups
            // Adding/removing the recent tab must not select a different catalog group.
            selectedIndex = (if (wasEmpty) 0 else oldCatalogIndex + if (nextHasRecent) 1 else 0)
                .coerceIn(0, (groups.size - 1).coerceAtLeast(0))
            hasRecent = nextHasRecent
            @Suppress("NotifyDataSetChanged")
            sidebarAdapter.notifyDataSetChanged()
            applySelectedGroup()
        }
        if (filterChanged) {
            @Suppress("NotifyDataSetChanged")
            gridAdapter.notifyDataSetChanged() // also applies skin-tone and font-only changes
        }
        fontGeneration = nextFont
        appearanceConfiguration = Configuration(configuration)
    }

    /** Refresh recents each time; filter the catalog only when its inputs change. */
    fun refresh() {
        if (style != stylePref.getValue()) applyStyle()
        rebuild()
    }

    private fun selectGroup(index: Int) {
        selectedIndex = index
        @Suppress("NotifyDataSetChanged")
        sidebarAdapter.notifyDataSetChanged()
        // 横向分组栏：点到边缘半露的标签时，把它完整滑进视野。
        // 纵向分组栏不需要——标签是整行等宽的，点谁谁本来就完整可见。
        if (style == SymbolPanelStyle.TopBar) {
            sidebarRecycler.smoothScrollToPosition(index)
        }
        applySelectedGroup()
    }

    private fun applySelectedGroup() {
        val group = groups.getOrNull(selectedIndex)
        gridAdapter.items = group?.symbols ?: emptyList()
        if (group != null) {
            gridRecycler.scrollToPosition(0)
        }
    }

    fun scrollToTop() {
        gridRecycler.scrollToPosition(0)
        sidebarRecycler.scrollToPosition(selectedIndex.coerceAtLeast(0))
    }

    private fun commit(raw: String) {
        val text = policy.transform(raw)
        keyActionListener.onKeyAction(KeyAction.CommitAction(text), KeyActionListener.Source.Keyboard)
        insertRecent(text)
    }

    private fun insertRecent(text: String) {
        // 单个数字（半角/全角）不进"最近使用"，否则面板一打开就被数字占满
        if (text.length == 1) {
            val code = text[0].code
            if (code in DigitRange || code in FullWidthDigitRange) return
        }
        recentlyUsed.insert(text)
    }

    /**
     * 屏蔽背景自带 padding 的包装层，见 [tabFill]。
     *
     * 层叠 drawable（[shadowedKeyBackgroundDrawable] / [borderedKeyBackgroundDrawable] /
     * [RippleDrawable] / [InsetDrawable]）都会把内缩量报成 padding，`View.setBackground`
     * 会采用它；恒返回「没有 padding」后，标签的内边距就只由代码里 `setPadding` 决定，
     * 选中 / 未选中切换不会再改变格子宽度。
     */
    internal class NoBackgroundPaddingDrawable(inner: Drawable) : DrawableWrapper(inner) {
        override fun getPadding(padding: Rect): Boolean = false
    }
}
