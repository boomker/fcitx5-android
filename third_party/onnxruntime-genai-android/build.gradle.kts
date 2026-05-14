import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.gradle.api.Project

plugins {
    base
    `maven-publish`
}

group = "org.fxboomk.fcitx5.android.thirdparty"
version = "0.13.1"

fun Project.githubPackagesRepositoryUrl(): String {
    val explicit = providers.gradleProperty("githubPackagesRepository").orNull?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val repositoryEnv = System.getenv("GITHUB_REPOSITORY")?.trim().orEmpty()
    if (repositoryEnv.isNotEmpty()) return "https://maven.pkg.github.com/$repositoryEnv"
    val owner = providers.gradleProperty("githubPackagesOwner").orNull?.trim().orEmpty().ifBlank { "boomker" }
    val repo = providers.gradleProperty("githubPackagesRepo").orNull?.trim().orEmpty().ifBlank { rootProject.name }
    return "https://maven.pkg.github.com/$owner/$repo"
}

fun Project.githubPackagesUsername(): String =
    providers.gradleProperty("gpr.user").orNull?.trim().orEmpty()
        .ifBlank { System.getenv("GITHUB_ACTOR").orEmpty().trim() }
        .ifBlank { System.getenv("GH_USERNAME").orEmpty().trim() }
        .ifBlank { System.getenv("GITHUB_USERNAME").orEmpty().trim() }

fun Project.githubPackagesToken(): String =
    providers.gradleProperty("gpr.key").orNull?.trim().orEmpty()
        .ifBlank { System.getenv("GITHUB_TOKEN").orEmpty().trim() }
        .ifBlank { System.getenv("GH_TOKEN").orEmpty().trim() }
        .ifBlank { System.getenv("PACKAGE_TOKEN").orEmpty().trim() }

fun isFileBackedRepository(url: String): Boolean = url.startsWith("file:", ignoreCase = true)

val artifactName = "onnxruntime-genai-android"
val artifactFileName = "$artifactName-$version.aar"
val artifactSha256 = "6cd42859af1c0d28c5a3c72ac4714a99b8e9f23b610b8616ff32d709bd51d4aa"
val artifactUrl =
    "https://github.com/microsoft/onnxruntime-genai/releases/download/v$version/$artifactFileName"
val downloadedArtifact = layout.buildDirectory.file("downloads/$artifactFileName")

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun requireExpectedSha256(file: File) {
    val actual = sha256(file)
    check(actual == artifactSha256) {
        "Unexpected SHA-256 for $artifactFileName: expected $artifactSha256, got $actual"
    }
}

val downloadOrtGenAiAar by tasks.registering {
    outputs.file(downloadedArtifact)
    doLast {
        val target = downloadedArtifact.get().asFile
        if (target.exists() && target.length() > 0L) {
            requireExpectedSha256(target)
            return@doLast
        }
        target.parentFile.mkdirs()
        val connection = (URL(artifactUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            check(connection.responseCode in 200..299) {
                "Download failed (${connection.responseCode}): $artifactUrl"
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
        requireExpectedSha256(target)
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
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = artifactName
            version = project.version.toString()
            artifact(downloadedArtifact) {
                extension = "aar"
                builtBy(downloadOrtGenAiAar)
            }
            pom {
                name.set("ONNX Runtime GenAI Android")
                description.set("Prebuilt ONNX Runtime GenAI Android artifact mirrored for fcitx5-android builds.")
                url.set("https://github.com/microsoft/onnxruntime-genai")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://github.com/microsoft/onnxruntime-genai/blob/main/LICENSE")
                    }
                }
            }
        }
    }
}
