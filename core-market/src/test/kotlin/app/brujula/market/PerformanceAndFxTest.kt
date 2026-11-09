package app.brujula.market

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PerformanceAndFxTest {

    private fun daily(from: String, to: String, price: (Long) -> BigDecimal): PriceSeries {
        val pts = ArrayList<PricePoint>()
        var day = d(from)
        var i = 0L
        while (!day.isAfter(d(to))) { pts += PricePoint(day, price(i)); day = day.plusDays(1); i++ }
        return PriceSeries.of(pts)
    }

    @Test
    fun laSerieSeOrdenaDeduplicaYRechazaPreciosNoPositivos() {
        val s = PriceSeries.of(listOf(pt("2026-01-03", "3"), pt("2026-01-01", "1"), pt("2026-01-03", "33")))
        assertEquals(listOf("2026-01-01", "2026-01-03"), s.points.map { it.date.toString() })
        assertEquals(bd("33"), s.last.close)
        assertFailsWith<IllegalArgumentException> { PriceSeries.of(listOf(pt("2026-01-01", "0"))) }
        assertFailsWith<IllegalArgumentException> { PriceSeries.of(listOf(pt("2026-01-01", "-1"))) }
    }

    @Test
    fun rentabilidadYtdUsaElUltimoCierreDelAnoAnterior() {
        val s = PriceSeries.of(listOf(pt("2025-12-31", "100"), pt("2026-06-01", "105"), pt("2026-11-06", "110")))
        val r = Performance.calculate(s, ReturnPeriod.YTD) as ReturnResult.Value
        assertEquals(bd("10.00"), r.percent)
        assertEquals(d("2025-12-31"), r.fromDate)
    }

    @Test
    fun siFaltaHistoricoSeIndicaYNoSeInventa() {
        val s = PriceSeries.of(listOf(pt("2026-01-15", "100"), pt("2026-11-06", "110")))
        val rs = Performance.returns(s)
        assertTrue(rs.getValue(ReturnPeriod.YTD) is ReturnResult.Insufficient)
        assertTrue(rs.getValue(ReturnPeriod.Y1) is ReturnResult.Insufficient)
        assertTrue(rs.getValue(ReturnPeriod.Y10) is ReturnResult.Insufficient)
        // 3 meses: no hay precio cerca de hace 3 meses (hueco enorme) -> tampoco
        assertTrue(rs.getValue(ReturnPeriod.M3) is ReturnResult.Insufficient)
        assertEquals(bd("10.00"), (rs.getValue(ReturnPeriod.ALL) as ReturnResult.Value).percent)
        assertTrue(Performance.calculate(PriceSeries.EMPTY, ReturnPeriod.ALL) is ReturnResult.Insufficient)
    }

    @Test
    fun usaCierreAjustadoSiTodosLosPuntosLoTraen() {
        val s = PriceSeries.of(listOf(pt("2025-12-31", "100", "50"), pt("2026-11-06", "100", "60")))
        assertEquals(bd("20.00"), (Performance.calculate(s, ReturnPeriod.YTD) as ReturnResult.Value).percent)
        val mixta = PriceSeries.of(listOf(pt("2025-12-31", "100", "50"), pt("2026-11-06", "120")))
        assertEquals(bd("20.00"), (Performance.calculate(mixta, ReturnPeriod.YTD) as ReturnResult.Value).percent)
    }

    @Test
    fun todosLosPeriodosConSerieLarga() {
        val s = daily("2015-01-01", "2026-11-06") { i -> BigDecimal(100 + i) }
        val rs = Performance.returns(s)
        assertEquals(ReturnPeriod.values().size, rs.size)
        assertTrue(rs.values.all { it is ReturnResult.Value })
        val y1 = rs.getValue(ReturnPeriod.Y1) as ReturnResult.Value
        assertEquals(d("2025-11-06"), y1.fromDate)
        assertTrue(y1.percent.signum() > 0)
    }

    @Test
    fun volatilidadCeroSiElCrecimientoEsConstanteYAltaSiOscila() {
        val constante = daily("2025-01-01", "2026-06-30") { i -> BigDecimal("100").multiply(BigDecimal("1.001").pow(i.toInt())) }
        val v0 = Risk.volatility(constante) as VolatilityResult.Value
        assertEquals(0, v0.annualizedPercent.compareTo(BigDecimal.ZERO))
        assertEquals(1, Risk.riskClass(v0.annualizedPercent))

        var p = BigDecimal(100)
        val oscila = daily("2025-01-01", "2026-06-30") { i -> if (i > 0) p = p.multiply(if (i % 2L == 0L) BigDecimal("1.01") else BigDecimal("0.99")); p }
        val v = Risk.volatility(oscila) as VolatilityResult.Value
        assertTrue(v.annualizedPercent > BigDecimal("15") && v.annualizedPercent < BigDecimal("17"), v.annualizedPercent.toString())
        assertEquals(6, Risk.riskClass(v.annualizedPercent))
    }

    @Test
    fun volatilidadSinDatosSuficientesSeIndica() {
        val corta = daily("2026-09-01", "2026-11-06") { BigDecimal(100) }
        assertTrue(Risk.volatility(corta) is VolatilityResult.Insufficient)
        assertTrue(Risk.volatility(PriceSeries.EMPTY) is VolatilityResult.Insufficient)
    }

    @Test
    fun tramosDeRiesgo() {
        fun c(s: String) = Risk.riskClass(BigDecimal(s))
        assertEquals(listOf(1, 2, 2, 3, 4, 5, 6, 7), listOf(c("0.49"), c("0.5"), c("1.99"), c("2"), c("5"), c("10"), c("15"), c("25")))
    }

    // ---------- divisas ----------

    @Test
    fun conversionDeDolaresYPenies() {
        val usd = PriceSeries.of(listOf(pt("2026-11-05", "110")))
        val fx = FxSeries("USD", mapOf(d("2026-11-05") to bd("1.10")))
        val eur = FxConverter.toEur(usd, "USD", fx)
        assertEquals(0, eur.series.last.close.compareTo(bd("100")))

        val gbp = FxSeries("GBP", mapOf(d("2026-11-05") to bd("0.85")))
        val pence = FxConverter.toEur(PriceSeries.of(listOf(pt("2026-11-05", "8500"))), "GBp", gbp)
        assertEquals(0, pence.series.last.close.compareTo(bd("100"))) // 85,00 GBP / 0,85
    }

    @Test
    fun siFaltaElTipoDeCambioNoSeInventa() {
        val s = PriceSeries.of(listOf(pt("2026-11-05", "110"), pt("2026-12-30", "120")))
        val fx = FxSeries("USD", mapOf(d("2026-11-04") to bd("1.10")))
        val eur = FxConverter.toEur(s, "USD", fx)
        assertEquals(1, eur.series.points.size)
        assertEquals(listOf(d("2026-12-30")), eur.missingFxDates) // más de 7 días sin tipo publicado
        val sinFx = FxConverter.toEur(s, "USD", null)
        assertTrue(sinFx.series.isEmpty)
        assertFailsWith<IllegalArgumentException> { FxConverter.toEur(s, "JPY", null) }
    }

    @Test
    fun eurNoSeConvierte() {
        val s = PriceSeries.of(listOf(pt("2026-11-05", "10")))
        assertEquals(s.last, FxConverter.toEur(s, "EUR", null).series.last)
    }
}
