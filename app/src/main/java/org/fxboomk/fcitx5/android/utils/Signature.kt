package org.fxboomk.fcitx5.android.utils

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build

fun PackageManager.packageSigners(packageName: String): Array<Signature> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo
            ?.apkContentsSigners
            ?: emptyArray()
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
    }
}
