/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import java.lang.ref.WeakReference
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.core.InputMethodSubMode
import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.data.pinyin.ShuangpinLayoutKeys
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.input.popup.PopupAction
import org.fxboomk.fcitx5.android.input.config.KeyIconIds
import org.fxboomk.fcitx5.android.input.config.KeyIconThemeStore
import splitties.views.imageResource
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

internal fun resolveCapsAwareAltText(
    appearance: KeyDef.Appearance.AltText,
    renderedText: String,
    displayUppercase: Boolean,
    keepLettersUppercase: Boolean,
    preserveConfiguredDisplayCase: Boolean = false,
): String {
    val sourceFromAppearance = renderedText.isEmpty() || renderedText == appearance.displayText
    val displayText = if (sourceFromAppearance) appearance.displayText else renderedText

    if (appearance.caseAwareCapsState) {
        if (preserveConfiguredDisplayCase) return appearance.displayText
        if (displayText.length != 1 || !displayText[0].isLetter()) return displayText
        if (appearance.displayText[0].isUpperCase()) return appearance.displayText
        return if (displayUppercase) displayText.uppercase() else displayText.lowercase()
    }
    if (!appearance.followsCapsState) return appearance.displayText

    val character = appearance.character
    val displayIsSingleLetter = displayText.length == 1 && displayText[0].isLetter()
    val characterIsSingleLetter = sourceFromAppearance &&
        character.length == 1 && character[0].isLetter()
    val isCustomLabel = !displayText.equals(character, ignoreCase = true)

    return when {
        isCustomLabel -> displayText
        keepLettersUppercase && displayIsSingleLetter -> displayText.uppercase()
        keepLettersUppercase -> displayText
        !displayUppercase && displayIsSingleLetter -> displayText.lowercase()
        !displayUppercase -> displayText
        displayIsSingleLetter -> displayText.uppercase()
        characterIsSingleLetter -> character.uppercase()
        else -> displayText
    }
}

/**
 * Virtual uppercase input uses Shift semantics for both one-shot and
 * persistent caps states. A persistent virtual caps state must not be
 * represented as a physical CapsLock modifier to input engines.
 */
internal fun virtualUppercaseKeyStates(): KeyStates =
    KeyStates(KeyState.Virtual, KeyState.Shift)

