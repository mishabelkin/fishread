package org.read.mobile

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

internal data class InstalledCleanupModelInfo(
    val modelId: String,
    val fileName: String,
    val sourceUri: String,
    val absolutePath: String? = null,
    val sizeBytes: Long,
    val lastModified: Long
)

internal class LocalCleanupModelRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver
    private val settingsRepository = ReaderCleanupSettingsRepository(appContext)
    private val executionDir = File(appContext.cacheDir, "cleanup_model_exec").apply { mkdirs() }

    fun sharedModelFolderUri(): Uri? {
        return settingsRepository.sharedModelFolderUri()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    fun sharedModelFolderLabel(): String? {
        val folder = currentSharedFolder()
        if (folder != null) {
            return folder.name ?: sharedModelFolderUri()?.lastPathSegment
        }
        return sharedModelFolderUri()?.lastPathSegment
    }

    fun hasSharedModelFolder(): Boolean = currentSharedFolder() != null

    fun installedModelInfo(modelId: String): InstalledCleanupModelInfo? {
        if (modelId.isBlank()) {
            return null
        }
        return listInstalledModelInfos().firstOrNull { it.modelId == modelId }
    }

    fun selectSharedModelFolder(contentResolver: ContentResolver, uri: Uri) {
        val previousUri = sharedModelFolderUri()
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (previousUri != null && previousUri != uri) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    previousUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            sharedFolderListingCache.remove(previousUri.toString())
        }
        settingsRepository.saveSharedModelFolderUri(uri.toString())
        settingsRepository.saveSelectedModelId(null)
        sharedFolderListingCache.remove(uri.toString())
    }

    fun listInstalledModelInfos(): List<InstalledCleanupModelInfo> {
        val folderUri = settingsRepository.sharedModelFolderUri()?.takeIf { it.isNotBlank() } ?: return emptyList()
        sharedFolderListingCache[folderUri]?.let { return it }
        val folder = currentSharedFolder() ?: return emptyList()
        val modelFiles = mutableListOf<DocumentFile>()
        collectModelFiles(folder, modelFiles)
        val listedModels = modelFiles
            .mapNotNull(::toInstalledModelInfo)
            .sortedWith(
                compareByDescending<InstalledCleanupModelInfo> { it.lastModified }
                    .thenBy { it.fileName.lowercase(Locale.US) }
            )
        sharedFolderListingCache[folderUri] = listedModels
        return listedModels
    }

    fun refreshInstalledModelInfos(): List<InstalledCleanupModelInfo> {
        val folderUri = settingsRepository.sharedModelFolderUri()?.takeIf { it.isNotBlank() }
        if (folderUri.isNullOrBlank()) {
            return emptyList()
        }
        sharedFolderListingCache.remove(folderUri)
        return listInstalledModelInfos()
    }

    fun executionCacheFileCount(): Int {
        return executionDir.listFiles()
            ?.count { it.isFile }
            ?: 0
    }

    fun clearExecutionCache(): Int {
        val cachedFiles = executionDir.listFiles()
            ?.filter { it.isFile }
            .orEmpty()
        cachedFiles.forEach { file ->
            runCatching { file.delete() }
        }
        return cachedFiles.count { !it.exists() }
    }

    suspend fun ensureExecutionFile(info: InstalledCleanupModelInfo): File = withContext(Dispatchers.IO) {
        val sourceHash = sha256(info.modelId).take(16)
        val targetFile = File(
            executionDir,
            buildString {
                append(sourceHash)
                append('_')
                append(info.lastModified.coerceAtLeast(0L))
                append('_')
                append(info.sizeBytes.coerceAtLeast(0L))
                append('_')
                append(sanitizeFileName(info.fileName))
            }
        )

        if (targetFile.exists() && targetFile.length() == info.sizeBytes) {
            return@withContext targetFile
        }

        executionDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("${sourceHash}_") && it != targetFile }
            ?.forEach { stale -> stale.delete() }

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.part")
        tempFile.delete()
        val sourceUri = Uri.parse(info.sourceUri)
        contentResolver.openInputStream(sourceUri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Unable to read the selected shared model file.")

        if (tempFile.length() <= 0L) {
            tempFile.delete()
            throw IllegalArgumentException("The selected shared model file was empty.")
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        targetFile
    }

    private fun currentSharedFolder(): DocumentFile? {
        val uri = sharedModelFolderUri() ?: return null
        return runCatching {
            DocumentFile.fromTreeUri(appContext, uri)
                ?.takeIf { it.exists() && it.isDirectory }
        }.getOrNull()
    }

    private fun collectModelFiles(directory: DocumentFile, sink: MutableList<DocumentFile>) {
        val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
        children.forEach { child ->
            when {
                child.isDirectory -> collectModelFiles(child, sink)
                child.isFile && isSupportedExtension(child.name) -> sink += child
            }
        }
    }

    private fun toInstalledModelInfo(document: DocumentFile): InstalledCleanupModelInfo? {
        val fileName = document.name?.trim().orEmpty()
        if (fileName.isBlank()) {
            return null
        }
        return InstalledCleanupModelInfo(
            modelId = document.uri.toString(),
            fileName = fileName,
            sourceUri = document.uri.toString(),
            sizeBytes = document.length().coerceAtLeast(0L),
            lastModified = document.lastModified().coerceAtLeast(0L)
        )
    }

    private fun isSupportedExtension(fileName: String?): Boolean {
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            .orEmpty()
        return extension in SUPPORTED_MODEL_EXTENSIONS
    }

    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .trim('_')
        return cleaned.ifBlank { "cleanup_model.litertlm" }
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val SUPPORTED_MODEL_EXTENSIONS = setOf("litertlm", "task", "tflite")
        private val sharedFolderListingCache = ConcurrentHashMap<String, List<InstalledCleanupModelInfo>>()
    }
}
