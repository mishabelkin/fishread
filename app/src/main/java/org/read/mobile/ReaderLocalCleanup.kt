package org.read.mobile

import android.content.Context
import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Backend.CPU as LiteRtCpuBackend
import com.google.ai.edge.litertlm.Conversation as LiteRtConversation
import com.google.ai.edge.litertlm.Content.Text as LiteRtTextContent
import com.google.ai.edge.litertlm.ConversationConfig as LiteRtConversationConfig
import com.google.ai.edge.litertlm.Engine as LiteRtEngine
import com.google.ai.edge.litertlm.EngineConfig as LiteRtEngineConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.SamplerConfig as LiteRtSamplerConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile

internal const val READER_CLEANUP_PROMPT_VERSION = 11
internal const val READER_SUMMARY_PROMPT_VERSION = 2
private const val TARGET_CLEANUP_CHUNK_CHARS = 1100
private const val TARGET_PDF_CLEANUP_CHUNK_CHARS = 1300
private const val MAX_CLEANUP_CHUNK_PARAGRAPHS = 3
private const val MAX_PDF_CLEANUP_CHUNK_PARAGRAPHS = 3
private const val CLEANUP_RESPONSE_BREAK = "<READ_PARAGRAPH_BREAK>"
internal const val CLEANUP_DROP_PARAGRAPH = "<READ_DROP_PARAGRAPH>"
private const val GEMMA_CLEANUP_MAX_TOKENS = 1024
private const val LITERTLM_CLEANUP_MAX_NUM_TOKENS = 4096
private const val SUMMARY_GENERATION_TIMEOUT_MS = 240_000L
private const val GEMMA_CLEANUP_TOP_K = 1
private const val GEMMA_CLEANUP_TEMPERATURE = 0.0f
private const val GEMMA_CLEANUP_RANDOM_SEED = 7
private const val LITERT_GPU_ENABLED = false
private const val LITERT_NPU_ENABLED = false

internal enum class CleanupBackendKind {
    MediaPipeTask,
    LiteRtLm
}

internal fun supportsDocumentCleanupBackend(backendKind: CleanupBackendKind): Boolean {
    return backendKind == CleanupBackendKind.MediaPipeTask
}

internal data class CleanupRuntimeSpec(
    val backendKind: CleanupBackendKind,
    val supportsFileName: (String) -> Boolean,
    val initializationErrorMessage: String
)

internal data class DiscoveredCleanupModel(
    val modelId: String,
    val fileName: String,
    val displayName: String,
    val runtimeSpec: CleanupRuntimeSpec
)

private data class CleanupArchiveProbe(
    val displayName: String?,
    val runtimeKind: CleanupBackendKind?,
    val metadataText: String?
)

private data class CleanupArchiveProbeCacheKey(
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModified: Long
)

private val cleanupArchiveProbeCache = ConcurrentHashMap<CleanupArchiveProbeCacheKey, CleanupArchiveProbe>()

private val MEDIA_PIPE_TASK_RUNTIME_SPEC = CleanupRuntimeSpec(
    backendKind = CleanupBackendKind.MediaPipeTask,
    supportsFileName = ::isSupportedMediaPipeTaskCleanupFile,
    initializationErrorMessage = "This .task bundle is not safe to run with the current on-device backend."
)

private val LITERT_LM_RUNTIME_SPEC = CleanupRuntimeSpec(
    backendKind = CleanupBackendKind.LiteRtLm,
    supportsFileName = ::isSupportedLiteRtLmCleanupFile,
    initializationErrorMessage = "This .litertlm model could not start on the selected on-device backend."
)

internal fun discoverCleanupRuntimeSpec(fileName: String): CleanupRuntimeSpec? {
    return when {
        LITERT_LM_RUNTIME_SPEC.supportsFileName(fileName) -> LITERT_LM_RUNTIME_SPEC
        MEDIA_PIPE_TASK_RUNTIME_SPEC.supportsFileName(fileName) -> MEDIA_PIPE_TASK_RUNTIME_SPEC
        else -> null
    }
}

internal fun isSupportedCleanupBackendFile(fileName: String): Boolean {
    return discoverCleanupRuntimeSpec(fileName) != null
}

internal fun discoverCleanupModel(info: InstalledCleanupModelInfo): DiscoveredCleanupModel? {
    val probe = probeCleanupArchive(info)
    val runtimeSpec = probe?.runtimeKind?.let(::cleanupRuntimeSpecForKind)
        ?: discoverCleanupRuntimeSpec(info.fileName)
        ?: return null
    if (!runtimeSpec.supportsFileName(info.fileName)) {
        return null
    }
    return DiscoveredCleanupModel(
        modelId = info.modelId,
        fileName = info.fileName,
        displayName = probe?.displayName ?: cleanupModelDisplayName(info.fileName),
        runtimeSpec = runtimeSpec
    )
}

private fun cleanupRuntimeSpecForKind(kind: CleanupBackendKind): CleanupRuntimeSpec {
    return when (kind) {
        CleanupBackendKind.MediaPipeTask -> MEDIA_PIPE_TASK_RUNTIME_SPEC
        CleanupBackendKind.LiteRtLm -> LITERT_LM_RUNTIME_SPEC
    }
}

internal data class CleanupChunk(
    val paragraphBlockIndices: List<Int>,
    val paragraphs: List<String>,
    val contextBefore: String? = null,
    val contextAfter: String? = null
)

internal data class CleanupChunkResult(
    val paragraphs: List<String>
)

internal enum class CleanupProfile(
    val allowParagraphDrop: Boolean
) {
    WEB(allowParagraphDrop = true),
    PDF(allowParagraphDrop = false)
}

private data class CleanupProfileConfig(
    val targetChunkChars: Int,
    val maxChunkParagraphs: Int,
    val minLengthRatio: Double,
    val maxLengthRatio: Double,
    val minParagraphRatio: Double,
    val maxParagraphRatio: Double,
    val minGlobalTokenPreservation: Float,
    val minParagraphTokenPreservation: Float,
    val minParagraphContentTokenPreservation: Float
)

private fun cleanupProfileConfig(cleanupProfile: CleanupProfile): CleanupProfileConfig {
    return when (cleanupProfile) {
        CleanupProfile.WEB -> CleanupProfileConfig(
            targetChunkChars = TARGET_CLEANUP_CHUNK_CHARS,
            maxChunkParagraphs = MAX_CLEANUP_CHUNK_PARAGRAPHS,
            minLengthRatio = 0.38,
            maxLengthRatio = 1.20,
            minParagraphRatio = 0.42,
            maxParagraphRatio = 1.25,
            minGlobalTokenPreservation = 0.54f,
            minParagraphTokenPreservation = 0.50f,
            minParagraphContentTokenPreservation = 0.68f
        )
        CleanupProfile.PDF -> CleanupProfileConfig(
            targetChunkChars = TARGET_PDF_CLEANUP_CHUNK_CHARS,
            maxChunkParagraphs = MAX_PDF_CLEANUP_CHUNK_PARAGRAPHS,
            minLengthRatio = 0.40,
            maxLengthRatio = 1.12,
            minParagraphRatio = 0.45,
            maxParagraphRatio = 1.15,
            minGlobalTokenPreservation = 0.56f,
            minParagraphTokenPreservation = 0.52f,
            minParagraphContentTokenPreservation = 0.70f
        )
    }
}

private val WEB_PROTECTED_TOKEN_IGNORES = setOf(
    "a", "an", "and", "as", "at", "be", "but", "by", "for", "from", "if",
    "in", "into", "is", "it", "its", "of", "on", "or", "so", "than", "that",
    "the", "their", "there", "these", "this", "those", "to", "was", "we",
    "were", "while", "with", "you", "your",
    "after", "before", "during", "when", "where", "why", "how", "however",
    "meanwhile", "although", "because", "since",
    "today", "tomorrow", "yesterday",
    "share", "comments", "comment", "print", "email", "copy", "link",
    "facebook", "twitter", "linkedin", "reddit", "whatsapp", "telegram",
    "safeframe", "container",
    "related", "latest", "read", "more", "photo", "photos", "image", "images",
    "credit", "credits", "caption", "captions", "source", "sources",
    "advertisement", "adchoices", "newsletter", "subscribe"
)

internal interface LocalCleanupModel {
    val isAvailable: Boolean

    suspend fun warmup()

    suspend fun cleanChunk(
        paragraphs: List<String>,
        contextBefore: String?,
        contextAfter: String?,
        instructions: String,
        cleanupProfile: CleanupProfile
    ): CleanupChunkResult
}

private object NoOpCleanupModel : LocalCleanupModel {
    override val isAvailable: Boolean = true

    override suspend fun warmup() = Unit

    override suspend fun cleanChunk(
        paragraphs: List<String>,
        contextBefore: String?,
        contextAfter: String?,
        instructions: String,
        cleanupProfile: CleanupProfile
    ): CleanupChunkResult {
        return CleanupChunkResult(paragraphs = paragraphs)
    }
}

internal data class ResolvedInstalledCleanupModel(
    val info: InstalledCleanupModelInfo,
    val discoveredModel: DiscoveredCleanupModel,
    val runtimeSpec: CleanupRuntimeSpec
)

internal fun findInstalledCleanupModelInfo(
    installedInfos: List<InstalledCleanupModelInfo>,
    modelId: String?
): InstalledCleanupModelInfo? {
    return modelId
        ?.takeIf { it.isNotBlank() }
        ?.let { preferred -> installedInfos.firstOrNull { it.modelId == preferred } }
}

internal fun resolveInstalledCleanupModel(
    installedInfos: List<InstalledCleanupModelInfo>,
    preferredModelId: String?
): ResolvedInstalledCleanupModel? {
    findInstalledCleanupModelInfo(
        installedInfos = installedInfos,
        modelId = preferredModelId
    )
        ?.let { preferredInfo ->
            discoverCleanupModel(preferredInfo)?.let { discovered ->
                return ResolvedInstalledCleanupModel(
                    info = preferredInfo,
                    discoveredModel = discovered,
                    runtimeSpec = discovered.runtimeSpec
                )
            }
        }

    return installedInfos.firstNotNullOfOrNull { info ->
        discoverCleanupModel(info)?.let { discovered ->
            ResolvedInstalledCleanupModel(
                info = info,
                discoveredModel = discovered,
                runtimeSpec = discovered.runtimeSpec
            )
        }
    }
}

