package org.fxboomk.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import org.fxboomk.fcitx5.android.R
import org.fxboomk.fcitx5.android.utils.appContext
import org.fxboomk.fcitx5.android.utils.errorRuntime
import org.fxboomk.fcitx5.android.utils.extract
import org.fxboomk.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.FileFilter
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ThemeFilesManager {

    private val dir = File(appContext.getExternalFilesDir(null), "theme").also { it.mkdirs() }

    private fun themeFile(theme: Theme.Custom) = File(dir, theme.name + ".json")

    fun newCustomBackgroundImages(): Triple<String, File, File> {
        val themeName = UUID.randomUUID().toString()
        val (croppedImageFile, srcImageFile) = newBackgroundImagesForTheme(themeName)
        return Triple(themeName, croppedImageFile, srcImageFile)
    }

    fun newBackgroundImagesForTheme(themeName: String): Pair<File, File> {
        val folder = File(dir, safeThemePathComponent(themeName)).also { it.mkdirs() }
        val fileBase = safeThemePathComponent(themeName)
        val croppedImageFile = File(folder, "$fileBase-cropped.png")
        val srcImageFile = File(folder, "$fileBase-src")
        return croppedImageFile to srcImageFile
    }

    fun alignBackgroundAssetsWithThemeName(theme: Theme.Custom): Theme.Custom {
        val bg = theme.backgroundImage ?: return theme
        val appFilesDir = appContext.getExternalFilesDir(null) ?: return theme
        val themeDir = File(appFilesDir, "theme")
        val srcFile = resolveImagePath(bg.srcFilePath, appFilesDir, themeDir)
        val croppedFile = resolveImagePath(bg.croppedFilePath, appFilesDir, themeDir)

        val fileBase = safeThemePathComponent(theme.name)
        val targetDir = File(dir, fileBase).also { it.mkdirs() }
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
        if (!source.exists()) return
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    private fun cleanupEmptyParents(start: File?) {
        var current = start
        while (current != null && current != dir) {
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
        themeFile(theme).writeText(Json.encodeToString(CustomThemeSerializer, theme))
    }

    fun deleteThemeFiles(theme: Theme.Custom, allThemes: List<Theme.Custom> = emptyList()) {
        val themeDir = dir
        
        // Collect directories and files to process
        val dirsToCheck = mutableSetOf<File>()
        val filesToDelete = mutableSetOf<File>()
        
        theme.backgroundImage?.let {
            val croppedFile = File(it.croppedFilePath)
            val srcFile = File(it.srcFilePath)
            
            collectParentDirs(croppedFile, dirsToCheck)
            collectParentDirs(srcFile, dirsToCheck)
            
            // Only delete files if no other theme is using them
            if (!isFileInUse(it.croppedFilePath, allThemes)) {
                filesToDelete.add(croppedFile)
            }
            if (!isFileInUse(it.srcFilePath, allThemes)) {
                filesToDelete.add(srcFile)
            }
        }

        // Delete theme JSON file
        themeFile(theme).delete()
        
        // Delete image files not in use by other themes
        filesToDelete.forEach { it.delete() }

        // Cleanup empty directories from deepest to shallowest
        dirsToCheck.sortedByDescending { it.absolutePath.length }.forEach { dir ->
            cleanupEmptyDir(dir, allThemes, themeDir)
        }
    }
    
    /**
     * Check if a file path is used by any other theme.
     */
    private fun isFileInUse(filePath: String, allThemes: List<Theme.Custom>): Boolean {
        return allThemes.any { theme ->
            theme.backgroundImage?.let { bg ->
                bg.croppedFilePath == filePath || bg.srcFilePath == filePath
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
        if (dir.absolutePath == baseDir.absolutePath) return

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
        val files = dir.listFiles(FileFilter { it.extension == "json" }) ?: return mutableListOf()
        return files
            .sortedByDescending { it.lastModified() } // newest first
            .mapNotNull decode@{
                val raw = it.readText()
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

                // If we changed the JSON text (normalized) or the serializer reported migration, persist the corrected JSON
                if (normalized != raw || migratedFromSerializer) {
                    saveThemeFiles(resolvedTheme)
                }

                if (resolvedTheme.backgroundImage != null) {
                    if (!File(resolvedTheme.backgroundImage.croppedFilePath).exists() ||
                        !File(resolvedTheme.backgroundImage.srcFilePath).exists()
                    ) {
                        return@decode null
                    }
                }

                return@decode resolvedTheme
            }.toMutableList()
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

    /**
     * Resolve image path from JSON to absolute file path.
     * Handles both absolute paths and relative paths.
     *
     * Examples:
     * - Absolute: /Android/data/org.fxboomk.fcitx5.android/files/theme/xxx.png → appFilesDir/theme/xxx.png
     * - Relative: theme/xxx.png → appFilesDir/theme/xxx.png
     * - Relative: ./xxx.png → appFilesDir/theme/xxx.png
     * - Relative: xxx.png → appFilesDir/theme/xxx.png
     */
    private fun resolveImagePath(jsonPath: String, appFilesDir: File, themeDir: File): File {
        // If already an absolute path in current app, use it directly
        if (jsonPath.startsWith(appFilesDir.absolutePath)) {
            return File(jsonPath)
        }
        
        // Handle /Android/data/[package]/files/... paths (from other app installations)
        if (jsonPath.startsWith("/Android/data/") || jsonPath.startsWith("/data/data/")) {
            val rel = jsonPath.substringAfter("/files/").trimStart('/')
            return File(appFilesDir, rel)
        }
        
        // Handle relative paths
        // Remove leading ./ if present
        val cleanPath = jsonPath.removePrefix("./")
        
        // If path starts with "theme/", resolve relative to appFilesDir
        if (cleanPath.startsWith("theme/")) {
            return File(appFilesDir, cleanPath)
        }
        
        // Otherwise, assume it's relative to theme directory
        return File(themeDir, cleanPath)
    }

    /**
     * @return (newCreated, theme, migrated)
     */
    fun importTheme(src: InputStream): Result<Triple<Boolean, Theme.Custom, Boolean>> =
        runCatching {
            // Read entire ZIP to byte array for multiple encoding attempts
            val zipBytes = src.readBytes()
            // Try importing with different ZIP encodings (UTF-8, GBK, Big5)
            // This handles ZIP files created on Windows with non-UTF-8 encodings
            val encodings = listOf("UTF-8", "GBK", "Big5")
            for (encoding in encodings) {
                try {
                    return@runCatching importThemeWithEncoding(zipBytes.inputStream(), encoding)
                } catch (e: Exception) {
                    // Try next encoding
                }
            }
            
            // All encodings failed
            errorRuntime(R.string.exception_theme_src_image)
        }
    
    /**
     * Import theme with specific ZIP entry encoding.
     * @param encoding Character encoding for ZIP entry names
     */
    private fun importThemeWithEncoding(src: InputStream, encoding: String?): Triple<Boolean, Theme.Custom, Boolean> {
        val charset = encoding?.let { Charset.forName(it) }
        return ZipInputStream(src, charset).use { zipStream ->
            withTempDir { tempDir ->
                // Extract all files and keep track of their paths
                val extractedPaths = mutableMapOf<String, File>()
                var jsonFile: File? = null

                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val file = File(tempDir, entry.name)
                        file.parentFile?.mkdirs()
                        zipStream.copyTo(file.outputStream())
                        extractedPaths[entry.name] = file
                        if (entry.name.endsWith(".json")) {
                            jsonFile = file
                        }
                    }
                    entry = zipStream.nextEntry
                }
                jsonFile ?: errorRuntime(R.string.exception_theme_json)
                val rawJson = jsonFile.readText()
                // Normalize paths to current app's external files dir (replace package name)
                val normalizedJson = rawJson.replace(
                    Regex("""/Android/data/[^/]+/files"""),
                    "/Android/data/${appContext.packageName}/files"
                )
                val (decoded, migrated) = Json.decodeFromString(
                    CustomThemeSerializer.WithMigrationStatus,
                    normalizedJson
                )
                if (ThemeManager.BuiltinThemes.find { it.name == decoded.name } != null)
                    errorRuntime(R.string.exception_theme_name_clash)
                val oldTheme = ThemeManager.getTheme(decoded.name) as? Theme.Custom
                val newCreated = oldTheme == null
                val newTheme = if (decoded.backgroundImage != null) {
                    val appFilesDir = appContext.getExternalFilesDir(null)!!
                    val themeDir = File(appFilesDir, "theme")

                    // Resolve target paths: handle both absolute and relative paths
                    val srcTarget = resolveImagePath(
                        decoded.backgroundImage.srcFilePath,
                        appFilesDir,
                        themeDir
                    )
                    val croppedTarget = resolveImagePath(
                        decoded.backgroundImage.croppedFilePath,
                        appFilesDir,
                        themeDir
                    )

                    srcTarget.parentFile?.mkdirs()
                    croppedTarget.parentFile?.mkdirs()

                    val oldSrcFile = oldTheme?.backgroundImage?.srcFilePath?.let { File(it) }
                    val srcFileNameMatches = oldSrcFile?.name == srcTarget.name

                    // Find source file by filename (handles ZIP encoding differences)
                    val srcFileInZip = extractedPaths.values.find { it.name == srcTarget.name }

                    srcFileInZip?.let {
                        it.copyTo(srcTarget, overwrite = srcFileNameMatches)
                    } ?: errorRuntime(R.string.exception_theme_src_image)

                    val oldCroppedFile = oldTheme?.backgroundImage?.croppedFilePath?.let { File(it) }
                    val croppedFileNameMatches = oldCroppedFile?.name == croppedTarget.name

                    // Find cropped file by filename
                    val croppedFileInZip = extractedPaths.values.find { it.name == croppedTarget.name }

                    croppedFileInZip?.let {
                        it.copyTo(croppedTarget, overwrite = croppedFileNameMatches)
                    } ?: errorRuntime(R.string.exception_theme_cropped_image)

                    if (!srcFileNameMatches) {
                        oldSrcFile?.delete()
                    }
                    if (!croppedFileNameMatches) {
                        oldCroppedFile?.delete()
                    }

                    // Save theme with relative paths (relative to theme dir)
                    decoded.copy(
                        backgroundImage = decoded.backgroundImage.copy(
                            croppedFilePath = croppedTarget.relativeTo(themeDir).path.replace(
                                '\\',
                                '/'
                            ),
                            srcFilePath = srcTarget.relativeTo(themeDir).path.replace('\\', '/')
                        )
                    )
                } else {
                    decoded
                }
                saveThemeFiles(newTheme)
                Triple(newCreated, newTheme, migrated)
            }
        }
    }

}
