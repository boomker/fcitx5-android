/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Register `assemble${Variant}Plugins` task for root project,
 * and make all plugins' `assemble${Variant}` depends on it
 */
class AndroidPluginAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val mainApplicationId = target.findProperty("mainApplicationId")?.toString()
            ?: target.findProperty("applicationId")?.toString()
            ?: "org.fcitx.fcitx5.android"

        target.extensions.configure<BaseAppModuleExtension> {
            buildTypes {
                release {
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$mainApplicationId\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to mainApplicationId,
                        )
                    )
                }
                debug {
                    // For debug, use the same application ID unless specified otherwise
                    val debugMainApplicationId = target.findProperty("mainApplicationId")?.toString()
                        ?: target.findProperty("applicationId")?.toString()?.let { "${it}.debug" }
                        ?: "org.fcitx.fcitx5.android.debug"
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$debugMainApplicationId\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to debugMainApplicationId,
                        )
                    )
                }
            }
            applicationVariants.all {
                val pluginsTaskName = "assemble${name.capitalized()}Plugins"
                val pluginsTask = target.rootProject.tasks.findByName(pluginsTaskName)
                    ?: target.rootProject.tasks.register(pluginsTaskName).get()
                pluginsTask.dependsOn(assembleProvider)
            }
        }
    }

}
