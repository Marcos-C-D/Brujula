package app.brujula.engine

import app.brujula.engine.json.Json
import app.brujula.engine.json.JsonException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {

    @Test
    fun escribirYLeerConservaTodo() {
        val original = linkedMapOf<String, Any?>(
            "texto" to "comillas \" barra \\ salto\n tab\t control\u0001 ñ € 😀",
            "num" to 42,
            "importe" to BigDecimal("12.50"),
            "si" to true,
            "no" to false,
            "nada" to null,
            "lista" to listOf("a", 1, null, listOf<Any?>()),
            "objeto" to linkedMapOf("x" to linkedMapOf<String, Any?>()),
        )
        val leido = Json.parse(Json.write(original)) as Map<*, *>
        assertEquals(original["texto"], leido["texto"])
        assertEquals(BigDecimal("42"), leido["num"])
        assertEquals("12.50", (leido["importe"] as BigDecimal).toPlainString())
        assertEquals(true, leido["si"])
        assertEquals(false, leido["no"])
        assertEquals(null, leido["nada"])
        assertEquals(4, (leido["lista"] as List<*>).size)
        assertEquals(setOf("texto", "num", "importe", "si", "no", "nada", "lista", "objeto"), leido.keys)
    }

    @Test
    fun entiendeEscapesUnicode() {
        assertEquals("ñ€", Json.parse("\"\\u00f1\\u20ac\""))
        assertEquals("a/b", Json.parse("\"a\\/b\""))
    }

    @Test
    fun rechazaJsonMalFormado() {
        val malos = listOf(
            "[1,]", "{\"a\":1,}", "{\"a\":}", "\"sin cerrar", "{\"a\":1,\"a\":2}", "01", "1.", "-", "tru",
            "[1] x", "{'a':1}", "\"\\x\"", "\"tab\tliteral\"", "", "[",
        )
        for (texto in malos) {
            assertFailsWith<JsonException>("debería fallar: $texto") { Json.parse(texto) }
        }
    }

    @Test
    fun elAnidamientoExtremoNoDesbordaLaPila() {
        val profundo = "[".repeat(5000) + "]".repeat(5000)
        assertFailsWith<JsonException> { Json.parse(profundo) }
    }

    @Test
    fun noEscribeTiposDesconocidos() {
        assertFailsWith<JsonException> { Json.write(object {}) }
        assertFailsWith<JsonException> { Json.write(mapOf(1 to 2)) }
    }
}
