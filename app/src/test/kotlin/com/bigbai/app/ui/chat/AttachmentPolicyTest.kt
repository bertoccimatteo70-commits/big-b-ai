package com.bigbai.app.ui.chat

import com.bigbai.core.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {

    @Test
    fun `classifies images by mime type and extension`() {
        assertEquals(Attachment.Kind.IMAGE, AttachmentPolicy.kindFor("foto", "image/jpeg"))
        assertEquals(Attachment.Kind.IMAGE, AttachmentPolicy.kindFor("FOTO.PNG", ""))
        assertEquals(Attachment.Kind.IMAGE, AttachmentPolicy.kindFor("scan.webp", "application/octet-stream"))
    }

    @Test
    fun `classifies pdf by mime type and extension`() {
        assertEquals(Attachment.Kind.PDF, AttachmentPolicy.kindFor("documento", "application/pdf"))
        assertEquals(Attachment.Kind.PDF, AttachmentPolicy.kindFor("Documento.PDF", ""))
    }

    @Test
    fun `classifies supported document formats`() {
        assertEquals(Attachment.Kind.DOCUMENT, AttachmentPolicy.kindFor("note.txt", "text/plain"))
        assertEquals(Attachment.Kind.DOCUMENT, AttachmentPolicy.kindFor("dati.csv", "text/csv"))
        assertEquals(Attachment.Kind.DOCUMENT, AttachmentPolicy.kindFor("contratto.docx", ""))
        assertEquals(Attachment.Kind.DOCUMENT, AttachmentPolicy.kindFor("budget.xlsx", ""))
    }

    @Test
    fun `rejects unsupported formats`() {
        assertNull(AttachmentPolicy.kindFor("video.mp4", "video/mp4"))
        assertNull(AttachmentPolicy.kindFor("senza-estensione", ""))
    }

    @Test
    fun `enforces image and pdf limits`() {
        assertNull(AttachmentPolicy.sizeError(Attachment.Kind.IMAGE, AttachmentPolicy.MAX_IMAGE_BYTES))
        assertTrue(
            AttachmentPolicy.sizeError(Attachment.Kind.IMAGE, AttachmentPolicy.MAX_IMAGE_BYTES + 1)
                ?.contains("24 MB") == true,
        )
        assertNull(AttachmentPolicy.sizeError(Attachment.Kind.PDF, AttachmentPolicy.MAX_PDF_BYTES))
        assertTrue(
            AttachmentPolicy.sizeError(Attachment.Kind.PDF, AttachmentPolicy.MAX_PDF_BYTES + 1)
                ?.contains("32 MB") == true,
        )
        assertNull(
            AttachmentPolicy.sizeError(Attachment.Kind.DOCUMENT, AttachmentPolicy.MAX_DOCUMENT_BYTES),
        )
        assertTrue(
            AttachmentPolicy.sizeError(Attachment.Kind.DOCUMENT, AttachmentPolicy.MAX_DOCUMENT_BYTES + 1)
                ?.contains("32 MB") == true,
        )
    }

    @Test
    fun `unknown size is not rejected by the policy`() {
        assertNull(AttachmentPolicy.sizeError(Attachment.Kind.IMAGE, -1))
        assertNull(AttachmentPolicy.sizeError(Attachment.Kind.PDF, -1))
    }
}
