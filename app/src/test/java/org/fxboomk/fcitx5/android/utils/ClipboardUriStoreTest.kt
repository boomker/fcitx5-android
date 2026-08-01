package org.fxboomk.fcitx5.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardUriStoreTest {

    @Test
    fun preservesUnicodeFileNames() {
        assertEquals("中文文件.txt", ClipboardUriStore.sanitizeFileName("中文文件.txt"))
        assertEquals("剪贴板图片.png", ClipboardUriStore.sanitizeFileName("剪贴板图片.png"))
        assertEquals("报告 2026-08.json", ClipboardUriStore.sanitizeFileName("报告 2026-08.json"))
    }

    @Test
    fun replacesPathAndControlCharacters() {
        assertEquals("目录_逃逸.txt", ClipboardUriStore.sanitizeFileName("目录/逃逸.txt"))
        assertEquals("a_b.txt", ClipboardUriStore.sanitizeFileName("a\u0000b.txt"))
        assertEquals("a_b.txt", ClipboardUriStore.sanitizeFileName("a\\b.txt"))
    }

    @Test
    fun replacesUnsafeSpecialNames() {
        assertEquals("clipboard", ClipboardUriStore.sanitizeFileName("."))
        assertEquals("clipboard", ClipboardUriStore.sanitizeFileName(".."))
    }
}
