package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanLlmCatalogClientTest {

    @Test
    fun remoteModelExposesProviderPrefixWithoutStrippingFullId() {
        val prefixed = LanLlmCatalogClient.RemoteModel(
            id = "xai/grok-4-1-fast-non-reasoning",
            displayName = "grok-4-1-fast-non-reasoning",
        )
        val local = LanLlmCatalogClient.RemoteModel(
            id = "qwen3-1.7b-4bit",
            displayName = "qwen3-1.7b-4bit",
        )

        assertEquals("xai/grok-4-1-fast-non-reasoning", prefixed.id)
        assertEquals("grok-4-1-fast-non-reasoning", prefixed.displayName)
        assertEquals("xai", prefixed.providerPrefix)

        assertEquals("qwen3-1.7b-4bit", local.id)
        assertEquals("qwen3-1.7b-4bit", local.displayName)
        assertNull(local.providerPrefix)
    }
}
