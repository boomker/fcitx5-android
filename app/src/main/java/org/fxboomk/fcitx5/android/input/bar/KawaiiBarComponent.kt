/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.CapabilityFlag
import org.fxboomk.fcitx5.android.core.CapabilityFlags
import org.fxboomk.fcitx5.android.core.FcitxEvent.CandidateListEvent
import org.fxboomk.fcitx5.android.data.clipboard.ClipboardManager
import org.fxboomk.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToAttachWindow
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.State.ClickToDetachWindow
import org.fxboomk.fcitx5.android.input.bar.ExpandButtonStateMachine.State.Hidden
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.CandidateEmpty
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.BooleanKey.PreeditEmpty
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.PreeditUpdated
import org.fxboomk.fcitx5.android.input.action.ButtonAction
import org.fxboomk.fcitx5.android.input.bar.KawaiiBarStateMachine.TransitionEvent.WindowDetached
import org.fxboomk.fcitx5.android.input.bar.ui.CandidateUi
import org.fxboomk.fcitx5.android.input.bar.ui.IdleUi
import org.fxboomk.fcitx5.android.input.status.ButtonsAdjustingWindow
import org.fxboomk.fcitx5.android.input.bar.ui.TitleUi
import org.fxboomk.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fxboomk.fcitx5.android.input.config.ConfigProviders
import org.fxboomk.fcitx5.android.input.config.ConfigurableButton
import org.fxboomk.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fxboomk.fcitx5.android.input.candidates.expanded.ExpandedCandidateStyle
import org.fxboomk.fcitx5.android.input.candidates.expanded.window.FlexboxExpandedCandidateWindow
import org.fxboomk.fcitx5.android.input.candidates.expanded.window.GridExpandedCandidateWindow
import org.fxboomk.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fxboomk.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fxboomk.fcitx5.android.input.clipboard.ClipboardWindow
import org.fxboomk.fcitx5.android.input.dependency.UniqueViewComponent
import org.fxboomk.fcitx5.android.input.dependency.context
import org.fxboomk.fcitx5.android.input.dependency.fcitx
import org.fxboomk.fcitx5.android.input.dependency.inputMethodService
import org.fxboomk.fcitx5.android.input.dependency.theme
import org.fxboomk.fcitx5.android.input.editing.TextEditingWindow
import org.fxboomk.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fxboomk.fcitx5.android.input.keyboard.CustomGestureView
import org.fxboomk.fcitx5.android.input.keyboard.KeyboardWindow
import org.fxboomk.fcitx5.android.input.popup.PopupComponent
import org.fxboomk.fcitx5.android.input.status.StatusAreaWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindow
import org.fxboomk.fcitx5.android.input.wm.InputWindowManager
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.core.SubtypeManager
import org.fxboomk.fcitx5.android.daemon.launchOnReady
import org.fxboomk.fcitx5.android.utils.InputMethodUtil
import org.fxboomk.fcitx5.android.utils.alpha
import org.fxboomk.fcitx5.android.utils.borderDrawable
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

