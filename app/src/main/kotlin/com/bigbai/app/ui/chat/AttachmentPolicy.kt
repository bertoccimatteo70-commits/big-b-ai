package com.bigbai.app.ui.chat

import com.bigbai.core.model.Attachment

/**
 * Pure policy for attachments. Kept free of Android types so it can be unit
 * tested on the JVM; the ViewModel supplies the name, MIME type and size read
 * from the content resolver.
 */
object AttachmentPolicy {

    /** Decoded images are downscaled before inference; this bounds the source file. */
    const val MAX_IMAGE_BYTES: Long = 24L * 1024 * 1024

    /** PDFBox loads the document in memory, so the source file must stay bounded. */
    const val MAX_PDF_BYTES: Long = 32L * 1024 * 1024

    /** DOCX/XLSX/CSV/TXT are parsed with bounded buffers. */
    const val MAX_DOCUMENT_BYTES: Long = 32L * 1024 * 1024

    fun kindFor(name: String, mimeType: String): Attachment.Kind? {
        val lower = name.lowercase()
        val mime = mimeType.lowercase()
        return when {
            mime.startsWith("image/") ||
                lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".webp") -> Attachment.Kind.IMAGE
            mime == "application/pdf" || lower.endsWith(".pdf") -> Attachment.Kind.PDF
            mime == "text/plain" ||
                mime == "text/markdown" ||
                mime == "text/csv" ||
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
                lower.endsWith(".txt") ||
                lower.endsWith(".md") ||
                lower.endsWith(".markdown") ||
                lower.endsWith(".csv") ||
                lower.endsWith(".docx") ||
                lower.endsWith(".xlsx") -> Attachment.Kind.DOCUMENT
            else -> null
        }
    }

    /**
     * Returns a user-facing error when [bytes] exceeds the limit for [kind].
     * A negative size means the provider did not expose one; decoding and
     * extraction still enforce their own bounds.
     */
    fun sizeError(kind: Attachment.Kind, bytes: Long): String? {
        if (bytes < 0) return null
        return when (kind) {
            Attachment.Kind.IMAGE -> if (bytes > MAX_IMAGE_BYTES) {
                "Immagine troppo grande: il limite è 24 MB."
            } else {
                null
            }
            Attachment.Kind.PDF -> if (bytes > MAX_PDF_BYTES) {
                "PDF troppo grande: il limite è 32 MB."
            } else {
                null
            }
            Attachment.Kind.DOCUMENT -> if (bytes > MAX_DOCUMENT_BYTES) {
                "Documento troppo grande: il limite è 32 MB."
            } else {
                null
            }
        }
    }
}
