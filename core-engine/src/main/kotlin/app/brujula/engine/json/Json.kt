package app.brujula.engine.json

import java.math.BigDecimal

class JsonException(message: String) : RuntimeException(message)

/**
 * JSON mínimo y estricto, sin dependencias, para copias de seguridad y almacenamiento.
 * Valores admitidos: Map<String, *>, List<*>, String, Boolean, null y números (Int, Long, BigDecimal).
 * Al leer, los números llegan como BigDecimal, los objetos como LinkedHashMap y los arrays como List.
 */
object Json {

    fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long -> out.append(value.toString())
            is BigDecimal -> out.append(value.toPlainString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (k !is String) throw JsonException("Las claves JSON deben ser texto")
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> throw JsonException("Tipo no admitido en JSON: ${value::class.java.simpleName}")
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Texto sobrante tras el JSON (posición ${p.pos})")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonException("$msg (posición $pos)")

        fun value(depth: Int): Any? {
            if (depth > 100) fail("Anidamiento excesivo")
            if (atEnd()) fail("Fin inesperado")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("Carácter inesperado '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("Valor no válido")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("Se esperaba una clave de texto")
                val key = str()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("Se esperaba ':'")
                pos++
                skipWs()
                if (map.containsKey(key)) fail("Clave duplicada '$key'")
                map[key] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("Objeto sin cerrar")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("Se esperaba ',' o '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list += value(depth + 1)
                skipWs()
                if (atEnd()) fail("Array sin cerrar")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("Se esperaba ',' o ']'")
                }
            }
        }

        private fun str(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Texto sin cerrar")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("Escape incompleto")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("Escape \\u incompleto")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("Escape \\u no válido")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("Escape no válido '\\$e'")
                        }
                    }
                    c < ' ' -> fail("Carácter de control sin escapar")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): BigDecimal {
            val start = pos
            if (s[pos] == '-') pos++
            if (atEnd()) fail("Número incompleto")
            if (s[pos] == '0') pos++ else if (s[pos] in '1'..'9') { while (!atEnd() && s[pos] in '0'..'9') pos++ } else fail("Número no válido")
            if (!atEnd() && s[pos] == '.') {
                pos++
                val d0 = pos
                while (!atEnd() && s[pos] in '0'..'9') pos++
                if (pos == d0) fail("Decimales ausentes")
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                val e0 = pos
                while (!atEnd() && s[pos] in '0'..'9') pos++
                if (pos == e0) fail("Exponente ausente")
            }
            return BigDecimal(s.substring(start, pos))
        }
    }
}
