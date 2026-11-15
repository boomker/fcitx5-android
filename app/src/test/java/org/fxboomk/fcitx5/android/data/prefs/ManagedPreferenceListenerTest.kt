/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.data.prefs

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class ManagedPreferenceListenerTest {

    @Test
    fun preferenceListenersCanReplaceTheirSubscriptionsDuringNotification() {
        val preference = TestPreference()
        val originalCalls = MutableList(3) { 0 }
        val addedCalls = MutableList(3) { 0 }
        val added = List(3) { index ->
            ManagedPreference.OnChangeListener<Int> { key, value ->
                assertEquals(preference.key, key)
                assertEquals(1, value)
                addedCalls[index]++
            }
        }
        val original = mutableListOf<ManagedPreference.OnChangeListener<Int>>()
        repeat(3) { index ->
            original += ManagedPreference.OnChangeListener { key, value ->
                assertEquals(preference.key, key)
                assertEquals(1, value)
                originalCalls[index]++
                preference.unregisterOnChangeListener(original[index])
                preference.registerOnChangeListener(added[index])
            }
        }
        original.forEach(preference::registerOnChangeListener)

        preference.fireChange()

        assertEquals(listOf(1, 1, 1), originalCalls)
        assertEquals(listOf(0, 0, 0), addedCalls)
        preference.fireChange()
        assertEquals(listOf(1, 1, 1), originalCalls)
        assertEquals(listOf(1, 1, 1), addedCalls)
    }

    @Test
    fun providerListenersCanReplaceTheirSubscriptionsWithoutSkippingPreferenceNotification() {
        val preference = TestPreference()
        val provider = object : ManagedPreferenceProvider() {
            init { preference.register() }
        }
        val originalCalls = MutableList(3) { 0 }
        val addedCalls = MutableList(3) { 0 }
        val added = List(3) { index ->
            ManagedPreferenceProvider.OnChangeListener { key ->
                assertEquals(preference.key, key)
                addedCalls[index]++
            }
        }
        val original = mutableListOf<ManagedPreferenceProvider.OnChangeListener>()
        repeat(3) { index ->
            original += ManagedPreferenceProvider.OnChangeListener { key ->
                assertEquals(preference.key, key)
                originalCalls[index]++
                provider.unregisterOnChangeListener(original[index])
                provider.registerOnChangeListener(added[index])
            }
        }
        var preferenceCalls = 0
        val preferenceListener = ManagedPreference.OnChangeListener<Int> { _, _ -> preferenceCalls++ }
        preference.registerOnChangeListener(preferenceListener)
        original.forEach(provider::registerOnChangeListener)

        provider.fireChange(preference.key)

        assertEquals(listOf(1, 1, 1), originalCalls)
        assertEquals(listOf(0, 0, 0), addedCalls)
        assertEquals(1, preferenceCalls)
        provider.fireChange(preference.key)
        assertEquals(listOf(1, 1, 1), originalCalls)
        assertEquals(listOf(1, 1, 1), addedCalls)
        assertEquals(2, preferenceCalls)
    }

    @Test
    fun nestedPreferenceChangesKeepEachNotificationsValue() {
        val preference = TestPreference()
        val firstValues = mutableListOf<Int>()
        val secondValues = mutableListOf<Int>()
        val first = ManagedPreference.OnChangeListener<Int> { _, value ->
            firstValues += value
            if (value == 1) {
                preference.setValue(2)
                preference.fireChange()
            }
        }
        val second = ManagedPreference.OnChangeListener<Int> { _, value -> secondValues += value }
        preference.registerOnChangeListener(first)
        preference.registerOnChangeListener(second)

        preference.fireChange()

        assertEquals(listOf(1, 2), firstValues.sorted())
        assertEquals(listOf(1, 2), secondValues.sorted())
    }

    private class TestPreference : ManagedPreference<Int>(
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, _ -> error("Unexpected storage access: ${method.name}") } as SharedPreferences,
        "test_preference",
        1,
    ) {
        private var value = defaultValue
        override fun getValue(): Int = value
        override fun setValue(value: Int) { this.value = value }
        override fun putValueTo(editor: SharedPreferences.Editor) = error("Unexpected storage write")
    }
}
