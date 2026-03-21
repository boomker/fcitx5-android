plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    alias(libs.plugins.kotlin.serialization)
}

val externalProjectDir = file(
    findProperty("clipboardSyncProjectDir")?.toString()
        ?: "/Users/cyzhu/gitrepos/Fcitx5-clipboard_sync"
)
val externalMainDir = externalProjectDir.resolve("app/src/main")
require(externalMainDir.isDirectory) {
    "Clipboard Sync source dir not found: $externalMainDir"
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.clipboard_sync"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.clipboard_sync"
    }

    buildTypes {
        release {
            isShrinkResources = false
        }
    }

    sourceSets.named("main") {
        manifest.srcFile("src/main/AndroidManifest.xml")
        res.srcDir(externalMainDir.resolve("res"))
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
