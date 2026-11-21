package com.bigbai.llm

import com.bigbai.core.model.AppSettings

/**
 * Per-model generation profile.
 *
 * Small instruct models do not behave like 7 B models: they need lower
 * temperature, a shorter answer budget, stronger repetition control and a
 * different top-p/top-k to avoid loops. The profile is chosen from the model
 * family and parameter count, so the app never applies one global configuration
 * to every model.
 */
data class ModelProfile(
    val family: String,
    val label: String,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
    val contextCap: Int,
    val concise: Boolean,
    val codeFriendly: Boolean,
    /** True when the model is small enough that long explanations degrade. */
    val limitedReasoning: Boolean,
    val repeatPenalty: Float,
    val minP: Float,
) {
    /**
     * Manual settings only win when the user unlocked advanced controls: this
     * keeps the "one profile per model" behaviour predictable, while still
     * letting an expert override it explicitly.
     */
    fun params(settings: AppSettings): GenerationParams {
        val maxTokens = if (settings.advancedUnlocked) {
            settings.maxTokens.coerceIn(64, 4096)
        } else {
            minOf(settings.maxTokens.coerceIn(64, 4096), maxTokens)
        }
        val temperature = if (settings.advancedUnlocked) settings.temperature.coerceIn(0f, 2f) else temperature
        val topP = if (settings.advancedUnlocked) settings.topP.coerceIn(0.1f, 1f) else topP
        val topK = if (settings.advancedUnlocked) settings.topK.coerceIn(1, 200) else topK
        val repeat = if (settings.advancedUnlocked) {
            settings.repeatPenalty.coerceIn(1f, 1.6f)
        } else {
            repeatPenalty
        }
        val minPValue = if (settings.advancedUnlocked) settings.minP.coerceIn(0f, 0.5f) else minP
        return GenerationParams(
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            topK = topK,
            repeatPenalty = repeat,
            minP = minPValue,
            seed = -1,
        )
    }

    /** Extra sentence appended to the system prompt for small models. */
    fun styleHint(italian: Boolean): String = when {
        concise && italian ->
            "Rispondi in modo breve: massimo 6 frasi o 5 punti. Vai dritto al contenuto, senza ripetere la domanda."
        concise ->
            "Answer briefly: at most 6 sentences or 5 bullets. Get to the point, do not restate the question."
        limitedReasoning && italian ->
            "Se il compito richiede ragionamento lungo, dichiara il limite del modello invece di inventare."
        limitedReasoning ->
            "If the task needs long reasoning, state the model's limit instead of inventing."
        else -> ""
    }
}

object ModelProfiles {

    private val CODER = Regex("""coder|codeqwen|deepseek-coder|starcoder|codegemma""", RegexOption.IGNORE_CASE)
    private val QWEN = Regex("""qwen""", RegexOption.IGNORE_CASE)
    private val LLAMA = Regex("""llama""", RegexOption.IGNORE_CASE)
    private val PHI = Regex("""phi-?3|phi3""", RegexOption.IGNORE_CASE)
    private val GEMMA = Regex("""gemma""", RegexOption.IGNORE_CASE)
    private val MISTRAL = Regex("""mistral|mixtral""", RegexOption.IGNORE_CASE)
    private val TINY = Regex("""tinyllama|smollm|qwen2\.5-0\.5b|qwen2-0\.5b""", RegexOption.IGNORE_CASE)

    fun forVariant(variant: ModelVariant): ModelProfile =
        detect("${variant.slug} ${variant.family} ${variant.params}", variant.role == ModelRole.CODE)

    fun detect(identifier: String, codeModel: Boolean = false): ModelProfile {
        val id = identifier.lowercase()
        val params = PARAMS.find(id)?.groupValues?.get(1)?.toDoubleOrNull()
        val small = params != null && params <= 1.6
        val tiny = params != null && params <= 0.6
        val coder = codeModel || CODER.containsMatchIn(id)

        return when {
            coder -> ModelProfile(
                family = "coder",
                label = "Programmazione",
                temperature = 0.2f,
                topP = 0.95f,
                topK = 20,
                maxTokens = 2048,
                contextCap = 8192,
                concise = false,
                codeFriendly = true,
                limitedReasoning = tiny,
                repeatPenalty = 1.05f,
                minP = 0.02f,
            )
            TINY.containsMatchIn(id) || tiny -> ModelProfile(
                family = "tiny",
                label = "Molto piccolo",
                temperature = 0.55f,
                topP = 0.9f,
                topK = 30,
                maxTokens = 640,
                contextCap = 4096,
                concise = true,
                codeFriendly = false,
                limitedReasoning = true,
                // Qwen recommends a light repetition penalty. 1.22 distorted
                // word choice on the 0.5B model; 1.15 still breaks loops.
                repeatPenalty = 1.15f,
                minP = 0.03f,
            )
            small -> ModelProfile(
                family = "small",
                label = "Piccolo",
                temperature = 0.55f,
                topP = 0.9f,
                topK = 30,
                maxTokens = 768,
                contextCap = 4096,
                concise = true,
                codeFriendly = false,
                limitedReasoning = true,
                repeatPenalty = 1.18f,
                minP = 0.04f,
            )
            QWEN.containsMatchIn(id) -> ModelProfile(
                family = "qwen",
                label = "Qwen",
                temperature = 0.7f,
                topP = 0.8f,
                topK = 20,
                maxTokens = 1536,
                contextCap = 8192,
                concise = false,
                codeFriendly = false,
                limitedReasoning = false,
                repeatPenalty = 1.1f,
                minP = 0.03f,
            )
            GEMMA.containsMatchIn(id) -> ModelProfile(
                family = "gemma",
                label = "Gemma",
                temperature = 0.9f,
                topP = 0.95f,
                topK = 64,
                maxTokens = 1536,
                contextCap = 8192,
                concise = false,
                codeFriendly = false,
                limitedReasoning = false,
                repeatPenalty = 1.08f,
                minP = 0.0f,
            )
            PHI.containsMatchIn(id) -> ModelProfile(
                family = "phi",
                label = "Phi",
                temperature = 0.6f,
                topP = 0.9f,
                topK = 40,
                maxTokens = 1024,
                contextCap = 4096,
                concise = true,
                codeFriendly = false,
                limitedReasoning = false,
                repeatPenalty = 1.15f,
                minP = 0.03f,
            )
            MISTRAL.containsMatchIn(id) -> ModelProfile(
                family = "mistral",
                label = "Mistral",
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
                maxTokens = 1536,
                contextCap = 8192,
                concise = false,
                codeFriendly = false,
                limitedReasoning = false,
                repeatPenalty = 1.1f,
                minP = 0.02f,
            )
            LLAMA.containsMatchIn(id) -> ModelProfile(
                family = "llama",
                label = "Llama",
                temperature = 0.65f,
                topP = 0.9f,
                topK = 40,
                maxTokens = 1536,
                contextCap = 8192,
                concise = false,
                codeFriendly = false,
                limitedReasoning = false,
                repeatPenalty = 1.1f,
                minP = 0.02f,
            )
            else -> ModelProfile(
                family = "generic",
                label = "Generico",
                temperature = 0.7f,
                topP = 0.9f,
                topK = 40,
                maxTokens = 1024,
                contextCap = 4096,
                concise = false,
                codeFriendly = false,
                limitedReasoning = small,
                repeatPenalty = 1.12f,
                minP = 0.03f,
            )
        }
    }

    private val PARAMS = Regex("""(\d+(?:\.\d+)?)\s*b\b""")
}
