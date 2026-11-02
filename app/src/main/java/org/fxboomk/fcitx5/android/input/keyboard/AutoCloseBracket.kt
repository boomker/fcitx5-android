/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

/**
 * 「自动补全成对符号」的配对表与查询工具（纯函数，便于单元测试）。
 *
 * 覆盖英文半角与中文全角两类成对符号。无论符号来自划动上屏、长按字母键弹窗选择，
 * 还是其它提交路径，只要最终提交的文本恰好是某个「左半边」，就补全其「右半边」；
 * 删除「左半边」且其右侧紧邻对应「右半边」时，一并删除。
 */
object AutoCloseBracket {

    /**
     * 左半边 -> 右半边。键与值均为单字符字符串。
     *
     * 覆盖输入法内置符号表（见 `bundled/symbols/symbols.json` 的 Brackets 括号类目
     * 与引号 / 书名号类目）里的全部成对符号，另附白色方头括号 〖〗 作补充。
     */
    val PAIRS: Map<String, String> = linkedMapOf(
        // 英文半角
        "(" to ")",
        "[" to "]",
        "{" to "}",
        "<" to ">",
        "\"" to "\"", // 直双引号（左右同形）
        "'" to "'",   // 直单引号（左右同形）
        "`" to "`",
        // 全角 ASCII
        "（" to "）",
        "［" to "］",
        "｛" to "｝",
        "＜" to "＞",
        // 竖排 / 小型变体括号
        "﹙" to "﹚",
        "﹝" to "﹞",
        // 数学与装饰性括号
        "⦃" to "⦄",
        "⦑" to "⦒",
        "⦓" to "⦔",
        "⦕" to "⦖",
        "⦇" to "⦈",
        "⁅" to "⁆",
        "⸦" to "⸧",
        "⸨" to "⸩",
        "⎰" to "⎱",
        "⊏" to "⊐",
        "⊑" to "⊒",
        "⟦" to "⟧",
        // CJK 括号 / 书名号 / 方头括号
        "〈" to "〉",
        "《" to "》",
        "「" to "」",
        "『" to "』",
        "【" to "】",
        "〔" to "〕",
        "〖" to "〗",
        "〘" to "〙",
        // CJK 引号
        "“" to "”", // U+201C / U+201D
        "‘" to "’", // U+2018 / U+2019
        // 装饰性括号 / 引号（U+2768..U+275E）
        "❨" to "❩",
        "❲" to "❳",
        "❴" to "❵",
        "❛" to "❜",
        "❝" to "❞",
        // 单 / 双尖角引号（guillemets）
        "‹" to "›",
        "«" to "»",
        // 竖排用括号表现形（U+FE35..U+FE42）
        "︵" to "︶",
        "︷" to "︸",
        "︹" to "︺",
        "︻" to "︼",
        "︽" to "︾",
        "︿" to "﹀",
        "﹁" to "﹂",
    )

    /** 若 [text] 恰好是一个已知「左半边」符号，返回应补全的「右半边」，否则返回 null。 */
    fun closerFor(text: String): String? = PAIRS[text]

    /**
     * 根据提交文本 [text] 与 Fcitx 上报的光标位置 [cursor] 判断是否应自动补全。
     *
     * [cursor] 为 -1 表示「提交后光标位于文本末尾」（普通 commitString）。Fcitx 的
     * `CommitStringWithCursor` 在上屏标点时会带上显式光标：默认配置下只提交「左半边」，
     * 光标正好等于 [text] 的长度；这种「光标在末尾」的情况与 -1 等价，同样应补全。
     * 若光标落在文本中间（例如引擎已把成对符号一起提交），则不应再补，避免重复。
     *
     * 注意：Fcitx 的 cursor 按码点计数，而此处 [text] 的长度按 UTF-16 计；配对表只含 BMP
     * 单字符，二者一致，因此可直接比较。
     */
    fun closerForCommit(text: String, cursor: Int): String? =
        if (cursor == -1 || cursor == text.length) closerFor(text) else null

    /** [opener] 与紧随其后的 [closer] 是否构成一对可自动闭合的符号。 */
    fun isPair(opener: Char, closer: Char): Boolean =
        PAIRS[opener.toString()] == closer.toString()
}
