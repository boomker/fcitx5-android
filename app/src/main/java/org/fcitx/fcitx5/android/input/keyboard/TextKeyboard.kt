/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import java.lang.ref.WeakReference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import splitties.views.imageResource
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

object DisplayTextResolver {
    fun resolve(
        displayText: JsonElement?,
        subModeLabel: String,
        subModeName: String,
        default: String
    ): String {
        return when {
            displayText == null -> default
            displayText is JsonPrimitive -> displayText.content
            displayText is JsonObject -> resolveMap(displayText, subModeLabel, subModeName) ?: default
            else -> default
        }
    }

    private fun resolveMap(
        map: JsonObject,
        subModeLabel: String,
        subModeName: String
    ): String? {
        // Prioritize matching sub-mode label, then name, finally fall back to empty key
        return map[subModeLabel]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
            ?: map[subModeName]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
            ?: map[""]?.takeIf { it is JsonPrimitive && it !is JsonNull }?.jsonPrimitive?.content
    }
}

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme
) : BaseKeyboard(context, theme, ::getLayout) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"
        private var lastModified = 0L
        var ime: InputMethodEntry? = null
        private var listenerRegistered = false
        private val attachedKeyboards = mutableListOf<WeakReference<TextKeyboard>>()

        @Synchronized
        private fun ensureListenerRegistered() {
            if (listenerRegistered) return
            org.fcitx.fcitx5.android.input.config.ConfigProviders.addTextKeyboardLayoutListener {
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
                ime?.let { keyboard.updateSpaceLabel(it) }
            }
        }

        @Serializable
        data class KeyJson(
            val type: String,
            val main: String? = null,
            val alt: String? = null,
            val displayText: JsonElement? = null,
            val label: String? = null,
            val subLabel: String? = null,
            val weight: Float? = null
        )

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
                val snapshot = org.fcitx.fcitx5.android.input.config.ConfigProviders
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

        private fun parseKeyJsonArray(rowArray: JsonArray): List<KeyJson> {
            return rowArray.map { it.jsonObject.let { obj ->
                KeyJson(
                    type = obj["type"]?.jsonPrimitive?.content ?: "",
                    main = obj["main"]?.jsonPrimitive?.content,
                    alt = obj["alt"]?.jsonPrimitive?.content,
                    displayText = obj["displayText"],
                    label = obj["label"]?.jsonPrimitive?.content,
                    subLabel = obj["subLabel"]?.jsonPrimitive?.content,
                    weight = obj["weight"]?.jsonPrimitive?.float
                )
            } }
        }

        private fun createKeyDef(key: KeyJson): KeyDef {
            return when (key.type) {
                "AlphabetKey" -> AlphabetKey(
                    character = key.main ?: "",
                    punctuation = key.alt ?: "",
                    displayText = DisplayTextResolver.resolve(
                        key.displayText,
                        ime?.subMode?.label ?: "",
                        ime?.subMode?.name ?: "",
                        key.main ?: ""
                    ),
                    weight = key.weight
                )
                "CapsKey" -> CapsKey(
                    percentWidth = key.weight ?: 0.15f
                )
                "LayoutSwitchKey" -> LayoutSwitchKey(
                    displayText = key.label ?: "?123",
                    to = key.subLabel ?: "",
                    percentWidth = key.weight ?: 0.15f
                )
                "CommaKey" -> CommaKey(
                    percentWidth = key.weight ?: 0.1f,
                    variant = KeyDef.Appearance.Variant.Alternative
                )
                "LanguageKey" -> LanguageKey(
                    percentWidth = key.weight ?: 0.1f
                )
                "SpaceKey" -> SpaceKey(
                    percentWidth = key.weight ?: 0f
                )
                "SymbolKey" -> SymbolKey(
                    symbol = key.label ?: ".",
                    percentWidth = key.weight ?: 0.1f,
                    variant = KeyDef.Appearance.Variant.Alternative
                )
                "ReturnKey" -> ReturnKey(
                    percentWidth = key.weight ?: 0.15f
                )
                "BackspaceKey" -> BackspaceKey(
                    percentWidth = key.weight ?: 0.15f
                )
                else -> SpaceKey() // Fallback
            }
        }

        fun getLayout(): List<List<KeyDef>> {
            val imeName = ime?.uniqueName
            val subModeLabel = ime?.subMode?.label ?: ""
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
                            // Use a cache key that includes submode for proper caching
                            // For both old format (JsonArray) and new format (JsonObject),
                            // include submode in cache key so displayText is re-resolved when submode changes
                            val cacheKey = "$layoutKey:$subModeLabel"
                            return cachedKeyDefLayouts.getOrPut(cacheKey) {
                                subModeLayoutElement.map { rowElement ->
                                    parseKeyJsonArray(rowElement.jsonArray)
                                        .map { createKeyDef(it) }
                                }
                            }
                        }
                    }
                    
                    // Fallback to global "default" layout
                    json["default"]?.let { layoutElement ->
                        if (layoutElement is JsonArray) {
                            return cachedKeyDefLayouts.getOrPut("default") {
                                layoutElement.map { rowElement ->
                                    parseKeyJsonArray(rowElement.jsonArray)
                                        .map { createKeyDef(it) }
                                }
                            }
                        }
                    }
                }
            }
            return DefaultLayout
        }

        val DefaultLayout: List<List<KeyDef>> = listOf(
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
                LanguageKey(),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
                ReturnKey()
            )
        )
    }

    val caps: ImageKeyView?
        get() = findViewById(R.id.button_caps)
    val backspace: ImageKeyView?
        get() = findViewById(R.id.button_backspace)
    val quickphrase: ImageKeyView?
        get() = findViewById(R.id.button_quickphrase)
    val lang: ImageKeyView?
        get() = findViewById(R.id.button_lang)
    val space: TextKeyView?
        get() = findViewById(R.id.button_space)
    val `return`: ImageKeyView?
        get() = findViewById(R.id.button_return)

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    init {
    }

    private val textKeys: List<TextKeyView>
        get() = allViews.filterIsInstance(TextKeyView::class.java).toList()

    private var capsState: CapsState = CapsState.None

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
                            transformed = action.copy(act = action.act.lowercase())
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
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerKeyboard(this)
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
    }

    override fun onDetachedFromWindow() {
        unregisterKeyboard(this)
        showLangSwitchKey.unregisterOnChangeListener(showLangSwitchKeyListener)
        super.onDetachedFromWindow()
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`?.img?.imageResource = returnDrawable
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    private fun updateSpaceLabel(ime: InputMethodEntry?) {
        if (ime == null) return
        space?.mainText?.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        // update ime of companion object ime
        TextKeyboard.ime = ime
        val signature = layoutSignature(ime)
        if (signature != lastLayoutSignature) {
            reloadLayout()
            lastLayoutSignature = signature
        }
        updateAlphabetKeys()
        updateSpaceLabel(ime)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    override fun onStyleRefreshFinished() {
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
        updateSpaceLabel(TextKeyboard.ime)
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
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps?.img?.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            val keyDef = it.def
            if (keyDef is KeyDef.Appearance.AltText) {
                it.mainText.text = if (keepLettersUppercase) {
                    keyDef.character.uppercase()
                } else {
                    when (capsState) {
                        CapsState.None -> keyDef.displayText.lowercase()
                        else -> keyDef.character.uppercase()
                    }
                }
            } else if (keyDef is KeyDef.Appearance.Text) {
                // handle other text keys if necessary, but mainly AlphabetKey is AltText
                val str = keyDef.displayText
                if (str.length == 1 && str[0].isLetter()) {
                     it.mainText.text = if (keepLettersUppercase) {
                        str.uppercase()
                    } else {
                        when (capsState) {
                            CapsState.None -> str.lowercase()
                            else -> str.uppercase()
                        }
                    }
                }
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
                    if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}
