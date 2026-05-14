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
    id("org.fxboomk.fcitx5.android.lib-convention")
    `maven-publish`
    alias(libs.plugins.gitVersion)
}

android {
    namespace = "org.fxboomk.fcitx5.android.lib.common"

    buildFeatures {
        aidl = true
    }
    publishing {
        singleVariant("release")
    }
}

val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()

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
    publications {
        register<MavenPublication>("release") {
            groupId = "org.fxboomk.fcitx5.android.lib"
            artifactId = "common"
            pom {
                licenses {
                    name.set("LGPL-2.1")
                    url.set("https://spdx.org/licenses/LGPL-2.1.html")
                }
            }
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}
