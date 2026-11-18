plugins {
    base
    `maven-publish`
}

group = "org.fxboomk.fcitx5.android.thirdparty"
version = "0.20.0-f5a.1"

fun Project.githubPackagesRepositoryUrl(): String {
    val explicit = providers.gradleProperty("githubPackagesRepository").orNull?.trim().orEmpty()
    if (explicit.isNotEmpty()) return explicit
    val localRepository = rootProject.rootDir.resolve(".local-thirdparty-artifacts")
    if (localRepository.isDirectory) return localRepository.toURI().toString()
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
val prebuiltArtifact = layout.projectDirectory.file("prebuilt/$artifactFileName")
val alignmentMarker = layout.projectDirectory.file("prebuilt/$artifactFileName.alignment.json")
val verifier = layout.projectDirectory.file("rebuild_mat.py")

val verifyOrtGenAiAar = tasks.register<Exec>("verifyOrtGenAiAar") {
    group = "third party"
    description = "Verifies the checksum and ELF alignment of the checked-in AAR before publishing."
    inputs.files(prebuiltArtifact, alignmentMarker, verifier)
    executable = providers.gradleProperty("pythonExecutable").orNull
        ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    args(verifier.asFile, "--verify", "--output", prebuiltArtifact.asFile)
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
            artifact(prebuiltArtifact.asFile) {
                extension = "aar"
                builtBy(verifyOrtGenAiAar)
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
