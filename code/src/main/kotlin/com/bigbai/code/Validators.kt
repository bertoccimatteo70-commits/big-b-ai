package com.bigbai.code

/**
 * Real, dependency-free validators used by the terminal and the verifier.
 *
 * They parse the document instead of searching for substrings: a file is
 * reported as valid only when the whole input was consumed by the grammar. No
 * Android or external library is involved, so the same code runs in JVM tests.
 */
object JsonValidator {

    data class Result(
        val valid: Boolean,
        val error: String? = null,
        val line: Int = 0,
        val column: Int = 0,
    ) {
        fun describe(): String =
            if (valid) "JSON valido" else "JSON non valido (riga $line, colonna $column): $error"
    }

    fun validate(text: String): Result {
        if (text.isBlank()) return Result(false, "documento vuoto", 1, 1)
        val parser = Parser(text)
        return try {
            parser.parseDocument()
            Result(true)
        } catch (error: JsonException) {
            Result(false, error.message, error.line, error.column)
        }
    }

    private class JsonException(
        override val message: String,
        val line: Int,
        val column: Int,
    ) : Exception(message)

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument() {
            skipWhitespace()
            parseValue(0)
            skipWhitespace()
            if (index != text.length) fail("contenuto inatteso dopo il valore")
        }

        private fun parseValue(depth: Int) {
            if (depth > 64) fail("annidamento eccessivo")
            skipWhitespace()
            if (index >= text.length) fail("fine del documento inattesa")
            when (text[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int) {
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                index++
                return
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("atteso un nome di campo tra virgolette")
                parseString()
                skipWhitespace()
                expect(':')
                parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return
                    }
                    else -> fail("attesi ',' o '}' nell'oggetto")
                }
            }
        }

        private fun parseArray(depth: Int) {
            expect('[')
            skipWhitespace()
            if (peek() == ']') {
                index++
                return
            }
            while (true) {
                parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return
                    }
                    else -> fail("attesi ',' o ']' nell'array")
                }
            }
        }

        private fun parseString() {
            expect('"')
            while (index < text.length) {
                val char = text[index]
                when {
                    char == '"' -> {
                        index++
                        return
                    }
                    char == '\\' -> {
                        index++
                        if (index >= text.length) fail("sequenza di escape incompleta")
                        when (val escaped = text[index]) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> index++
                            'u' -> {
                                index++
                                repeat(4) {
                                    if (index >= text.length || text[index].digitToIntOrNull(16) == null) {
                                        fail("escape \\u non valido")
                                    }
                                    index++
                                }
                            }
                            else -> fail("escape non valido: \\$escaped")
                        }
                    }
                    char.code < 0x20 -> fail("carattere di controllo non consentito nella stringa")
                    else -> index++
                }
            }
            fail("stringa non chiusa")
        }

        private fun parseNumber() {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length || !text[index].isDigit()) fail("numero non valido")
            if (peek() == '0') {
                index++
            } else {
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek() == '.') {
                index++
                if (index >= text.length || !text[index].isDigit()) fail("parte decimale mancante")
                while (index < text.length && text[index].isDigit()) index++
            }
            if (peek() == 'e' || peek() == 'E') {
                index++
                if (peek() == '+' || peek() == '-') index++
                if (index >= text.length || !text[index].isDigit()) fail("esponente mancante")
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index == start) fail("valore non riconosciuto")
        }

        private fun literal(value: String) {
            if (!text.startsWith(value, index)) fail("atteso $value")
            index += value.length
        }

        private fun expect(char: Char) {
            if (peek() != char) fail("atteso '$char'")
            index++
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
                index++
            }
        }

        private fun fail(message: String): Nothing {
            val before = text.take(index.coerceIn(0, text.length))
            val line = before.count { it == '\n' } + 1
            val column = index - before.lastIndexOf('\n')
            throw JsonException(message, line, column)
        }
    }
}

/** Well-formedness checks for XML-like documents. Not a schema validator. */
object XmlValidator {

    data class Result(val errors: List<String> = emptyList()) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun validate(text: String): Result {
        val errors = mutableListOf<String>()
        if (text.isBlank()) return Result(listOf("documento vuoto"))
        var index = 0
        var line = 1
        val stack = ArrayDeque<Pair<String, Int>>()
        var roots = 0
        var sawRoot = false

        fun advance(count: Int) {
            val end = (index + count).coerceAtMost(text.length)
            for (position in index until end) if (text[position] == '\n') line++
            index = end
        }