private data class LiteRtEngineKey(
    val modelPath: String,
    val backendKey: String
)

private data class LiteRtBackendCandidate(
    val backendKey: String,
    val label: String
)

private fun isLiteRtGpuBlockedForFileName(fileName: String?): Boolean {
    if (!LITERT_GPU_ENABLED) {
        return true
    }
    val normalized = fileName
        ?.lowercase(Locale.US)
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        .orEmpty()
    return normalized.contains("gemma-3n")
}

private fun isLiteRtNpuBlockedForFileName(fileName: String?): Boolean {
    if (!LITERT_NPU_ENABLED) {
        return true
    }
    val normalized = fileName
        ?.lowercase(Locale.US)
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        .orEmpty()
    return normalized.contains("gemma-3n")
}

internal fun liteRtBackendKeysForMode(
    mode: ReaderCleanupAccelerationMode,
    fileName: String? = null
): List<String> {
    val requested = when (mode) {
        ReaderCleanupAccelerationMode.Cpu -> listOf(ReaderCleanupAccelerationMode.Cpu.storageValue)
        ReaderCleanupAccelerationMode.Gpu -> listOf(ReaderCleanupAccelerationMode.Gpu.storageValue)
        ReaderCleanupAccelerationMode.Npu -> listOf(ReaderCleanupAccelerationMode.Npu.storageValue)
        ReaderCleanupAccelerationMode.Auto -> listOf(
            ReaderCleanupAccelerationMode.Npu.storageValue,
            ReaderCleanupAccelerationMode.Gpu.storageValue,
            ReaderCleanupAccelerationMode.Cpu.storageValue
        )
    }
    val filtered = requested.filterNot { backendKey ->
        when (backendKey) {
            ReaderCleanupAccelerationMode.Gpu.storageValue -> isLiteRtGpuBlockedForFileName(fileName)
            ReaderCleanupAccelerationMode.Npu.storageValue -> isLiteRtNpuBlockedForFileName(fileName)
            else -> false
        }
    }
    return if (filtered.isNotEmpty()) filtered else listOf(ReaderCleanupAccelerationMode.Cpu.storageValue)
}

internal fun liteRtBackendLabelForKey(backendKey: String): String {
    return when (backendKey) {
        ReaderCleanupAccelerationMode.Npu.storageValue -> "NPU / TPU"
        ReaderCleanupAccelerationMode.Gpu.storageValue -> "GPU"
        else -> "CPU"
    }
}

private fun liteRtBackendCandidatesForMode(
    mode: ReaderCleanupAccelerationMode,
    fileName: String? = null
): List<LiteRtBackendCandidate> {
    return liteRtBackendKeysForMode(mode, fileName).map { backendKey ->
        when (backendKey) {
            ReaderCleanupAccelerationMode.Npu.storageValue -> LiteRtBackendCandidate(
                backendKey = backendKey,
                label = liteRtBackendLabelForKey(backendKey)
            )
            ReaderCleanupAccelerationMode.Gpu.storageValue -> LiteRtBackendCandidate(
                backendKey = backendKey,
                label = liteRtBackendLabelForKey(backendKey)
            )
            else -> LiteRtBackendCandidate(
                backendKey = ReaderCleanupAccelerationMode.Cpu.storageValue,
                label = liteRtBackendLabelForKey(ReaderCleanupAccelerationMode.Cpu.storageValue)
            )
        }
    }
}

private class MediaPipeTaskRuntime(private val appContext: Context) {
    private val modelLock = Any()
    @Volatile private var inference: LlmInference? = null
    @Volatile private var inferencePath: String? = null
    @Volatile private var failedInferencePath: String? = null
    @Volatile private var failedInferenceMessage: String? = null

    fun isAvailable(modelPath: String): Boolean = modelPath != failedInferencePath

    fun warmup(modelPath: String, errorMessage: String) {
        ensureInference(modelPath, errorMessage)
    }

    suspend fun generateResponse(
        modelPath: String,
        errorMessage: String,
        prompt: String,
        expectedParagraphCount: Int
    ): List<String> {
        val response = generateText(modelPath, errorMessage, prompt)
        return parseCleanupParagraphs(response, expectedParagraphCount)
    }

    fun generateText(
        modelPath: String,
        errorMessage: String,
        prompt: String,
        timeoutMillis: Long? = null,
        onPartialText: ((String) -> Unit)? = null
    ): String {
        val engine = ensureInference(modelPath, errorMessage)
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(GEMMA_CLEANUP_TOP_K)
            .setTemperature(GEMMA_CLEANUP_TEMPERATURE)
            .setRandomSeed(GEMMA_CLEANUP_RANDOM_SEED)
            .build()
        return LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
            session.addQueryChunk(prompt)
            if (timeoutMillis == null && onPartialText == null) {
                session.generateResponse()
            } else {
                var streamedText = ""
                val future = if (onPartialText == null) {
                    session.generateResponseAsync()
                } else {
                    session.generateResponseAsync(
                        ProgressListener<String> { partial, _ ->
                            streamedText = mergeStreamingText(streamedText, partial)
                            normalizeStreamingSummaryText(streamedText)?.let(onPartialText)
                        }
                    )
                }
                try {
                    if (timeoutMillis != null) {
                        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
                    } else {
                        future.get()
                    }
                } catch (error: TimeoutException) {
                    session.cancelGenerateResponseAsync()
                    throw IllegalStateException("The local model took too long to summarize this document.", error)
                }
            }
        }
    }

    private fun ensureInference(modelPath: String, errorMessage: String): LlmInference {
        if (failedInferencePath == modelPath) {
            throw IllegalStateException(failedInferenceMessage ?: errorMessage)
        }

        inference?.takeIf { inferencePath == modelPath }?.let { return it }

        synchronized(modelLock) {
            inference?.takeIf { inferencePath == modelPath }?.let { return it }

            inference?.close()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(GEMMA_CLEANUP_MAX_TOKENS)
                .setMaxTopK(GEMMA_CLEANUP_TOP_K)
                .build()
            return try {
                val created = LlmInference.createFromOptions(appContext, options)
                inference = created
                inferencePath = modelPath
                failedInferencePath = null
                failedInferenceMessage = null
                created
            } catch (error: Throwable) {
                inference = null
                inferencePath = null
                failedInferencePath = modelPath
                failedInferenceMessage = errorMessage
                throw IllegalStateException(failedInferenceMessage, error)
            }
        }
    }
}