@SuppressLint("ViewConstructor")
class TextKeyboard private constructor(
    context: Context,
    theme: Theme,
    private val layoutState: TextKeyboardLayoutState,
) : BaseKeyboard(context, theme, layoutState::getLayout) {

    constructor(
        context: Context,
        theme: Theme,
        initialIme: InputMethodEntry? = null,
    ) : this(context, theme, TextKeyboardLayoutState(initialIme))

    enum class CapsState { None, Once, Lock }

    internal data class KeyDefLayoutCacheKey(
        val sourceKey: String,
        val subModeLabel: String,
        val showLangSwitch: Boolean,
        val theme: Theme,
    )

    companion object {
        const val Name = "Text"
        private var lastModified = 0L
        internal var ime: InputMethodEntry? = null
            set(value) {
                field = value
                TextKeyboardLayoutState.observeActiveIme(value)
            }
        private var listenerRegistered = false
        private var iconListenerRegistered = false
        private val attachedKeyboards = mutableListOf<WeakReference<TextKeyboard>>()

        @Synchronized
        private fun ensureListenerRegistered() {
            if (listenerRegistered) return
            org.fxboomk.fcitx5.android.input.config.ConfigProviders.addTextKeyboardLayoutListener {
                onTextLayoutFileChanged()
            }
            listenerRegistered = true
            if (!iconListenerRegistered) {
                KeyIconThemeStore.addOnChangedListener {
                    attachedKeyboards.mapNotNull { it.get() }.forEach {
                        it.refreshStyle()
                        it.refreshCapsPresentation()
                    }
                }
                iconListenerRegistered = true
            }
        }

        @Synchronized
        private fun registerKeyboard(keyboard: TextKeyboard) {
            attachedKeyboards.removeAll { it.get() == null || it.get() === keyboard }
            attachedKeyboards.add(WeakReference(keyboard))
            ensureListenerRegistered()
        }

        @Synchronized
        private fun unregisterKeyboard(keyboard: TextKeyboard) {
            attachedKeyboards.removeAll { it.get() == null || it.get() === keyboard }
        }

        @Synchronized
        private fun onTextLayoutFileChanged() {
            cachedRawLayoutJson = null
            lastRawLayoutFile = null
            lastRawModified = 0L
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.refreshStyle()
                keyboard.refreshDynamicState()
            }
        }

        @Synchronized
        fun refreshCapsPresentationOnAll() {
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.refreshCapsPresentation()
            }
        }

        @Synchronized
        fun clearCapsStateOnAll() {
            val living = attachedKeyboards.mapNotNull { it.get() }
            attachedKeyboards.removeAll { it.get() == null }
            living.forEach { keyboard ->
                keyboard.clearLocalCapsState()
            }
        }

        // Cache for raw JSON layout (preserves submode structure)
        internal var cachedRawLayoutJson: JsonObject? = null
        private var lastRawLayoutFile: String? = null
        private var lastRawModified = 0L

        // Compatibility alias for cachedRawLayoutJson (used by SplitKeyboardCalibrationActivity)
        @JvmStatic
        var cachedLayoutJsonMap: JsonObject?
            get() = cachedRawLayoutJson
            set(value) {
                cachedRawLayoutJson = value
            }

        // Cache for parsed KeyDef layouts to avoid recreating them on every reloadLayout()
        private val cachedKeyDefLayouts = mutableMapOf<KeyDefLayoutCacheKey, List<List<KeyDef>>>()
        private var lastLayoutCacheInvalidated = 0L

        /**
         * Clear KeyDef layout cache. Call this after saving layout changes.
         */
        fun clearCachedKeyDefLayouts() {
            cachedKeyDefLayouts.clear()
            lastLayoutCacheInvalidated = 0L
        }

        val textLayoutJson: JsonObject?
            @Synchronized
            get() {
                val providers = org.fxboomk.fcitx5.android.input.config.ConfigProviders
                val provider = providers.provider
                val memoryJson = provider.textKeyboardLayoutJson()
                if (memoryJson != null) {
                    providers.ensureWatching()
                    if (cachedRawLayoutJson !== memoryJson || lastRawLayoutFile != null) {
                        cachedRawLayoutJson = memoryJson
                        lastRawLayoutFile = null
                        lastRawModified = Long.MIN_VALUE
                        lastLayoutCacheInvalidated = 0L
                        cachedKeyDefLayouts.clear()
                    }
                    return cachedRawLayoutJson
                }

                val file = provider.textKeyboardLayoutFile()
                val currentFile = file?.absolutePath
                val currentModified = file?.takeIf { it.exists() }?.lastModified() ?: 0L
                if (cachedRawLayoutJson != null &&
                    currentFile == lastRawLayoutFile &&
                    currentModified == lastRawModified
                ) {
                    providers.ensureWatching()
                    return cachedRawLayoutJson
                }

                val snapshot = providers.readTextKeyboardLayout<JsonObject>() ?: run {
                    cachedRawLayoutJson = null
                    lastRawLayoutFile = null
                    lastRawModified = 0L
                    return null
                }
                if (cachedRawLayoutJson == null ||
                    currentFile != lastRawLayoutFile ||
                    snapshot.lastModified != lastRawModified
                ) {
                    lastRawLayoutFile = currentFile
                    lastRawModified = snapshot.lastModified
                    cachedRawLayoutJson = snapshot.value
                    // Invalidate KeyDef cache when JSON changes
                    lastLayoutCacheInvalidated = snapshot.lastModified
                    cachedKeyDefLayouts.clear()
                }
                return cachedRawLayoutJson
            }

        fun getLayout(): List<List<KeyDef>> = getLayout(
            ime,
            ThemeManager.activeTheme,
            TextKeyboardLayoutState.activeLayoutSubMode(ime),
        )

        internal fun layoutSubModeForIme(ime: InputMethodEntry): InputMethodSubMode =
            TextKeyboardLayoutState.activeLayoutSubMode(ime)

        internal fun getLayout(
            currentIme: InputMethodEntry?,
            theme: Theme,
            layoutSubMode: InputMethodSubMode = currentIme?.subMode ?: InputMethodSubMode(),
        ): List<List<KeyDef>> {
            val imeName = currentIme?.uniqueName
            val subModeLabel = layoutSubMode.label
            val showLangSwitch = AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
            if (imeName != null) {
                val json = textLayoutJson
                if (json != null) {
                    val resolution = resolveTextKeyboardLayout(
                        json = json,
                        uniqueName = imeName,
                        displayName = currentIme.displayName,
                        subModeLabel = subModeLabel,
                    )
                    val rows = resolution?.rows
                    if (rows != null) {
                        val cacheKey = KeyDefLayoutCacheKey(
                            sourceKey = resolution.sourceKey,
                            subModeLabel = subModeLabel,
                            showLangSwitch = showLangSwitch,
                            theme = theme,
                        )
                        return cachedKeyDefLayouts.getOrPut(cacheKey) {
                            rows.map { rowElement ->
                                LayoutJsonUtils.createKeyDefsForRowElement(
                                    rowElement = rowElement,
                                    showLangSwitch = showLangSwitch,
                                    subModeLabel = subModeLabel,
                                    subModeName = layoutSubMode.name,
                                    theme = theme,
                                )
                            }
                        }
                    }
                }
            }
            return getDefaultLayout(showLangSwitch)
        }

        fun getDefaultLayout(showLangSwitch: Boolean = true): List<List<KeyDef>> {
            return listOf(
                listOf(
                    AlphabetKey("Q", "1"),
                    AlphabetKey("W", "2"),
                    AlphabetKey("E", "3"),
                    AlphabetKey("R", "4"),
                    AlphabetKey("T", "5"),
                    AlphabetKey("Y", "6"),
                    AlphabetKey("U", "7"),
                    AlphabetKey("I", "8"),
                    AlphabetKey("O", "9"),
                    AlphabetKey("P", "0")
                ),
                listOf(
                    AlphabetKey("A", "@"),
                    AlphabetKey("S", "*"),
                    AlphabetKey("D", "+"),
                    AlphabetKey("F", "-"),
                    AlphabetKey("G", "="),
                    AlphabetKey("H", "/"),
                    AlphabetKey("J", "#"),
                    AlphabetKey("K", "("),
                    AlphabetKey("L", ")")
                ),
                listOf(
                    CapsKey(),
                    AlphabetKey("Z", "'"),
                    AlphabetKey("X", ":"),
                    AlphabetKey("C", "\""),
                    AlphabetKey("V", "?"),
                    AlphabetKey("B", "!"),
                    AlphabetKey("N", "~"),
                    AlphabetKey("M", "\\"),
                    BackspaceKey()
                ),
                listOf(
                    LayoutSwitchKey("?123", ""),
                    CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
                    *if (showLangSwitch) arrayOf(LanguageKey()) else emptyArray(),
                    SpaceKey(),
                    SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                    ReturnKey()
                )
            )
        }
    }

    private var specialKeyViews: SpecialKeyViews = SpecialKeyViews(
        caps = emptyList(),
        backspace = emptyList(),
        quickphrase = emptyList(),
        space = emptyList(),
        `return` = emptyList()
    )

    data class SpecialKeyViews(
        val caps: List<KeyViewWithImage>,
        val backspace: List<KeyViewWithImage>,
        val quickphrase: List<KeyViewWithImage>,
        val space: List<TextKeyView>,
        val `return`: List<KeyViewWithImage>
    )

    private fun findAllSpecialKeyViews(): SpecialKeyViews {
        val caps = mutableListOf<KeyViewWithImage>()
        val backspace = mutableListOf<KeyViewWithImage>()
        val quickphrase = mutableListOf<KeyViewWithImage>()
        val space = mutableListOf<TextKeyView>()
        val returnKeys = mutableListOf<KeyViewWithImage>()

        allViews.forEach { view ->
            when (view.tag) {
                // Match by KeyViewWithImage, not a concrete class: a key with a
                // swipe label ("划动标签") renders as ImageAltTextKeyView instead of
                // ImageKeyView, and casting to the concrete type would drop it so
                // its icon (e.g. the caps/shift state) would never update.
                R.id.button_caps -> (view as? KeyViewWithImage)?.let(caps::add)
                R.id.button_backspace -> (view as? KeyViewWithImage)?.let(backspace::add)
                R.id.button_quickphrase -> (view as? KeyViewWithImage)?.let(quickphrase::add)
                R.id.button_space -> (view as? TextKeyView)?.let(space::add)
                R.id.button_return -> (view as? KeyViewWithImage)?.let(returnKeys::add)
            }
        }

        return SpecialKeyViews(
            caps = caps,
            backspace = backspace,
            quickphrase = quickphrase,
            space = space,
            `return` = returnKeys
        )
    }
    
    private fun ensureSpecialKeyViewsInitialized() {
        specialKeyViews = findAllSpecialKeyViews()
    }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey
    private val spaceKeyLabelMode = AppPrefs.getInstance().keyboard.spaceKeyLabelMode
    private val punctuationPosition = ThemeManager.prefs.punctuationPosition
    private val uppercasePosition = ThemeManager.prefs.uppercasePosition
    private var currentIme: InputMethodEntry? = layoutState.ime

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // Clear cache when showLangSwitch setting changes
        cachedKeyDefLayouts.clear()
        // Reload layout to show/hide LanguageKey
        reloadLayout()
    }

    @Keep
    private val spaceKeyLabelModeListener = ManagedPreference.OnChangeListener<SpaceKeyLabelMode> { _, mode ->
        currentIme?.let { updateSpaceLabel(it, mode) }
    }

    @Keep
    private val altTextPositionListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        post { refreshAltTextLayouts() }
    }

    private val keepLettersUppercasePref: Boolean by AppPrefs.getInstance().keyboard.keepLettersUppercase

    /**
     * 预览等场景强制字母按键大写显示（覆盖 keepLettersUppercase 偏好）。
     * 仅作用于单个 TextKeyboard 实例；空值时跟随用户偏好。
     */
    @Volatile
    internal var keepLettersUppercaseOverride: Boolean? = null
    internal var preserveMacroDisplayCaseOverride: Boolean = false

    private val keepLettersUppercase: Boolean
        get() = keepLettersUppercaseOverride ?: keepLettersUppercasePref

    init {
        currentIme?.let { lastLayoutSignature = layoutSignature(it) }
    }

    private val textKeys: List<TextKeyView>
        get() = allViews.filterIsInstance(TextKeyView::class.java).toList()

    private var capsState: CapsState = CapsState.None

    private fun isDisplayCapsOn(): Boolean {
        return capsState != CapsState.None || isSimulatedCapsLockOn()
    }

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private var lastLayoutSignature: String? = null
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    private fun layoutSignature(ime: InputMethodEntry): String {
        val json = textLayoutJson
        val parentKey = ShuangpinLayoutKeys.parentBaseLayoutKey(ime.uniqueName)
        val layoutSource = when {
            json?.containsKey(ime.uniqueName) == true -> "u:${ime.uniqueName}"
            json?.containsKey(ime.displayName) == true -> "d:${ime.displayName}"
            // 双拼方案回退到"双拼"父级输入法布局，签名需随父布局变化
            parentKey != null && json?.containsKey(parentKey) == true -> "p:$parentKey"
            else -> "default"
        }
        val subModeLabel = layoutState.layoutSubMode(ime)
            .run { label.ifEmpty { name.ifEmpty { "" } } }
        return "$layoutSource|$subModeLabel|$lastRawModified"
    }

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = if (isSimulatedCapsLockOn()) {
                                action.copy(
                                    act = action.act.uppercase(),
                                    states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                                )
                            } else {
                                action.copy(act = action.act.lowercase())
                            }
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = virtualUppercaseKeyStates()
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            // A persistent virtual-cap state is a Shift lock, not a
                            // physical CapsLock modifier. Rime treats CapsLock as a
                            // mode switch and may commit the key instead of accepting
                            // the uppercase letter as input code.
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = virtualUppercaseKeyStates()
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> {
                if (!action.lock && source == KeyActionListener.Source.Keyboard && tryConsumeMacroCapsLock()) {
                    // MacroKey tap Caps_Lock opened lock state: single tap on CapsKey should send Caps_Lock again.
                } else {
                    val behavior = AppPrefs.getInstance().keyboard.capsKeyBehavior.getValue()
                    switchCapsState(shouldLockCapsAction(action.lock, behavior))
                }
            }
            is KeyAction.CommitAction -> {
                // 需求 5：大写（Caps）状态开启时，标点划动映射提交输出其大写列
                // （未定义则回退为原始小写），优先于中英 / 全角解析。
                // 仅 swipeSymbol 提交参与，普通提交（选词、符号面板、字面键）不受影响。
                if (action.swipeSymbol && isDisplayCapsOn()) {
                    transformed = KeyAction.CommitAction(
                        resolveSwipeUppercase(action.text, SwipeSymbolMappingStore.current())
                    )
                }
            }
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun preprocessMacroAction(
        action: MacroAction,
        source: KeyActionListener.Source
    ): MacroAction {
        val allowConsumeCapsOnce = source == KeyActionListener.Source.Keyboard
        var consumeCapsOnce = false
        val simulatedCapsOn = isSimulatedCapsLockOn()
        val pendingUppercaseDown = mutableMapOf<String, Int>()

        fun isLetter(code: String): Boolean = code.length == 1 && code[0].isLetter()

        fun consumeUppercaseDecision(): Boolean {
            return when (capsState) {
                CapsState.None -> simulatedCapsOn
                CapsState.Once -> {
                    if (allowConsumeCapsOnce && !consumeCapsOnce) {
                        consumeCapsOnce = true
                        true
                    } else {
                        simulatedCapsOn
                    }
                }
                CapsState.Lock -> true
            }
        }

        fun nonConsumingUppercaseDecision(): Boolean {
            return when (capsState) {
                CapsState.None -> simulatedCapsOn
                CapsState.Once -> simulatedCapsOn
                CapsState.Lock -> true
            }
        }

        fun transformTapLetter(code: String): String {
            if (!isLetter(code)) return code
            val lower = code.lowercase()
            return if (consumeUppercaseDecision()) lower.uppercase() else lower
        }

        fun transformDownLetter(code: String): String {
            if (!isLetter(code)) return code
            val lower = code.lowercase()
            val transformed = if (consumeUppercaseDecision()) {
                pendingUppercaseDown[lower] = (pendingUppercaseDown[lower] ?: 0) + 1
                lower.uppercase()
            } else {
                lower
            }
            return transformed
        }

        fun transformUpLetter(code: String): String {
            if (!isLetter(code)) return code
            val lower = code.lowercase()
            val pending = pendingUppercaseDown[lower] ?: 0
            return if (pending > 0) {
                if (pending == 1) pendingUppercaseDown.remove(lower) else pendingUppercaseDown[lower] = pending - 1
                lower.uppercase()
            } else {
                if (nonConsumingUppercaseDecision()) lower.uppercase() else lower
            }
        }

        fun transformShortcutKey(code: String): String {
            if (code.length != 1 || !code[0].isLetter()) return code
            val lower = code.lowercase()
            return if (consumeUppercaseDecision()) lower.uppercase() else lower
        }

        fun transformKeyRef(keyRef: KeyRef, step: MacroStep): KeyRef {
            return when (keyRef) {
                is KeyRef.Fcitx -> keyRef.copy(
                    code = when (step) {
                        is MacroStep.Down -> transformDownLetter(keyRef.code)
                        is MacroStep.Up -> transformUpLetter(keyRef.code)
                        is MacroStep.Tap -> transformTapLetter(keyRef.code)
                        else -> keyRef.code
                    }
                )
                is KeyRef.Android -> keyRef
            }
        }

        val transformedSteps = action.steps.map { step ->
            when (step) {
                is MacroStep.Down -> step.copy(keys = step.keys.map { transformKeyRef(it, step) })
                is MacroStep.Up -> step.copy(keys = step.keys.map { transformKeyRef(it, step) })
                is MacroStep.Tap -> step.copy(keys = step.keys.map { transformKeyRef(it, step) })
                is MacroStep.Shortcut -> step.copy(
                    modifiers = step.modifiers,
                    key = when (step.key) {
                        is KeyRef.Fcitx -> step.key.copy(code = transformShortcutKey(step.key.code))
                        is KeyRef.Android -> step.key
                    }
                )
                is MacroStep.Text, is MacroStep.Edit, is MacroStep.AppAction -> step
            }
        }

        if (consumeCapsOnce) {
            switchCapsState()
        }

        return action.copy(steps = transformedSteps)
    }

    private fun tryConsumeMacroCapsLock(): Boolean {
        val service = getService() ?: return false
        if (!service.isSimulatedCapsLockOnByMacroTap()) return false
        service.sendSimulatedCapsLockTapFromMacro()
        return true
    }

    private fun getService(): FcitxInputMethodService? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is FcitxInputMethodService) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return context as? FcitxInputMethodService
    }

    override fun onAttach() {
        ensureSpecialKeyViewsInitialized()
        capsState = if (getService()?.isVirtualShiftLockOn() == true) {
            CapsState.Lock
        } else {
            CapsState.None
        }
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerKeyboard(this)
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        spaceKeyLabelMode.registerOnChangeListener(spaceKeyLabelModeListener)
        punctuationPosition.registerOnChangeListener(altTextPositionListener)
        uppercasePosition.registerOnChangeListener(altTextPositionListener)
        refreshDynamicState()
    }

    override fun onDetachedFromWindow() {
        unregisterKeyboard(this)
        showLangSwitchKey.unregisterOnChangeListener(showLangSwitchKeyListener)
        spaceKeyLabelMode.unregisterOnChangeListener(spaceKeyLabelModeListener)
        punctuationPosition.unregisterOnChangeListener(altTextPositionListener)
        uppercasePosition.unregisterOnChangeListener(altTextPositionListener)
        super.onDetachedFromWindow()
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
        post { refreshAltTextLayouts() }
    }

    private fun updateSpaceLabel(ime: InputMethodEntry?, mode: SpaceKeyLabelMode = spaceKeyLabelMode.getValue()) {
        if (ime == null) return
        val subModeText = ime.subMode.run { label.ifEmpty { name.ifEmpty { "" } } }
        val newText = when (mode) {
            SpaceKeyLabelMode.Default -> {
                buildString {
                    append(ime.displayName)
                    if (subModeText.isNotEmpty()) append(" ($subModeText)")
                }
            }
            SpaceKeyLabelMode.CompactWhenSubMode -> {
                val imeText = if (subModeText.isNotEmpty()) ime.label.ifEmpty { ime.displayName } else ime.displayName
                val combined = if (subModeText.isNotEmpty()) "$imeText ($subModeText)" else imeText
                if (subModeText.isNotEmpty() && combined.length > 10) subModeText else combined
            }
            SpaceKeyLabelMode.SubModeOnly -> {
                if (subModeText.isNotEmpty()) subModeText else ime.displayName
            }
        }
        ensureSpecialKeyViewsInitialized()
        specialKeyViews.space.forEach { spaceKey ->
            spaceKey.mainText.text = newText
        }
    }

    private fun refreshDynamicState() {
        ensureSpecialKeyViewsInitialized()
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
        updateGboardStyleIcons()
        updateSpaceLabel(currentIme, spaceKeyLabelMode.getValue())
        post { refreshAltTextLayouts() }
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        currentIme = ime
        layoutState.ime = ime
        val signature = layoutSignature(ime)
        if (signature != lastLayoutSignature) {
            reloadLayout()
            lastLayoutSignature = signature
        }
        refreshDynamicState()
    }

    override fun onStyleRefreshFinished() {
        refreshDynamicState()
    }

    override fun onThemeUpdate(newTheme: Theme) {
        ensureSpecialKeyViewsInitialized()
        updateCapsButtonIcon()
        // Note: returnDrawable is managed by KeyboardWindow
    }

    override fun onCompositionStateChanged(composing: Boolean) {
        super.onCompositionStateChanged(composing)
        ensureSpecialKeyViewsInitialized()
        // Compose-state switches may recreate the Caps key view; restore every state-dependent
        // presentation, including the lock icon, after the hierarchy has been updated.
        refreshCapsPresentation()
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter()) {
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        } else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        val oldCapsState = capsState
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        val oldLocked = oldCapsState == CapsState.Lock
        val newLocked = capsState == CapsState.Lock
        if (oldLocked != newLocked) {
            getService()?.setVirtualShiftLockState(newLocked)
        }
        refreshCapsPresentation()
    }

    private fun refreshCapsPresentation() {
        updateCapsButtonIcon()
        updateAlphabetKeys()
        // 大写切换时同步刷新键面副字符（大写副字符显示）
        updatePunctuationKeys()
    }

    private fun clearLocalCapsState() {
        if (capsState == CapsState.None) return
        capsState = CapsState.None
        refreshCapsPresentation()
    }

    private fun updateCapsButtonIcon() {
        val displayLock = isDisplayCapsOn()
        specialKeyViews.caps.forEach { cap ->
            cap.img.apply {
                val fallback = when (capsState) {
                    CapsState.None -> if (displayLock) R.drawable.ic_capslock_lock else R.drawable.ic_capslock_none
                    CapsState.Once -> R.drawable.ic_capslock_once
                    CapsState.Lock -> R.drawable.ic_capslock_lock
                }
                val id = when (capsState) {
                    CapsState.None -> if (displayLock) KeyIconIds.CAPS_LOCK else KeyIconIds.CAPS_NONE
                    CapsState.Once -> KeyIconIds.CAPS_ONCE
                    CapsState.Lock -> KeyIconIds.CAPS_LOCK
                }
                KeyIconThemeStore.resolveDrawableObject(context, id, fallback)?.let {
                    setImageDrawable(it)
                }
            }
        }
    }

    private fun updateAlphabetKeys() {
        val displayUppercase = isDisplayCapsOn()
        textKeys.forEach { keyView ->
            val appearance = keyView.def
            when (appearance) {
                is KeyDef.Appearance.AltText -> {
                    keyView.mainText.text = resolveCapsAwareAltText(
                        appearance = appearance,
                        renderedText = keyView.mainText.text.toString(),
                        displayUppercase = displayUppercase,
                        keepLettersUppercase = keepLettersUppercase,
                        preserveConfiguredDisplayCase = preserveMacroDisplayCaseOverride,
                    )
                }
                is KeyDef.Appearance.Text -> {
                    val renderedText = keyView.mainText.text.toString()
                    val str = if (renderedText.isEmpty() || renderedText == appearance.displayText) {
                        appearance.displayText
                    } else {
                        renderedText
                    }
                    if (str.length == 1 && str[0].isLetter()) {
                        keyView.mainText.text = if (keepLettersUppercase) {
                            str.uppercase()
                        } else {
                            if (displayUppercase) str.uppercase() else str.lowercase()
                        }
                    }
                }
                else -> { /* Other appearance types: Image, ImageText - do nothing */ }
            }
        }
    }

    private fun updatePunctuationKeys() {
        val capsOn = isDisplayCapsOn()
        val swipeMap: SwipeSymbolMap = if (capsOn) SwipeSymbolMappingStore.current() else emptyMap()
        textKeys.forEach {
            if (it is AltTextKeyView) {
                val altDef = it.def as KeyDef.Appearance.AltText
                it.altText.text = resolveAltFaceText(altDef.altText, capsOn, swipeMap)
                // 副字符一：仅在未显示大写提示时（该槽位是真正的副字符一）才套用大写副字符；
                // 其余情况保持其原有显示（def.altText1），不改动非大写时的渲染。
                val alt1 = altDef.altText1
                if (!alt1.isNullOrEmpty()) {
                    val alt1Upper = if (capsOn && !it.isUppercaseHintActive()) {
                        swipeMap[alt1]?.upper?.takeIf { u -> u.isNotEmpty() }
                    } else {
                        null
                    }
                    it.altText1.text = alt1Upper ?: alt1
                }
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    val first = str.firstOrNull() ?: return@forEach
                    if (first.run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

    /**
     * 键面副字符（altText）文本：大写状态开启、且该副字符在「符号划动映射」中定义了
     * 大写列时，直接显示其大写副字符；否则按中英全角规则（[transformPunctuation]）显示。
     */
    private fun resolveAltFaceText(rawAlt: String, capsOn: Boolean, swipeMap: SwipeSymbolMap): String {
        if (capsOn) {
            val upper = swipeMap[rawAlt]?.upper
            if (!upper.isNullOrEmpty()) return upper
        }
        return transformPunctuation(rawAlt)
    }

}
