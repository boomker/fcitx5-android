/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTextModeSupportTest {
    @Test fun englishAndIndividualShuangpinLayoutsDoNotOfferModes() {
        assertEquals(false, displayTextModeSupportOverride("default", "keyboard-us"))
        assertEquals(false, displayTextModeSupportOverride("keyboard-us", "keyboard-us"))
        assertEquals(false, displayTextModeSupportOverride("English", "keyboard-us"))
        assertEquals(false, displayTextModeSupportOverride("shuangpin-xiaohe", "shuangpin-xiaohe"))
        assertEquals(false, displayTextModeSupportOverride("shuangpin-ms", "shuangpin-ms"))
        assertEquals(false, displayTextModeSupportOverride("shuangpin-custom", "shuangpin-custom"))
        assertEquals(false, displayTextModeSupportOverride("shuangpin-custom", null))
    }

    @Test fun shuangpinParentAlwaysOffersModesForItsChildSchemes() {
        assertEquals(true, displayTextModeSupportOverride("shuangpin", null))
        assertEquals(true, displayTextModeSupportOverride("shuangpin", "shuangpin"))
    }

    @Test fun otherImesUseTheirReportedSubmodeSupport() {
        assertNull(displayTextModeSupportOverride("pinyin", "pinyin"))
        assertNull(displayTextModeSupportOverride("rime", "rime"))
    }
}
