/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.prefs

import androidx.annotation.Keep
import androidx.annotation.MainThread
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.core.FcitxAPI
import org.fxboomk.fcitx5.android.core.InputMethodEntry
import org.fxboomk.fcitx5.android.data.theme.ThemeManager
import timber.log.Timber

@Serializable
internal data class CandidateBarHeightProfile(
    val adaptive: Boolean,
    val manualHeightDp: Int,
)

@Serializable
internal data class SchemeCandidateBarHeightProfiles(
    val right: CandidateBarHeightProfile,
    val top: CandidateBarHeightProfile? = null,
    val topPlacement: Boolean = false,
    val annotationEnabled: Boolean = false,
) {
    val annotationOnTop: Boolean
        get() = topPlacement && annotationEnabled

    val selected: CandidateBarHeightProfile
        get() = if (annotationOnTop) top ?: right.copy(adaptive = true) else right
}

/** The baseline is frozen before applying a scheme so new schemes never inherit another scheme. */
@Serializable
internal data class CandidateBarHeightProfiles(
    val baseline: CandidateBarHeightProfile,
    val schemes: Map<String, SchemeCandidateBarHeightProfiles> = emptyMap(),
) {
    fun scheme(key: String): SchemeCandidateBarHeightProfiles =
        schemes[key] ?: SchemeCandidateBarHeightProfiles(right = baseline)

    fun select(key: String, topPlacement: Boolean, annotationEnabled: Boolean): CandidateBarHeightProfiles {
        val previous = scheme(key)
        val selected = previous.copy(
            top = if (topPlacement && annotationEnabled) {
                previous.top ?: previous.right.copy(adaptive = true)
            } else previous.top,
            topPlacement = topPlacement,
            annotationEnabled = annotationEnabled,
        )
        return copy(schemes = schemes + (key to selected))
    }

    fun update(key: String, profile: CandidateBarHeightProfile): CandidateBarHeightProfiles {
        val previous = scheme(key)
        val updated = if (previous.annotationOnTop) previous.copy(top = profile) else previous.copy(right = profile)
        return copy(schemes = schemes + (key to updated))
    }
}

/**
 * Keeps the existing height preferences as the active scheme's UI values. Calculated heights
 * are deliberately absent here: disabling adaptation restores the saved manual value.
 */
object CandidateBarHeightSettings {
    private val json = Json { ignoreUnknownKeys = true }
    private var initialized = false
    private var applying = false
    private var activeEntry: InputMethodEntry? = null
    private var refreshGeneration = 0L
    private var cachedStorage: String? = null
    private var cachedProfiles: CandidateBarHeightProfiles? = null

    @Keep
    private val onProxyChange = ManagedPreference.OnChangeListener<Any> { _, _ ->
        if (!applying) captureCurrentProfile()
    }

    val annotationOnTop: Boolean
        get() = activeEntry?.let { entry ->
            profiles().scheme(scopeKey(entry)).annotationOnTop
        } ?: false

    internal fun scopeKey(entry: InputMethodEntry): String = json.encodeToString(
        listOf(entry.uniqueName, entry.subMode.name.takeIf { entry.addon == "rime" }.orEmpty())
    )

    private fun currentProxy() = CandidateBarHeightProfile(
        AppPrefs.getInstance().keyboard.toolbarDynamicHeight.getValue(),
        ThemeManager.prefs.toolbarHeight.getValue(),
    )

    private fun profiles(): CandidateBarHeightProfiles {
        val raw = AppPrefs.getInstance().internal.candidateBarHeightProfiles.getValue()
        if (raw == cachedStorage) cachedProfiles?.let { return it }
        val decoded = if (raw.isEmpty()) null else runCatching {
            json.decodeFromString<CandidateBarHeightProfiles>(raw)
        }.onFailure { Timber.w(it, "Cannot read candidate height profiles") }.getOrNull()
        return (decoded ?: CandidateBarHeightProfiles(currentProxy())).also {
            cachedStorage = raw
            cachedProfiles = it
        }
    }

