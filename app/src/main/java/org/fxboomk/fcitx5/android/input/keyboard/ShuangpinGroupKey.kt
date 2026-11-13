/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

/** A stable native group identity whose placement and displayed label can be customized. */
internal class ShuangpinGroupKey(
    val groupId: String,
    showSemicolon: Boolean = true,
    val punctuation: String = Shuangpin18Keys.defaultCorner(groupId),
    val punctuation1: String? = null,
    val configuredDisplayText: String? = null,
    weight: Float = 0.1f,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null,
    val longPress: MacroAction? = null,
    textColor: Int? = null,
    textColorMonet: String? = null,
    altTextColor: Int? = null,
    altTextColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
) : KeyDef(
    Appearance.AltText(
        displayText = configuredDisplayText ?: Shuangpin18Keys.displayGroup(groupId, showSemicolon),
        altText = if (swipeUpLabel != null || swipeDownLabel != null) swipeUpLabel.orEmpty() else punctuation,
        altText1 = if (swipeUpLabel != null || swipeDownLabel != null) swipeDownLabel else punctuation1,
        character = Shuangpin18Keys.displayGroup(groupId, showSemicolon),
        textSize = 23f,
        percentWidth = weight,
        isLetterGroup = true,
        supportsUppercaseHint = true,
        preserveConfiguredDisplayCase = configuredDisplayText != null,
        textColor = textColor,
        textColorMonet = textColorMonet,
        altTextColor = altTextColor,
        altTextColorMonet = altTextColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet,
    ),
    buildSet {
        add(Behavior.Press(Shuangpin18Keys.action(Shuangpin18Keys.groups.indexOf(groupId))))
        if (punctuation.isNotEmpty() || !punctuation1.isNullOrEmpty() || swipeUp != null || swipeDown != null) {
            add(Behavior.Swipe(
                action = punctuation.takeIf { it.isNotEmpty() }?.let(KeyAction::FcitxKeyAction),
                downAction = punctuation1?.takeIf { it.isNotEmpty() }?.let(KeyAction::FcitxKeyAction),
                upMacro = swipeUp,
                downMacro = swipeDown,
            ))
        }
        longPress?.let { add(Behavior.LongPress(it)) }
    },
    buildList {
        add(Popup.AltPreview(
            configuredDisplayText ?: Shuangpin18Keys.displayGroup(groupId, showSemicolon),
            punctuation,
            punctuation1,
        ))
        if (longPress == null) {
            add(Popup.Keyboard.GroupedPreset(
                Shuangpin18Keys.displayGroup(groupId, showSemicolon), punctuation
            ))
        }
    }.toTypedArray(),
) {
    init {
        appearance.directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
    }
}