        while (index < text.length) {
            val open = text.indexOf('<', index)
            if (open < 0) {
                if (text.substring(index).isNotBlank() && !sawRoot) {
                    errors += "riga $line: testo fuori da un elemento"
                }
                break
            }
            if (text.substring(index, open).isNotBlank() && !sawRoot) {
                errors += "riga $line: testo prima del primo elemento"
            }
            advance(open - index)

            when {
                text.startsWith("<!--", index) -> {
                    val end = text.indexOf("-->", index)
                    if (end < 0) {
                        errors += "riga $line: commento non chiuso"
                        break
                    }
                    advance(end + 3 - index)
                }
                text.startsWith("<![CDATA[", index) -> {
                    val end = text.indexOf("]]>", index)
                    if (end < 0) {
                        errors += "riga $line: CDATA non chiuso"
                        break
                    }
                    advance(end + 3 - index)
                }
                text.startsWith("<?", index) -> {
                    val end = text.indexOf("?>", index)
                    if (end < 0) {
                        errors += "riga $line: istruzione <? non chiusa"
                        break
                    }
                    advance(end + 2 - index)
                }
                text.startsWith("<!", index) -> {
                    val end = text.indexOf('>', index)
                    if (end < 0) {
                        errors += "riga $line: dichiarazione non chiusa"
                        break
                    }
                    advance(end + 1 - index)
                }
                text.startsWith("</", index) -> {
                    val end = text.indexOf('>', index)
                    if (end < 0) {
                        errors += "riga $line: tag di chiusura non terminato"
                        break
                    }
                    val name = text.substring(index + 2, end).trim()
                    advance(end + 1 - index)
                    val expected = stack.lastOrNull()?.first
                    if (expected == null) {
                        errors += "riga $line: chiusura </$name> senza apertura"
                    } else if (expected != name) {
                        errors += "riga $line: chiusura </$name> mentre era aperto <$expected>"
                        stack.removeLast()
                    } else {
                        stack.removeLast()
                    }
                }
                else -> {
                    val startLine = line
                    val end = findTagEnd(text, index)
                    if (end < 0) {
                        errors += "riga $line: tag non terminato"
                        break
                    }
                    val raw = text.substring(index + 1, end)
                    advance(end + 1 - index)
                    val selfClosing = raw.trimEnd().endsWith("/")
                    val body = raw.trimEnd().removeSuffix("/").trim()
                    val name = body.substringBefore(' ').substringBefore('\t').trim()
                    if (name.isEmpty()) {
                        errors += "riga $startLine: nome di elemento mancante"
                        continue
                    }
                    if (!validAttributes(body.substringAfter(name, ""))) {
                        errors += "riga $startLine: attributi non racchiusi tra virgolette in <$name>"
                    }
                    if (!sawRoot) {
                        sawRoot = true
                        roots += 1
                    } else if (stack.isEmpty() && roots >= 1) {
                        errors += "riga $startLine: più elementi radice nel documento"
                    }
                    if (!selfClosing) stack.addLast(name to startLine)
                }
            }
        }

        for ((name, startLine) in stack) {
            errors += "riga $startLine: elemento <$name> non chiuso"
        }
        if (!sawRoot && errors.isEmpty()) errors += "nessun elemento radice"
        return Result(errors.take(12))
    }

    private fun findTagEnd(text: String, start: Int): Int {
        var index = start + 1
        var quote: Char? = null
        while (index < text.length) {
            val char = text[index]
            when {
                quote != null -> if (char == quote) quote = null
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return index
            }
            index++
        }
        return -1
    }

    private fun validAttributes(rest: String): Boolean {
        val trimmed = rest.trim()
        if (trimmed.isEmpty()) return true
        var index = 0
        while (index < trimmed.length) {
            while (index < trimmed.length && trimmed[index].isWhitespace()) index++
            if (index >= trimmed.length) return true
            val nameStart = index
            while (index < trimmed.length && !trimmed[index].isWhitespace() && trimmed[index] != '=') index++
            if (index == nameStart) return false
            while (index < trimmed.length && trimmed[index].isWhitespace()) index++
            if (index >= trimmed.length || trimmed[index] != '=') return false
            index++
            while (index < trimmed.length && trimmed[index].isWhitespace()) index++
            if (index >= trimmed.length) return false
            val quote = trimmed[index]
            if (quote != '"' && quote != '\'') return false
            index++
            while (index < trimmed.length && trimmed[index] != quote) index++
            if (index >= trimmed.length) return false
            index++
        }
        return true
    }
}

/** Row/column consistency for CSV and TSV files, quote-aware. */
object CsvValidator {

    data class Result(
        val rows: Int,
        val columns: Int,
        val errors: List<String> = emptyList(),
    ) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun validate(text: String, delimiter: Char = ','): Result {
        if (text.isBlank()) return Result(0, 0, listOf("file vuoto"))
        val errors = mutableListOf<String>()
        val rows = parse(text, delimiter, errors)
        if (rows.isEmpty()) return Result(0, 0, errors.ifEmpty { listOf("nessuna riga") })
        val expected = rows.first().size
        val inconsistent = rows.withIndex().filter { it.value.size != expected }
        inconsistent.take(5).forEach { (index, row) ->
            errors += "riga ${index + 1}: ${row.size} colonne invece di $expected"
        }
        return Result(rows.size, expected, errors)
    }

    private fun parse(text: String, delimiter: Char, errors: MutableList<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0
        var line = 1
        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes -> {
                    if (char == '"') {
                        if (index + 1 < text.length && text[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        if (char == '\n') line++
                        field.append(char)
                    }
                }
                char == '"' -> inQuotes = true
                char == delimiter -> {
                    row += field.toString()
                    field.clear()
                }
                char == '\n' -> {
                    row += field.toString()
                    field.clear()
                    rows += row
                    row = mutableListOf()
                    line++
                }
                char == '\r' -> Unit
                else -> field.append(char)
            }
            index++
        }
        if (inQuotes) errors += "virgolette non chiuse (riga $line)"
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }
}
