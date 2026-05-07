/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.bar

internal fun clipboardSuggestionTimeoutMillis(timeoutSeconds: Int): Long {
    return timeoutSeconds * 1000L
}
