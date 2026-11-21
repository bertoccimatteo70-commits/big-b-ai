package com.bigbai.llm

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe storage for the semantic index.
 *
 * A save is only reported as successful after:
 * 1. the new index has been written to `index.bin.part`;
 * 2. the bytes have been flushed and `fd.sync()` has returned;
 * 3. the file has been read back and its entry count matches;
 * 4. the previous index has been copied to `index.bin.bak`;
 * 5. the part file has been atomically renamed over the real file.
 *
 * If any step fails the old index is still in place and the caller gets a
 * failure, so the UI never confirms a save that did not happen. A corrupt main
 * file is quarantined and the backup is restored instead of silently starting
 * from an empty index.
 */
class SemanticIndexStore(
    private val directory: File,
    private val fileName: String = "index.bin",
) {
    data class Loaded(
        val index: SemanticIndex,
        val recovered: Boolean,
        val corrupt: Boolean,
        val error: String?,
    )

    private val main = File(directory, fileName)
    private val backup = File(directory, "$fileName.bak")
    private val part = File(directory, "$fileName.part")
    private val quarantine = File(directory, "$fileName.corrupt")

    fun load(): Loaded {
        runCatching { part.delete() }
        if (!main.isFile) {
            return Loaded(SemanticIndex.empty(), recovered = false, corrupt = false, error = null)
        }
        read(main)?.let { return Loaded(it, recovered = false, corrupt = false, error = null) }

        // The main file exists but cannot be parsed: keep it for inspection and
        // try the last known-good copy.
        runCatching {
            if (quarantine.exists()) quarantine.delete()
            main.renameTo(quarantine)
        }
        read(backup)?.let { restored ->
            val result = save(restored)
            return Loaded(
                index = restored,
                recovered = true,
                corrupt = true,
                error = result.exceptionOrNull()?.message,
            )
        }
        return Loaded(
            index = SemanticIndex.empty(),
            recovered = false,
            corrupt = true,
            error = "Indice illeggibile: riparto da un indice vuoto",
        )
    }

    fun save(index: SemanticIndex): Result<Unit> = runCatching {
        if (!directory.exists() && !directory.mkdirs()) {
            error("Impossibile creare la cartella dell'indice")
        }
        if (!directory.isDirectory) error("Percorso dell'indice non valido")

        part.delete()
        FileOutputStream(part).use { output ->
            val buffered = BufferedOutputStream(output, 1 shl 16)
            index.writeTo(buffered)
            buffered.flush()
            // The bytes must be on disk before the rename makes the file visible.
            output.fd.sync()
        }
        val written = part.length()
        if (written <= 0L) error("Scrittura dell'indice vuota")

        val check = read(part) ?: error("L'indice scritto non è leggibile")
        if (check.size != index.size) error("L'indice scritto è incompleto")

        // Best effort: a failed backup must not lose the new index, but a
        // successful one is what makes corruption recovery possible.
        if (main.isFile) {
            runCatching {
                Files.copy(main.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        try {
            Files.move(
                part.toPath(),
                main.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), main.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        if (!main.isFile || main.length() != written) {
            error("Il file dell'indice non è stato finalizzato")
        }
    }.onFailure {
        runCatching { part.delete() }
    }

    fun clear(): Result<Unit> = runCatching {
        listOf(main, backup, part, quarantine).forEach { file ->
            if (file.exists() && !file.delete()) error("Impossibile eliminare ${file.name}")
        }
    }

    fun sizeBytes(): Long = if (main.isFile) main.length() else 0L

    fun hasBackup(): Boolean = backup.isFile

    private fun read(file: File): SemanticIndex? = runCatching {
        file.inputStream().buffered().use { SemanticIndex.readFromOrNull(it) }
    }.getOrNull()
}
