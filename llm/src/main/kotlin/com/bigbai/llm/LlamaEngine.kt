package com.bigbai.llm

import android.content.Context
import android.os.SystemClock
import com.bigbai.core.device.MemorySampler
import com.bigbai.core.model.GenerationMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

data class PromptMessage(val role: String, val content: String)

data class GenerationParams(
    val maxTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
)

data class VisionInput(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
)

data class LoadedModel(
    val variant: ModelVariant,
    val contextSize: Int,
    val threads: Int,
    val vision: Boolean,
    val loadTimeMs: Long,
    val info: Map<String, String>,
    val fileBytes: Long,
) {
    val layers: Int get() = info["n_layer"]?.toIntOrNull() ?: variant.layers
    val kvHeads: Int get() = info["n_head_kv"]?.toIntOrNull() ?: variant.kvHeads
    val headDim: Int get() = info["head_dim"]?.toIntOrNull() ?: variant.headDim
}

/**
 * The single owner of the native model. Exactly one model is resident at a time.
 *
 * All native calls are serialised on a dedicated single-thread dispatcher, so
 * generation, model switching and unloading can never overlap. Cancellation is
 * the only operation allowed from another thread and is handled natively with a
 * registry so it can never touch a freed session.
 */
class LlamaEngine(
    private val context: Context,
    private val memorySampler: MemorySampler,
) {
    sealed interface EngineState {
        data object Idle : EngineState
        data class Loading(val modelId: String) : EngineState
        data class Ready(val model: LoadedModel) : EngineState
        data class Error(val message: String) : EngineState
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val cancelled = AtomicBoolean(false)
    @Volatile private var handle: Long = 0L
    @Volatile private var loadedModel: LoadedModel? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state

    fun current(): LoadedModel? = loadedModel

    fun capabilities(): Map<String, String> = parseInfo(NativeLlama.nativeCapabilities())

    fun version(): String = NativeLlama.nativeVersion()

    suspend fun load(
        variant: ModelVariant,
        modelPath: String,
        mmprojPath: String?,
        contextSize: Int,
        threads: Int,
        batchSize: Int,
        budgetBytes: Long,
    ): Result<LoadedModel> = withContext(inferenceDispatcher) {
        val existing = loadedModel
        if (handle != 0L && existing != null &&
            existing.variant.id == variant.id && existing.contextSize == contextSize
        ) {
            return@withContext Result.success(existing)
        }

        unloadInternal()

        val estimate = variant.estimatedRamBytes(contextSize)
        if (estimate > budgetBytes) {
            val message = "RAM insufficiente: stimati ${ModelDownloadManager.formatBytes(estimate)}, " +
                "budget ${ModelDownloadManager.formatBytes(budgetBytes)}"
            _state.value = EngineState.Error(message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        _state.value = EngineState.Loading(variant.id)
        val started = SystemClock.elapsedRealtime()
        val newHandle = NativeLlama.nativeLoadModel(
            path = modelPath,
            mmproj = mmprojPath,
            nCtx = contextSize,
            nThreads = threads,
            nBatch = batchSize,
            useMlock = false,
            useMmap = true,
        )
        if (newHandle == 0L) {
            val message = NativeLlama.nativeLastError().ifBlank { "Caricamento del modello non riuscito" }
            _state.value = EngineState.Error(message)
            return@withContext Result.failure(IllegalStateException(message))
        }

        handle = newHandle
        val info = parseInfo(NativeLlama.nativeModelInfo(newHandle))
        val loaded = LoadedModel(
            variant = variant,
            contextSize = NativeLlama.nativeContextSize(newHandle),
            threads = threads,
            vision = info["vision"] == "true",
            loadTimeMs = SystemClock.elapsedRealtime() - started,
            info = info,
            fileBytes = variant.totalBytes,
        )
        loadedModel = loaded
        _state.value = EngineState.Ready(loaded)
        Result.success(loaded)
    }

    suspend fun unload() = withContext(inferenceDispatcher) { unloadInternal() }

    private fun unloadInternal() {
        val current = handle
        handle = 0L
        loadedModel = null
        if (current != 0L) {
            NativeLlama.nativeFreeModel(current)
        }
        _state.value = EngineState.Idle
    }

    /** Cooperative cancellation; safe to call from the main thread. */
    fun stop() {
        cancelled.set(true)
        val current = handle
        if (current != 0L) NativeLlama.nativeCancel(current)
    }

    suspend fun countTokens(text: String): Int = withContext(inferenceDispatcher) {
        if (handle == 0L) 0 else NativeLlama.nativeCountTokens(handle, text)
    }

    suspend fun generate(
        messages: List<PromptMessage>,
        params: GenerationParams,
        vision: VisionInput? = null,
        onToken: (String) -> Unit,
    ): Result<GenerationMetrics> = withContext(inferenceDispatcher) {
        val model = loadedModel
        if (handle == 0L || model == null) {
            return@withContext Result.failure(IllegalStateException("Nessun modello caricato"))
        }
        if (vision != null && !model.vision) {
            return@withContext Result.failure(
                IllegalStateException("Il modello attivo non supporta le immagini"),
            )
        }

        cancelled.set(false)
        val prompt = buildPrompt(messages)
        if (prompt.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Prompt vuoto"))
        }

        val promptStarted = SystemClock.elapsedRealtime()
        val started = if (vision != null) {
            NativeLlama.nativeBeginVision(
                handle, prompt, vision.rgb, vision.width, vision.height,
                params.maxTokens, params.temperature, params.topP, params.topK,
            )
        } else {
            NativeLlama.nativeBegin(
                handle, prompt, params.maxTokens,
                params.temperature, params.topP, params.topK,
            )
        }
        if (!started) {
            val message = NativeLlama.nativeLastError().ifBlank { "Avvio della generazione non riuscito" }
            return@withContext Result.failure(IllegalStateException(message))
        }

        val decoder = Utf8Stream()
        var tokens = 0
        var firstTokenMs = 0L
        var peakRam = memorySampler.current()
        var stoppedByUser = false

        while (true) {
            val bytes = NativeLlama.nativeNextToken(handle)
            if (bytes == null) {
                stoppedByUser = cancelled.get()
                break
            }
            if (firstTokenMs == 0L) {
                firstTokenMs = SystemClock.elapsedRealtime() - promptStarted
            }
            val text = decoder.feed(bytes)
            if (text.isNotEmpty()) onToken(text)
            tokens += 1
            if (tokens % 8 == 0) {
                peakRam = max(peakRam, memorySampler.current())
            }
        }
        val tail = decoder.flush()
        if (tail.isNotEmpty()) onToken(tail)

        val finished = SystemClock.elapsedRealtime()
        val perf = NativeLlama.nativePerf(handle)
        val promptTokens = perf?.getOrNull(2)?.toInt() ?: 0
        val completionTokens = perf?.getOrNull(3)?.toInt() ?: tokens
        val generationMs = (finished - promptStarted - firstTokenMs).coerceAtLeast(1)
        val measuredTps = if (tokens > 0 && finished > promptStarted + firstTokenMs) {
            tokens.toDouble() * 1000.0 / generationMs
        } else {
            0.0
        }

        Result.success(
            GenerationMetrics(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                promptMs = firstTokenMs,
                generationMs = generationMs,
                firstTokenMs = firstTokenMs,
                tokensPerSecond = measuredTps,
                peakRamBytes = peakRam,
                modelId = model.variant.id,
            ),
        ).also {
            if (stoppedByUser) cancelled.set(false)
        }
    }

    private fun buildPrompt(messages: List<PromptMessage>): String {
        if (messages.isEmpty()) return ""
        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }
        val templated = NativeLlama.nativeApplyChatTemplate(handle, roles, contents, true)
        if (!templated.isNullOrBlank()) return templated
        // Conservative fallback for models without a usable template.
        return buildString {
            for (message in messages) {
                append(message.role).append(": ").append(message.content).append("\n\n")
            }
            append("assistant:")
        }
    }

    private fun parseInfo(raw: String): Map<String, String> =
        raw.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
            }
            .toMap()
}

/**
 * Incremental UTF-8 decoder. llama.cpp emits raw bytes per token and a single
 * character can span two tokens; buffering incomplete sequences avoids the
 * replacement characters that per-token `String(bytes)` would produce.
 */
internal class Utf8Stream {
    private val pending = ByteArrayOutputStream(8)

    fun feed(bytes: ByteArray): String {
        val combined = pending.toByteArray() + bytes
        pending.reset()
        val builder = StringBuilder()
        var index = 0
        while (index < combined.size) {
            val first = combined[index].toInt() and 0xFF
            val length = when {
                first < 0x80 -> 1
                first in 0xC2..0xDF -> 2
                first in 0xE0..0xEF -> 3
                first in 0xF0..0xF4 -> 4
                else -> 1
            }
            if (index + length > combined.size) break
            builder.append(String(combined, index, length, Charsets.UTF_8))
            index += length
        }
        if (index < combined.size) {
            pending.write(combined, index, combined.size - index)
        }
        return builder.toString()
    }

    fun flush(): String {
        if (pending.size() == 0) return ""
        val text = String(pending.toByteArray(), Charsets.UTF_8)
        pending.reset()
        return text
    }
}
