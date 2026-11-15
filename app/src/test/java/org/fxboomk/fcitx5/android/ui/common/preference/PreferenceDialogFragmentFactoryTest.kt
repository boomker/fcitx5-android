/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fxboomk.fcitx5.android.ui.common.preference

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceDialogFragmentFactoryTest {
    private val classLoader = requireNotNull(ResettableEditTextPreferenceDialogFragment::class.java.classLoader)

    @Test
    fun publishedDialogNamesRestoreTheCorrespondingResettableDialogs() {
        val factory = PreferenceDialogFragmentFactory(object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                error("A published preference dialog must not reach the delegate: $className")
        })

        assertEquals(
            ResettableEditTextPreferenceDialogFragment::class.java,
            factory.instantiate(classLoader,
                "org.fxboomk.fcitx5.android.ui.main.modified.MyEditTextPreferenceDialogFragment").javaClass
        )
        assertEquals(
            ResettableListPreferenceDialogFragment::class.java,
            factory.instantiate(classLoader,
                "org.fxboomk.fcitx5.android.ui.main.modified.MyListPreferenceDialogFragment").javaClass
        )
    }

    @Test
    fun currentDialogNamesRemainInstantiableThroughTheDefaultFactory() {
        val factory = PreferenceDialogFragmentFactory(FragmentFactory())
        for (type in listOf(
            ResettableEditTextPreferenceDialogFragment::class.java,
            ResettableListPreferenceDialogFragment::class.java,
        )) {
            assertEquals(type, factory.instantiate(classLoader, type.name).javaClass)
        }
    }

    @Test
    fun unmappedNamesUseTheOriginalDelegateAndClassLoader() {
        val requestedLoader = object : ClassLoader(classLoader) {}
        val requestedName = "custom.preference.InjectedFragment"
        val expected = Fragment()
        var calls = 0
        val factory = PreferenceDialogFragmentFactory(object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
                calls++
                assertSame(requestedLoader, classLoader)
                assertEquals(requestedName, className)
                return expected
            }
        })

        assertSame(expected, factory.instantiate(requestedLoader, requestedName))
        assertEquals(1, calls)
    }

    @Test
    fun aMissingClassStillFailsWithTheDefaultFactoryError() {
        val factory = PreferenceDialogFragmentFactory(FragmentFactory())

        val failure = assertThrows(Fragment.InstantiationException::class.java) {
            factory.instantiate(classLoader, "custom.preference.MissingFragment")
        }

        assertTrue(failure.cause is ClassNotFoundException)
    }
}
