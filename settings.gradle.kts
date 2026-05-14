fun githubPackagesRepositoryUrl(): String {
    val explicit = providers.gradleProperty("githubPackagesRepository").orNull?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val repositoryEnv = System.getenv("GITHUB_REPOSITORY")?.trim().orEmpty()
    if (repositoryEnv.isNotEmpty()) return "https://maven.pkg.github.com/$repositoryEnv"
    val owner = providers.gradleProperty("githubPackagesOwner").orNull?.trim().orEmpty().ifBlank { "boomker" }
    val repo = providers.gradleProperty("githubPackagesRepo").orNull?.trim().orEmpty().ifBlank { "fcitx5-android" }
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

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
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
            filter {
                includeGroup("org.fxboomk.fcitx5.android.thirdparty")
            }
        }
    }
}

rootProject.name = "fcitx5-android"

include(":lib:common")
include(":lib:fcitx5")
include(":lib:fcitx5-lua")
include(":lib:libime")
include(":lib:fcitx5-chinese-addons")
include(":codegen")
include(":app")
include(":lib:plugin-base")
include(":third_party:onnxruntime-genai-android")
include(":plugin:anthy")
include(":plugin:clipboard-filter")
include(":plugin:clipboard-sync")
include(":plugin:unikey")
include(":plugin:rime")
include(":plugin:hangul")
include(":plugin:chewing")
include(":plugin:sayura")
include(":plugin:jyutping")
include(":plugin:thai")
