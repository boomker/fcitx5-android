plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val packageBase = "org.fcitx.fcitx5.android"
val appIdBase = packageBase
val appIdFxSuffix = ".fx"
val flavorFx = "fx"
val flavorMainline = "mainline"
val appLabelDefault = "@string/app_name"
val appLabelMainlineRelease = "@string/app_name_mainline_release"
val appLabelMainlineDebug = "@string/app_name_mainline_debug"
val originalPluginManifestAction = "$packageBase.plugin.MANIFEST"
val originalIpcAction = "$packageBase.IPC"
val originalDebugIpcAction = "$packageBase.debug.IPC"
val imeSettingsActivity = "$packageBase.ui.main.MainActivity"
val includeMainlineFlavor =
    providers.gradleProperty("includeMainlineFlavor").map(String::toBoolean).orElse(false)

android {
    namespace = packageBase

    defaultConfig {
        applicationId = appIdBase
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = appLabelDefault
        manifestPlaceholders["originalPluginManifestAction"] = originalPluginManifestAction
        manifestPlaceholders["originalIpcAction"] = originalIpcAction
        manifestPlaceholders["originalDebugIpcAction"] = originalDebugIpcAction
        buildConfigField("String", "ORIGINAL_PLUGIN_MANIFEST_ACTION", "\"$originalPluginManifestAction\"")
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

    flavorDimensions += "brand"
    productFlavors {
        create(flavorFx) {
            dimension = "brand"
            applicationIdSuffix = appIdFxSuffix
            buildConfigField("boolean", "IS_FX_BUILD", "true")
        }
        create(flavorMainline) {
            dimension = "brand"
            buildConfigField("boolean", "IS_FX_BUILD", "false")
        }
    }

    buildTypes {
        release {
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
}

fun fallbackAliasFromFxTask(taskName: String): String? = when {
    "FxDebug" in taskName -> taskName.replace("FxDebug", "Debug")
    "FxRelease" in taskName -> taskName.replace("FxRelease", "Release")
    else -> null
}

afterEvaluate {
    val fxTasks = tasks.names
        .filter { it.contains("FxDebug") || it.contains("FxRelease") }
        .toList()
    fxTasks.forEach { fxTaskName ->
        val alias = fallbackAliasFromFxTask(fxTaskName) ?: return@forEach
        if (tasks.findByName(alias) != null) return@forEach
        val fxTask = tasks.findByName(fxTaskName) ?: return@forEach
        tasks.register(alias) {
            group = fxTask.group
            description = "Alias of $fxTaskName"
            dependsOn(fxTaskName)
        }
    }
}

fun String.capitalized(): String = replaceFirstChar { c ->
    if (c.isLowerCase()) c.titlecase() else c.toString()
}

fun registerFxApkCompatCopy(buildType: String) {
    val buildTypeCap = buildType.capitalized()
    val assembleTask = "assembleFx$buildTypeCap"
    val compatTask = "syncFx${buildTypeCap}ApkToLegacyDir"
    tasks.register(compatTask) {
        dependsOn(assembleTask)
        doLast {
            // clear old APKs in target folder to avoid confusion (delete only .apk files)
            val destDir = layout.buildDirectory.dir("outputs/apk/$buildType").get().asFile
            if (destDir.exists()) {
                destDir.listFiles()?.filter { it.isFile && it.extension == "apk" }?.forEach { file ->
                    try {
                        file.delete()
                    } catch (_: Exception) {
                        // ignore deletion failures
                    }
                }
            } else {
                destDir.mkdirs()
            }

            // copy new fx APKs into legacy location
            copy {
                from(layout.buildDirectory.dir("outputs/apk/fx/$buildType"))
                into(destDir)
                include("*.apk")
            }
        }
    }
    tasks.matching { it.name == assembleTask }.configureEach {
        finalizedBy(compatTask)
    }
}

registerFxApkCompatCopy("debug")
registerFxApkCompatCopy("release")

androidComponents {
    beforeVariants(selector().withFlavor("brand" to flavorMainline)) { variantBuilder ->
        variantBuilder.enable = includeMainlineFlavor.get()
    }
}

android {
    applicationVariants.all {
        when (flavorName) {
            flavorMainline -> {
                val mainlineAppName = if (buildType.name == "debug") {
                    appLabelMainlineDebug
                } else {
                    appLabelMainlineRelease
                }
                mergedFlavor.manifestPlaceholders["appLabel"] = mainlineAppName
                outputs.all {
                    this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                    outputFileName = outputFileName.replace("-mainline-", "-")
                }
            }
            flavorFx -> {
                outputs.all {
                    this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                    outputFileName = outputFileName.replace("org.fcitx.fcitx5.android-", "org.fcitx.fcitx5.android.fx-")
                    outputFileName = outputFileName.replace("-fx-", "-")
                }
            }
        }
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
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
    testImplementation(libs.junit)
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