    private fun persist(state: CandidateBarHeightProfiles) {
        val encoded = json.encodeToString(state)
        cachedStorage = encoded
        cachedProfiles = state
        val storage = AppPrefs.getInstance().internal.candidateBarHeightProfiles
        if (storage.getValue() != encoded) storage.setValue(encoded)
    }

    private fun initialize() {
        if (initialized) return
        persist(profiles())
        AppPrefs.getInstance().keyboard.toolbarDynamicHeight.registerOnChangeListener(onProxyChange)
        ThemeManager.prefs.toolbarHeight.registerOnChangeListener(onProxyChange)
        initialized = true
    }

    private fun captureCurrentProfile() {
        val state = profiles()
        val profile = currentProxy()
        val entry = activeEntry
        if (entry == null) {
            if (state.schemes.isEmpty() && state.baseline != profile) persist(state.copy(baseline = profile))
        } else if (state.scheme(scopeKey(entry)).selected != profile) {
            persist(state.update(scopeKey(entry), profile))
        }
    }

    /** Apply the persisted placement immediately, before awaiting the engine's current configuration. */
    @MainThread
    fun apply(entry: InputMethodEntry) {
        initialize()
        refreshGeneration++
        if (entry.uniqueName.isEmpty()) return
        captureCurrentProfile()
        val previousAnnotationOnTop = annotationOnTop
        activeEntry = entry
        val state = profiles()
        val key = scopeKey(entry)
        val ensured = if (key in state.schemes) state else state.select(key, false, false)
        persist(ensured)
        applyProxy(ensured.scheme(key).selected, previousAnnotationOnTop)
    }

    /**
     * Resolve the native per-input-method configuration once at activation/configuration time.
     * Both the request generation and current identity are checked after the suspended read.
     */
    suspend fun refresh(fcitx: FcitxAPI) {
        val (entry, generation) = withContext(Dispatchers.Main.immediate) {
            val entry = fcitx.inputMethodEntryCached
            apply(entry)
            entry to refreshGeneration
        }
        if (entry.uniqueName.isEmpty()) return
        val config = try {
            if (entry.addon == "pinyin") fcitx.getImConfig(entry.uniqueName).findByName("cfg") else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Cannot refresh candidate height for %s", entry.uniqueName)
            return
        }
        withContext(Dispatchers.Main.immediate) {
            if (generation != refreshGeneration ||
                scopeKey(fcitx.inputMethodEntryCached) != scopeKey(entry) ||
                activeEntry?.let(::scopeKey) != scopeKey(entry)
            ) return@withContext
            // An unavailable engine configuration must not erase a remembered placement.
            if (entry.addon == "pinyin" && config == null) return@withContext
            val previousAnnotationOnTop = annotationOnTop
            val state = profiles().select(
                scopeKey(entry),
                topPlacement = config?.findByName("PinyinAnnotationPlacement")?.value == "Top",
                annotationEnabled = config?.findByName("PinyinAnnotation")?.value?.let { it != "Do not show" } == true,
            )
            persist(state)
            applyProxy(state.scheme(scopeKey(entry)).selected, previousAnnotationOnTop)
        }
    }

    private fun applyProxy(profile: CandidateBarHeightProfile, previousAnnotationOnTop: Boolean) {
        val keyboard = AppPrefs.getInstance().keyboard
        val adaptive = keyboard.toolbarDynamicHeight
        val manualHeight = ThemeManager.prefs.toolbarHeight
        val adaptiveChanged = adaptive.getValue() != profile.adaptive
        applying = true
        try {
            adaptive.sharedPreferences.edit {
                if (adaptiveChanged) putBoolean(adaptive.key, profile.adaptive)
                if (manualHeight.getValue() != profile.manualHeightDp) putInt(manualHeight.key, profile.manualHeightDp)
            }
            if (!adaptiveChanged && previousAnnotationOnTop != annotationOnTop) {
                keyboard.fireChange(adaptive.key)
            }
        } finally {
            applying = false
        }
    }
}

