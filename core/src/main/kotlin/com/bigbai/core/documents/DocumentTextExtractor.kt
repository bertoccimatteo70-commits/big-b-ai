package com.bigbai.core.documents

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ExtractedDocument(
    val name: String,
    val format: String,
    val text: String,
    val characters: Int,
    val truncated: Boolean,
)

/**
 * Offline extraction for the document formats ITAGPT advertises.
 *
 * DOCX and XLSX are ZIP containers with XML inside; parsing them locally avoids
 * a heavyweight office suite and works without network. Old binary .doc/.xls
 * files are deliberately rejected: pretending to read them would produce
 * garbage. PDFs are handled by [com.bigbai.core.pdf.PdfExtractor] because they
 * need a real PDF engine.
 *
 * All input is bounded before parsing so a malicious zip bomb cannot exhaust
 * the app's memory.
 */
object DocumentTextExtractor {

    const val MAX_BYTES: Long = 32L * 1024 * 1024
    const val MAX_CHARS: Int = 120_000

    private val SUPPORTED = setOf("txt", "md", "markdown", "csv", "docx", "xlsx")

    fun supports(name: String, mimeType: String = ""): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension in SUPPORTED) return true
        val mime = mimeType.lowercase()
        return mime == "text/plain" ||
            mime == "text/markdown" ||
            mime == "text/csv" ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    fun extract(name: String, input: InputStream): Result<ExtractedDocument> = runCatching {
        val extension = name.substringAfterLast('.', "").lowercase()
        val bytes = input.readBounded()
        val raw = when (extension) {
            "docx" -> docx(bytes)
            "xlsx" -> xlsx(bytes)
            "txt", "md", "markdown", "csv" -> String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            "doc", "xls" -> error(
                "Il formato binario .$extension non è supportato. Salva il file come " +
                    (if (extension == "doc") ".docx" else ".xlsx") + " e riprova.",
            )
            else -> error("Formato non supportato: .$extension")
        }
        val clean = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.isBlank()) {
            error("Il documento non contiene testo estraibile.")
        }
        val truncated = clean.length > MAX_CHARS
        ExtractedDocument(
            name = name,
            format = extension,
            text = if (truncated) clean.take(MAX_CHARS) else clean,
            characters = minOf(clean.length, MAX_CHARS),
            truncated = truncated,
        )
    }

    private fun docx(bytes: ByteArray): String {
        val entries = unzip(bytes)
        val xml = entries["word/document.xml"]
            ?: error("DOCX non valido: manca word/document.xml")
        val document = parse(xml)
        val paragraphs = document.getElementsByTagName("w:p")
        val builder = StringBuilder()
        for (index in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(index) ?: continue
            val text = paragraphText(paragraph).trim()
            if (text.isNotEmpty()) builder.append(text).append('\n')
        }
        // Some generators use a different prefix for the Word namespace.
        if (builder.isBlank()) {
            val fallback = document.getElementsByTagNameNS("*", "p")
            for (index in 0 until fallback.length) {
                val text = paragraphText(fallback.item(index)).trim()
                if (text.isNotEmpty()) builder.append(text).append('\n')
            }
        }
        return builder.toString()
    }

    private fun xlsx(bytes: ByteArray): String {
        val entries = unzip(bytes)
        val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
            val document = parse(xml)
            val items = document.getElementsByTagName("si")
            buildList {
                for (index in 0 until items.length) {
                    add(nodesText(items.item(index)).trim())
                }
            }
        } ?: emptyList()

        val sheets = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.substringAfter("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) error("XLSX non valido: nessun foglio trovato.")

        val builder = StringBuilder()
        sheets.forEachIndexed { sheetIndex, path ->
            val document = parse(entries.getValue(path))
            builder.append("[Foglio ").append(sheetIndex + 1).append("]\n")
            val rows = document.getElementsByTagName("row")
            for (rowIndex in 0 until rows.length) {
                val row = rows.item(rowIndex) as? org.w3c.dom.Element ?: continue
                val cells = row.getElementsByTagName("c")
                val values = mutableListOf<String>()
                for (cellIndex in 0 until cells.length) {
                    val cell = cells.item(cellIndex) as? org.w3c.dom.Element ?: continue
                    val reference = cell.getAttribute("r")
                    val type = cell.getAttribute("t")
                    val raw = childText(cell, "v")
                    val value = when {
                        type == "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        type == "inlineStr" -> nodesText(cell).trim()
                        type == "b" -> if (raw == "1") "VERO" else "FALSO"
                        else -> raw
                    }
                    if (value.isNotBlank()) {
                        values.add(if (reference.isBlank()) value else "$reference: $value")
                    }
                }
                if (values.isNotEmpty()) builder.append(values.joinToString(" | ")).append('\n')
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun paragraphText(node: Node?): String {
        if (node == null) return ""
        val builder = StringBuilder()
        fun visit(current: Node) {
            when (current.nodeType) {
                Node.TEXT_NODE -> builder.append(current.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val local = current.localName ?: current.nodeName.substringAfter(':')
                    when (local) {
                        "tab" -> builder.append('\t')
                        "br", "cr" -> builder.append('\n')
                        "t" -> builder.append(current.textContent.orEmpty())
                        else -> {
                            var child = current.firstChild
                            while (child != null) {
                                visit(child)
                                child = child.nextSibling
                            }
                        }
                    }
                }
            }
        }
        visit(node)
        return builder.toString()
    }

    private fun nodesText(node: Node?): String {
        if (node == null) return ""
        if (node.nodeType == Node.TEXT_NODE) return node.nodeValue.orEmpty()
        val builder = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            builder.append(nodesText(child))
            child = child.nextSibling
        }
        return builder.toString()
    }

    private fun childText(element: org.w3c.dom.Element, tag: String): String {
        val nodes = element.getElementsByTagName(tag)
        return if (nodes.length > 0) nodes.item(0).textContent.orEmpty().trim() else ""
    }

    private fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_BYTES) error("Archivio troppo grande: il limite è 32 MB.")
                    output.write(buffer, 0, read)
                }
                result[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun InputStream.readBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_BYTES) error("File troppo grande: il limite è 32 MB.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
