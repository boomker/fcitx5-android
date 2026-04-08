plugins {
    id("org.fxboomk.fcitx5.android.app-convention")
    id("org.fxboomk.fcitx5.android.plugin-app-convention")
    id("org.fxboomk.fcitx5.android.build-metadata")
    id("org.fxboomk.fcitx5.android.data-descriptor")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fxboomk.fcitx5.android.plugin.clipboard_sync"

    defaultConfig {
        applicationId = "org.fxboomk.fcitx5.android.plugin.clipboard_sync"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isShrinkResources = false
        }
    }
}

dependencies {
    implementation(project(":lib:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
