package org.fxboomk.fcitx5.android.data.theme

import android.util.AtomicFile
import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.errorRuntime
import org.fxboomk.fcitx5.android.utils.errorT
import org.fxboomk.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ThemeFilesManager {

    private val dir = File(appContext.getExternalFilesDir(null), "theme").also { it.mkdirs() }

    private val imagesDir = File(dir, "images").also { it.mkdirs() }

    private fun themeFile(theme: Theme.Custom) = File(dir, theme.name + ".json")

    private fun themeFiles(): List<File> = dir.listFiles(FileFilter {
        it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak"))
    })?.map { File(dir, it.name.removeSuffix(".bak")) }?.distinct().orEmpty()

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val (croppedImageFile, srcImageFile) = newBackgroundImagesForTheme(themeName)
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun newBackgroundImagesForTheme(themeName: String): Pair<File, File> {
        val folder = imagesDir.also { it.mkdirs() }
        val fileBase = safeThemePathComponent(themeName)
        val croppedImageFile = File(folder, "$fileBase-cropped.png")
        val srcImageFile = File(folder, "$fileBase-src")
        return croppedImageFile to srcImageFile
    }

    fun duplicateBuiltinTheme(theme: Theme.Builtin, name: String): Theme.Custom {
        val customTheme = theme.deriveCustomNoBackground(name)
        val croppedAsset = theme.backgroundAssetPath ?: return customTheme
        val sourceAsset = requireNotNull(theme.backgroundSourceAssetPath) {
            "Bundled theme ${theme.name} is missing its source background image"
        }
        val (croppedFile, sourceFile) = newBackgroundImagesForTheme(name)
        try {
            copyThemeAsset(croppedAsset, croppedFile)
            copyThemeAsset(sourceAsset, sourceFile)
        } catch (error: Exception) {
            croppedFile.delete()
            sourceFile.delete()
            throw error
        }
        return customTheme.copy(
            backgroundImage = Theme.Custom.CustomBackground(
                croppedFilePath = croppedFile.absolutePath,
                srcFilePath = sourceFile.absolutePath,
                brightness = theme.backgroundBrightness,
                cropRect = theme.backgroundCropRect,
                cropRotation = theme.backgroundCropRotation,
                blurRadius = theme.backgroundBlurRadius
            )
        )
    }

    private fun copyThemeAsset(assetPath: String, target: File) {
        appContext.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    fun alignBackgroundAssetsWithThemeName(theme: Theme.Custom): Theme.Custom {
        val bg = theme.backgroundImage ?: return theme
        val appFilesDir = appContext.getExternalFilesDir(null) ?: return theme
        val themeDir = File(appFilesDir, "theme")
        val srcFile = resolveImagePath(bg.srcFilePath, appFilesDir, themeDir)
        val croppedFile = resolveImagePath(bg.croppedFilePath, appFilesDir, themeDir)

        val fileBase = safeThemePathComponent(theme.name)
        val targetDir = imagesDir.also { it.mkdirs() }
        val srcExt = srcFile.extension.takeIf { it.isNotEmpty() }
        val targetSrc = File(targetDir, buildString {
            append(fileBase)
            append("-src")
            if (srcExt != null) {
                append('.')
                append(srcExt)
            }
        })
        val targetCropped = File(targetDir, "$fileBase-cropped.png")

        moveOrCopyFile(croppedFile, targetCropped)
        moveOrCopyFile(srcFile, targetSrc)
        cleanupEmptyParents(croppedFile.parentFile)
        cleanupEmptyParents(srcFile.parentFile)

        return theme.copy(
            backgroundImage = bg.copy(
                croppedFilePath = targetCropped.absolutePath,
                srcFilePath = targetSrc.absolutePath
            )
        )
    }

    private fun moveOrCopyFile(source: File, target: File) {
        if (source.absolutePath == target.absolutePath) return
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        // A copied/renamed theme may still share these files with the saved original.
        if (!isFileInUse(source.absolutePath, ThemeManager.getAllThemes().filterIsInstance<Theme.Custom>())) {
            source.delete()
        }
    }

    private fun cleanupEmptyParents(start: File?) {
        var current = start
        while (current != null && current != dir && current != imagesDir &&
            current.absolutePath.startsWith(dir.absolutePath + File.separator)) {
            val files = current.listFiles()
            if (files != null && files.isEmpty()) {
                if (!current.delete()) break
            } else {
                break
            }
            current = current.parentFile
        }
    }

    private fun safeThemePathComponent(name: String): String {
        val trimmed = name.trim().ifEmpty { "theme" }
        return trimmed.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
    }

    fun saveThemeFiles(theme: Theme.Custom) {
        val stored = theme.backgroundImage?.let { bg ->
            fun relative(path: String): String {
                val file = resolveImagePath(path, dir.parentFile!!, dir)
                return file.relativeTo(dir).invariantSeparatorsPath
            }
            theme.copy(backgroundImage = bg.copy(
                croppedFilePath = relative(bg.croppedFilePath),
                srcFilePath = relative(bg.srcFilePath)
            ))
        } ?: theme
        val bytes = Json.encodeToString(CustomThemeSerializer, stored).encodeToByteArray()
        val file = AtomicFile(themeFile(theme))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    fun deleteThemeFiles(theme: Theme.Custom, allThemes: List<Theme.Custom> = emptyList()) {
        val themeDir = dir
        // Filenames are not necessarily the name stored in the JSON (e.g. copied files).
        // Include every backing file, even aliases hidden by listThemes' name deduplication.
        val configurations = themeFiles().mapNotNull { file ->
            runCatching {
                val raw = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
                file to Json.decodeFromString(CustomThemeSerializer, raw)
            }.getOrNull()
        }
        val matching = configurations.filter { it.second.name == theme.name }
        val remainingThemes = allThemes + configurations.map { it.second }
            .filter { it.name != theme.name }

        // Collect directories and files to process
        val dirsToCheck = mutableSetOf<File>()
        val filesToDelete = mutableSetOf<File>()
        
        (listOf(theme) + matching.map { it.second }).forEach { configuration ->
            configuration.backgroundImage?.let { background ->
                listOf(background.croppedFilePath, background.srcFilePath).forEach { path ->
                    val file = resolveImagePath(path, dir.parentFile!!, dir)
                    collectParentDirs(file, dirsToCheck)
                    if (!isFileInUse(file.path, remainingThemes)) {
                        filesToDelete.add(file)
                    }
                }
            }
        }

        // Always include the canonical file, even if it could not be decoded. AtomicFile
        // also deletes recovery files so a deleted theme cannot be restored on refresh.
        (matching.map { it.first } + themeFile(theme)).distinct().forEach {
            AtomicFile(it).delete()
            // Also remove pending writes on older Android versions whose AtomicFile
            // implementation only knows about the base file and .bak recovery file.
            File("${it.path}.new").delete()
        }
        
        // Delete image files not in use by other themes
        filesToDelete.forEach { it.delete() }

        // Cleanup empty directories from deepest to shallowest
        dirsToCheck.sortedByDescending { it.absolutePath.length }.forEach { dir ->
            cleanupEmptyDir(dir, remainingThemes, themeDir)
        }
    }
    
    /**
     * Check if a file path is used by any other theme.
     */
    private fun isFileInUse(filePath: String, allThemes: List<Theme.Custom>): Boolean {
        fun resolve(path: String) = resolveImagePath(path, dir.parentFile!!, dir).canonicalPath
        val target = resolve(filePath)
        return allThemes.any { theme ->
            theme.backgroundImage?.let { bg ->
                resolve(bg.croppedFilePath) == target || resolve(bg.srcFilePath) == target
            } ?: false
        }
    }
    
    /**
     * Collect all parent directories from a file up to the base theme dir.
     */
    private fun collectParentDirs(file: File, dirs: MutableSet<File>) {
        var parent = file.parentFile
        while (parent != null) {
            dirs.add(parent)
            parent = parent.parentFile
        }
    }
    
    /**
     * Clean up an empty directory if no other theme is using files in it.
     * Recursively cleans up parent directories if they become empty.
     *
     * @param dir The directory to check and potentially delete
     * @param allThemes List of remaining themes to check for directory usage
     * @param baseDir The base theme directory - stop cleanup at this level
     */
    private fun cleanupEmptyDir(dir: File, allThemes: List<Theme.Custom>, baseDir: File) {
        // Don't delete the base theme directory itself
        if (dir == imagesDir || dir == baseDir ||
            !dir.absolutePath.startsWith(baseDir.absolutePath + File.separator)) return

        // Check if directory exists and is empty
        if (!dir.exists() || !dir.isDirectory) return
        val remainingFiles = dir.listFiles()
        if (remainingFiles?.isNotEmpty() == true) return  // Directory not empty, skip

        // Check if any other theme is using files in this directory or its subdirectories
        val isDirInUse = allThemes.any { theme ->
            theme.backgroundImage?.let { bg ->
                bg.croppedFilePath.startsWith(dir.absolutePath) ||
                bg.srcFilePath.startsWith(dir.absolutePath)
            } ?: false
        }

        // Delete directory if not in use, then recursively check parent
        if (!isDirInUse && dir.delete()) {
            cleanupEmptyDir(dir.parentFile ?: return, allThemes, baseDir)
        }
    }

    fun listThemes(): MutableList<Theme.Custom> {
        imagesDir.mkdirs()
        runCatching {
            BundledThemes.removeLegacyInstalledThemes(dir)
        }.onFailure { Timber.w(it, "Failed to remove legacy bundled themes") }
        val loadedNames = mutableSetOf<String>()
        return themeFiles()
            .sortedByDescending { file ->
                // openRead restores .bak in preference to the base file, if present.
                val backup = File("${file.path}.bak")
                if (backup.isFile) backup.lastModified() else file.lastModified()
            }
            .mapNotNull decode@{
                val raw = AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() }
                // Normalize paths to this app's external files dir
                // Replace any package name with current app's package name
                val normalized = raw.replace(
                    Regex("""/Android/data/[^/]+/files"""),
                    "/Android/data/${appContext.packageName}/files"
                )
                val (theme, migratedFromSerializer) = runCatching {
                    Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, normalized)
                }.getOrElse { e ->
                    Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                    return@decode null
                }

                // Resolve relative paths to absolute paths
                val resolvedTheme = if (theme.backgroundImage != null) {
                    val appFilesDir = appContext.getExternalFilesDir(null)!!
                    val themeDir = File(appFilesDir, "theme")
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = resolveImagePath(
                                theme.backgroundImage.croppedFilePath,
                                appFilesDir,
                                themeDir
                            ).absolutePath,
                            srcFilePath = resolveImagePath(
                                theme.backgroundImage.srcFilePath,
                                appFilesDir,
                                themeDir
                            ).absolutePath
                        )
                    )
                } else {
                    theme
                }

                if (resolvedTheme.backgroundImage != null) {
                    if (!File(resolvedTheme.backgroundImage.croppedFilePath).exists() ||
                        !File(resolvedTheme.backgroundImage.srcFilePath).exists()
                    ) {
                        return@decode null
                    }
                }

                // Keep the newest valid theme before migration can write a canonical file.
                // An older alias must not overwrite it or produce a second card.
                if (!loadedNames.add(resolvedTheme.name)) return@decode null

                return@decode runCatching {
                    val relocated = copyLegacyImages(resolvedTheme)
                    if (relocated != resolvedTheme || normalized != raw || migratedFromSerializer) {
                        saveThemeFiles(relocated)
                    }
                    relocated
                }.getOrElse { error ->
                    Timber.w(error, "Failed to migrate theme images for ${theme.name}")
                    resolvedTheme
                }
            }
            .toMutableList()
    }

    private fun copyLegacyImages(theme: Theme.Custom): Theme.Custom {
        val bg = theme.backgroundImage ?: return theme
        val cropped = File(bg.croppedFilePath)
        val source = File(bg.srcFilePath)
        if (cropped.parentFile == imagesDir && source.parentFile == imagesDir) return theme
        val (newCropped, newSource) = newBackgroundImagesForTheme("${theme.name}-${UUID.randomUUID()}")
        try {
            cropped.copyTo(newCropped)
            source.copyTo(newSource)
        } catch (e: Exception) {
            newCropped.delete()
            newSource.delete()
            throw e
        }
        // Keep legacy originals: another theme (including an unreadable config) may share them.
        return theme.copy(backgroundImage = bg.copy(
            croppedFilePath = newCropped.absolutePath,
            srcFilePath = newSource.absolutePath
        ))
    }

    /**
     * [dest] will be closed on finished
     */
    fun exportTheme(theme: Theme.Custom, dest: OutputStream) =
        runCatching {
            ZipOutputStream(dest.buffered()).use { zipStream ->
                // we don't export the internal path of images
                val tweakedTheme = theme.backgroundImage?.let {
                    theme.copy(
                        backgroundImage = theme.backgroundImage.copy(
                            croppedFilePath = theme.backgroundImage.croppedFilePath
                                .substringAfterLast('/'),
                            srcFilePath = theme.backgroundImage.srcFilePath
                                .substringAfterLast('/'),
                        )
                    )
                } ?: theme
                if (tweakedTheme.backgroundImage != null) {
                    requireNotNull(theme.backgroundImage)
                    // write cropped image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.croppedFilePath))
                    File(theme.backgroundImage.croppedFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                    // write src image
                    zipStream.putNextEntry(ZipEntry(tweakedTheme.backgroundImage.srcFilePath))
                    File(theme.backgroundImage.srcFilePath).inputStream()
                        .use { it.copyTo(zipStream) }
                }
                // write json
                zipStream.putNextEntry(ZipEntry("${tweakedTheme.name}.json"))
                zipStream.write(
                    Json.encodeToString(CustomThemeSerializer, tweakedTheme)
                        .encodeToByteArray()
                )
                // done
                zipStream.closeEntry()
            }
        }

    /** Resolve current, relative and legacy installation paths without writing outside theme/. */
    private fun resolveImagePath(jsonPath: String, appFilesDir: File, themeDir: File): File {
        val path = jsonPath.replace('\\', '/')
        val relative = path.substringAfter("/files/", path).removePrefix("./").removePrefix("theme/")
        val direct = if (path.startsWith(appFilesDir.absolutePath + "/")) File(path) else File(themeDir, relative)
        val root = themeDir.canonicalPath + File.separator
        if (direct.canonicalPath.startsWith(root) && direct.isFile) return direct
        val image = File(imagesDir, path.substringAfterLast('/'))
        if (image.isFile) return image
        return if (direct.canonicalPath.startsWith(root)) direct else image
    }

    /**
     * @return (newCreated, theme, migrated)
     */
    fun importTheme(src: InputStream, importedName: String? = null): Result<Triple<Boolean, Theme.Custom, Boolean>> =
        runCatching {
            // Read entire ZIP to byte array for multiple encoding attempts
            val zipBytes = src.readBytes()
            // Try importing with different ZIP encodings (UTF-8, GBK, Big5)
            // This handles ZIP files created on Windows with non-UTF-8 encodings
            val encodings = listOf("UTF-8", "GBK", "Big5")
            var lastError: Exception? = null
            for (encoding in encodings) {
                try {
                    return@runCatching importThemeWithEncoding(zipBytes.inputStream(), encoding, importedName)
                } catch (e: ThemeImportException) {
                    // Definitive domain error (e.g. name clash): retrying with another
                    // encoding cannot fix it and would replace the message
                    throw e
                } catch (e: Exception) {
                    // Try next encoding
                    lastError = e
                }
            }

            // All encodings failed
            Timber.w(lastError, "Theme import failed with all zip encodings")
            errorRuntime(R.string.exception_theme_parse)
        }

    /**
     * Import a bare theme configuration JSON (no ZIP wrapper).
     * Themes referencing background images cannot ship the images in a bare
     * JSON and are rejected; use the ZIP export/import flow for those.
     */
    fun importThemeJson(raw: String): Result<Theme.Custom> = runCatching {
        val decoded = Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, raw).first
        if (decoded.backgroundImage != null) {
            errorRuntime(R.string.exception_theme_json_requires_zip)
        }
        require(decoded.name.isNotBlank() && decoded.name == safeThemePathComponent(decoded.name)) {
            "Invalid theme name"
        }
        if (ThemeManager.getAllThemes().any { it !is Theme.Custom && it.name == decoded.name })
            errorT(::ThemeImportException, R.string.exception_theme_name_clash)
        val occupiedNames = buildSet {
            addAll(ThemeManager.getAllThemes().map { it.name })
            addAll(listThemes().map { it.name })
            addAll(dir.listFiles().orEmpty().mapNotNull { file ->
                when {
                    file.name.endsWith(".json.bak") -> file.name.removeSuffix(".json.bak")
                    file.name.endsWith(".json.new") -> file.name.removeSuffix(".json.new")
                    file.name.endsWith(".json") -> file.name.removeSuffix(".json")
                    else -> null
                }
            })
        }
        val newTheme = decoded.copy(name = uniqueImportName(decoded.name, occupiedNames))
        saveThemeFiles(newTheme)
        newTheme
    }

    private fun uniqueImportName(name: String, occupiedNames: Set<String>): String {
        if (name !in occupiedNames) return name
        val base = "$name (imported)"
        if (base !in occupiedNames) return base
        var index = 2
        while (true) {
            val candidate = "$base $index"
            if (candidate !in occupiedNames) return candidate
            index++
        }
    }

    fun decodeTheme(src: InputStream): Result<Theme.Custom> =
        runCatching {
            val zipBytes = src.readBytes()
            val encodings = listOf("UTF-8", "GBK", "Big5")
            for (encoding in encodings) {
                try {
                    return@runCatching decodeThemeWithEncoding(zipBytes.inputStream(), encoding)
                } catch (e: Exception) {
                    // Try next encoding
                }
            }
            errorRuntime(R.string.exception_theme_json)
        }

    private fun decodeThemeWithEncoding(src: InputStream, encoding: String): Theme.Custom =
        withTempDir { tempDir ->
            ThemeArchive.read(src, tempDir, Charset.forName(encoding)).first
        }

    private fun importThemeWithEncoding(
        src: InputStream,
        encoding: String?,
        importedName: String?
    ): Triple<Boolean, Theme.Custom, Boolean> = withTempDir { tempDir ->
        val (decoded, migrated) = ThemeArchive.read(src, tempDir, Charset.forName(encoding ?: "UTF-8"))
        val name = importedName ?: ThemeManager.nonActiveImportName(decoded.name)
        require(name.isNotBlank() && name == safeThemePathComponent(name)) { "Invalid theme name" }
        if (ThemeManager.BuiltinThemes.any { it.name == name })
            errorT(::ThemeImportException, R.string.exception_theme_name_clash)
        val oldTheme = ThemeManager.getTheme(name) as? Theme.Custom
        val copied = mutableListOf<File>()
        try {
            val bg = decoded.backgroundImage
            val newTheme = if (bg != null) {
                // Independent destinations protect existing themes and make failed imports reversible.
                val (croppedTarget, srcTarget) = newBackgroundImagesForTheme("$name-${UUID.randomUUID()}")
                copied.addAll(listOf(croppedTarget, srcTarget))
                File(bg.croppedFilePath).copyTo(croppedTarget)
                File(bg.srcFilePath).copyTo(srcTarget)
                decoded.copy(name = name, backgroundImage = bg.copy(
                    croppedFilePath = croppedTarget.absolutePath,
                    srcFilePath = srcTarget.absolutePath
                ))
            } else decoded.copy(name = name)
            saveThemeFiles(newTheme)
            // Retire only assets no other saved theme references, after the replacement is complete.
            runCatching { oldTheme?.backgroundImage?.let { old ->
                val otherThemes = ThemeManager.getAllThemes().filterIsInstance<Theme.Custom>()
                    .filter { it.name != name }
                listOf(old.croppedFilePath, old.srcFilePath).forEach { path ->
                    if (!isFileInUse(path, otherThemes)) {
                        val file = resolveImagePath(path, dir.parentFile!!, dir)
                        if (file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) file.delete()
                    }
                }
            } }.onFailure { Timber.w(it, "Failed to remove replaced theme images") }
            Triple(oldTheme == null, newTheme, migrated)
        } catch (e: Exception) {
            copied.forEach { it.delete() }
            throw e
        }
    }

}

/** Import failed for a reason that no other ZIP encoding could fix. */
class ThemeImportException(message: String) : RuntimeException(message)