private class LiteRtLmRuntime(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "litertlm_cleanup").apply {
        mkdirs()
    }
    private val engineLock = Any()
    @Volatile private var engine: LiteRtEngine? = null
    @Volatile private var engineKey: LiteRtEngineKey? = null
    private val failedEngineMessages = ConcurrentHashMap<LiteRtEngineKey, String>()

    fun isAvailable(
        modelPath: String,
        accelerationMode: ReaderCleanupAccelerationMode
    ): Boolean {
        return liteRtBackendCandidatesForMode(accelerationMode, File(modelPath).name).any { candidate ->
            !failedEngineMessages.containsKey(LiteRtEngineKey(modelPath, candidate.backendKey))
        }
    }

    fun warmup(
        modelPath: String,
        errorMessage: String,
        accelerationMode: ReaderCleanupAccelerationMode
    ) {
        ensureEngine(modelPath, errorMessage, accelerationMode)
    }

    fun activeBackendLabel(): String? {
        val currentKey = engineKey ?: return null
        return liteRtBackendLabelForKey(currentKey.backendKey)
    }

    suspend fun generateResponse(
        modelPath: String,
        errorMessage: String,
        prompt: String,
        expectedParagraphCount: Int,
        accelerationMode: ReaderCleanupAccelerationMode
    ): List<String> {
        val response = generateText(
            modelPath = modelPath,
            errorMessage = errorMessage,
            prompt = prompt,
            accelerationMode = accelerationMode
        )
        return parseCleanupParagraphs(
            response,
            expectedParagraphCount = expectedParagraphCount
        )
    }

    suspend fun generateText(
        modelPath: String,
        errorMessage: String,
        prompt: String,
        accelerationMode: ReaderCleanupAccelerationMode,
        timeoutMillis: Long? = null,
        onPartialText: ((String) -> Unit)? = null
    ): String {
        val samplerConfig = LiteRtSamplerConfig(
            topK = GEMMA_CLEANUP_TOP_K,
            topP = 1.0,
            temperature = GEMMA_CLEANUP_TEMPERATURE.toDouble(),
            seed = GEMMA_CLEANUP_RANDOM_SEED
        )
        return withLiteRtConversation(
            modelPath = modelPath,
            errorMessage = errorMessage,
            accelerationMode = accelerationMode,
            conversationConfig = LiteRtConversationConfig(samplerConfig = samplerConfig)
        ) { conversation ->
            if (timeoutMillis == null && onPartialText == null) {
                val response = conversation.sendMessage(prompt, emptyMap<String, Any>())
                extractLiteRtLmResponseText(response)
            } else {
                var latestText = ""
                try {
                    withTimeout(timeoutMillis ?: Long.MAX_VALUE) {
                        conversation.sendMessageAsync(prompt, emptyMap<String, Any>()).collect { message ->
                            val text = extractLiteRtLmResponseText(message)
                            val mergedText = mergeStreamingText(latestText, text)
                            if (mergedText.isNotBlank() && mergedText != latestText) {
                                latestText = mergedText
                                normalizeStreamingSummaryText(mergedText)?.let { partial ->
                                    onPartialText?.invoke(partial)
                                }
                            }
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    runCatching { conversation.cancelProcess() }
                    throw IllegalStateException("The local model took too long to summarize this document.", error)
                }
                latestText
            }
        }
    }

    private suspend fun <T> withLiteRtConversation(
        modelPath: String,
        errorMessage: String,
        accelerationMode: ReaderCleanupAccelerationMode,
        conversationConfig: LiteRtConversationConfig,
        block: suspend (LiteRtConversation) -> T
    ): T {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val currentEngine = ensureEngine(modelPath, errorMessage, accelerationMode)
            val conversation = try {
                currentEngine.createConversation(conversationConfig)
            } catch (error: Throwable) {
                if (attempt == 0 && isLiteRtSingleSessionError(error)) {
                    invalidateEngine(modelPath)
                    lastError = error
                    return@repeat
                }
                throw error
            }

            try {
                return block(conversation)
            } finally {
                val closeFailure = closeLiteRtConversation(modelPath, conversation)
                if (closeFailure != null && lastError == null) {
                    lastError = closeFailure
                }
            }
        }

        throw IllegalStateException(
            "$errorMessage Read! had to reset the local model conversation and could not recover.",
            lastError
        )
    }

    private fun closeLiteRtConversation(
        modelPath: String,
        conversation: LiteRtConversation
    ): Throwable? {
        return runCatching {
            conversation.close()
        }.exceptionOrNull()?.also {
            invalidateEngine(modelPath)
        }
    }

    private fun invalidateEngine(modelPath: String? = null) {
        synchronized(engineLock) {
            val activeKey = engineKey
            if (modelPath != null && activeKey?.modelPath != modelPath) {
                return
            }
            closeLiteRtResource(engine)
            engine = null
            engineKey = null
        }
    }

    private fun isLiteRtSingleSessionError(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }
            .mapNotNull { throwable -> throwable.message }
            .any { message ->
                val normalized = message.lowercase(Locale.US)
                normalized.contains("a session already exists") ||
                    normalized.contains("only one session is supported at a time")
            }
    }

    private fun ensureEngine(
        modelPath: String,
        errorMessage: String,
        accelerationMode: ReaderCleanupAccelerationMode
    ): LiteRtEngine {
        val candidates = liteRtBackendCandidatesForMode(accelerationMode, File(modelPath).name)
        currentEngineIfUsable(modelPath, candidates)?.let { return it }

        synchronized(engineLock) {
            currentEngineIfUsable(modelPath, candidates)?.let { return it }

            closeLiteRtResource(engine)
            engine = null
            engineKey = null
            var lastError: Throwable? = null
            var lastMessage = errorMessage

            candidates.forEach { candidate ->
                val key = LiteRtEngineKey(modelPath, candidate.backendKey)
                val cachedFailure = failedEngineMessages[key]
                if (cachedFailure != null) {
                    lastMessage = cachedFailure
                    return@forEach
                }
                try {
                    val config = LiteRtEngineConfig(
                        modelPath = modelPath,
                        backend = createLiteRtBackend(candidate.backendKey),
                        maxNumTokens = LITERTLM_CLEANUP_MAX_NUM_TOKENS,
                        cacheDir = cacheDir.absolutePath
                    )
                    val created = LiteRtEngine(config)
                    created.initialize()
                    engine = created
                    engineKey = key
                    return created
                } catch (error: Throwable) {
                    closeLiteRtResource(engine)
                    engine = null
                    engineKey = null
                    val backendMessage = buildLiteRtBackendErrorMessage(
                        baseErrorMessage = errorMessage,
                        candidate = candidate,
                        accelerationMode = accelerationMode
                    )
                    failedEngineMessages[key] = backendMessage
                    lastMessage = backendMessage
                    lastError = error
                }
            }

            throw IllegalStateException(lastMessage, lastError)
        }
    }

    private fun currentEngineIfUsable(
        modelPath: String,
        candidates: List<LiteRtBackendCandidate>
    ): LiteRtEngine? {
        val currentKey = engineKey ?: return null
        if (currentKey.modelPath != modelPath) {
            return null
        }
        if (candidates.none { it.backendKey == currentKey.backendKey }) {
            return null
        }
        return engine
    }

    private fun createLiteRtBackend(backendKey: String): LiteRtBackend {
        return LiteRtCpuBackend()
    }

    private fun buildLiteRtBackendErrorMessage(
        baseErrorMessage: String,
        candidate: LiteRtBackendCandidate,
        accelerationMode: ReaderCleanupAccelerationMode
    ): String {
        return when (accelerationMode) {
            ReaderCleanupAccelerationMode.Auto ->
                "$baseErrorMessage ${candidate.label} initialization failed, so Read! will try a safer fallback."
            else ->
                "$baseErrorMessage ${candidate.label} initialization failed on this device. Switch back to CPU or Auto if needed."
        }
    }
}

internal class ConfiguredCleanupModel(
    context: Context,
    private val repository: LocalCleanupModelRepository,
    private val settingsRepository: ReaderCleanupSettingsRepository
) : LocalCleanupModel {
    private val mediaPipeRuntime = MediaPipeTaskRuntime(context.applicationContext)
    private val liteRtLmRuntime = LiteRtLmRuntime(context.applicationContext)

    override val isAvailable: Boolean
        get() = resolveInstalledModel() != null

    fun supportsDocumentCleanup(): Boolean {
        val resolved = resolveInstalledModel() ?: return false
        return supportsDocumentCleanupBackend(resolved.runtimeSpec.backendKind)
    }

    private fun cleanupAccelerationMode(): ReaderCleanupAccelerationMode = ReaderCleanupAccelerationMode.Cpu

    private fun summaryAccelerationMode(): ReaderCleanupAccelerationMode = ReaderCleanupAccelerationMode.Cpu

    override suspend fun warmup() {
        warmupForSummary()
    }

    suspend fun warmupForCleanup() {
        warmupInternal(cleanupAccelerationMode())
    }

    suspend fun warmupForSummary() {
        warmupInternal(summaryAccelerationMode())
    }

    private suspend fun warmupInternal(accelerationMode: ReaderCleanupAccelerationMode) {
        withContext(Dispatchers.IO) {
            val resolved = resolveInstalledModel()
                ?: throw IllegalStateException("No local cleanup model is installed.")
            val modelPath = repository.ensureExecutionFile(resolved.info).absolutePath
            when (resolved.runtimeSpec.backendKind) {
                CleanupBackendKind.MediaPipeTask ->
                    mediaPipeRuntime.warmup(
                        modelPath = modelPath,
                        errorMessage = resolved.runtimeSpec.initializationErrorMessage
                    )
                CleanupBackendKind.LiteRtLm ->
                    liteRtLmRuntime.warmup(
                        modelPath = modelPath,
                        errorMessage = resolved.runtimeSpec.initializationErrorMessage,
                        accelerationMode = accelerationMode
                    )
            }
        }
    }

    override suspend fun cleanChunk(
        paragraphs: List<String>,
        contextBefore: String?,
        contextAfter: String?,
        instructions: String,
        cleanupProfile: CleanupProfile
    ): CleanupChunkResult = withContext(Dispatchers.IO) {
        val resolved = resolveInstalledModel()
            ?: throw IllegalStateException("No local cleanup model is installed.")
        if (!supportsDocumentCleanupBackend(resolved.runtimeSpec.backendKind)) {
            return@withContext CleanupChunkResult(paragraphs = paragraphs)
        }
        val modelPath = repository.ensureExecutionFile(resolved.info).absolutePath
        val accelerationMode = cleanupAccelerationMode()
        val prompt = buildCleanupPrompt(
            paragraphs = paragraphs,
            contextBefore = contextBefore,
            contextAfter = contextAfter,
            instructions = instructions,
            cleanupProfile = cleanupProfile
        )
        val cleanedParagraphs = when (resolved.runtimeSpec.backendKind) {
            CleanupBackendKind.MediaPipeTask ->
                mediaPipeRuntime.generateResponse(
                    modelPath = modelPath,
                    errorMessage = resolved.runtimeSpec.initializationErrorMessage,
                    prompt = prompt,
                    expectedParagraphCount = paragraphs.size
                )
            CleanupBackendKind.LiteRtLm ->
                liteRtLmRuntime.generateResponse(
                    modelPath = modelPath,
                    errorMessage = resolved.runtimeSpec.initializationErrorMessage,
                    prompt = prompt,
                    expectedParagraphCount = paragraphs.size,
                    accelerationMode = accelerationMode
                )
        }
        CleanupChunkResult(paragraphs = cleanedParagraphs)
    }

    suspend fun generateText(
        prompt: String,
        timeoutMillis: Long? = null,
        onPartialText: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val resolved = resolveInstalledModel()
            ?: throw IllegalStateException("No local cleanup model is installed.")
        val modelPath = repository.ensureExecutionFile(resolved.info).absolutePath
        val accelerationMode = summaryAccelerationMode()
        when (resolved.runtimeSpec.backendKind) {
            CleanupBackendKind.MediaPipeTask ->
                mediaPipeRuntime.generateText(
                    modelPath = modelPath,
                    errorMessage = resolved.runtimeSpec.initializationErrorMessage,
                    prompt = prompt,
                    timeoutMillis = timeoutMillis,
                    onPartialText = onPartialText
                )
            CleanupBackendKind.LiteRtLm ->
                liteRtLmRuntime.generateText(
                    modelPath = modelPath,
                    errorMessage = resolved.runtimeSpec.initializationErrorMessage,
                    prompt = prompt,
                    accelerationMode = accelerationMode,
                    timeoutMillis = timeoutMillis,
                    onPartialText = onPartialText
                )
        }
    }

    fun currentExecutionBackendLabel(): String? {
        val resolved = resolveInstalledModel() ?: return null
        return when (resolved.runtimeSpec.backendKind) {
            CleanupBackendKind.MediaPipeTask -> null
            CleanupBackendKind.LiteRtLm -> liteRtLmRuntime.activeBackendLabel()
        }
    }

    fun resolvedModelId(): String? = resolveInstalledModel()?.info?.modelId

    private fun resolveInstalledModel(): ResolvedInstalledCleanupModel? {
        val preferredModelId = settingsRepository.selectedModelId()
        val compatibleInfo = resolveInstalledCleanupModel(
            installedInfos = repository.listInstalledModelInfos(),
            preferredModelId = preferredModelId
        )

        if (compatibleInfo != null && preferredModelId != compatibleInfo.info.modelId) {
            settingsRepository.saveSelectedModelId(compatibleInfo.info.modelId)
        }

        return compatibleInfo
    }
}

