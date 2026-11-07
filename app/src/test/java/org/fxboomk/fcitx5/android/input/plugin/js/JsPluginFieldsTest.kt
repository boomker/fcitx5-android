/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.input.plugin.js

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsPluginFieldsTest {
    @Test
    fun scriptDeclarationsPassResetMetadataThroughThePrelude() = runBlocking {
        val fields = JsScript.describe("""
            foxy.plugin.register({configure(ui) {
                ui.text("model", "Model", "model", {resettable:false});
                ui.select("protocol", "Protocol", ["chat", "anthropic"], "anthropic", {resetValue:"chat"});
                ui.text("url", "URL", "", {});
            }});
        """.trimIndent(), "en")
        assertNull(JsPluginFields.resetValue(fields[0], emptyMap()))
        assertEquals("chat", JsPluginFields.resetValue(fields[1], emptyMap()))
        assertEquals("", JsPluginFields.resetValue(fields[2], emptyMap()))
    }

    @Test
    fun resetUsesTheDeclaredDefaultRatherThanTheSavedValue() {
        val fields = JsPluginFields.parse("""[
            {"kind":"select","key":"purpose","default":"short",
             "options":[{"value":"short"},{"value":"polish"}]},
            {"kind":"text","key":"url","default":"https://api.deepseek.com"},
            {"kind":"number","key":"tokens","default":"4096"}
        ]""")
        val stored = mapOf("purpose" to "polish", "url" to "https://relay.example", "tokens" to "8192")
        assertEquals(listOf("short", "https://api.deepseek.com", "4096"),
            fields.map { JsPluginFields.resetValue(it, stored) })
    }

    @Test
    fun resetCanDifferFromTheInitialProtocol() {
        val field = JsPluginFields.parse("""[
            {"kind":"select","key":"protocol","default":"anthropic","resetValue":"chat",
             "options":[{"value":"chat"},{"value":"anthropic"}]}
        ]""").single()
        assertEquals("anthropic", JsPluginFields.currentValues(listOf(field), emptyMap())[field.key])
        assertEquals("chat", JsPluginFields.resetValue(field, emptyMap()))
    }

    @Test
    fun secretsAndFieldsWithoutResetActionsDoNotOfferDefaults() {
        val fields = JsPluginFields.parse("""[
            {"kind":"secret","key":"key","default":""},
            {"kind":"text","key":"model","default":"model","resettable":false}
        ]""")
        fields.forEach { assertNull(JsPluginFields.resetValue(it, emptyMap())) }
    }

    @Test
    fun dependentDefaultsFollowTheCurrentControllingValue() {
        val field = JsPluginFields.parse("""[
            {"kind":"text","key":"url","default":"fallback",
             "defaultWhen":{"key":"provider","values":{"a":"https://a.example","b":"https://b.example"}}}
        ]""").single()
        assertEquals("https://b.example", JsPluginFields.resetValue(field, mapOf("provider" to "b", "url" to "custom")))
    }

    @Test
    fun emptyDefaultRemainsAResetActionAndInvalidSelectDefaultsUseTheFirstOption() {
        val fields = JsPluginFields.parse("""[
            {"kind":"text","key":"url","default":""},
            {"kind":"select","key":"purpose","default":"missing","options":[{"value":"short"}]}
        ]""")
        assertEquals("", JsPluginFields.resetValue(fields[0], emptyMap()))
        assertEquals("short", JsPluginFields.resetValue(fields[1], emptyMap()))
    }
}
