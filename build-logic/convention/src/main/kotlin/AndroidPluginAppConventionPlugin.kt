/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
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
            ?: "org.fxboomk.fcitx5.android"

        target.extensions.configure<ApplicationExtension> {
            buildFeatures {
                buildConfig = true
            }
            defaultConfig {
                buildConfigField("String", "MAIN_APPLICATION_ID", "\"$mainApplicationId\"")
                addManifestPlaceholders(
                    mapOf(
                        "mainApplicationId" to mainApplicationId,
                    )
                )
            }
            buildTypes {
                release {
                }
                debug {
                    val debugMainApplicationId = target.findProperty("mainApplicationId")?.toString()
                        ?: target.findProperty("applicationId")?.toString()?.let { "${it}.debug" }
                        ?: "org.fxboomk.fcitx5.android.debug"
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"$debugMainApplicationId\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to debugMainApplicationId,
                        )
                    )
                }
            }
        }
        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.capitalized()
                target.afterEvaluate {
                    val pluginsTaskName = "assemble${variantName}Plugins"
                    val pluginsTask = target.rootProject.tasks.findByName(pluginsTaskName)
                        ?: target.rootProject.tasks.register(pluginsTaskName).get()
                    pluginsTask?.dependsOn(target.tasks.getByName("assemble${variantName}"))
                }
            }
        }
    }

}
