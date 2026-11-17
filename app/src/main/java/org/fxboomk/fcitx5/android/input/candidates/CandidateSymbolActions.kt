/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.candidates

import org.fxboomk.fcitx5.android.core.CandidateAction

/** The native pinyin engine marks the alternatives of a folded symbol run. */
internal fun Array<CandidateAction>.foldedSymbolActions(): List<CandidateAction> =
    filter { !it.isSeparator && it.icon == "folded-symbol" && it.text.isNotBlank() }
