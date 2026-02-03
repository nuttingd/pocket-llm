package dev.nutting.pocketllm.domain.tool

import javax.script.ScriptEngineManager

object CalculatorTool {

    fun evaluate(expression: String): String {
        return try {
            val result = evaluateExpression(expression)
            result.toString()
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun evaluateExpression(expression: String): Double {
        val sanitized = expression.replace(Regex("[^0-9+\\-*/().\\s]"), "")
        if (sanitized.isBlank()) throw IllegalArgumentException("Invalid expression")
        return Parser(sanitized).parseExpression()
    }

    /** Simple recursive descent parser for arithmetic expressions. */
    private class Parser(private val input: String) {
        private var pos = 0

        fun parseExpression(): Double {
            val result = parseAddSub()
            skipWhitespace()
            if (pos < input.length) throw IllegalArgumentException("Unexpected character: ${input[pos]}")
            return result
        }

        private fun parseAddSub(): Double {
            var left = parseMulDiv()
            while (pos < input.length) {
                skipWhitespace()
                if (pos >= input.length) break
                val op = input[pos]
                if (op != '+' && op != '-') break
                pos++
                val right = parseMulDiv()
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun parseMulDiv(): Double {
            var left = parseUnary()
            while (pos < input.length) {
                skipWhitespace()
                if (pos >= input.length) break
                val op = input[pos]
                if (op != '*' && op != '/') break
                pos++
                val right = parseUnary()
                left = if (op == '*') left * right else left / right
            }
            return left
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            if (pos < input.length && input[pos] == '-') {
                pos++
                return -parseAtom()
            }
            if (pos < input.length && input[pos] == '+') {
                pos++
            }
            return parseAtom()
        }

        private fun parseAtom(): Double {
            skipWhitespace()
            if (pos < input.length && input[pos] == '(') {
                pos++
                val result = parseAddSub()
                skipWhitespace()
                if (pos < input.length && input[pos] == ')') pos++
                return result
            }
            return parseNumber()
        }

        private fun parseNumber(): Double {
            skipWhitespace()
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Expected number at position $pos")
            return input.substring(start, pos).toDouble()
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}
