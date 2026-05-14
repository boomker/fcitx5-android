import org.gradle.accessors.dm.LibrariesForLibs

fun githubPackagesRepositoryUrl(): String {
    val explicit = providers.gradleProperty("githubPackagesRepository").orNull?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val repositoryEnv = System.getenv("GITHUB_REPOSITORY")?.trim().orEmpty()
    if (repositoryEnv.isNotEmpty()) return "https://maven.pkg.github.com/$repositoryEnv"
    val owner = providers.gradleProperty("githubPackagesOwner").orNull?.trim().orEmpty().ifBlank { "boomker" }
    val repo = providers.gradleProperty("githubPackagesRepo").orNull?.trim().orEmpty().ifBlank { rootProject.name }
    return "https://maven.pkg.github.com/$owner/$repo"
}

fun githubPackagesUsername(): String =
    providers.gradleProperty("gpr.user").orNull?.trim().orEmpty()
        .ifBlank { System.getenv("GITHUB_ACTOR").orEmpty().trim() }
        .ifBlank { System.getenv("GH_USERNAME").orEmpty().trim() }
        .ifBlank { System.getenv("GITHUB_USERNAME").orEmpty().trim() }

fun githubPackagesToken(): String =
    providers.gradleProperty("gpr.key").orNull?.trim().orEmpty()
        .ifBlank { System.getenv("GITHUB_TOKEN").orEmpty().trim() }
        .ifBlank { System.getenv("GH_TOKEN").orEmpty().trim() }
        .ifBlank { System.getenv("PACKAGE_TOKEN").orEmpty().trim() }

fun isFileBackedRepository(url: String): Boolean = url.startsWith("file:", ignoreCase = true)

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version embeddedKotlinVersion
    `maven-publish`
    alias(libs.plugins.gitVersion)
    `java-gradle-plugin`
}

group = "org.fxboomk.fcitx5.android.build_logic"

val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()

java {
    withSourcesJar()
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.aboutlibraries.plugin)
    implementation(libs.kotlinx.serialization.json)
    // A workaround to enable version catalog usage in the convention plugin,
    // see https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
    implementation(files(LibrariesForLibs::class.java.protectionDomain.codeSource.location))
}

gradlePlugin {
    plugins {
        register("androidAppConvention") {
            id = "org.fxboomk.fcitx5.android.app-convention"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("androidLibConvention") {
            id = "org.fxboomk.fcitx5.android.lib-convention"
            implementationClass = "AndroidLibConventionPlugin"
        }
        register("androidPluginAppConvention") {
            id = "org.fxboomk.fcitx5.android.plugin-app-convention"
            implementationClass = "AndroidPluginAppConventionPlugin"
        }
        register("buildMetadata") {
            id = "org.fxboomk.fcitx5.android.build-metadata"
            implementationClass = "BuildMetadataPlugin"
        }
        register("dataDescriptor") {
            id = "org.fxboomk.fcitx5.android.data-descriptor"
            implementationClass = "DataDescriptorPlugin"
        }
        register("fcitxComponent") {
            id = "org.fxboomk.fcitx5.android.fcitx-component"
            implementationClass = "FcitxComponentPlugin"
        }
        register("fcitxHeaders") {
            id = "org.fxboomk.fcitx5.android.fcitx-headers"
            implementationClass = "FcitxHeadersPlugin"
        }
        register("nativeAppConvention") {
            id = "org.fxboomk.fcitx5.android.native-app-convention"
            implementationClass = "NativeAppConventionPlugin"
        }
        register("nativeLibConvention") {
            id = "org.fxboomk.fcitx5.android.native-lib-convention"
            implementationClass = "NativeLibConventionPlugin"
        }
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            val repositoryUrl = githubPackagesRepositoryUrl()
            url = uri(repositoryUrl)
            if (!isFileBackedRepository(repositoryUrl)) {
                credentials {
                    username = githubPackagesUsername()
                    password = githubPackagesToken()
                }
            }
        }
    }
}
