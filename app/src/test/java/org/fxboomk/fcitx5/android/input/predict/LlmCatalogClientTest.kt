package org.fxboomk.fcitx5.android.input.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LlmCatalogClientTest {

    @Test
    fun remoteModelExposesProviderPrefixWithoutStrippingFullId() {
        val prefixed = LlmCatalogClient.RemoteModel(
            id = "xai/grok-4-1-fast-non-reasoning",
            displayName = "grok-4-1-fast-non-reasoning",
        )
        val local = LlmCatalogClient.RemoteModel(
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
