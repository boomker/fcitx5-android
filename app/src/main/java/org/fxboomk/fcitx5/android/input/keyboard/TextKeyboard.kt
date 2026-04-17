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
import org.fxboomk.fcitx5.android.core.KeyState
import org.fxboomk.fcitx5.android.core.KeyStates
import org.fxboomk.fcitx5.android.input.FcitxInputMethodService
import org.fxboomk.fcitx5.android.data.prefs.AppPrefs
import org.fxboomk.fcitx5.android.data.prefs.ManagedPreference
import org.fxboomk.fcitx5.android.data.theme.Theme
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import org.fxboomk.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fxboomk.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import org.fxboomk.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, ::getLayout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"
        private var lastModified = 0L
        internal var ime: InputMethodEntry? = null
        private var listenerRegistered = false
        private val attachedKeyboards = mutableListOf<WeakReference<TextKeyboard>>()

        @Synchronized
        private fun ensureListenerRegistered() {
            if (listenerRegistered) return
            org.fxboomk.fcitx5.android.input.config.ConfigProviders.addTextKeyboardLayoutListener {
                onTextLayoutFileChanged()
            }
            listenerRegistered = true
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
        private var lastRawModified = 0L

        // Compatibility alias for cachedRawLayoutJson (used by SplitKeyboardCalibrationActivity)
        @JvmStatic
        var cachedLayoutJsonMap: JsonObject?
            get() = cachedRawLayoutJson
            set(value) {
                cachedRawLayoutJson = value
            }

        // Cache for parsed KeyDef layouts to avoid recreating them on every reloadLayout()
        private val cachedKeyDefLayouts = mutableMapOf<String, List<List<KeyDef>>>()
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
                val snapshot = org.fxboomk.fcitx5.android.input.config.ConfigProviders
                    .readTextKeyboardLayout<JsonObject>() ?: run {
                    cachedRawLayoutJson = null
                    return null
                }
                if (cachedRawLayoutJson == null || snapshot.lastModified != lastRawModified) {
                    lastRawModified = snapshot.lastModified
                    cachedRawLayoutJson = snapshot.value
                    // Invalidate KeyDef cache when JSON changes
                    lastLayoutCacheInvalidated = snapshot.lastModified
                    cachedKeyDefLayouts.clear()
                }
                return cachedRawLayoutJson
            }

        private fun getTextLayoutJsonForIme(displayName: String): JsonArray? {
            val json = textLayoutJson ?: return null
            return json[displayName]?.jsonArray
        }

        fun getLayout(): List<List<KeyDef>> {
            val imeName = ime?.uniqueName
            val subModeLabel = ime?.subMode?.label ?: ""
            val showLangSwitch = AppPrefs.getInstance().keyboard.showLangSwitchKey.getValue()
            if (imeName != null) {
                val json = textLayoutJson
                if (json != null) {
                    // Try uniqueName first, then displayName
                    val layoutKey = imeName
                    val imeLayoutElement = json[layoutKey]
                        ?: json[ime?.displayName]

                    if (imeLayoutElement != null) {
                        // Check if this is a submode structure (JsonObject) or direct layout (JsonArray)
                        val subModeLayoutElement = if (imeLayoutElement is JsonObject) {
                            // Submode structure: try submode label, then "default", then empty string
                            imeLayoutElement[subModeLabel]
                                ?: imeLayoutElement["default"]
                                ?: imeLayoutElement[""]
                        } else {
                            // Direct layout array, use as-is
                            imeLayoutElement
                        }

                        if (subModeLayoutElement is JsonArray) {
                            // Use a cache key that includes submode and showLangSwitch for proper caching
                            // Include showLangSwitch in cache key so layout is re-created when setting changes
                            val cacheKey = "$layoutKey:$subModeLabel:$showLangSwitch"
                            return cachedKeyDefLayouts.getOrPut(cacheKey) {
                                subModeLayoutElement.map { rowElement ->
                                    LayoutJsonUtils.parseKeyJsonArray(rowElement.jsonArray, showLangSwitch)
                                        .map { LayoutJsonUtils.createKeyDef(it, subModeLabel, ime?.subMode?.name ?: "") }
                                }
                            }
                        }
                    }

                    // Fallback to global "default" layout
                    json["default"]?.let { layoutElement ->
                        if (layoutElement is JsonArray) {
                            return cachedKeyDefLayouts.getOrPut("default:$showLangSwitch") {
                                layoutElement.map { rowElement ->
                                    LayoutJsonUtils.parseKeyJsonArray(rowElement.jsonArray, showLangSwitch)
                                        .map { LayoutJsonUtils.createKeyDef(it) }
                                }
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
        val caps: List<ImageKeyView>,
        val backspace: List<ImageKeyView>,
        val quickphrase: List<ImageKeyView>,
        val space: List<TextKeyView>,
        val `return`: List<ImageKeyView>
    )

    private fun findAllSpecialKeyViews(): SpecialKeyViews {
        val caps = mutableListOf<ImageKeyView>()
        val backspace = mutableListOf<ImageKeyView>()
        val quickphrase = mutableListOf<ImageKeyView>()
        val space = mutableListOf<TextKeyView>()
        val returnKeys = mutableListOf<ImageKeyView>()

        allViews.forEach { view ->
            when (view.tag) {
                R.id.button_caps -> (view as? ImageKeyView)?.let(caps::add)
                R.id.button_backspace -> (view as? ImageKeyView)?.let(backspace::add)
                R.id.button_quickphrase -> (view as? ImageKeyView)?.let(quickphrase::add)
                R.id.button_space -> (view as? TextKeyView)?.let(space::add)
                R.id.button_return -> (view as? ImageKeyView)?.let(returnKeys::add)
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
    private var currentIme: InputMethodEntry? = null

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
    private val punctuationPositionListener = ManagedPreference.OnChangeListener<PunctuationPosition> { _, _ ->
        post { refreshAltTextLayouts() }
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
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
        val layoutSource = when {
            json?.containsKey(ime.uniqueName) == true -> "u:${ime.uniqueName}"
            json?.containsKey(ime.displayName) == true -> "d:${ime.displayName}"
            else -> "default"
        }
        val subModeLabel = ime.subMode.run { label.ifEmpty { name.ifEmpty { "" } } }
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
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
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
                    switchCapsState(action.lock)
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
                is MacroStep.Text, is MacroStep.Edit -> step
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
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerKeyboard(this)
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        spaceKeyLabelMode.registerOnChangeListener(spaceKeyLabelModeListener)
        punctuationPosition.registerOnChangeListener(punctuationPositionListener)
        refreshDynamicState()
    }

    override fun onDetachedFromWindow() {
        unregisterKeyboard(this)
        showLangSwitchKey.unregisterOnChangeListener(showLangSwitchKeyListener)
        spaceKeyLabelMode.unregisterOnChangeListener(spaceKeyLabelModeListener)
        punctuationPosition.unregisterOnChangeListener(punctuationPositionListener)
        super.onDetachedFromWindow()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        specialKeyViews.`return`.forEach { returnKey ->
            returnKey.img.imageResource = returnDrawable
        }
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
        updateSpaceLabel(currentIme, spaceKeyLabelMode.getValue())
        post { refreshAltTextLayouts() }
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        currentIme = ime
        TextKeyboard.ime = ime
        val signature = layoutSignature(ime)
        if (signature != lastLayoutSignature) {
            reloadLayout()
            lastLayoutSignature = signature
        }
        refreshDynamicState()
        if (capsState != CapsState.None) {
            switchCapsState()
        }
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
                val label = action.keyboard.label
                if (label.length == 1 && label[0].isLetter())
                    action.copy(keyboard = KeyDef.Popup.Keyboard(transformAlphabet(label)))
                else action
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
            getService()?.setVirtualCapsLockState(newLocked)
        }
        refreshCapsPresentation()
    }

    private fun refreshCapsPresentation() {
        updateCapsButtonIcon()
        updateAlphabetKeys()
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
                imageResource = when (capsState) {
                    CapsState.None -> if (displayLock) R.drawable.ic_capslock_lock else R.drawable.ic_capslock_none
                    CapsState.Once -> R.drawable.ic_capslock_once
                    CapsState.Lock -> R.drawable.ic_capslock_lock
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
                    keyView.mainText.text = if (keepLettersUppercase) {
                        appearance.character.uppercase()
                    } else {
                        if (displayUppercase) appearance.character.uppercase() else appearance.displayText.lowercase()
                    }
                }
                is KeyDef.Appearance.Text -> {
                    val str = appearance.displayText
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
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
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

}
