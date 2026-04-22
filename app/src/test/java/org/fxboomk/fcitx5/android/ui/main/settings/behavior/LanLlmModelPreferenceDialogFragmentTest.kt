/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

package org.fxboomk.fcitx5.android.ui.main.settings.behavior

import org.junit.Assert.assertEquals
import org.junit.Test

class LanLlmModelPreferenceDialogFragmentTest {

    @Test
    fun resolveUnprefixedGroupLabelUsesCurrentProviderNameForBuiltInProviders() {
        val label = LanLlmModelPreferenceDialogFragment.resolveUnprefixedGroupLabel(
            providerLabel = "MiniMax CN",
            isCustomProvider = false,
            emptyLabel = "空",
        )

        assertEquals("MiniMax CN", label)
    }

    @Test
    fun resolveUnprefixedGroupLabelUsesEmptyForCustomProvider() {
        val label = LanLlmModelPreferenceDialogFragment.resolveUnprefixedGroupLabel(
            providerLabel = "自定义",
            isCustomProvider = true,
            emptyLabel = "空",
        )

        assertEquals("空", label)
    }
}
