/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.core

import kotlinx.coroutines.flow.SharedFlow

/**
 * API of fcitx that hides lifecycle stuffs from [Fcitx]
 *
 * Functions can be safely used in any coroutine,
 * as the underlying operation is always dispatched in fcitx thread.
 */
interface FcitxAPI {

    enum class AddonDep {
        Required,
        Optional
    }

    /**
     * Subscribe this flow to receive event sent from fcitx
     */
    val eventFlow: SharedFlow<FcitxEvent<*>>

    val isReady: Boolean

    val inputMethodEntryCached: InputMethodEntry

    val statusAreaActionsCached: Array<Action>

    val clientPreeditCached: FormattedText

    val inputPanelCached: FcitxEvent.InputPanelEvent.Data

    fun setLogRule(verbose: Boolean)

    fun getAddonReverseDependencies(addon: String): List<Pair<String, AddonDep>>

    fun translate(str: String, domain: String = "fcitx5"): String

    suspend fun save()

    /**
     * Save the input method profile and non-Rime addon state.
     *
     * Use this for paths that run implicitly (e.g. settings activity stopped),
     * so leaving them does not trigger Rime's session release and user-data
     * synchronization. Full [save] remains for shutdown and export paths that
     * explicitly need to persist every addon.
     */
    suspend fun saveNonRimeState()

    suspend fun reloadConfig()

    /**
     * Re-decorate the cached current input method entry and re-emit
     * [FcitxEvent.IMChangeEvent].
     *
     * Needed after saving settings that rename the active input method
     * (e.g. the custom shuangpin scheme name), because the native side keeps
     * the static entry name until the next event arrives.
     */
    suspend fun refreshImEntry()

    suspend fun sendKey(key: String, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(c: Char, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: Int, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun select(idx: Int): Boolean
    /** Synchronize the visible highlight before key dispatch, without selecting or learning it. */
    suspend fun setCandidateCursor(idx: Int): Boolean
    /** Select only from the exact prediction page that was visible when the key was pressed. */
    suspend fun selectPrediction(idx: Int, expected: FcitxEvent.PagedCandidateEvent.Data): Boolean
    suspend fun isEmpty(): Boolean
    suspend fun reset()
    suspend fun moveCursor(position: Int)

    suspend fun availableIme(): Array<InputMethodEntry>
    suspend fun enabledIme(): Array<InputMethodEntry>

    suspend fun setEnabledIme(array: Array<String>)

    suspend fun toggleIme()
    suspend fun activateIme(ime: String)
    suspend fun enumerateIme(forward: Boolean = true)

    suspend fun currentIme(): InputMethodEntry

    suspend fun getGlobalConfig(): RawConfig

    suspend fun setGlobalConfig(config: RawConfig)

    suspend fun getAddonConfig(addon: String): RawConfig

    suspend fun setAddonConfig(addon: String, config: RawConfig)

    suspend fun getAddonSubConfig(addon: String, path: String): RawConfig

    suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig = RawConfig())

    suspend fun getImConfig(key: String): RawConfig

    suspend fun setImConfig(key: String, config: RawConfig)

    suspend fun addons(): Array<AddonInfo>
    suspend fun setAddonState(name: Array<String>, state: BooleanArray)

    suspend fun triggerQuickPhrase()
    suspend fun triggerUnicode()

    suspend fun focus(focus: Boolean = true)
    suspend fun focusOutIn()
    suspend fun activate(uid: Int, pkgName: String)
    suspend fun deactivate(uid: Int)
    suspend fun setCapFlags(flags: CapabilityFlags)

    suspend fun statusArea(): Array<Action>

    suspend fun activateAction(id: Int)

    /** Registered Rime schemas, without switching the current input method. */
    suspend fun rimeSchemaActions(): Array<Action>

    /**
     * Activate a still-valid action from [rimeSchemaActions], switching to Rime.
     * An installed, available Rime engine is appended to the current group if
     * needed. Disabled addons remain disabled.
     */
    suspend fun activateRimeSchemaAction(id: Int): Boolean

    suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord>

    /** Expanded presentation after the actual horizontal row, with original engine indices. */
    suspend fun getExpandedCandidates(
        offset: Int,
        limit: Int,
        firstHiddenIndex: Int,
        excludedIndices: IntArray = intArrayOf(),
    ): Array<CandidateWord> = getCandidates(offset, limit)

    /** Probe a current native presentation without changing candidate order or selection. */
    suspend fun getCandidateViewportSnapshot(limit: Int): CandidateViewportSnapshot =
        CandidateViewportSnapshot.Empty

    /**
     * Apply a first-page layout only while [revision] still belongs to the active input context.
     * Size zero and symbol index -2 restore the configured floating-page policy.
     */
    suspend fun setCandidateViewportIfCurrent(
        revision: Long,
        firstPageSize: Int,
        firstPageIndices: IntArray,
        firstSymbolIndex: Int,
    ): Boolean = false

    suspend fun getCandidateActions(idx: Int): Array<CandidateAction>
    suspend fun triggerCandidateAction(idx: Int, actionIdx: Int, isOnFirstPage: Boolean? = null)
    /** Null means the displayed candidate is no longer current; an empty array is a valid candidate. */
    suspend fun getCandidateActionsIfCurrent(idx: Int, candidateText: String): Array<CandidateAction>?
    /** Check the current candidate and action together before committing a held popup choice. */
    suspend fun triggerCandidateActionIfCurrent(
        idx: Int,
        candidateText: String,
        actionId: Int,
        actionText: String,
        actionIcon: String,
    ): Boolean

    suspend fun setCandidatePagingMode(mode: Int)
    suspend fun offsetCandidatePage(delta: Int)

    suspend fun triggerCandidateListTabAction(id: Int)

}
