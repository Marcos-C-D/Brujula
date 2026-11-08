package app.brujula.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun sumaDecimalExactaSinErroresDeComaFlotante() {
        val resultado = Money.of("0.10") + Money.of("0.20")
        assertEquals(Money.of("0.30"), resultado)
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
    fun igualdadYHashIgnoranLaEscala() {
        assertEquals(Money.of("1.0"), Money.of("1.00"))
        assertEquals(Money.of("1.0").hashCode(), Money.of("1.0000").hashCode())
        assertEquals(Money.ZERO, Money.of("0.00"))
        assertEquals(setOf(Money.of("5")), setOf(Money.of("5.00"), Money.of("5.0")))
    }

    @Test
    fun comparacionIgnoraLaEscala() {
        assertTrue(Money.of("1.0") >= Money.of("1.00"))
        assertTrue(Money.of("1.0") <= Money.of("1.00"))
        assertTrue(Money.of("1.01") > Money.of("1.00"))
    }

    @Test
    fun centimosExactos() {
        assertTrue(Money.of("25.50").isWholeCents)
        assertTrue(Money.of("25.500").isWholeCents)
        assertTrue(Money.of("100").isWholeCents)
        assertTrue(Money.of("1E+2").isWholeCents)
        assertFalse(Money.of("0.001").isWholeCents)
        assertFalse(Money.of("25.505").isWholeCents)
    }

    @Test
    fun redondeoExplicitoACentimos() {
        assertEquals(Money.of("0.13"), Money.of("0.125").roundedToCents())
        assertEquals(Money.of("0.12"), Money.of("0.125").roundedToCents(RoundingMode.HALF_EVEN))
        assertEquals("33.33", Money.of("33.333333").roundedToCents().toString())
    }

    @Test
    fun multiplicarPorUnidadesEsExacto() {
        val precio = Money.of("12.345678")
        val unidades = Units.of("2.5")
        assertEquals(Money.of("30.864195"), precio * unidades)
        assertEquals(Money.of("30.864195"), precio * BigDecimal("2.5"))
    }

    @Test
    fun unidadesIgualdadIgnoraEscala() {
        assertEquals(Units.of("1.50"), Units.of("1.5"))
        assertEquals(Units.of("0.1") + Units.of("0.2"), Units.of("0.3"))
        assertTrue(Units.of("0.000001").isPositive)
    }

    @Test
    fun identificadoresNoPuedenEstarVacios() {
        assertFailsWith<IllegalArgumentException> { AccountId(" ") }
        assertFailsWith<IllegalArgumentException> { AssetId("") }
        assertEquals(AccountId("a"), AccountId("a"))
    }
}
