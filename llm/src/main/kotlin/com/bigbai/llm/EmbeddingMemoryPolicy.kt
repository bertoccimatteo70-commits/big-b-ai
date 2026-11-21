package com.bigbai.llm

/**
 * Decides whether the bundled embedding model can be loaded right now.
 *
 * 1.5.0 required 550 MiB of free RAM for a 132 MB Q8_0 model, which refused
 * perfectly capable devices. The real cost is the weights plus a small context
 * and the runtime overhead; the safety margin grows only on low-RAM devices or
 * when Android already reports memory pressure.
 *
 * This is a pure policy object on purpose: the decision is unit-tested without
 * a device, and the UI can show exactly the same numbers it used.
 */
object EmbeddingMemoryPolicy {

    /** Size of the bundled multilingual-e5-small Q8_0 file. */
    const val MODEL_BYTES = 132_439_008L

    /** llama.cpp context, compute buffers and allocator overhead for 512 tokens. */
    const val RUNTIME_BYTES = 48L * 1024 * 1024

    /** Headroom so loading does not push the process into the LMK. */
    const val SAFETY_BYTES = 56L * 1024 * 1024

    /** Extra headroom on devices Android itself marks as low-RAM. */
    const val LOW_RAM_EXTRA_BYTES = 48L * 1024 * 1024

    data class Decision(
        val allowed: Boolean,
        val requiredBytes: Long,
        val availableBytes: Long,
        val pressureLevel: Int,
        val reason: String?,
    )

    /**
     * @param pressureLevel 0 = relaxed, 1 = moderate, 2 = low, 3 = critical
     * (the same scale as [com.bigbai.core.device.MemorySampler.pressureLevel]).
     */
    fun requiredBytes(lowRamDevice: Boolean = false, pressureLevel: Int = 0): Long {
        var required = MODEL_BYTES + RUNTIME_BYTES + SAFETY_BYTES
        if (lowRamDevice) required += LOW_RAM_EXTRA_BYTES
        if (pressureLevel >= 2) required += SAFETY_BYTES
        return required
    }

    fun evaluate(
        availableBytes: Long,
        pressureLevel: Int,
        lowRamDevice: Boolean = false,
    ): Decision {
        val required = requiredBytes(lowRamDevice, pressureLevel)
        val critical = pressureLevel >= 3
        val enough = availableBytes >= required
        val reason = when {
            critical -> "Memoria sotto pressione critica: il modello di embedding non viene caricato"
            !enough -> "RAM libera insufficiente per il modello di embedding: " +
                "${mib(availableBytes)} disponibili, ${mib(required)} necessari"
            else -> null
        }
        return Decision(
            allowed = !critical && enough,
            requiredBytes = required,
            availableBytes = availableBytes,
            pressureLevel = pressureLevel,
            reason = reason,
        )
    }

    fun mib(bytes: Long): String = "${(bytes / (1024L * 1024L)).coerceAtLeast(0)} MiB"
}
