package com.bigbai.core.lang

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Deterministic arithmetic used to verify calculations the model is asked to do.
 *
 * Small quantized models are unreliable at arithmetic: they can produce a
 * plausible-looking wrong result. The app therefore evaluates the expression
 * itself and injects the verified result into the prompt, so the model explains
 * a number it did not invent. Nothing here is a language model: it is a parser
 * with a fixed grammar and explicit limits.
 */
object Calculator {

    data class Verified(
        val expression: String,
        val value: Double,
        val formatted: String,
    )

    private const val MAX_LENGTH = 240
    private const val MAX_DEPTH = 24

    /**
     * Evaluates a plain arithmetic expression (`+ - * / % ^`, parentheses,
     * unary minus, decimal point). Returns null for anything else, including
     * variables, functions and text.
     */
    fun evaluate(expression: String): Double? {
        val input = expression.trim()
        if (input.isEmpty() || input.length > MAX_LENGTH) return null
        val parser = Parser(tokenize(input) ?: return null)
        val value = parser.parseExpression(0) ?: return null
        if (!parser.atEnd()) return null
        if (value.isNaN() || value.isInfinite()) return null
        return value
    }

    /** Formats without a locale-dependent decimal separator ambiguity. */
    fun format(value: Double): String {
        if (value == 0.0) return "0"
        val rounded = BigDecimal(value).round(MathContext(12, RoundingMode.HALF_UP))
        val stripped = rounded.stripTrailingZeros()
        val plain = stripped.toPlainString()
        return if (plain == "-0") "0" else plain
    }

    /**
     * Finds arithmetic expressions inside a user message and evaluates them.
     *
     * It deliberately ignores year ranges ("2024-2025"), times and dates, which
     * look like subtractions but are not calculations.
     */
    fun detect(text: String): List<Verified> {
        if (text.length > 4_000) return emptyList()
        val results = LinkedHashMap<String, Verified>()

        val percent = Regex("""(?<![\w.])(\d+(?:[.,]\d+)?)\s*%\s*(?:di|del|della|of)\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
        for (match in percent.findAll(text)) {
            val percentage = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val total = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            val value = percentage / 100.0 * total
            val expression = "${format(percentage)}% di ${format(total)}"
            results[expression] = Verified(expression, value, format(value))
        }

        val arithmetic = Regex("""(?<![\w.,])(-?\d+(?:\.\d+)?(?:\s*[-+*/^%]\s*-?\d+(?:\.\d+)?)+)(?![\w.])""")
        for (match in arithmetic.findAll(text)) {
            val raw = match.groupValues[1].trim()
            if (isYearRange(raw)) continue
            val value = evaluate(raw) ?: continue
            val normalized = raw.replace(Regex("""\s+"""), " ")
            results[normalized] = Verified(normalized, value, format(value))
        }
        return results.values.take(4)
    }

    /** Prompt section with verified results, empty when there is nothing to add. */
    fun promptSection(verified: List<Verified>, italian: Boolean): String {
        if (verified.isEmpty()) return ""
        val builder = StringBuilder()
        builder.append(
            if (italian) {
                "CALCOLI VERIFICATI DALL'APP (usa questi risultati, non ricalcolarli a mente):\n"
            } else {
                "CALCULATIONS VERIFIED BY THE APP (use these results, do not recompute them mentally):\n"
            },
        )
        verified.forEach { item ->
            builder.append("- ").append(item.expression).append(" = ").append(item.formatted).append('\n')
        }
        builder.append(
            if (italian) "Se il risultato contraddice la tua stima, prevale il calcolo verificato.\n"
            else "If the result contradicts your estimate, the verified calculation wins.\n",
        )
        return builder.toString()
    }

    private fun isYearRange(raw: String): Boolean {
        val years = Regex("""^(19|20)\d{2}\s*[-–]\s*(19|20)\d{2}$""")
        return years.matches(raw)
    }

    // ------------------------------------------------------------------ parser

    private sealed interface Token {
        data class Number(val value: Double) : Token
        data class Operator(val symbol: Char) : Token
        data object Open : Token
        data object Close : Token
    }

    private fun tokenize(input: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var index = 0
        var expectNumber = true
        while (index < input.length) {
            val char = input[index]
            when {
                char.isWhitespace() -> index += 1
                char.isDigit() || (char == '.' && index + 1 < input.length && input[index + 1].isDigit()) -> {
                    val start = index
                    while (index < input.length && (input[index].isDigit() || input[index] == '.')) index += 1
                    val literal = input.substring(start, index)
                    val value = literal.toDoubleOrNull() ?: return null
                    tokens.add(Token.Number(value))
                    expectNumber = false
                }
                char == '(' -> {
                    tokens.add(Token.Open)
                    index += 1
                    expectNumber = true
                }
                char == ')' -> {
                    tokens.add(Token.Close)
                    index += 1
                    expectNumber = false
                }
                char in "+-*/%^" -> {
                    if (expectNumber) {
                        if (char != '-') return null
                        tokens.add(Token.Number(-1.0))
                        tokens.add(Token.Operator('*'))
                    } else {
                        tokens.add(Token.Operator(char))
                    }
                    index += 1
                    expectNumber = true
                }
                else -> return null
            }
        }
        return tokens.takeIf { it.isNotEmpty() }
    }

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        fun atEnd(): Boolean = position >= tokens.size

        fun parseExpression(depth: Int): Double? {
            if (depth > MAX_DEPTH) return null
            var left = parseTerm(depth + 1) ?: return null
            while (true) {
                val operator = peekOperator() ?: break
                if (operator != '+' && operator != '-') break
                position += 1
                val right = parseTerm(depth + 1) ?: return null
                left = if (operator == '+') left + right else left - right
            }
            return left
        }

        private fun parseTerm(depth: Int): Double? {
            if (depth > MAX_DEPTH) return null
            var left = parsePower(depth + 1) ?: return null
            while (true) {
                val operator = peekOperator() ?: break
                if (operator != '*' && operator != '/' && operator != '%') break
                position += 1
                val right = parsePower(depth + 1) ?: return null
                left = when (operator) {
                    '*' -> left * right
                    '/' -> if (right == 0.0) return null else left / right
                    else -> if (right == 0.0) return null else left % right
                }
            }
            return left
        }

        private fun parsePower(depth: Int): Double? {
            if (depth > MAX_DEPTH) return null
            val base = parsePrimary(depth + 1) ?: return null
            val operator = peekOperator()
            if (operator == '^') {
                position += 1
                val exponent = parsePower(depth + 1) ?: return null
                if (kotlin.math.abs(exponent) > 64) return null
                return Math.pow(base, exponent)
            }
            return base
        }

        private fun parsePrimary(depth: Int): Double? {
            if (depth > MAX_DEPTH) return null
            val token = tokens.getOrNull(position) ?: return null
            return when (token) {
                is Token.Number -> {
                    position += 1
                    token.value
                }
                Token.Open -> {
                    position += 1
                    val value = parseExpression(depth + 1) ?: return null
                    if (tokens.getOrNull(position) != Token.Close) return null
                    position += 1
                    value
                }
                else -> null
            }
        }

        private fun peekOperator(): Char? {
            val token = tokens.getOrNull(position) as? Token.Operator ?: return null
            return token.symbol
        }
    }
}
