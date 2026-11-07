package app.brujula.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun sumaDecimalExactaSinErroresDeComaFlotante() {
        val resultado = Money.of("0.10") + Money.of("0.20")
        assertEquals(0, resultado.compareTo(Money.of("0.30")))
        assertEquals("0.30", resultado.toString())
    }

    @Test
    fun restaPuedeQuedarNegativa() {
        val resultado = Money.of("10.00") - Money.of("25.50")
        assertTrue(resultado.isNegative)
        assertEquals("-15.50", resultado.toString())
    }

    @Test
    fun cantidadInvalidaSeRechaza() {
        assertFailsWith<NumberFormatException> { Money.of("abc") }
    }

    @Test
    fun ceroEsNeutroEnLaSuma() {
        val x = Money.of("123.45")
        assertEquals("123.45", (x + Money.ZERO).toString())
    }

    @Test
    fun comparacionIgnoraLaEscala() {
        assertTrue(Money.of("1.0") >= Money.of("1.00"))
        assertTrue(Money.of("1.0") <= Money.of("1.00"))
    }
}
