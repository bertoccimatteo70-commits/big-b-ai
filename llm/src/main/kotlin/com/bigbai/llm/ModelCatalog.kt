package com.bigbai.llm

import android.content.Context
import com.bigbai.core.device.DeviceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogFile(
    val name: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class CatalogVariant(
    val quant: String,
    val files: List<CatalogFile>,
)

@Serializable
data class CatalogModel(
    val slug: String,
    val family: String,
    val role: String,
    val tier: String,
    val params: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val repo: String,
    val contextTrain: Int,
    val layers: Int,
    val kvHeads: Int,
    val headDim: Int,
    val mmproj: CatalogFile? = null,
    val variants: List<CatalogVariant>,
)

@Serializable
data class CatalogRoot(
    val version: Int,
    val updated: String,
    val models: List<CatalogModel>,
)

enum class ModelRole { CHAT, CODE, VISION }

enum class ModelTier { FAST, BALANCED, SMART, MAX }

/**
 * A concrete downloadable artifact: one model family at one quantization.
 *
 * Every field shown in the advanced UI comes from the catalog, which in turn is
 * built from the publisher's own files. Sizes and SHA-256 digests are the LFS
 * object metadata published by Hugging Face.
 */
data class ModelVariant(
    val id: String,
    val slug: String,
    val family: String,
    val role: ModelRole,
    val tier: ModelTier,
    val params: String,
    val quant: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val repo: String,
    val contextTrain: Int,
    val layers: Int,
    val kvHeads: Int,
    val headDim: Int,
    val files: List<CatalogFile>,
    val mmproj: CatalogFile?,
) {
    val totalBytes: Long = files.sumOf { it.size } + (mmproj?.size ?: 0L)

    val displayName: String get() = "$family · $quant"

    fun downloadUrl(file: CatalogFile): String =
        "https://huggingface.co/$repo/resolve/main/${file.name}?download=true"

    fun kvBytes(context: Int): Long =
        2L * 2L * layers * kvHeads * headDim * context

    /**
     * Conservative estimate used for *selection*, never as a memory reservation.
     * Model weights + 18% runtime overhead + KV cache + fixed compute buffers.
     */
    fun estimatedRamBytes(context: Int): Long =
        (totalBytes * 1.18).toLong() + kvBytes(context) + 96L * 1024 * 1024

    fun fitsIn(budgetBytes: Long, context: Int): Boolean = estimatedRamBytes(context) <= budgetBytes
}

class ModelCatalog private constructor(private val root: CatalogRoot) {

    val updated: String get() = root.updated

    val all: List<ModelVariant> = root.models.flatMap { model ->
        model.variants.map { variant ->
            ModelVariant(
                id = "${model.slug}-${variant.quant.lowercase()}",
                slug = model.slug,
                family = model.family,
                role = runCatching { ModelRole.valueOf(model.role) }.getOrDefault(ModelRole.CHAT),
                tier = runCatching { ModelTier.valueOf(model.tier) }.getOrDefault(ModelTier.BALANCED),
                params = model.params,
                quant = variant.quant,
                license = model.license,
                licenseUrl = model.licenseUrl,
                sourceUrl = model.sourceUrl,
                repo = model.repo,
                contextTrain = model.contextTrain,
                layers = model.layers,
                kvHeads = model.kvHeads,
                headDim = model.headDim,
                files = variant.files,
                mmproj = model.mmproj,
            )
        }
    }

    fun byId(id: String): ModelVariant? = all.firstOrNull { it.id == id }

    fun forRole(role: ModelRole): List<ModelVariant> = all.filter { it.role == role }

    /**
     * Simple mode: for each tier pick the highest-quality quantization that fits
     * the current RAM budget. When nothing fits, return the smallest variant so
     * the user still sees an actionable option instead of an empty list.
     */
    fun recommended(role: ModelRole, budgetBytes: Long, context: Int): List<ModelVariant> {
        val quality = listOf("Q6_K", "Q5_K_M", "Q4_K_M", "Q3_K_M", "Q2_K", "Q8_0")
        return forRole(role)
            .groupBy { it.tier }
            .toSortedMap(compareBy { it.ordinal })
            .mapNotNull { (_, variants) ->
                val sorted = variants.sortedBy { quality.indexOf(it.quant).let { i -> if (i < 0) 99 else i } }
                sorted.firstOrNull { it.fitsIn(budgetBytes, context) } ?: sorted.minByOrNull { it.totalBytes }
            }
    }

    /** Highest quant of a family that fits, used when the user changes RAM profile. */
    fun bestFit(family: String, budgetBytes: Long, context: Int): ModelVariant? {
        val quality = listOf("Q6_K", "Q5_K_M", "Q4_K_M", "Q3_K_M", "Q2_K")
        return all.filter { it.slug == family }
            .sortedBy { quality.indexOf(it.quant).let { i -> if (i < 0) 99 else i } }
            .firstOrNull { it.fitsIn(budgetBytes, context) }
    }

    fun adaptiveContext(variant: ModelVariant, profile: DeviceProfile, budgetBytes: Long): Int =
        profile.adaptiveContext(budgetBytes, variant.layers, variant.kvHeads, variant.headDim, variant.contextTrain)

    fun maxContextFor(variant: ModelVariant, budgetBytes: Long): Int {
        val available = budgetBytes - (variant.totalBytes * 1.18).toLong() - 96L * 1024 * 1024
        if (available <= 0) return 512
        val perToken = 2L * 2L * variant.layers * variant.kvHeads * variant.headDim
        if (perToken <= 0) return variant.contextTrain
        return (available / perToken).toInt().coerceIn(512, variant.contextTrain)
    }

    companion object {
        private const val ASSET = "model_catalog.json"
        private val parser = Json { ignoreUnknownKeys = true }

        fun load(context: Context): ModelCatalog {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            return ModelCatalog(parser.decodeFromString<CatalogRoot>(text))
        }
    }
}
