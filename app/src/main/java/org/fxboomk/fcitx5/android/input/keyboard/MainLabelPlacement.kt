/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fxboomk.fcitx5.android.input.keyboard

import kotlin.math.min

internal data class MainLabelGeometry(val baseline: Float, val scale: Float)

/**
 * Center the visible main-label ink on the key face.
 *
 * Edge labels only limit the scale needed to preserve clearance. All band/face
 * values share one coordinate space; ink bounds are relative to a zero baseline.
 */
internal fun computeMainLabelGeometry(
    bandTop: Float,
    bandBottom: Float,
    faceCenter: Float,
    inkTop: Float,
    inkBottom: Float,
    minGap: Float,
    scaleOverride: Float? = null,
): MainLabelGeometry {
    val inkHeight = (inkBottom - inkTop).coerceAtLeast(0.001f)
    val centerReference = (inkTop + inkBottom) / 2f
    val halfHeight = inkHeight / 2f
    val scaleLimit = min(
        (faceCenter - bandTop - minGap) / halfHeight,
        (bandBottom - minGap - faceCenter) / halfHeight,
    )
    val scale = min(scaleOverride ?: 1f, scaleLimit).coerceIn(0.001f, 1f)

    return MainLabelGeometry(
        baseline = faceCenter - centerReference * scale,
        scale = scale,
    )
}
