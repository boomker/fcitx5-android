/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.preedit

private val syllablePattern = Regex("[a-zü:;]+|\\p{IsHan}", RegexOption.IGNORE_CASE)

/** Native preedit separates syllables with spaces/apostrophes; selected Han characters count too. */
internal fun preeditSyllableCount(preedit: String): Int = syllablePattern.findAll(preedit).count()
