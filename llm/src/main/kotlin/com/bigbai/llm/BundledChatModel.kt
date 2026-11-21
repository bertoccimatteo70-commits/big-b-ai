package com.bigbai.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Materialises the chat model that ships inside the APK.
 *
 * The asset is copied into the same private directory the download manager uses
 * for catalog models, so the rest of the app does not need a second model
 * source. The copy is accepted only when its size and SHA-256 match the pinned
 * values; the installer's marker makes later starts cheap. One mutex prevents
 * onboarding and the first chat from copying the same 491 MB file at once.
 */
class BundledChatModel(
    private val catalog: ModelCatalog,
    private val store: ModelStore,
    private val installer: BundledModelInstaller,
) {
    private val mutex = Mutex()

    val variant: ModelVariant? = catalog.byId(BundledModels.CHAT_VARIANT_ID)

    fun isBundled(candidate: ModelVariant): Boolean = candidate.id == BundledModels.CHAT_VARIANT_ID

    fun isInstalled(): Boolean = variant?.let { store.installed(it) != null } == true

    /**
     * Copies and verifies the bundled model, or returns the already installed
     * copy. [onProgress] is called from an IO thread with bytes copied so far.
     */
    suspend fun ensure(
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<InstalledModel> = mutex.withLock {
        val model = variant
            ?: return@withLock Result.failure(
                IllegalStateException("Il modello incluso non è presente nel catalogo di questa build."),
            )
        store.installed(model)?.let { return@withLock Result.success(it) }

        val free = store.freeSpaceBytes()
        val required = BundledModels.CHAT.sizeBytes + MIN_HEADROOM_BYTES
        if (free in 1 until required) {
            return@withLock Result.failure(
                IllegalStateException(
                    "Spazio insufficiente per preparare il modello incluso: servono " +
                        "${format(required)} e ne sono disponibili ${format(free)}. " +
                        "Libera spazio e riprova.",
                ),
            )
        }

        val directory = store.directoryFor(model)
        val copied = installer.ensure(BundledModels.CHAT, directory, onProgress)
        if (copied.isFailure) {
            return@withLock Result.failure(
                copied.exceptionOrNull()
                    ?: IllegalStateException("Preparazione del modello incluso non riuscita."),
            )
        }

        // The copy phase has already hashed the file. Marking it installed makes
        // it visible to the normal chat loader and the model manager.
        store.markInstalled(model, verified = true)
        val installed = store.installed(model)
        if (installed == null) {
            return@withLock Result.failure(
                IllegalStateException(
                    "Il modello incluso è stato copiato ma non è leggibile. " +
                        "Riavvia l'app o libera spazio e riprova.",
                ),
            )
        }
        Result.success(installed)
    }

    private fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        else -> "%d MB".format(bytes / (1L shl 20))
    }

    private companion object {
        /** Room for the marker, SQLite writes and a future update. */
        const val MIN_HEADROOM_BYTES = 64L * 1024 * 1024
    }
}