internal class LocalCleanupModelRegistry(private val context: Context) {
    private val repository = LocalCleanupModelRepository(context.applicationContext)
    private val settingsRepository = ReaderCleanupSettingsRepository(context.applicationContext)
    private val configuredModel by lazy {
        ConfiguredCleanupModel(
            context = context.applicationContext,
            repository = repository,
            settingsRepository = settingsRepository
        )
    }

    fun resolve(): LocalCleanupModel {
        return configuredModel
    }

    fun configuredModel(): ConfiguredCleanupModel {
        return configuredModel
    }
}

internal class ReaderDocumentCleanupPipeline(
    context: Context,
    private val settingsRepository: ReaderCleanupSettingsRepository = ReaderCleanupSettingsRepository(context),
    private val modelRegistry: LocalCleanupModelRegistry = LocalCleanupModelRegistry(context)
) {
    suspend fun cleanDocumentIfEligible(
        document: ReaderDocument,
        onPartialDocument: ((ReaderDocument) -> Unit)? = null
    ): ReaderDocument = withContext(Dispatchers.Default) {
        val settings = settingsRepository.settings()
        val captureDiagnostics = settings.diagnosticsEnabled
        val cleanupProfile = cleanupProfileForDocument(document, settings.mode) ?: return@withContext document
        val model = modelRegistry.configuredModel()
        val resolvedModelId = model.resolvedModelId()
            ?: return@withContext document

        if (document.presentation != null && document.presentation.promptVersion > READER_CLEANUP_PROMPT_VERSION) {
            return@withContext document
        }

        val existingPresentation = document.presentation
        val existingDiagnostics = document.cleanupDiagnostics
        val hasMatchingPresentation =
            existingPresentation != null &&
                existingPresentation.modelId == resolvedModelId &&
                existingPresentation.promptVersion == READER_CLEANUP_PROMPT_VERSION
        val hasMatchingDiagnostics =
            existingDiagnostics != null &&
                existingDiagnostics.modelId == resolvedModelId &&
                existingDiagnostics.promptVersion == READER_CLEANUP_PROMPT_VERSION
        if (
            hasMatchingPresentation &&
            (!captureDiagnostics || hasMatchingDiagnostics)
        ) {
            return@withContext document
        }
        if (document.presentation == null && hasMatchingDiagnostics) {
            return@withContext document
        }

        if (!model.isAvailable) {
            return@withContext document
        }
        if (!model.supportsDocumentCleanup()) {
            return@withContext document
        }

        val chunks = buildCleanupChunks(document.blocks, cleanupProfile)
        if (chunks.isEmpty()) {
            return@withContext document
        }
        val totalParagraphs = document.blocks.count { it.type == ReaderBlockType.Paragraph }
        val eligibleParagraphs = chunks.sumOf { it.paragraphs.size }
        val eligibleCharsBefore = chunks.sumOf { chunk -> chunk.paragraphs.sumOf { it.length } }

        val warmedUp = runCatching {
            model.warmupForCleanup()
        }.isSuccess
        if (!warmedUp) {
            return@withContext document
        }
        val executionBackendLabel = model.currentExecutionBackendLabel()

        val cleanedBlocks = document.blocks.toMutableList()
        var changed = false
        var attemptedChunks = 0
        var acceptedChunks = 0
        var rejectedChunks = 0
        var changedParagraphs = 0
        var droppedParagraphs = 0
        val changeKindCounts = linkedMapOf<String, Int>()
        val rejectionReasonCounts = linkedMapOf<String, Int>()
        val chunkReports = mutableListOf<CleanupChunkReport>()
        chunks.forEach { chunk ->
            attemptedChunks += 1
            val cleanupAttempt = runCatching {
                model.cleanChunk(
                    paragraphs = chunk.paragraphs,
                    contextBefore = chunk.contextBefore,
                    contextAfter = chunk.contextAfter,
                    instructions = buildCleanupInstructions(cleanupProfile),
                    cleanupProfile = cleanupProfile
                )
            }
            val result = cleanupAttempt.getOrNull()
            if (result == null) {
                rejectedChunks += 1
                incrementCount(rejectionReasonCounts, CleanupValidationFailureReason.ModelFailure.storageKey)
                if (captureDiagnostics) {
                    chunkReports += CleanupChunkReport(
                        firstBlockIndex = chunk.paragraphBlockIndices.first(),
                        lastBlockIndex = chunk.paragraphBlockIndices.last(),
                        status = CleanupChunkStatus.Rejected,
                        rejectionReason = CleanupValidationFailureReason.ModelFailure.storageKey,
                        failureSummary = cleanupAttempt.exceptionOrNull()?.let(::summarizeCleanupModelFailure),
                        targetParagraphCount = chunk.paragraphs.size,
                        contextBeforeChars = chunk.contextBefore?.length ?: 0,
                        contextAfterChars = chunk.contextAfter?.length ?: 0,
                        originalChars = chunk.paragraphs.sumOf { it.length }
                    )
                }
                return@forEach
            }

            when (val validationResult = validateCleanupOutput(chunk.paragraphs, result.paragraphs, cleanupProfile)) {
                is CleanupOutputValidationResult.Rejected -> {
                    rejectedChunks += 1
                    incrementCount(rejectionReasonCounts, validationResult.reason.storageKey)
                    if (captureDiagnostics) {
                        chunkReports += CleanupChunkReport(
                            firstBlockIndex = chunk.paragraphBlockIndices.first(),
                            lastBlockIndex = chunk.paragraphBlockIndices.last(),
                            status = CleanupChunkStatus.Rejected,
                            rejectionReason = validationResult.reason.storageKey,
                            targetParagraphCount = chunk.paragraphs.size,
                            contextBeforeChars = chunk.contextBefore?.length ?: 0,
                            contextAfterChars = chunk.contextAfter?.length ?: 0,
                            originalChars = chunk.paragraphs.sumOf { it.length },
                            cleanedChars = result.paragraphs.sumOf { cleanedText ->
                                normalizeCleanupParagraph(
                                    if (cleanedText == CLEANUP_DROP_PARAGRAPH) "" else cleanedText
                                ).length
                            }
                        )
                    }
                    return@forEach
                }

                CleanupOutputValidationResult.Accepted -> Unit
            }

            acceptedChunks += 1
            val paragraphReports = mutableListOf<CleanupParagraphReport>()
            var chunkChangedParagraphs = 0
            var chunkDroppedParagraphs = 0
            var chunkCleanedChars = 0
            var chunkChanged = false
            result.paragraphs.forEachIndexed { index, cleanedText ->
                val blockIndex = chunk.paragraphBlockIndices[index]
                val originalText = cleanedBlocks[blockIndex].text
                val normalized = normalizeCleanupParagraph(cleanedText)
                val finalText = if (normalized == CLEANUP_DROP_PARAGRAPH) "" else normalized
                chunkCleanedChars += finalText.length
                if (finalText != originalText) {
                    cleanedBlocks[blockIndex] = cleanedBlocks[blockIndex].copy(text = finalText)
                    changed = true
                    chunkChanged = true
                    chunkChangedParagraphs += 1
                    changedParagraphs += 1
                    if (finalText.isBlank()) {
                        chunkDroppedParagraphs += 1
                        droppedParagraphs += 1
                    }
                    val changeKinds = classifyCleanupChangeKinds(
                        original = originalText,
                        cleaned = finalText,
                        cleanupProfile = cleanupProfile
                    )
                    changeKinds.forEach { incrementCount(changeKindCounts, it) }
                    if (captureDiagnostics) {
                        paragraphReports += CleanupParagraphReport(
                            blockIndex = blockIndex,
                            changeKinds = changeKinds,
                            beforeText = originalText,
                            afterText = finalText
                        )
                    }
                }
            }

            if (captureDiagnostics) {
                chunkReports += CleanupChunkReport(
                    firstBlockIndex = chunk.paragraphBlockIndices.first(),
                    lastBlockIndex = chunk.paragraphBlockIndices.last(),
                    status = if (chunkChanged) CleanupChunkStatus.AcceptedChanged else CleanupChunkStatus.AcceptedUnchanged,
                    targetParagraphCount = chunk.paragraphs.size,
                    contextBeforeChars = chunk.contextBefore?.length ?: 0,
                    contextAfterChars = chunk.contextAfter?.length ?: 0,
                    originalChars = chunk.paragraphs.sumOf { it.length },
                    cleanedChars = chunkCleanedChars,
                    changedParagraphs = chunkChangedParagraphs,
                    droppedParagraphs = chunkDroppedParagraphs,
                    paragraphReports = paragraphReports
                )
            }

            if (chunkChanged) {
                onPartialDocument?.invoke(
                    document.copy(
                        presentation = ReaderPresentation(
                            modelId = resolvedModelId,
                            promptVersion = READER_CLEANUP_PROMPT_VERSION,
                            executionBackendLabel = executionBackendLabel,
                            blocks = cleanedBlocks.toList()
                        )
                    )
                )
            }
        }

        val diagnosticsReport = captureDiagnostics.takeIf { it }?.let {
            CleanupRunDiagnostics(
                modelId = resolvedModelId,
                promptVersion = READER_CLEANUP_PROMPT_VERSION,
                executionBackendLabel = executionBackendLabel,
                totalParagraphs = totalParagraphs,
                eligibleParagraphs = eligibleParagraphs,
                totalChunks = chunks.size,
                attemptedChunks = attemptedChunks,
                acceptedChunks = acceptedChunks,
                rejectedChunks = rejectedChunks,
                changedParagraphs = changedParagraphs,
                droppedParagraphs = droppedParagraphs,
                eligibleCharsBefore = eligibleCharsBefore,
                eligibleCharsAfter = chunkReports.sumOf { it.cleanedChars ?: it.originalChars },
                changeKindCounts = diagnosticsCounts(changeKindCounts),
                rejectionReasonCounts = diagnosticsCounts(rejectionReasonCounts),
                chunkReports = chunkReports.toList()
            )
        }

        if (!changed) {
            return@withContext document.copy(cleanupDiagnostics = diagnosticsReport ?: document.cleanupDiagnostics)
        }

        document.copy(
            presentation = ReaderPresentation(
                modelId = resolvedModelId,
                promptVersion = READER_CLEANUP_PROMPT_VERSION,
                executionBackendLabel = executionBackendLabel,
                blocks = cleanedBlocks
            ),
            cleanupDiagnostics = diagnosticsReport
        )
    }
}

