package com.bigbai.llm

/**
 * Thin JNI surface over llama.cpp. Every function is blocking and must be
 * called from the engine's single inference thread.
 */
internal object NativeLlama {

    init {
        System.loadLibrary("bigbai")
    }

    external fun nativeVersion(): String

    external fun nativeLastError(): String

    external fun nativeCapabilities(): String

    external fun nativeLoadModel(
        path: String,
        mmproj: String?,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        useMlock: Boolean,
        useMmap: Boolean,
    ): Long

    external fun nativeFreeModel(handle: Long)

    external fun nativeModelInfo(handle: Long): String

    external fun nativeApplyChatTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    external fun nativeCountTokens(handle: Long, text: String): Int

    external fun nativeBegin(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        addSpecial: Boolean,
    ): Boolean

    external fun nativeBeginVision(
        handle: Long,
        prompt: String,
        rgb: ByteArray,
        width: Int,
        height: Int,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        addSpecial: Boolean,
    ): Boolean

    /** Returns the next token as raw UTF-8 bytes, or null at end of stream. */
    external fun nativeNextToken(handle: Long): ByteArray?

    external fun nativeCancel(handle: Long)

    external fun nativePerf(handle: Long): LongArray?

    external fun nativeContextSize(handle: Long): Int
}
