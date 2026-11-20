package com.bigbai.core.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PdfPage(val number: Int, val text: String)

data class PdfDocument(
    val name: String,
    val pages: List<PdfPage>,
    val pageCount: Int,
    val hasExtractableText: Boolean,
) {
    val fullText: String get() = pages.joinToString("\n\n") { it.text }
    val charCount: Int get() = pages.sumOf { it.text.length }
}

class PdfExtractor(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * Extracts the text layer of a PDF. Scanned PDFs without an embedded text
     * layer are detected and reported: OCR is intentionally out of scope for v1.
     */
    suspend fun extract(uri: Uri, name: String, maxPages: Int = 400): Result<PdfDocument> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open PDF" }
                    PDDocument.load(input).use { document ->
                        val stripper = PDFTextStripper().apply { sortByPosition = true }
                        val pages = mutableListOf<PdfPage>()
                        val total = document.numberOfPages
                        val limit = minOf(total, maxPages)
                        for (page in 1..limit) {
                            stripper.startPage = page
                            stripper.endPage = page
                            val text = stripper.getText(document).trim()
                            if (text.isNotEmpty()) pages.add(PdfPage(page, text))
                        }
                        val chars = pages.sumOf { it.text.length }
                        PdfDocument(
                            name = name,
                            pages = pages,
                            pageCount = total,
                            hasExtractableText = chars > 40,
                        )
                    }
                }
            }
        }

    /** Case-insensitive search returning page numbers and short snippets. */
    fun search(document: PdfDocument, query: String, maxHits: Int = 20): List<PdfSearchHit> {
        if (query.isBlank()) return emptyList()
        val hits = mutableListOf<PdfSearchHit>()
        for (page in document.pages) {
            var index = page.text.indexOf(query, ignoreCase = true)
            while (index >= 0 && hits.size < maxHits) {
                val start = (index - 60).coerceAtLeast(0)
                val end = (index + query.length + 60).coerceAtMost(page.text.length)
                hits.add(PdfSearchHit(page.number, page.text.substring(start, end).replace('\n', ' ')))
                index = page.text.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        return hits
    }

    /**
     * Builds a bounded context for the model. Pages are included whole when they
     * fit, otherwise truncated with an explicit marker so the model knows the
     * excerpt is partial.
     */
    fun contextForModel(document: PdfDocument, maxChars: Int): String {
        val builder = StringBuilder()
        for (page in document.pages) {
            val header = "\n[Pagina ${page.number}]\n"
            if (builder.length + header.length + page.text.length > maxChars) {
                val remaining = maxChars - builder.length - header.length
                if (remaining > 200) {
                    builder.append(header).append(page.text.take(remaining)).append("\n[…testo troncato]")
                }
                break
            }
            builder.append(header).append(page.text)
        }
        return builder.toString().trim()
    }
}

data class PdfSearchHit(val page: Int, val snippet: String)
