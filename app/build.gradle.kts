plugins {
    id("org.fxboomk.fcitx5.android.app-convention")
    id("org.fxboomk.fcitx5.android.native-app-convention")
    id("org.fxboomk.fcitx5.android.build-metadata")
    id("org.fxboomk.fcitx5.android.data-descriptor")
    id("org.fxboomk.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val packageBase = "org.fxboomk.fcitx5.android"
val appIdBase = "org.fxboomk.fcitx5.android"
val originalPackageBase = "org.fcitx.fcitx5.android"
val appLabelDefault = "@string/app_name"
val appLabelRelease = "@string/app_name_release"
val appLabelDebug = "@string/app_name_debug"
val originalPluginManifestAction = "$originalPackageBase.plugin.MANIFEST"
val originalDebugPluginManifestAction = "$originalPackageBase.debug.plugin.MANIFEST"
val originalIpcAction = "$originalPackageBase.IPC"
val originalDebugIpcAction = "$originalPackageBase.debug.IPC"
val imeSettingsActivity = "$packageBase.ui.main.MainActivity"

android {
    namespace = packageBase

    defaultConfig {
        applicationId = appIdBase
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = appLabelDefault
        manifestPlaceholders["originalPluginManifestAction"] = originalPluginManifestAction
        manifestPlaceholders["originalDebugPluginManifestAction"] = originalDebugPluginManifestAction
        manifestPlaceholders["originalIpcAction"] = originalIpcAction
        manifestPlaceholders["originalDebugIpcAction"] = originalDebugIpcAction
        buildConfigField("String", "ORIGINAL_PLUGIN_MANIFEST_ACTION", "\"$originalPluginManifestAction\"")
        buildConfigField("String", "ORIGINAL_DEBUG_PLUGIN_MANIFEST_ACTION", "\"$originalDebugPluginManifestAction\"")
        resValue("string", "ime_settings_activity", imeSettingsActivity)

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            manifestPlaceholders["appLabel"] = appLabelRelease
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            manifestPlaceholders["appLabel"] = appLabelDebug
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // Keep only Wubi (wbx) from the table-based input methods.
    val tableInputMethods = listOf("cangjie", "db", "erbi", "qxm", "wanfeng", "wbpy", "zrm")
    // Code tables shipped prebuilt (see f5a-prebuilder Rules/LibIME.hs)
    val tableDicts = listOf("db", "wbpy", "zrm")
    // exclude (delete immediately after install)
    excludeFiles = listOf("usr/share/fcitx5/inputmethod/shuangpin-zhongwenzhixing.conf") +
        tableInputMethods.map { "usr/share/fcitx5/inputmethod/$it.conf" } +
        tableDicts.map { "usr/share/fcitx5/table/$it.main.dict" }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
    implementation(project(":lib:common"))
    implementation(libs.onnxruntime.genai.android)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.onnxruntime.android)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.xz)
    implementation(libs.pictureselector)
    implementation(libs.okhttp)
    implementation(libs.quickjs.kt)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}

// The Android QuickJS artifact only ships a .so, so JVM unit tests need the desktop artifact.
configurations.matching {
    it.name.endsWith("UnitTestRuntimeClasspath") || it.name.endsWith("UnitTestCompileClasspath")
}.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:" + libs.versions.quickjs.kt.get()))
    }
}
