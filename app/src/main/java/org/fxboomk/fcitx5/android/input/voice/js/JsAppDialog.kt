/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.voice.js

import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.widget.*
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.*
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.utils.toast

/** Attached window; typing stays in the local editor while host APIs retain the app connection. */
internal class JsAppDialog(
    private val service: FcitxInputMethodService,
    private val event: (String, JsonObject) -> Unit,
    private val closed: () -> Unit,
    private val keyboardView: () -> View? = { service.inputView?.keyboardView },
) : Dialog(service) {
    private val theme = ThemeManager.activeTheme
    // Widget geometry follows Foxy's Material form; all colors come from the keyboard theme.
    private val uiContext = ContextThemeWrapper(service, if (theme.isDark)
        com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar
        else com.google.android.material.R.style.Theme_Material3_Light_NoActionBar)
    private val surfaceColor = theme.popupBackgroundColor or Color.BLACK
    private val textColor = theme.popupTextColor
    private val mutedColor = ColorUtils.setAlphaComponent(textColor, (Color.alpha(textColor) * 0.68f).toInt())
    private val outlineColor = ColorUtils.setAlphaComponent(textColor, (Color.alpha(textColor) * 0.24f).toInt())
    private val accentColor = theme.accentKeyBackgroundColor
    private val controls = linkedMapOf<String, View>()
    private val editors = linkedMapOf<String, EditText>()
    private val selections = linkedMapOf<String, String>()
    private val selectors = linkedMapOf<String, Pair<MaterialAutoCompleteTextView, List<Pair<String, String>>>>()
    private val enabled = mutableMapOf<String, Boolean>()
    private val content = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; addView(content) }
    private val scroll = BoundedScrollView(uiContext).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = false
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private val status = label("", size = 11f).apply {
        setTextColor(if (theme.isDark) 0xffffb4ab.toInt() else 0xffb3261e.toInt())
        maxLines = 2
        visibility = View.GONE
        setPadding(dp(2), dp(4), dp(2), 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(uiContext).apply {
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(accentColor)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val root = JsAppPanelLayout(uiContext, ::movePanel).apply {
        background = GradientDrawable().apply {
            setColor(surfaceColor)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), outlineColor)
        }
        addView(LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(scroll, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -2))
        addView(progress, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); marginEnd = dp(10)
        })
    }
    private var prompt: CompletableDeferred<Boolean>? = null
    private var busy = false
    private var dismissed = false
    private var maxWidthDp = 340
    private var maxHeightDp = 360
    private var moved = false
    private var panelX = 0
    private var panelY = 0
    private var observedKeyboard: View? = null
    private val keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        applyBounds()
        // A pending keyboard layout can become ready without changing its screen bounds.
        root.invalidate()
    }
    private val firstDrawListener = ViewTreeObserver.OnPreDrawListener { prepareDraw() }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(false)
        setContentView(root)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setWindowAnimations(0)
        setPanelVisible(false)
        root.viewTreeObserver.addOnPreDrawListener(firstDrawListener)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyBounds()
        }
        applyBounds()
    }

    override fun show() {
        // The shared IME dialog host adds dimming; this panel must leave the app and keyboard usable.
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            setDimAmount(0f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        observedKeyboard = keyboardView()
        observedKeyboard?.viewTreeObserver?.addOnGlobalLayoutListener(keyboardLayoutListener)
        // Attach with measured bounds, rather than correcting an already visible window.
        applyBounds()
        super.show()
    }

    override fun dismiss() {
        if (!dismissed) {
            dismissed = true
            observedKeyboard?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(keyboardLayoutListener)
            observedKeyboard = null
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(firstDrawListener)
            selectors.values.forEach { it.first.dismissDropDown() }
            prompt?.complete(false)
            closed()
        }
        super.dismiss()
    }

    fun values() = buildJsonObject {
        editors.forEach { (id, view) -> put(id, view.text.toString()) }
        selections.forEach { (id, value) -> put(id, value) }
    }

    fun setBusy(value: Boolean) {
        busy = value
        progress.visibility = if (value && prompt == null) View.VISIBLE else View.GONE
        controls.forEach { (id, view) ->
            val active = enabled[id] != false && (!value || id in setOf("close", "cancel"))
            view.isEnabled = active
            view.alpha = if (active) 1f else 0.5f
        }
        if (value) {
            blockTyping()
            selectors.values.forEach { it.first.dismissDropDown() }
        } else focusEditor()
    }

    fun showError(message: String) {
        if (dismissed) return
        // A failed initial render has no close control; release the editor instead of leaving a bare error panel.
        if (message.isNotBlank() && content.childCount == 0 && prompt == null) {
            service.toast(message.take(500))
            dismiss()
            return
        }
        status.text = message.take(500)
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        applyBounds()
    }

    fun setValue(id: String, value: String) {
        editors[id]?.let { it.setText(value.take(65536)); it.setSelection(it.text.length) }
        selectors[id]?.let { (view, options) ->
            options.firstOrNull { it.second == value }?.let { (label, selected) ->
                selections[id] = selected
                view.setText(label, false)
            }
        }
    }

    fun setEnabled(id: String, value: Boolean) {
        enabled[id] = value
        setBusy(busy)
    }

    suspend fun confirm(title: String, message: String, allow: String, deny: String): Boolean {
        val reply = CompletableDeferred<Boolean>()
        prompt = reply
        content.visibility = View.GONE
        progress.visibility = View.GONE
        blockTyping()
        val panel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, bold = true, size = 13f, draggable = true))
            addView(label(message, size = 12f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(LinearLayout(uiContext).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(button(deny) { reply.complete(false) })
                addView(button(allow, filled = true) { reply.complete(true) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        body.addView(panel)
        applyBounds()
        try { return reply.await() } finally {
            body.removeView(panel)
            content.visibility = View.VISIBLE
            prompt = null
            if (!dismissed) { applyBounds(); setBusy(busy) }
        }
    }

    fun render(spec: JsonObject) {
        selectors.values.forEach { it.first.dismissDropDown() }
        controls.clear(); editors.clear(); selections.clear(); selectors.clear(); enabled.clear()
        blockTyping()
        content.removeAllViews()
        status.text = ""
        status.visibility = View.GONE
        var nodes = 0
        var hasTitle = false
        fun node(obj: JsonObject, depth: Int): View {
            require(depth <= 12 && ++nodes <= 128) { "Dialog layout is too large" }
            val id = obj.text("id")
            val view: View = when (obj.text("type")) {
                "column", "row", "header", "actions" -> LinearLayout(uiContext).apply {
                    orientation = if (obj.text("type") == "column") LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or when (obj.text("align")) {
                        "end" -> Gravity.END; "center" -> Gravity.CENTER_HORIZONTAL
                        else -> if (obj.text("type") == "actions") Gravity.END else Gravity.START
                    }
                    val gap = dp((obj["gap"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 32) ?: 6)
                    (obj["children"] as? JsonArray).orEmpty().forEachIndexed { index, child ->
                        val c = child.jsonObject
                        val weight = (c["weight"] as? JsonPrimitive)?.floatOrNull?.takeIf { it.isFinite() }?.coerceIn(0f, 20f) ?: 0f
                        // A field's height belongs to its input box, independently of its label.
                        val params = if (orientation == LinearLayout.HORIZONTAL)
                            LinearLayout.LayoutParams(if (weight > 0) 0 else -2, -2, weight)
                        else LinearLayout.LayoutParams(-1, if (weight > 0) 0 else -2, weight)
                        if (index > 0) {
                            if (orientation == LinearLayout.HORIZONTAL) params.marginStart = gap else params.topMargin = gap
                        }
                        addView(node(c, depth + 1), params)
                    }
                }
                "text" -> label(obj.text("text").take(400),
                    (obj["bold"] as? JsonPrimitive)?.booleanOrNull == true,
                    (obj["size"] as? JsonPrimitive)?.floatOrNull?.coerceIn(10f, 24f) ?: 14f,
                    (obj["muted"] as? JsonPrimitive)?.booleanOrNull == true, draggable = !hasTitle).also { hasTitle = true }
                "input" -> {
                    val edit = EditText(uiContext).apply {
                        setTextColor(textColor); setHintTextColor(mutedColor)
                        textSize = 14f
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = null
                        minHeight = 0; minimumHeight = 0
                        val multiline = (obj["multiline"] as? JsonPrimitive)?.booleanOrNull == true
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                            if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                        setSingleLine(!multiline)
                        gravity = if (multiline) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
                        showSoftInputOnFocus = false
                        filters = arrayOf(InputFilter.LengthFilter(65536))
                        setText(obj.text("value").take(65536)); hint = obj.text("placeholder")
                        setSelection(text.length)
                        val readOnly = (obj["readOnly"] as? JsonPrimitive)?.booleanOrNull == true
                        if (readOnly) {
                            keyListener = null
                            setTextIsSelectable(true)
                        }
                        setOnFocusChangeListener { _, focused ->
                            if (focused && !dismissed) {
                                if (readOnly) blockTyping() else bindEditor(this)
                            }
                        }
                        // Returning false leaves click and accessibility handling to EditText.
                        @SuppressLint("ClickableViewAccessibility")
                        val editorTouchListener = View.OnTouchListener { _, _ ->
                            if (isEnabled && !dismissed) {
                                if (readOnly) blockTyping() else bindEditor(this)
                            }
                            false
                        }
                        setOnTouchListener(editorTouchListener)
                    }
                    editors[id] = edit
                    field(obj, fieldBox(edit), defaultHeight = if ((obj["multiline"] as? JsonPrimitive)?.booleanOrNull == true) 64 else 40)
                }
                "select" -> {
                    val options = (obj["options"] as? JsonArray).orEmpty().take(128).map { option ->
                        if (option is JsonObject) option.text("label").take(100) to option.text("value")
                        else option.jsonPrimitive.content.take(100) to option.jsonPrimitive.content
                    }
                    val selected = options.firstOrNull { it.second == obj.text("value") } ?: options.firstOrNull()
                    selections[id] = selected?.second.orEmpty()
                    val input = EditorPreservingSelect(uiContext, ::panelArea) { focusEditor() }.apply {
                        textSize = 14f
                        setTextColor(textColor); setHintTextColor(mutedColor)
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        minHeight = 0; minimumHeight = 0
                        threshold = 0
                        inputType = InputType.TYPE_NULL
                        isCursorVisible = false
                        showSoftInputOnFocus = false
                        setAdapter(object : ArrayAdapter<String>(uiContext, android.R.layout.simple_dropdown_item_1line,
                            options.map { it.first }) {
                            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                                (super.getView(position, convertView, parent) as TextView).apply {
                                    setTextColor(textColor); textSize = 14f
                                    // Override the system template's fixed listPreferredItemHeight.
                                    layoutParams.height = dp(40)
                                    minHeight = 0; minimumHeight = 0
                                    setPadding(dp(12), dp(4), dp(12), dp(4))
                                }
                        })
                        setText(selected?.first.orEmpty(), false)
                        setOnClickListener { if (isEnabled) showDropDown() }
                        setOnItemClickListener { _, _, position, _ ->
                            if (!busy && isEnabled && position in options.indices) {
                                val (label, value) = options[position]
                                setText(label, false)
                                selections[id] = value
                                event(id, values())
                            }
                        }
                        setDropDownBackgroundDrawable(GradientDrawable().apply {
                            setColor(surfaceColor); cornerRadius = dp(8).toFloat(); setStroke(dp(1), outlineColor)
                        })
                    }
                    selectors[id] = input to options
                    val box = fieldBox(input).apply {
                        endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
                        setEndIconTintList(ColorStateList.valueOf(mutedColor))
                        endIconMinSize = dp(32)
                    }
                    field(obj, box, defaultHeight = 40)
                }
                "button" -> button(obj.text("label").take(80), filled = obj.text("style") == "filled") {
                    if (!busy || id in setOf("close", "cancel")) {
                        service.jsAppInputConnection?.finishComposingText()
                        event(id, values())
                    }
                }
                "spacer" -> View(uiContext).apply {
                    minimumHeight = dp((obj["height"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 320) ?: 0)
                }
                else -> error("Unsupported layout node")
            }
            if (id.isNotBlank()) {
                require(id !in controls) { "Duplicate control ID" }
                controls[id] = if (id in editors || id in selectors) {
                    // Enable/disable the whole Material box, including its dropdown icon.
                    (view as ViewGroup).getChildAt(view.childCount - 1)
                } else view
            } else require(obj.text("type") !in setOf("input", "select", "button")) { "Invalid control ID" }
            return view
        }
        val layout = spec["layout"] as? JsonObject ?: error("Dialog must declare a layout")
        content.addView(node(layout, 0), LinearLayout.LayoutParams(-1, -2))
        maxWidthDp = (spec["maxWidth"] as? JsonPrimitive)?.intOrNull?.coerceIn(200, 600) ?: 340
        maxHeightDp = (spec["maxHeight"] as? JsonPrimitive)?.intOrNull?.coerceIn(96, 600) ?: 360
        applyBounds()
        setBusy(busy)
    }

    private fun fieldBox(input: EditText) = TextInputLayout(uiContext).apply {
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = surfaceColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursorColor = ColorStateList.valueOf(accentColor)
        }
        isHintEnabled = false
        setBoxCornerRadii(dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat(), dp(4).toFloat())
        boxStrokeWidth = dp(1); boxStrokeWidthFocused = dp(1)
        setBoxStrokeColorStateList(ColorStateList(arrayOf(
            intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_hovered), intArrayOf()),
            intArrayOf(outlineColor, accentColor, mutedColor, outlineColor)))
        addView(input, LinearLayout.LayoutParams(-1, -1))
    }

    private fun field(spec: JsonObject, box: TextInputLayout, defaultHeight: Int): View {
        val height = dp((spec["height"] as? JsonPrimitive)?.intOrNull?.coerceIn(30, 320) ?: defaultHeight)
        val top = spec.text("labelPlacement") == "top"
        return LinearLayout(uiContext).apply {
            orientation = if (top) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (spec.text("label").isNotBlank()) addView(label(spec.text("label"), size = 11f, muted = true).apply {
                setPadding(dp(2), 0, dp(4), 0)
                gravity = if (top) Gravity.START else Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(if (top) -1 else -2, if (top) -2 else height))
            addView(box, if (top || spec.text("label").isBlank()) LinearLayout.LayoutParams(-1, height)
                else LinearLayout.LayoutParams(0, height, 1f))
        }
    }

    private fun panelArea(): Rect {
        val metrics = service.resources.displayMetrics
        // Panel insets change as it moves; using them would feed its own position back into bounds.
        val imeInsets = keyboardView()?.rootView?.rootWindowInsets
            ?: service.window?.window?.decorView?.rootWindowInsets
        val insets = imeInsets?.let {
            WindowInsetsCompat.toWindowInsetsCompat(it).getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        }
        val area = Rect(insets?.left ?: 0, insets?.top ?: 0,
            metrics.widthPixels - (insets?.right ?: 0), metrics.heightPixels - (insets?.bottom ?: 0))
        keyboardView()?.takeIf { it.isShown && it.height > 0 }?.let {
            val location = IntArray(2)
            it.getLocationOnScreen(location)
            if (location[1] > area.top) area.bottom = minOf(area.bottom, location[1])
        }
        area.inset(dp(8), dp(8))
        area.right = maxOf(area.right, area.left + 1)
        area.bottom = maxOf(area.bottom, area.top + 1)
        return area
    }

    private fun applyBounds(): Boolean {
        if (dismissed) return false
        val area = panelArea()
        val width = minOf(dp(maxWidthDp), area.width())
        val maximum = minOf(dp(maxHeightDp), area.height())
        root.maximumHeight = maximum
        val errorHeight = if (status.visibility == View.GONE) 0 else status.lineHeight * 2 + dp(4)
        scroll.maximumHeight = (maximum - dp(16) - errorHeight).coerceAtLeast(1)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maximum, View.MeasureSpec.AT_MOST))
        val height = root.measuredHeight.coerceAtLeast(1)
        if (!moved) { panelX = area.centerX() - width / 2; panelY = area.bottom - height }
        // Keep the requested position through temporary sizes while a new form is measured.
        val x = panelX.coerceIn(area.left, maxOf(area.left, area.right - width))
        val y = panelY.coerceIn(area.top, maxOf(area.top, area.bottom - height))
        window?.let { w ->
            val attrs = w.attributes
            @Suppress("RtlHardcoded") val gravity = Gravity.TOP or Gravity.LEFT
            if (attrs.width != width || attrs.height != height ||
                attrs.gravity != gravity || attrs.x != x || attrs.y != y) {
                attrs.width = width; attrs.height = height
                attrs.gravity = gravity; attrs.x = x; attrs.y = y
                w.attributes = attrs
                root.requestLayout()
                return true
            }
        }
        return false
    }

    private fun prepareDraw(): Boolean {
        if (dismissed) return true
        val keyboard = keyboardView()
        val keyboardReady = keyboard == null || (keyboard.isShown && keyboard.isAttachedToWindow &&
            keyboard.isLaidOut && !keyboard.isLayoutRequested && keyboard.width > 0 && keyboard.height > 0)
        val hasContent = prompt != null || content.childCount > 0 || status.visibility == View.VISIBLE
        if (!hasContent || !keyboardReady) {
            setPanelVisible(false)
            return true
        }
        // Attribute changes in this traversal take effect at the next WindowManager layout.
        // Keep the first frame transparent until that final size/position has been laid out.
        if (applyBounds() || root.width != root.measuredWidth || root.height != root.measuredHeight) {
            root.requestLayout()
            return false
        }
        setPanelVisible(true)
        return true
    }

    private fun setPanelVisible(visible: Boolean) {
        window?.let { w ->
            val alpha = if (visible) 1f else 0f
            val attrs = w.attributes
            val flags = if (visible) attrs.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else attrs.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (attrs.alpha != alpha || attrs.flags != flags) {
                attrs.alpha = alpha; attrs.flags = flags
                w.attributes = attrs
            }
        }
    }

    private fun movePanel(dx: Float, dy: Float) {
        moved = true
        panelX = (window?.attributes?.x ?: panelX) + dx.roundToInt()
        panelY = (window?.attributes?.y ?: panelY) + dy.roundToInt()
        applyBounds()
    }

    private fun blockTyping() {
        service.jsAppInputConnection = JsAppInputConnection.blocked(service)
    }
    private fun focusEditor() {
        if (dismissed || busy || prompt != null) return
        blockTyping()
        editors.values.firstOrNull { it.isEnabled && it.keyListener != null }?.let {
            it.requestFocus(); bindEditor(it)
        }
    }
    private fun bindEditor(editor: EditText) {
        if (!dismissed && !busy && prompt == null && editor.isEnabled && editor.keyListener != null)
            service.jsAppInputConnection = JsAppInputConnection(editor)
    }
    private fun label(value: String, bold: Boolean = false, size: Float = 14f, muted: Boolean = false,
        draggable: Boolean = false) = (if (draggable) JsAppPanelTitle(uiContext) else TextView(uiContext)).apply {
        text = value; textSize = size; setTextColor(if (muted) mutedColor else textColor)
        setPadding(dp(2), 0, dp(2), 0)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun button(value: String, filled: Boolean = false, action: () -> Unit) = MaterialButton(uiContext, null,
        if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = value; textSize = 13f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = dp(36); minimumHeight = dp(36)
        insetTop = 0; insetBottom = 0; cornerRadius = dp(18)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (filled) theme.accentKeyTextColor else textColor)
        strokeWidth = if (filled) 0 else dp(1)
        strokeColor = ColorStateList.valueOf(outlineColor)
        backgroundTintList = ColorStateList.valueOf(if (filled) accentColor else surfaceColor)
        rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 0x30))
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
}

private class BoundedScrollView(context: Context) : ScrollView(context) {
    var maximumHeight = Int.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
            maximumHeight else minOf(size, maximumHeight)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}

/** A focusable popup would end the original app's IME session and cancel pending generation. */
@SuppressLint("ViewConstructor")
private class EditorPreservingSelect(context: Context, private val availableArea: () -> Rect,
    private val closed: () -> Unit) : MaterialAutoCompleteTextView(context) {
    private var popup: ListPopupWindow? = null
    private var dismissListener: OnDismissListener? = null

    override fun showDropDown() {
        if (!isEnabled || popup?.isShowing == true) return
        val area = availableArea()
        val location = IntArray(2).also(::getLocationOnScreen)
        val below = (area.bottom - location[1] - height).coerceAtLeast(0)
        val above = (location[1] - area.top).coerceAtLeast(0)
        val rowHeight = (40 * resources.displayMetrics.density).roundToInt()
        val desiredHeight = minOf(rowHeight * adapter.count, (256 * resources.displayMetrics.density).roundToInt())
        val showAbove = below < desiredHeight && above > below
        val popupHeight = minOf(desiredHeight, if (showAbove) above else below).coerceAtLeast(1)
        popup = ListPopupWindow(context).apply {
            anchorView = this@EditorPreservingSelect
            isModal = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            width = this@EditorPreservingSelect.width
            height = popupHeight
            if (showAbove) verticalOffset = -this@EditorPreservingSelect.height - popupHeight
            setAdapter(this@EditorPreservingSelect.adapter)
            setBackgroundDrawable(dropDownBackground)
            setOnItemClickListener { parent, view, position, id ->
                this@EditorPreservingSelect.onItemClickListener?.onItemClick(parent, view, position, id)
                dismiss()
            }
            setOnDismissListener {
                popup = null
                dismissListener?.onDismiss()
                closed()
            }
        }.also { it.show() }
    }

    override fun isPopupShowing() = popup?.isShowing == true
    override fun dismissDropDown() { popup?.dismiss() }
    override fun setOnDismissListener(listener: OnDismissListener?) { dismissListener = listener }
}