internal class ReaderDocumentSummaryPipeline(
    context: Context,
    private val settingsRepository: ReaderCleanupSettingsRepository = ReaderCleanupSettingsRepository(context),
    private val modelRegistry: LocalCleanupModelRegistry = LocalCleanupModelRegistry(context)
) {
    suspend fun summarizeDocument(
        document: ReaderDocument,
        forceRegenerate: Boolean = false,
        onPrepared: ((String?) -> Unit)? = null,
        onPartialText: ((String) -> Unit)? = null
    ): ReaderDocument = withContext(Dispatchers.Default) {
        val model = modelRegistry.configuredModel()
        val resolvedModelId = model.resolvedModelId()
            ?: throw IllegalStateException("No runnable local model selected.")
        val summarySourceText = buildDocumentSummarySource(document)
        if (summarySourceText.isBlank()) {
            return@withContext document
        }
        val sourceSignature = summarySourceSignature(summarySourceText)
        val existingSummary = document.summary
        if (
            !forceRegenerate &&
            existingSummary != null &&
            existingSummary.modelId == resolvedModelId &&
            existingSummary.promptVersion == READER_SUMMARY_PROMPT_VERSION &&
            existingSummary.sourceSignature == sourceSignature
        ) {
            return@withContext document
        }

        if (!model.isAvailable) {
            throw IllegalStateException("No runnable local model selected.")
        }
        model.warmupForSummary()
        onPrepared?.invoke(model.currentExecutionBackendLabel())
        val response = model.generateText(
            buildDocumentSummaryPrompt(
                title = document.title,
                kind = document.kind,
                sourceText = summarySourceText
            ),
            timeoutMillis = SUMMARY_GENERATION_TIMEOUT_MS,
            onPartialText = onPartialText
        )
        val normalizedSummary = normalizeGeneratedSummary(response)
        if (normalizedSummary.isBlank()) {
            throw IllegalStateException("The local model did not return a usable summary.")
        }
        val executionBackendLabel = model.currentExecutionBackendLabel()
        document.copy(
            summary = ReaderSummary(
                modelId = resolvedModelId,
                promptVersion = READER_SUMMARY_PROMPT_VERSION,
                executionBackendLabel = executionBackendLabel,
                sourceSignature = sourceSignature,
                text = normalizedSummary
            )
        )
    }
}

private fun cleanupProfileForDocument(
    document: ReaderDocument,
    mode: ReaderCleanupMode
): CleanupProfile? {
    return when (mode) {
        ReaderCleanupMode.Off -> null
        ReaderCleanupMode.WebAndPdf -> when (document.kind) {
            DocumentKind.WEB -> CleanupProfile.WEB
            DocumentKind.PDF -> CleanupProfile.PDF
        }
    }
}

internal fun buildCleanupChunks(
    blocks: List<ReaderBlock>,
    cleanupProfile: CleanupProfile = CleanupProfile.WEB
): List<CleanupChunk> {
    val config = cleanupProfileConfig(cleanupProfile)
    val chunks = mutableListOf<CleanupChunk>()
    val pendingIndices = mutableListOf<Int>()
    val pendingParagraphs = mutableListOf<String>()
    var pendingChars = 0

    fun flush() {
        if (pendingIndices.isEmpty()) {
            return
        }
        val firstIndex = pendingIndices.first()
        val lastIndex = pendingIndices.last()
        chunks += CleanupChunk(
            paragraphBlockIndices = pendingIndices.toList(),
            paragraphs = pendingParagraphs.toList(),
            contextBefore = cleanupContextParagraph(blocks, firstIndex - 1, cleanupProfile),
            contextAfter = cleanupContextParagraph(blocks, lastIndex + 1, cleanupProfile)
        )
        pendingIndices.clear()
        pendingParagraphs.clear()
        pendingChars = 0
    }

    blocks.forEachIndexed { index, block ->
        if (block.type != ReaderBlockType.Paragraph) {
            flush()
            return@forEachIndexed
        }

        val paragraph = normalizeCleanupParagraph(block.text)
        if (!isEligibleCleanupParagraph(paragraph, cleanupProfile)) {
            flush()
            return@forEachIndexed
        }

        if (
            pendingParagraphs.isNotEmpty() &&
            (pendingParagraphs.size >= config.maxChunkParagraphs ||
                pendingChars + paragraph.length > config.targetChunkChars)
        ) {
            flush()
        }

        pendingIndices += index
        pendingParagraphs += paragraph
        pendingChars += paragraph.length
    }
    flush()

    return chunks
}

private fun cleanupContextParagraph(
    blocks: List<ReaderBlock>,
    index: Int,
    cleanupProfile: CleanupProfile
): String? {
    val block = blocks.getOrNull(index) ?: return null
    if (block.type != ReaderBlockType.Paragraph) {
        return null
    }
    val paragraph = normalizeCleanupParagraph(block.text)
    return paragraph.takeIf { isEligibleCleanupParagraph(it, cleanupProfile) }
}

internal enum class CleanupValidationFailureReason(val storageKey: String) {
    ModelFailure("model_failure"),
    EmptyOutput("empty_output"),
    ParagraphCountMismatch("paragraph_count_mismatch"),
    ParagraphDropNotAllowed("paragraph_drop_not_allowed"),
    BlankCombinedOutput("blank_combined_output"),
    LengthRatioOutOfRange("length_ratio_out_of_range"),
    SuspiciousFormatting("suspicious_formatting"),
    ImportantTokenLoss("important_token_loss"),
    BlankParagraph("blank_paragraph"),
    ParagraphLengthOutOfRange("paragraph_length_out_of_range"),
    ParagraphTokenLoss("paragraph_token_loss"),
    ParagraphContentLoss("paragraph_content_loss"),
    UnexpectedDuplication("unexpected_duplication")
}

internal enum class CleanupChangeKind(val storageKey: String) {
    ParagraphDrop("paragraph_drop"),
    WhitespaceCleanup("whitespace_cleanup"),
    Dehyphenation("dehyphenation"),
    CitationCleanup("citation_cleanup"),
    BoilerplateRemoval("boilerplate_removal"),
    MinorRewrite("minor_rewrite"),
    SubstantialRewrite("substantial_rewrite")
}

internal sealed interface CleanupOutputValidationResult {
    data object Accepted : CleanupOutputValidationResult

    data class Rejected(
        val reason: CleanupValidationFailureReason
    ) : CleanupOutputValidationResult
}

internal fun isCleanupOutputValid(
    originalParagraphs: List<String>,
    cleanedParagraphs: List<String>,
    cleanupProfile: CleanupProfile = CleanupProfile.WEB
): Boolean {
    return validateCleanupOutput(originalParagraphs, cleanedParagraphs, cleanupProfile) == CleanupOutputValidationResult.Accepted
}

internal fun validateCleanupOutput(
    originalParagraphs: List<String>,
    cleanedParagraphs: List<String>,
    cleanupProfile: CleanupProfile = CleanupProfile.WEB
): CleanupOutputValidationResult {
    val config = cleanupProfileConfig(cleanupProfile)
    if (originalParagraphs.isEmpty() || cleanedParagraphs.isEmpty()) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.EmptyOutput)
    }

    if (cleanedParagraphs.size != originalParagraphs.size) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphCountMismatch)
    }

    if (!cleanupProfile.allowParagraphDrop && cleanedParagraphs.any { it == CLEANUP_DROP_PARAGRAPH }) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphDropNotAllowed)
    }

    val originalText = originalParagraphs.joinToString("\n\n")
    val cleanedText = cleanedParagraphs
        .map { if (it == CLEANUP_DROP_PARAGRAPH) "" else it }
        .joinToString("\n\n")
    if (cleanedText.isBlank()) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.BlankCombinedOutput)
    }

    val originalValidationText = cleanupValidationText(originalText, cleanupProfile)
    val cleanedValidationText = cleanupValidationText(cleanedText, cleanupProfile)

    val lengthRatio =
        cleanedValidationText.length.toDouble() / originalValidationText.length.coerceAtLeast(1).toDouble()
    if (lengthRatio < config.minLengthRatio || lengthRatio > config.maxLengthRatio) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.LengthRatioOutOfRange)
    }

    val suspiciousTokens = listOf("```", "# ", "Here is the cleaned text", "- ")
    if (suspiciousTokens.any { cleanedText.contains(it, ignoreCase = true) }) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.SuspiciousFormatting)
    }

    val originalImportantTokens = extractImportantCleanupTokens(originalValidationText)
    val cleanedImportantTokens = extractImportantCleanupTokens(cleanedValidationText)
    if (originalImportantTokens.isNotEmpty()) {
        val preserved = originalImportantTokens.count { it in cleanedImportantTokens }
        if (preserved < (originalImportantTokens.size * config.minGlobalTokenPreservation)) {
            return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ImportantTokenLoss)
        }
    }

    originalParagraphs.zip(cleanedParagraphs).forEach { (original, cleanedRaw) ->
        if (cleanedRaw == CLEANUP_DROP_PARAGRAPH) {
            return@forEach
        }
        val cleaned = normalizeCleanupParagraph(cleanedRaw)
        if (cleaned.isBlank()) {
            return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.BlankParagraph)
        }

        val originalValidationParagraph = cleanupValidationText(original, cleanupProfile)
        val cleanedValidationParagraph = cleanupValidationText(cleaned, cleanupProfile)
        val paragraphLengthRatio =
            cleanedValidationParagraph.length.toDouble() / originalValidationParagraph.length.coerceAtLeast(1).toDouble()
        if (paragraphLengthRatio < config.minParagraphRatio || paragraphLengthRatio > config.maxParagraphRatio) {
            return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphLengthOutOfRange)
        }

        val originalTokens = extractImportantCleanupTokens(originalValidationParagraph)
        val cleanedTokens = extractImportantCleanupTokens(cleanedValidationParagraph)
        if (originalTokens.isNotEmpty()) {
            val preserved = originalTokens.count { it in cleanedTokens }
            if (preserved < (originalTokens.size * config.minParagraphTokenPreservation)) {
                return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphTokenLoss)
            }
        }

        if (cleanupProfile == CleanupProfile.WEB) {
            val protectedTokens = extractProtectedWebCleanupTokens(originalValidationParagraph)
            if (protectedTokens.isNotEmpty()) {
                val cleanedProtectedTokens = extractProtectedWebCleanupTokens(cleanedValidationParagraph)
                if (!cleanedProtectedTokens.containsAll(protectedTokens)) {
                    return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphTokenLoss)
                }
            }
        }

        val originalContentTokens = cleanupContentTokens(originalValidationParagraph)
        if (originalContentTokens.isNotEmpty()) {
            val cleanedContentTokens = cleanupContentTokens(cleanedValidationParagraph)
            val preserved = originalContentTokens.count { it in cleanedContentTokens }
            if (preserved < (originalContentTokens.size * config.minParagraphContentTokenPreservation)) {
                return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphContentLoss)
            }
        }
    }

    if (hasUnexpectedParagraphDuplication(originalParagraphs, cleanedParagraphs)) {
        return CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.UnexpectedDuplication)
    }

    return CleanupOutputValidationResult.Accepted
}

