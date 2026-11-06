/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.daemon.FcitxConnection
import org.fxboomk.fcitx5.android.daemon.FcitxDaemon
import org.fxboomk.fcitx5.android.utils.AppUtil
import org.fxboomk.fcitx5.android.utils.appContext

class MainViewModel : ViewModel() {
    enum class ButtonMode { NONE, EDIT, DELETE }

    private val appLabel = AppUtil.appLabel(appContext)

    val toolbarTitle = MutableLiveData(appLabel)

    val toolbarShadow = MutableLiveData(true)

    val toolbarSaveButtonOnClickListener = MutableLiveData<(() -> Unit)?>()

    val toolbarButton = MutableLiveData(ButtonMode.NONE)

    val pluginMenuVisible = MutableLiveData(false)
    val pluginJsTabVisible = MutableLiveData(false)
    val pluginMenuTrigger = MutableLiveData<Unit?>()

    private var pendingPreferenceScrollKey: String? = null

    /**
     * Index of the tab holding the pending preference, for pages that host their
     * preferences in a ViewPager. Null when the page has a single tab.
     */
    private var pendingPreferenceTab: Int? = null

    val fcitx: FcitxConnection = FcitxDaemon.connect(javaClass.name)

    fun setToolbarTitle(title: String) {
        toolbarTitle.value = title
    }

    fun enableToolbarShadow() {
        toolbarShadow.value = true
    }

    fun disableToolbarShadow() {
        toolbarShadow.value = false
    }

    fun enableToolbarSaveButton(onClick: () -> Unit) {
        toolbarSaveButtonOnClickListener.value = onClick
    }

    fun disableToolbarSaveButton() {
        toolbarSaveButtonOnClickListener.value = null
    }

    fun enablePluginMenu() {
        pluginMenuVisible.value = true
    }

    fun disablePluginMenu() {
        pluginMenuVisible.value = false
    }

    fun setPluginJsTabVisible(visible: Boolean) {
        pluginJsTabVisible.value = visible
    }

    fun triggerPluginMenu() {
        pluginMenuTrigger.value = Unit
    }

    fun clearPluginMenuTrigger() {
        pluginMenuTrigger.value = null
    }

    fun requestPreferenceScroll(key: String, tab: Int? = null) {
        pendingPreferenceScrollKey = key
        pendingPreferenceTab = tab
    }

    fun peekPendingPreferenceScrollKey(): String? = pendingPreferenceScrollKey

    fun peekPendingPreferenceTab(): Int? = pendingPreferenceTab

    fun consumePendingPreferenceScrollKey(key: String) {
        if (pendingPreferenceScrollKey == key) {
            pendingPreferenceScrollKey = null
            pendingPreferenceTab = null
        }
    }

    override fun onCleared() {
        FcitxDaemon.disconnect(javaClass.name)
    }
}
