package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Small calculator for the "Vypočítat" block:
 * `+ - * / % ^`, parentheses, decimal comma or point and the functions
 * `round(x)`, `round(x, places)`, `floor`, `ceil`, `abs`, `sqrt`, `min(a, b, …)`, `max(a, b, …)`.
 */
object Calc {
    fun evaluate(expression: String): Double {
        val parser = Parser(normalize(expression))
        val value = parser.expression()
        parser.skipSpaces()
        if (!parser.atEnd()) throw BlockException(tr("nečekaný znak \"%s\" ve výrazu", parser.rest().take(10)))
        if (value.isNaN() || value.isInfinite()) throw BlockException(tr("výsledek není číslo (dělení nulou?)"))
        return value
    }

    /** "1 234,5" → "1234.5"; commas between digits are decimal commas, others separate arguments. */
    private fun normalize(text: String): String =
        text.replace(' ', ' ')
            .replace(Regex("(?<=\\d) (?=\\d{3}(\\D|$))"), "")
            .replace(Regex("(?<=\\d),(?=\\d)"), ".")

    private class Parser(val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length
        fun rest() = text.substring(pos)
        fun skipSpaces() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun take(char: Char): Boolean {
            skipSpaces()
            if (pos < text.length && text[pos] == char) {
                pos++
                return true
            }
            return false
        }

        fun expression(): Double {
            var value = term()
            while (true) {
                value = when {
                    take('+') -> value + term()
                    take('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = power()
            while (true) {
                value = when {
                    take('*') || take('×') -> value * power()
                    take('/') || take(':') -> value / power()
                    take('%') -> value % power()
                    else -> return value
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            return if (take('^')) base.pow(power()) else base
        }

        private fun unary(): Double = when {
            take('-') -> -unary()
            take('+') -> unary()
            else -> primary()
        }

        private fun primary(): Double {
            skipSpaces()
            if (take('(')) {
                val value = expression()
                if (!take(')')) throw BlockException(tr("ve výrazu chybí )"))
                return value
            }
            val start = pos
            if (pos < text.length && text[pos].isLetter()) {
                while (pos < text.length && text[pos].isLetter()) pos++
                val name = text.substring(start, pos).lowercase()
                if (!take('(')) throw BlockException(tr("za %s chybí (", name))
                val args = mutableListOf(expression())
                while (take(',') || take(';')) args += expression()
                if (!take(')')) throw BlockException(tr("ve výrazu chybí )"))
                return function(name, args)
            }
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E') && pos > start) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val number = text.substring(start, pos)
            return number.toDoubleOrNull()
                ?: throw BlockException(if (atEnd()) tr("výraz je neúplný") else tr("\"%s\" není číslo", rest().take(10)))
        }

        private fun function(name: String, args: List<Double>): Double {
            fun one(): Double {
                if (args.size != 1) throw BlockException(tr("%s potřebuje jedno číslo", name))
                return args[0]
            }
            return when (name) {
                "round", "zaokrouhli" -> {
                    val places = args.getOrNull(1)?.toInt() ?: 0
                    val factor = 10.0.pow(places)
                    (args[0] * factor).roundToLong() / factor
                }
                "floor" -> floor(one())
                "ceil" -> ceil(one())
                "abs" -> abs(one())
                "sqrt" -> sqrt(one())
                "min" -> args.min()
                "max" -> args.max()
                else -> throw BlockException(tr("neznámá funkce %s", name))
            }
        }
    }
}
