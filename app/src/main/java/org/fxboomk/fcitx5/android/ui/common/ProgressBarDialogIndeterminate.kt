/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fxboomk.fcitx5.android.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.getGlobalSettings
import splitties.dimensions.dp
import splitties.resources.resolveThemeAttribute
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.styles.AndroidStyles
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.verticalMargin
import splitties.views.textAppearance

@Suppress("FunctionName")
fun Context.ProgressBarDialogIndeterminate(
    @StringRes title: Int,
    cancelable: Boolean = false,
    @StringRes negativeButton: Int? = null,
    onNegativeButtonClick: (() -> Unit)? = null
): AlertDialog.Builder {
    val androidStyles = AndroidStyles(this)
    return AlertDialog.Builder(this)
        .setTitle(title)
        .setView(verticalLayout {
            val shouldAnimate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                getGlobalSettings<Float>(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f
            }
            add(if (shouldAnimate) {
                androidStyles.progressBar.horizontal {
                    isIndeterminate = true
                }
            } else {
                textView {
                    setText(R.string.please_wait)
                    textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
                }
            }, lParams {
                width = matchParent
                verticalMargin = dp(20)
                horizontalMargin = dp(26)
            })
        })
        .setCancelable(cancelable)
        .apply {
            if (negativeButton != null) {
                setNegativeButton(negativeButton) { _, _ ->
                    onNegativeButtonClick?.invoke()
                }
            }
        }
}

fun LifecycleCoroutineScope.withLoadingDialog(
    context: Context,
    @StringRes title: Int = R.string.loading,
    threshold: Long = 200L,
    cancellable: Boolean = false,
    @StringRes negativeButton: Int? = null,
    onCancel: (() -> Unit)? = null,
    action: suspend () -> Unit
): Job {
    var loadingDialog: AlertDialog? = null
    var cancelHandled = false
    var actionJob: Job? = null
    fun handleCancel() {
        if (cancelHandled) return
        cancelHandled = true
        onCancel?.invoke()
        actionJob?.cancel()
    }
    val loadingJob = launch(Dispatchers.Main) {
        delay(threshold)
        if (actionJob?.isActive != true) return@launch
        loadingDialog = context.ProgressBarDialogIndeterminate(
            title = title,
            cancelable = cancellable,
            negativeButton = negativeButton,
            onNegativeButtonClick = { handleCancel() }
        ).show().apply {
            setCanceledOnTouchOutside(false)
            setOnCancelListener { handleCancel() }
        }
    }
    actionJob = launch {
        try {
            action()
        } finally {
            loadingJob.cancelAndJoin()
            loadingDialog?.dismiss()
        }
    }
    return checkNotNull(actionJob)
}