private fun cleanupValidationText(
    text: String,
    cleanupProfile: CleanupProfile
): String {
    return when (cleanupProfile) {
        CleanupProfile.WEB -> text
        CleanupProfile.PDF -> stripPdfValidationArtifacts(text)
    }
}

private fun stripPdfValidationArtifacts(text: String): String {
    return stripPdfRunningHeaderArtifacts(stripPdfCitationMarkers(text))
}

private fun stripPdfCitationMarkers(text: String): String {
    return text
        .replace(Regex("""(\p{L})-\s+(\p{L})"""), "$1$2")
        .replace(Regex("""\[(?:\s*\d{1,3}\s*(?:[,\u2013;\-]\s*\d{1,3}\s*)*)\]"""), " ")
        .replace(Regex("""\((?:\s*\d{1,3}\s*(?:[,\u2013;\-]\s*\d{1,3}\s*)*)\)"""), " ")
        .replace(Regex("""[\u00B9\u00B2\u00B3\u2070-\u2079]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun stripPdfRunningHeaderArtifacts(text: String): String {
    return text
        .replace(
            Regex(
                """\barxiv:\d{4}\.\d{4,5}(?:v\d+)?\s+\[[^\]]+\]\s+\d{1,2}\s+[A-Za-z]{3}\s+\d{4}\b""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        .replace(Regex("""\b\d+\s+(?:[A-Z][A-Z]+(?:\s+[A-Z][A-Z]+){1,12})\b"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

internal fun normalizeCleanupParagraph(text: String): String {
    return text
        .replace('\u00A0', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun isEligibleCleanupParagraph(text: String, cleanupProfile: CleanupProfile): Boolean {
    val minimumLength = when (cleanupProfile) {
        CleanupProfile.WEB -> 16
        CleanupProfile.PDF -> 24
    }
    val maximumLength = when (cleanupProfile) {
        CleanupProfile.WEB -> 2200
        CleanupProfile.PDF -> 2600
    }
    if (text.length < minimumLength || text.length > maximumLength) {
        return false
    }

    if (text.count { it.isLetterOrDigit() }.toDouble() / text.length.coerceAtLeast(1).toDouble() < 0.45) {
        return false
    }

    val lower = text.lowercase()
    val codeLikeMarkers = listOf("```", "</", "/>", " = ", "{", "}")
    if (codeLikeMarkers.any { lower.contains(it) }) {
        return false
    }

    if (cleanupProfile == CleanupProfile.PDF) {
        if (Regex("""^(figure|table|algorithm|appendix)\s+\d""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return false
        }
        if (Regex("""^\s*(\[\d+\]|\d+\.)\s+[A-Z]""").containsMatchIn(text)) {
            return false
        }
        val mathLikeOperators = listOf(" = ", " + ", " - ", " × ", " ÷ ", " ∈ ", " ≤ ", " ≥ ", "→")
        if (mathLikeOperators.count { text.contains(it) } >= 3) {
            return false
        }
    }

    return true
}

private fun extractImportantCleanupTokens(text: String): Set<String> {
    val ignoredTokens = setOf(
        "share", "comments", "comment", "print", "email", "copy", "link",
        "facebook", "twitter", "linkedin", "reddit", "whatsapp", "telegram",
        "safeframe", "container", "advertisement", "adchoices", "related",
        "the", "this", "that", "these", "those"
    )
    return Regex("""\b([A-Z][a-z]+|[A-Z]{2,}|\d[\d,.:/%-]*)\b""")
        .findAll(text)
        .map { it.value }
        .filterNot { it.lowercase() in ignoredTokens }
        .filter { it.length >= 2 }
        .take(64)
        .toSet()
}

private fun extractProtectedWebCleanupTokens(text: String): Set<String> {
    return Regex("""\b(?:[A-Z][\p{L}\p{N}'’.\-]{2,}|[\p{L}\p{N}'’.\-]*[A-Z][\p{Ll}\p{N}'’.\-]+[A-Z][\p{L}\p{N}'’.\-]*)\b""")
        .findAll(text)
        .map { it.value.trim('\'', '’', '.', ',', ';', ':', '!', '?', '(', ')', '[', ']') }
        .filter { it.length >= 3 }
        .filterNot { it.lowercase() in WEB_PROTECTED_TOKEN_IGNORES }
        .take(64)
        .toSet()
}

private fun hasUnexpectedParagraphDuplication(
    originalParagraphs: List<String>,
    cleanedParagraphs: List<String>
): Boolean {
    if (cleanedParagraphs.size < 2) {
        return false
    }

    for (index in 1 until cleanedParagraphs.size) {
        val cleanedPrevious = cleanedParagraphs[index - 1]
        val cleanedCurrent = cleanedParagraphs[index]
        if (cleanedPrevious == CLEANUP_DROP_PARAGRAPH || cleanedCurrent == CLEANUP_DROP_PARAGRAPH) {
            continue
        }

        val cleanedSimilarity = paragraphTokenSimilarity(cleanedPrevious, cleanedCurrent)
        if (cleanedSimilarity < 0.82f) {
            continue
        }

        val originalSimilarity = paragraphTokenSimilarity(
            originalParagraphs[index - 1],
            originalParagraphs[index]
        )
        if (cleanedSimilarity > originalSimilarity + 0.20f) {
            return true
        }
    }

    return false
}

private fun paragraphTokenSimilarity(first: String, second: String): Float {
    val firstTokens = cleanupContentTokens(first)
    val secondTokens = cleanupContentTokens(second)
    if (firstTokens.isEmpty() || secondTokens.isEmpty()) {
        return 0f
    }
    val overlap = firstTokens.intersect(secondTokens).size.toFloat()
    val union = firstTokens.union(secondTokens).size.toFloat().coerceAtLeast(1f)
    return overlap / union
}

private fun cleanupContentTokens(text: String): Set<String> {
    return Regex("""\b[\p{L}\p{N}][\p{L}\p{N}'’\-]{2,}\b""")
        .findAll(text.lowercase())
        .map { it.value }
        .filterNot { it in COMMON_CLEANUP_STOP_WORDS }
        .toSet()
}

private fun incrementCount(
    counts: MutableMap<String, Int>,
    key: String
) {
    counts[key] = (counts[key] ?: 0) + 1
}

internal fun diagnosticsCounts(counts: Map<String, Int>): List<CleanupDiagnosticsCount> {
    return counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { CleanupDiagnosticsCount(key = it.key, count = it.value) }
}

internal fun summarizeCleanupModelFailure(error: Throwable): String {
    val outer = cleanupDiagnosticErrorLine(error)
    val root = generateSequence(error) { it.cause }.last()
    val rootLine = root
        .takeIf { it !== error }
        ?.let(::cleanupDiagnosticErrorLine)
        ?.takeIf { it != outer }
    return listOfNotNull(outer, rootLine).joinToString(separator = " | caused by ")
        .take(280)
        .trimEnd()
}

private fun cleanupDiagnosticErrorLine(error: Throwable): String {
    val type = error::class.java.simpleName.takeIf { it.isNotBlank() } ?: "Error"
    val message = error.message
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return if (message == null) type else "$type: $message"
}

internal fun classifyCleanupChangeKinds(
    original: String,
    cleaned: String,
    cleanupProfile: CleanupProfile
): List<String> {
    if (cleaned.isBlank()) {
        return listOf(CleanupChangeKind.ParagraphDrop.storageKey)
    }

    val kinds = linkedSetOf<String>()
    val originalValidation = cleanupValidationText(original, cleanupProfile)
    val cleanedValidation = cleanupValidationText(cleaned, cleanupProfile)
    val similarity = paragraphTokenSimilarity(originalValidation, cleanedValidation)

    if (whitespaceSignature(original) == whitespaceSignature(cleaned) && original != cleaned) {
        kinds += CleanupChangeKind.WhitespaceCleanup.storageKey
    }
    if (countDehyphenationSites(original) > countDehyphenationSites(cleaned)) {
        kinds += CleanupChangeKind.Dehyphenation.storageKey
    }
    if (countCitationLikeMarkers(original) > countCitationLikeMarkers(cleaned)) {
        kinds += CleanupChangeKind.CitationCleanup.storageKey
    }
    if (countBoilerplateLikeMarkers(original, cleanupProfile) > countBoilerplateLikeMarkers(cleaned, cleanupProfile)) {
        kinds += CleanupChangeKind.BoilerplateRemoval.storageKey
    }

    if (similarity < 0.76f) {
        kinds += CleanupChangeKind.SubstantialRewrite.storageKey
    } else if (original != cleaned && kinds.isEmpty()) {
        kinds += CleanupChangeKind.MinorRewrite.storageKey
    } else if (original != cleaned && similarity < 0.96f) {
        kinds += CleanupChangeKind.MinorRewrite.storageKey
    }

    return kinds.ifEmpty { linkedSetOf(CleanupChangeKind.MinorRewrite.storageKey) }.toList()
}

private fun whitespaceSignature(text: String): String {
    return text.replace(Regex("""\s+"""), "")
}

private fun countDehyphenationSites(text: String): Int {
    return Regex("""\p{L}-\s+\p{L}""").findAll(text).count()
}

private fun countCitationLikeMarkers(text: String): Int {
    return Regex("""\[(?:\s*\d{1,3}(?:\s*[,\u2013;\-]\s*\d{1,3})*)\]|\((?:\s*\d{1,3}(?:\s*[,\u2013;\-]\s*\d{1,3})*)\)|[\u00B9\u00B2\u00B3\u2070-\u2079]+""")
        .findAll(text)
        .count()
}

private fun countBoilerplateLikeMarkers(
    text: String,
    cleanupProfile: CleanupProfile
): Int {
    val lower = text.lowercase(Locale.US)
    val candidates = when (cleanupProfile) {
        CleanupProfile.WEB -> listOf(
            "share",
            "comments",
            "all rights reserved",
            "related links",
            "photo credit",
            "copyright",
            "follow us"
        )

        CleanupProfile.PDF -> listOf(
            "arxiv:",
            "accepted for",
            "page ",
            "copyright",
            "all rights reserved"
        )
    }
    return candidates.count(lower::contains)
}

internal fun buildCleanupInstructions(cleanupProfile: CleanupProfile = CleanupProfile.WEB): String {
    return when (cleanupProfile) {
        CleanupProfile.WEB -> buildWebCleanupInstructions()
        CleanupProfile.PDF -> buildPdfCleanupInstructions()
    }
}

internal fun buildWebCleanupInstructions(): String {
    return """
        You clean extracted article text.
        Preserve meaning exactly.
        Do not summarize.
        Do not add information.
        Do not remove substantive prose.
        Remove obvious website chrome, button text, share text, comment prompts, ad labels, and site footer fragments only if present.
        Remove copyright/footer boilerplate such as publisher copyright notices, All Rights Reserved lines, publisher footer labels, and long tracking or hash-like strings when they are clearly not part of article prose.
        Remove repeated UI labels, duplicated boilerplate lines, repeated photo credits, and related-links fragments when they are clearly not part of the article body.
        Preserve inline linked text exactly when it is part of article prose.
        Preserve names of people, organizations, products, and publications exactly when they appear in article prose.
        Do not remove a word or phrase just because it came from a hyperlink in the source page.
        If a paragraph is mostly website chrome, comments UI, related links, ad labels, or navigation/footer junk, return exactly $CLEANUP_DROP_PARAGRAPH for that paragraph.
        Repair broken paragraph formatting and line breaks.
        Keep the original paragraph order.
        Do not move sentences between paragraphs.
        Do not repeat a sentence in more than one paragraph.
        Preserve meaningful dates, prices, statistics, identifiers, and article numbers when they are part of the prose.
        If you are unsure, keep the paragraph unchanged.
        Return plain text only.
        Do not use markdown.
        Do not add titles or headings.
    """.trimIndent()
}

internal fun buildPdfCleanupInstructions(): String {
    return """
        You clean extracted PDF prose.
        Preserve meaning exactly.
        Do not summarize.
        Do not add information.
        Do not remove substantive prose.
        Repair broken line wraps, line-break hyphenation, interrupted paragraphs, and bad blank-line sentence breaks when the intended prose is clear.
        Remove inline numeric citation markers like [12], [3, 4], superscript reference numbers, and citation-only parentheticals when they are clearly bibliography references rather than prose.
        Remove arXiv identifiers, page numbers, publication dates, and repeated running author/title headers when they were accidentally merged into prose.
        Remove only obvious running header/footer fragments if they were accidentally merged into a prose paragraph.
        Preserve citations, quotations, names, numbers, section labels, and technical terms exactly.
        Do not drop paragraphs.
        Do not rewrite style or simplify wording.
        Do not move sentences between paragraphs.
        Do not repeat a sentence in more than one paragraph.
        If you are unsure, keep the paragraph unchanged.
        Return plain text only.
        Do not use markdown.
        Do not add titles or headings.
    """.trimIndent()
}

internal fun buildCleanupPrompt(
    paragraphs: List<String>,
    contextBefore: String?,
    contextAfter: String?,
    instructions: String,
    cleanupProfile: CleanupProfile = CleanupProfile.WEB
): String {
    val inputBlocks = paragraphs.mapIndexed { index, paragraph ->
        """
        [Target Paragraph ${index + 1}]
        $paragraph
        [/Target Paragraph ${index + 1}]
        """.trimIndent()
    }.joinToString("\n\n")

    val contextBlock = buildString {
        contextBefore?.let {
            appendLine("Read-only context before:")
            appendLine("[Context Before]")
            appendLine(it)
            appendLine("[/Context Before]")
            appendLine()
        }
        contextAfter?.let {
            appendLine("Read-only context after:")
            appendLine("[Context After]")
            appendLine(it)
            appendLine("[/Context After]")
        }
    }.trim()

    return """
        You are cleaning extracted article text for reading and text-to-speech.
        $instructions

        Output requirements:
        - Return exactly ${paragraphs.size} paragraphs.
        - Keep each output paragraph aligned to the corresponding target paragraph.
        - Preserve names, numbers, quotations, and meaning exactly.
        - ${if (cleanupProfile == CleanupProfile.WEB) "Only remove obvious extraction junk if present." else "Only repair clear extraction damage; do not paraphrase."}
        - ${if (cleanupProfile.allowParagraphDrop) "If one target paragraph is mostly chrome or non-article UI, return exactly $CLEANUP_DROP_PARAGRAPH for that paragraph." else "Do not drop any target paragraph."}
        - ${if (cleanupProfile == CleanupProfile.WEB) "Remove inline junk like share labels, safeframe labels, repeated photo credits, and related-links boilerplate when they appear inside a paragraph." else "Repair broken line wraps, dehyphenate words broken across line ends, smooth paragraph interruptions when the intended prose is clear, remove inline numeric citation markers when they are clearly references rather than prose, and strip merged arXiv/date/page-header fragments."}
        - ${if (cleanupProfile == CleanupProfile.WEB) "Preserve inline linked text exactly when it belongs to the article sentence; do not delete a phrase just because it was hyperlinked." else "Preserve every substantive phrase in the target paragraph even when formatting is repaired."}
        - ${if (cleanupProfile == CleanupProfile.WEB) "Preserve names of people, organizations, products, and publications exactly; do not replace them with generic labels." else "Preserve names, technical terms, and identifiers exactly."}
        - ${if (cleanupProfile == CleanupProfile.WEB) "Remove inline copyright/footer boilerplate such as publisher copyright notices, All Rights Reserved, publisher footer labels, and long tracking or hash-like strings when they are clearly not part of article prose." else "Remove only obvious running header/footer fragments such as arXiv IDs, page numbers, dates, and repeated author/title headers when they were accidentally merged into the paragraph."}
        - Use context paragraphs only to understand boundaries and references.
        - Do not copy context sentences into the output unless they already belong inside the target paragraph.
        - Do not move sentences between target paragraphs.
        - Do not duplicate one target paragraph into another.
        - ${if (cleanupProfile == CleanupProfile.WEB) "Preserve meaningful dates, prices, statistics, identifiers, and article numbers that belong to the article." else "Preserve names, identifiers, formulas, years, and technical language exactly, but citation-number markers may be removed."}
        - If uncertain, keep the paragraph unchanged.
        - Separate paragraphs with a single line containing exactly $CLEANUP_RESPONSE_BREAK
        - Return only the cleaned paragraphs and separator lines.

        ${if (contextBlock.isNotBlank()) "Context:\n$contextBlock\n\n" else ""}Target paragraphs:
        $inputBlocks
    """.trimIndent()
}

internal fun parseCleanupParagraphs(
    response: String,
    expectedParagraphCount: Int
): List<String> {
    val normalizedResponse = response
        .replace("\r\n", "\n")
        .replace("```", "")
        .trim()
    if (normalizedResponse.isBlank()) {
        return emptyList()
    }

    splitCleanupParagraphs(normalizedResponse, CLEANUP_RESPONSE_BREAK)
        .takeIf { it.size == expectedParagraphCount }
        ?.let { return it }

    splitCleanupParagraphs(normalizedResponse, Regex("""\n\s*\n+"""))
        .takeIf { it.size == expectedParagraphCount }
        ?.let { return it }

    return emptyList()
}

private fun splitCleanupParagraphs(response: String, separator: String): List<String> {
    return response
        .split(separator)
        .map(::normalizeCleanupParagraph)
        .filter { it.isNotBlank() }
}

private fun splitCleanupParagraphs(response: String, separator: Regex): List<String> {
    return response
        .split(separator)
        .map(::normalizeCleanupParagraph)
        .filter { it.isNotBlank() }
}

internal fun documentContentHash(document: ReaderDocument): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(document.sourceLabel.toByteArray())
    document.blocks.forEach { block ->
        digest.update(block.type.name.toByteArray())
        digest.update(0)
        digest.update(block.text.toByteArray())
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun probeCleanupArchive(info: InstalledCleanupModelInfo): CleanupArchiveProbe? {
    val absolutePath = info.absolutePath?.takeIf { it.isNotBlank() } ?: return null
    val cacheKey = CleanupArchiveProbeCacheKey(
        absolutePath = absolutePath,
        sizeBytes = info.sizeBytes,
        lastModified = info.lastModified
    )
    cleanupArchiveProbeCache[cacheKey]?.let { return it }

    val probed = runCatching {
        probeCleanupArchive(File(absolutePath), info.fileName)
    }.getOrNull()
    if (probed != null) {
        cleanupArchiveProbeCache[cacheKey] = probed
    }
    return probed
}

private fun probeCleanupArchive(file: File, fileName: String): CleanupArchiveProbe? {
    val normalizedFileName = fileName.lowercase(Locale.US)
    val extension = normalizedFileName.substringAfterLast('.', "")
    if (extension !in setOf("litertlm", "task")) {
        return null
    }

    val runtimeKindFromExtension = when (extension) {
        "litertlm" -> CleanupBackendKind.LiteRtLm
        "task" -> CleanupBackendKind.MediaPipeTask
        else -> null
    }

    return ZipFile(file).use { zipFile ->
        val entryNames = zipFile.entries().asSequence().map { it.name }.toList()
        val textualMetadata = readArchiveMetadataText(zipFile, entryNames)
        val combinedText = buildString {
            append(normalizedFileName)
            if (entryNames.isNotEmpty()) {
                append('\n')
                append(entryNames.joinToString("\n").lowercase(Locale.US))
            }
            textualMetadata?.let {
                if (it.isNotBlank()) {
                    append('\n')
                    append(it.lowercase(Locale.US))
                }
            }
        }
        CleanupArchiveProbe(
            displayName = extractArchiveDisplayName(textualMetadata)
                ?: inferCleanupModelDisplayName(combinedText)
                ?: cleanupModelDisplayName(fileName),
            runtimeKind = inferCleanupRuntimeKind(combinedText) ?: runtimeKindFromExtension,
            metadataText = textualMetadata
        )
    }
}

private fun readArchiveMetadataText(zipFile: ZipFile, entryNames: List<String>): String? {
    val candidateNames = listOf(
        "manifest.json",
        "metadata.json",
        "model.json",
        "config.json",
        "model_info.json",
        "genai.json",
        "tokenizer_config.json"
    )
    val selectedEntries = entryNames
        .mapNotNull { entryName ->
            val lower = entryName.lowercase(Locale.US)
            if (candidateNames.any { lower.endsWith(it) }) {
                zipFile.getEntry(entryName)
            } else {
                null
            }
        }
        .filter { entry -> entry.size in 1..131072 }
        .take(4)

    if (selectedEntries.isEmpty()) {
        return null
    }

    return selectedEntries.joinToString("\n") { entry ->
        zipFile.getInputStream(entry).bufferedReader().use { it.readText() }
    }
}

private fun extractArchiveDisplayName(metadataText: String?): String? {
    if (metadataText.isNullOrBlank()) {
        return null
    }
    val patterns = listOf(
        Regex(""""display[_ ]?name"\s*:\s*"([^"]{2,120})"""", RegexOption.IGNORE_CASE),
        Regex(""""model[_ ]?name"\s*:\s*"([^"]{2,120})"""", RegexOption.IGNORE_CASE),
        Regex(""""name"\s*:\s*"([^"]{2,120})"""", RegexOption.IGNORE_CASE)
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.find(metadataText)?.groupValues?.getOrNull(1)?.trim()
    }?.takeIf { it.isNotBlank() }
}

private fun inferCleanupRuntimeKind(combinedText: String): CleanupBackendKind? {
    return when {
        combinedText.contains("litertlm") -> CleanupBackendKind.LiteRtLm
        combinedText.contains(".task") || combinedText.contains("mediapipe") -> CleanupBackendKind.MediaPipeTask
        else -> null
    }
}

private fun inferCleanupModelDisplayName(combinedText: String): String? {
    return when {
        combinedText.contains("gemma") && combinedText.contains("3n") && combinedText.contains("e2b") ->
            "Gemma 3n E2B"
        combinedText.contains("gemma") && Regex("""(?:^|[^0-9])3(?:[^0-9]|$)""").containsMatchIn(combinedText) &&
            combinedText.contains("1b") ->
            "Gemma 3 1B"
        combinedText.contains("qwen") && combinedText.contains("2.5") && combinedText.contains("1.5b") ->
            "Qwen 2.5 1.5B"
        combinedText.contains("qwen") && combinedText.contains("1.5b") ->
            "Qwen 1.5B"
        else -> null
    }
}

internal fun buildDocumentSummarySource(document: ReaderDocument): String {
    val sections = mutableListOf<String>()
    var pendingHeading: String? = null

    document.displayBlocks.forEach { block ->
        val clean = block.text.replace(Regex("""\s+"""), " ").trim()
        if (clean.isBlank()) {
            return@forEach
        }
        when (block.type) {
            ReaderBlockType.Heading -> pendingHeading = clean
            ReaderBlockType.Paragraph -> {
                val sectionText = pendingHeading?.let { heading -> "[$heading] $clean" } ?: clean
                sections += sectionText
                pendingHeading = null
            }
            else -> Unit
        }
    }

    return sections.joinToString(separator = "\n").trim()
}

internal fun buildDocumentSummaryPrompt(
    title: String,
    kind: DocumentKind,
    sourceText: String
): String {
    return """
        Summarize this ${if (kind == DocumentKind.PDF) "document" else "article"} for later reading.
        Use only the supplied text.
        Do not invent facts, names, findings, or interpretations.
        Keep names and claims exact.
        Ignore extraction boilerplate and repeated headers if present.
        Keep the whole result under about 120 words.
        Write exactly 1 short overview paragraph and exactly 3 short bullet points.
        Stop after bullet 3.
        Return plain text only.

        Return exactly this format:
        Overview:
        <one concise paragraph>

        Key points:
        - <point 1>
        - <point 2>
        - <point 3>

        Title: $title

        Text:
        $sourceText
    """.trimIndent()
}

private fun normalizeGeneratedSummary(text: String): String {
    return text
        .replace(Regex("""```(?:[\w-]+)?"""), "")
        .replace(Regex("""\r\n?"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

private fun normalizeStreamingSummaryText(text: String): String? {
    val normalized = normalizeGeneratedSummary(text)
    return normalized.takeIf { it.isNotBlank() }
}

internal fun mergeStreamingText(existing: String, incoming: String): String {
    if (incoming.isBlank()) {
        return existing
    }
    if (existing.isBlank()) {
        return incoming
    }
    if (incoming.startsWith(existing)) {
        return incoming
    }
    if (existing.endsWith(incoming)) {
        return existing
    }

    val maxOverlap = minOf(existing.length, incoming.length)
    for (overlap in maxOverlap downTo 1) {
        if (existing.endsWith(incoming.take(overlap))) {
            return existing + incoming.drop(overlap)
        }
    }

    return existing + incoming
}

private fun summarySourceSignature(sourceText: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(sourceText.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun extractLiteRtLmResponseText(message: LiteRtMessage): String {
    return message.contents.contents
        .mapNotNull { content ->
            when (content) {
                is LiteRtTextContent -> content.text
                else -> null
            }
        }
        .joinToString(separator = "")
}

private fun closeLiteRtResource(resource: Any?) {
    if (resource == null) {
        return
    }
    runCatching {
        resource.javaClass.methods
            .firstOrNull { method -> method.name == "close" && method.parameterCount == 0 }
            ?.invoke(resource)
    }
}

internal fun isSupportedLiteRtLmCleanupFile(fileName: String): Boolean {
    val normalized = fileName.lowercase(Locale.US)
    if (!normalized.endsWith(".litertlm")) {
        return false
    }
    if (containsStandaloneBundleQualifier(normalized, "web")) {
        return false
    }
    if (Regex("""(^|[^a-z0-9])(f32|fp32|float32)([^a-z0-9]|$)""").containsMatchIn(normalized)) {
        return false
    }
    return true
}

internal fun isSupportedMediaPipeTaskCleanupFile(fileName: String): Boolean {
    val normalized = fileName.lowercase(Locale.US)
    if (!normalized.endsWith(".task")) {
        return false
    }
    if (containsStandaloneBundleQualifier(normalized, "web")) {
        return false
    }
    if (looksLikeKnownUnsafeMediaPipeTaskBundle(normalized)) {
        return false
    }
    return true
}

private fun containsStandaloneBundleQualifier(normalizedFileName: String, token: String): Boolean {
    return Regex("""(^|[^a-z0-9])${Regex.escape(token)}([^a-z0-9]|$)""").containsMatchIn(normalizedFileName)
}

private fun looksLikeKnownUnsafeMediaPipeTaskBundle(normalizedFileName: String): Boolean {
    return normalizedFileName.contains("gemma") && normalizedFileName.contains("3n")
}

internal fun cleanupModelDisplayName(fileName: String): String {
    val baseName = fileName.substringBeforeLast('.')
    return baseName
        .replace(Regex("""[-_]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private val COMMON_CLEANUP_STOP_WORDS = setOf(
    "the", "and", "that", "with", "from", "this", "have", "were", "their", "there",
    "about", "into", "after", "before", "while", "when", "where", "which", "would",
    "could", "should", "because", "these", "those", "your", "article", "button",
    "share", "comment", "comments", "photo", "credit", "related", "links",
    "safeframe", "container", "print", "email", "facebook", "twitter", "linkedin",
    "reddit", "whatsapp", "telegram", "newsletter", "subscribe", "copyright",
    "reserved", "publisher", "footer", "boilerplate", "promo", "promoted",
    "advert", "advertisement", "sponsored", "label"
)
