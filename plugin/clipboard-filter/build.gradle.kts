import com.android.build.gradle.tasks.MergeSourceSetFolders
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import kotlinx.serialization.json.Json

plugins {
    id("org.fxboomk.fcitx5.android.app-convention")
    id("org.fxboomk.fcitx5.android.plugin-app-convention")
    id("org.fxboomk.fcitx5.android.build-metadata")
    id("org.fxboomk.fcitx5.android.data-descriptor")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.fxboomk.fcitx5.android.plugin.clipboard_filter"

    defaultConfig {
        applicationId = "org.fxboomk.fcitx5.android.plugin.clipboard_filter"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }
}

abstract class MinifyClearUrlsRulesTask : DefaultTask() {
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val outDir = outputDir.get().asFile.apply { mkdirs() }
        val outFile = outDir.resolve("data.min.json")
        outFile.writeText(Json.parseToJsonElement(inputFile.get().asFile.readText()).toString())
    }
}

val minifyClearUrlsRules by tasks.registering(MinifyClearUrlsRulesTask::class) {
    inputFile.set(layout.projectDirectory.file("ClearURLsRules/data.min.json"))
    outputDir.set(layout.buildDirectory.dir("generated/clearurls-assets"))
}

extensions.configure<AndroidComponentsExtension<*, *, *>>("androidComponents") {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            minifyClearUrlsRules,
            MinifyClearUrlsRulesTask::outputDir
        )
    }
}

tasks.withType<MergeSourceSetFolders>().configureEach {
    if (name.endsWith("Assets")) {
        dependsOn(minifyClearUrlsRules)
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation(libs.kotlinx.serialization.json)
}
