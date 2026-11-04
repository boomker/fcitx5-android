/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.common.ipc.IVoiceInputProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// In-process registry for voice input providers that live inside the IME
// itself (e.g. a future built-in echo / test provider). External plugin
// providers are discovered via PackageManager queries; see
// VoiceInputProviderManager.listProviders.
object VoiceInputProviderRegistry {
    data class Entry(
        val id: String,
        val label: CharSequence,
        val packageName: String,
        val provider: IVoiceInputProvider,
    )

    private val providers = ConcurrentHashMap<String, Entry>()

    private val changes = AtomicLong()
    internal val revision get() = changes.get()

    fun register(entry: Entry) {
        providers[entry.id] = entry
        changes.incrementAndGet()
    }

    fun unregister(id: String): Entry? = providers.remove(id).also { changes.incrementAndGet() }

    fun get(id: String): Entry? = providers[id]

    fun all(): List<Entry> = providers.values.toList()

    fun clear() {
        providers.clear()
        changes.incrementAndGet()
    }
}