class KawaiiBarComponent : UniqueViewComponent<KawaiiBarComponent, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val fcitx by manager.fcitx()
    private val windowManager: InputWindowManager by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val popup: PopupComponent by manager.must()

    var onFloatingToggleListener: (() -> Unit)? = null
    var onFloatingLongPressListener: (() -> Unit)? = null

    fun setFloatingState(isFloating: Boolean) {
        idleUi.buttonsUi.setFloatingState(isFloating)
    }

    fun setOneHandKeyboardState(isOneHanded: Boolean) {
        idleUi.buttonsUi.setOneHandKeyboardState(isOneHanded)
    }

    private val prefs = AppPrefs.getInstance()

    private val clipboardSuggestion = prefs.clipboard.clipboardSuggestion
    private val clipboardItemTimeout = prefs.clipboard.clipboardItemTimeout
    private val clipboardMaskSensitive by prefs.clipboard.clipboardMaskSensitive
    private val expandedCandidateStyle by prefs.keyboard.expandedCandidateStyle
    private val expandToolbarByDefault by prefs.keyboard.expandToolbarByDefault
    private val toolbarNumRowOnPassword by prefs.keyboard.toolbarNumRowOnPassword
    private val showVoiceInputButton by prefs.keyboard.showVoiceInputButton
    private val preferredVoiceInput by prefs.keyboard.preferredVoiceInput

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    private var isCapabilityFlagsPassword: Boolean = false
    private var isKeyboardLayoutNumber: Boolean = false

    private enum class NumberRowState { Auto, ForceShow, ForceHide }

    private var numberRowState = NumberRowState.Auto

    @Keep
    private val onClipboardUpdateListener =
        ClipboardManager.OnClipboardUpdateListener {
            if (!clipboardSuggestion.getValue()) return@OnClipboardUpdateListener
            service.lifecycleScope.launch {
                if (it.text.isEmpty()) {
                    isClipboardFresh = false
                } else {
                    idleUi.clipboardUi.text.text = if (it.sensitive && clipboardMaskSensitive) {
                        ClipboardEntry.BULLET.repeat(min(42, it.text.length))
                    } else {
                        it.text.take(42)
                    }
                    isClipboardFresh = true
                    launchClipboardTimeoutJob()
                }
                evalIdleUiState()
            }
        }

    @Keep
    private val onClipboardSuggestionUpdateListener =
        ManagedPreference.OnChangeListener<Boolean> { _, it ->
            if (!it) {
                isClipboardFresh = false
                evalIdleUiState()
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
            }
        }

    @Keep
    private val onClipboardTimeoutUpdateListener =
        ManagedPreference.OnChangeListener<Int> { _, _ ->
            when (idleUi.currentState) {
                IdleUi.State.Clipboard -> {
                    // renew timeout when clipboard suggestion is present
                    launchClipboardTimeoutJob()
                }

                else -> {}
            }
        }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardItemTimeout.getValue() * 1000L
        // never transition to ClipboardTimedOut state when timeout < 0
        if (timeout < 0L) return
        val effectiveTimeout = min(timeout, 3000L)
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(effectiveTimeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalIdleUiState()
        }
    }

    private fun evalIdleUiState(fromUser: Boolean = false) {
        val newState = when {
            numberRowState == NumberRowState.ForceShow -> IdleUi.State.NumberRow
            isClipboardFresh -> IdleUi.State.Clipboard
            isInlineSuggestionPresent -> IdleUi.State.InlineSuggestion
            isCapabilityFlagsPassword && !isKeyboardLayoutNumber && numberRowState != NumberRowState.ForceHide -> IdleUi.State.NumberRow
            /**
             * state matrix:
             *                               expandToolbarByDefault
             *                          |   \   |    true |   false
             * toolbarManuallyToggled   |  true |   Empty | Toolbar
             *                          | false | Toolbar |   Empty
             */
            expandToolbarByDefault == prefs.keyboard.toolbarManuallyToggled.getValue() -> IdleUi.State.Empty
            else -> IdleUi.State.Toolbar
        }
        if (newState == idleUi.currentState) return
        idleUi.updateState(newState, fromUser)
    }

    private val hideKeyboardCallback = View.OnClickListener {
        service.requestHideSelf(0)
    }

    private val swipeDownExpandCallback = CustomGestureView.OnGestureListener { _, e ->
        if (e.type == CustomGestureView.GestureType.Up && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    // Combined gesture: determine primary direction by comparing totalX and totalY.
    // - If horizontal is dominant and left, show number row (when allowed).
    // - If vertical is dominant and down, hide keyboard.
    private val swipeHideKeyboardCallback = CustomGestureView.OnGestureListener { v, e ->
        val numberRowAvailable = isCapabilityFlagsPassword && !isKeyboardLayoutNumber
        if (numberRowAvailable) {
            val dir = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) 1 else -1
            // We can't access the rawX and rawY of the MotionEvent, so we need to do some math.
            // `e.x` and `e.y` are relative to the view's top-left corner, we want to rotate
            // around the center of the view, so we translate them to be relative to the center
            val relX = e.x - v.width / 2f
            val relY = e.y - v.height / 2f

            // rotate the relative coordinates by current rotation to get absolute coordinates
            // the button is ↓, so apply -90 degrees offset
            val theta = Math.toRadians(v.rotation.toDouble()) - PI / 2
            val c = cos(theta)
            val s = sin(theta)
            val screenX = c * relX - s * relY
            val screenY = s * relX + c * relY
            val distance = hypot(screenX, screenY)
            var angle = Math.toDegrees(atan2(screenY, screenX)).toFloat()

            when (e.type) {
                CustomGestureView.GestureType.Move -> {
                    angle = if (angle in -45f..45f) {
                        angle.coerceIn(-10f, 10f)
                    } else abs(angle).coerceIn(90f - 10f, 90f + 10f) * dir
                    v.rotation = angle
                }
                CustomGestureView.GestureType.Up -> {
                    val thresholdX = (v as CustomGestureView).swipeThresholdX
                    val thresholdY = v.swipeThresholdY
                    val handled = when (angle) {
                        in -45f..45f if distance > thresholdY -> {
                            service.requestHideSelf(0)
                            true
                        }
                        !in -45f..45f if distance > thresholdX -> {
                            v.rotation = 90f * dir
                            numberRowState = NumberRowState.ForceShow
                            evalIdleUiState(fromUser = true)
                            true
                        }
                        else -> false
                    }
                    v.rotation = 0f
                    return@OnGestureListener handled
                }
                else -> {}
            }
        }

        if (e.type == CustomGestureView.GestureType.Up && abs(e.totalY) > abs(e.totalX) && e.totalY > 0) {
            service.requestHideSelf(0)
            true
        } else false
    }

    private var voiceInputSubtype: Pair<String, InputMethodSubtype>? = null

    private val switchToVoiceInputCallback = View.OnClickListener {
        val (id, subtype) = voiceInputSubtype ?: return@OnClickListener
        InputMethodUtil.switchInputMethod(service, id, subtype)
    }

    private fun refreshHideKeyboardButtonState(
        ui: IdleUi = idleUi,
        capFlags: CapabilityFlags = CapabilityFlags.fromEditorInfo(service.currentInputEditorInfo)
    ) {
        isCapabilityFlagsPassword = toolbarNumRowOnPassword && capFlags.has(CapabilityFlag.Password)
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        val shouldShowVoiceInput =
            showVoiceInputButton && voiceInputSubtype != null && !capFlags.has(CapabilityFlag.Password)
        ui.setHideKeyboardIsVoiceInput(
            shouldShowVoiceInput,
            if (shouldShowVoiceInput) switchToVoiceInputCallback else hideKeyboardCallback
        )
    }

    // Load buttons config from file or use default
    private fun loadButtonsConfig(): List<ConfigurableButton> {
        val snapshot = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()
        val config = snapshot?.value ?: ButtonsLayoutConfig.default()

        // Filter out 'more' button from config
        val filteredButtons = config.kawaiiBarButtons.filter { it.id != "more" }

        // Return buttons without 'more' button (removed per user request)
        return filteredButtons
    }

    private var _idleUi: IdleUi? = null
    private var currentButtonsConfig: List<ConfigurableButton> = emptyList()

    private val idleUi: IdleUi
        get() {
            if (_idleUi == null) {
                currentButtonsConfig = loadButtonsConfig()
                _idleUi = IdleUi(context, theme, popup, commonKeyActionListener, currentButtonsConfig)
                setupIdleUiCallbacks(_idleUi!!)
            }
            return _idleUi!!
        }

    private fun setupIdleUiCallbacks(ui: IdleUi) {
        ui.menuButton.setOnClickListener {
            // menuButton now opens StatusAreaWindow (secondary menu) instead of toggling toolbar
            windowManager.attachWindow(StatusAreaWindow())
        }
        ui.menuButton.setOnLongClickListener {
            // Completely disable toggle when adjusting overlay is visible to prevent conflicts
            if (service.inputView?.isButtonsAdjustingOverlayVisible == true) {
                return@setOnLongClickListener true
            }
            service.inputView?.showButtonsAdjustingOverlay()
            true
        }
        ui.hideKeyboardButton.apply {
            setOnClickListener(hideKeyboardCallback)
            swipeEnabled = true
            swipeThresholdY = dp(HEIGHT.toFloat())
            onGestureListener = swipeHideKeyboardCallback
        }
        ui.buttonsUi.apply {
            // Setup click listeners using ButtonAction
            ButtonAction.allConfigurableActions.forEach { action ->
                setOnClickListener(action.id) {
                    action.execute(
                        context = context,
                        service = service,
                        fcitx = fcitx,
                        windowManager = windowManager,
                        view = null,
                        onActionComplete = {
                            // Refresh UI state after action completion
                            when (action.id) {
                                "floating_toggle" -> evalIdleUiState()
                                "one_handed_keyboard", "theme_toggle" -> {
                                    updateButtonsState()
                                    refreshHideKeyboardButtonState(ui)
                                }
                            }
                        }
                    )
                }
            }

            // Special handling for 'more' button
            setOnClickListener("more") {
                windowManager.attachWindow(StatusAreaWindow())
            }

            setOnClickListener("floating_toggle") {
                val action = ButtonAction.fromId("floating_toggle")
                if (onFloatingToggleListener != null) {
                    onFloatingToggleListener?.invoke()
                    updateButtonsState()
                } else {
                    action?.execute(
                        context = context,
                        service = service,
                        fcitx = fcitx,
                        windowManager = windowManager,
                        view = null,
                        onActionComplete = { updateButtonsState() }
                    )
                }
            }

            // Special handling for floating_toggle long press
            setOnLongClickListener("floating_toggle") {
                if (onFloatingLongPressListener != null) {
                    onFloatingLongPressListener?.invoke()
                } else {
                    ButtonAction.fromId("floating_toggle")?.onLongPress(
                        context = context,
                        service = service,
                        fcitx = fcitx,
                        windowManager = windowManager,
                        view = ui.buttonsUi.root
                    )
                }
                true
            }
            setOnLongClickListener("theme_toggle") {
                ButtonAction.fromId("theme_toggle")?.onLongPress(
                    context = context,
                    service = service,
                    fcitx = fcitx,
                    windowManager = windowManager,
                    view = ui.buttonsUi.root
                )
                true
            }

            // Keep language switch long-press behavior aligned with keyboard globe key.
            setOnLongClickListener("language_switch") {
                ButtonAction.fromId("language_switch")?.onLongPress(
                    context = context,
                    service = service,
                    fcitx = fcitx,
                    windowManager = windowManager,
                    view = ui.buttonsUi.root
                )
                true
            }
            updateButtonsState(service)
        }
        refreshHideKeyboardButtonState(ui)
        ui.numberRow.onCollapseListener = {
            numberRowState = NumberRowState.ForceHide
            evalIdleUiState(fromUser = true)
        }
        ui.clipboardUi.suggestionView.apply {
            setOnClickListener {
                ClipboardManager.lastEntry?.let {
                    service.commitText(it.text)
                }
                clipboardTimeoutJob?.cancel()
                clipboardTimeoutJob = null
                isClipboardFresh = false
                evalIdleUiState()
            }
            setOnLongClickListener {
                ClipboardManager.lastEntry?.let {
                    AppUtil.launchClipboardEdit(context, it.id, true)
                }
                true
            }
        }
    }

    // Reload Kawaii Bar buttons config (called when config file changes)
    fun reloadButtonsConfig() {
        val newConfig = loadButtonsConfig()
        if (newConfig != currentButtonsConfig) {
            currentButtonsConfig = newConfig
            // Update the existing IdleUi with new config
            _idleUi?.buttonsUi?.updateConfig(newConfig)
            updateButtonsState()
        }
    }

    private val candidateUi by lazy {
        CandidateUi(context, theme, horizontalCandidate.view).apply {
            expandButton.apply {
                swipeEnabled = true
                swipeThresholdY = dp(HEIGHT.toFloat())
                onGestureListener = swipeDownExpandCallback
            }
        }
    }

    private val titleUi by lazy {
        TitleUi(context, theme)
    }

    private val barStateMachine = KawaiiBarStateMachine.new {
        switchUiByState(it)
    }

    val expandButtonStateMachine = ExpandButtonStateMachine.new {
        when (it) {
            ClickToAttachWindow -> {
                setExpandButtonToAttach()
                setExpandButtonEnabled(true)
            }

            ClickToDetachWindow -> {
                setExpandButtonToDetach()
                setExpandButtonEnabled(true)
            }

            Hidden -> {
                setExpandButtonEnabled(false)
            }
        }
    }

    // set expand candidate button to create expand candidate
    private fun setExpandButtonToAttach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(
                when (expandedCandidateStyle) {
                    ExpandedCandidateStyle.Grid -> GridExpandedCandidateWindow()
                    ExpandedCandidateStyle.Flexbox -> FlexboxExpandedCandidateWindow()
                }
            )
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_more_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.expand_candidates_list)
    }

    // set expand candidate button to close expand candidate
    private fun setExpandButtonToDetach() {
        candidateUi.expandButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.expandButton.setIcon(R.drawable.ic_baseline_expand_less_24)
        candidateUi.expandButton.contentDescription = context.getString(R.string.hide_candidates_list)
    }

    // should be used with setExpandButtonToAttach or setExpandButtonToDetach
    private fun setExpandButtonEnabled(enabled: Boolean) {
        candidateUi.expandButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun switchUiByState(state: KawaiiBarStateMachine.State) {
        val index = state.ordinal
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != titleUi.root) {
            titleUi.setReturnButtonOnClickListener { }
            titleUi.setTitle("")
            titleUi.removeExtension()
        }
        view.displayedChild = index
    }

    private fun resolveBarBackgroundColor(): Int {
        return if (ThemeManager.prefs.keyBorder.getValue()) Color.TRANSPARENT else theme.barColor
    }

    private fun resolveBarBorderColor(backgroundColor: Int): Int {
        val keyShadow = theme.keyShadowColor
        if (Color.alpha(keyShadow) >= 0x26 && (keyShadow and 0x00ffffff) != (backgroundColor and 0x00ffffff)) {
            return keyShadow
        }

        val divider = theme.dividerColor
        if (Color.alpha(divider) >= 0x26 && (divider and 0x00ffffff) != (backgroundColor and 0x00ffffff)) {
            return divider
        }

        // Fallback to a high-contrast stroke, so navbar border is always visible across themes.
        return if (theme.isDark) Color.WHITE.alpha(0.30f) else Color.BLACK.alpha(0.22f)
    }

    override val view by lazy {
        ViewAnimator(context).apply {
            background = run {
                val backgroundColor = resolveBarBackgroundColor()
                if (ThemeManager.prefs.navbarBorder.getValue()) {
                    val cornerRadius = dp(max(6f, ThemeManager.prefs.keyRadius.getValue().toFloat())).toFloat()
                    borderDrawable(
                        width = dp(1),
                        stroke = resolveBarBorderColor(backgroundColor),
                        background = backgroundColor,
                        cornerRadius = cornerRadius
                    )
                } else {
                    ColorDrawable(backgroundColor)
                }
            }
            add(idleUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(titleUi.root, lParams(matchParent, matchParent))
        }
    }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        ClipboardManager.lastEntry?.let {
            val now = System.currentTimeMillis()
            val clipboardTimeout = clipboardItemTimeout.getValue() * 1000L
            val effectiveTimeout = if (clipboardTimeout < 0L) clipboardTimeout else min(clipboardTimeout, 3000L)
            if (now - it.timestamp < effectiveTimeout) {
                onClipboardUpdateListener.onUpdate(it)
            }
        }
        ClipboardManager.addOnUpdateListener(onClipboardUpdateListener)
        clipboardSuggestion.registerOnChangeListener(onClipboardSuggestionUpdateListener)
        clipboardItemTimeout.registerOnChangeListener(onClipboardTimeoutUpdateListener)
    }

    override fun onStartInput(info: EditorInfo, capFlags: CapabilityFlags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            idleUi.privateMode(info.imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        }
        isInlineSuggestionPresent = false
        numberRowState = NumberRowState.Auto
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            idleUi.inlineSuggestionsBar.clear()
        }
        voiceInputSubtype = InputMethodUtil.findVoiceSubtype(preferredVoiceInput)
        val shouldShowVoiceInput =
            showVoiceInputButton && voiceInputSubtype != null && !capFlags.has(CapabilityFlag.Password)
        idleUi.setHideKeyboardIsVoiceInput(
            shouldShowVoiceInput,
            if (shouldShowVoiceInput) switchToVoiceInputCallback else hideKeyboardCallback
        )
        evalIdleUiState()
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        barStateMachine.push(PreeditUpdated, PreeditEmpty to empty)
    }

    override fun onCandidateUpdate(data: CandidateListEvent.Data) {
        // When using "Always" floating mode, don't show candidates in Kawaii Bar
        val floatingMode = AppPrefs.getInstance().candidates.mode.getValue()
        val useFloatingAlways = floatingMode == FloatingCandidatesMode.Always

        if (useFloatingAlways) {
            // Force stay in Idle state when using floating candidates
            barStateMachine.push(CandidatesUpdated, CandidateEmpty to true)
        } else {
            barStateMachine.push(CandidatesUpdated, CandidateEmpty to data.candidates.isEmpty())
        }
    }

    override fun onWindowAttached(window: InputWindow) {
        when (window) {
            is InputWindow.ExtendedInputWindow<*> -> {
                titleUi.setTitle(window.title)
                window.onCreateBarExtension()?.let { titleUi.addExtension(it, window.showTitle) }
                titleUi.setReturnButtonOnClickListener {
                    windowManager.attachWindow(KeyboardWindow)
                }
                barStateMachine.push(ExtendedWindowAttached)
            }

            else -> {}
        }
        service.inputView?.requestBlurRefresh()
    }

    override fun onWindowDetached(window: InputWindow) {
        barStateMachine.push(WindowDetached)
        service.inputView?.requestBlurRefresh()
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(HEIGHT))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            evalIdleUiState()
            idleUi.inlineSuggestionsBar.clear()
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            idleUi.inlineSuggestionsBar.setPinnedView(
                pinned?.let { inflateInlineContentView(it) }
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            idleUi.inlineSuggestionsBar.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalIdleUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? {
        return suspendCoroutine { c ->
            // callback view might be null
            suggestion.inflate(context, suggestionSize, directExecutor) { v ->
                c.resume(v)
            }
        }
    }

    companion object {
        const val HEIGHT = 40
    }

    private fun updateButtonsState() {
        _idleUi?.buttonsUi?.updateButtonsState(service)
    }

    fun onKeyboardLayoutSwitched(isNumber: Boolean) {
        isKeyboardLayoutNumber = isNumber
        evalIdleUiState()
    }

}
