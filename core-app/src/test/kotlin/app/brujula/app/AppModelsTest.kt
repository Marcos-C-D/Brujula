package app.brujula.app

import app.brujula.engine.*
import app.brujula.market.*
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun d(s: String) = LocalDate.parse(s)
private fun m(s: String) = Money.of(s)
private fun u(s: String) = Units.of(s)
private fun bd(s: String) = BigDecimal(s)
private fun pt(date: String, close: String) = PricePoint(d(date), bd(close))

private val ETF = AssetId("etf")
private val FUND = AssetId("fund")
private val CATALOG = listOf(
    AssetDefinition(ETF, "ETF Mundo", "ETF", AssetKind.ETF, "x", emptyList(), "XETRA"),
    AssetDefinition(FUND, "MSCI World", "Fondos indexados", AssetKind.INDEX_FUND, "y", emptyList(), "EURONEXT"),
).associateBy { it.id }

class AppModelsTest {

    // ---------------- formato ----------------

    @Test
    fun formatoEspanol() {
        assertEquals("1.234,50 €", Fmt.eur(m("1234.5")))
        assertEquals("1.234.567,89 €", Fmt.eur(m("1234567.891")))
        assertEquals("-15,00 €", Fmt.eur(m("-15")))
        assertEquals("0,00 €", Fmt.eur(m("0")))
        assertEquals("—", Fmt.eurOrDash(null))
        assertEquals("10,5 €".replace("10,5", "10,50"), Fmt.price(m("10.5")))
        assertEquals("12,3457 €", Fmt.price(m("12.345678")))
        assertEquals("0,255", Fmt.units(u("0.255000")))
        assertEquals("+12,34 %", Fmt.pct(bd("12.34"), signed = true))
        assertEquals("-3,10 %", Fmt.pct(bd("-3.1"), signed = true))
        assertEquals("—", Fmt.pctOrDash(null))
        assertEquals("09/11/2026", Fmt.date(d("2026-11-09")))
    }

    @Test
    fun entradaDelUsuario() {
        assertEquals(m("1234.50"), Fmt.parseMoney("1.234,50"))
        assertEquals(m("25.5"), Fmt.parseMoney("25,5"))
        assertEquals(m("25.50"), Fmt.parseMoney(" 25.50 "))
        assertNull(Fmt.parseMoney(""))
        assertNull(Fmt.parseMoney("abc"))
        assertEquals(u("0.255"), Fmt.parseUnits("0,255"))
    }

    // ---------------- valoración ----------------

    private fun stateConCartera(): AccountState {
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-01-02"), m("1000.00")))
        l.post(Purchase(d("2026-01-05"), ETF, m("300.00"), u("30")))
        l.post(Purchase(d("2026-01-05"), FUND, m("200.00"), u("20")))
        return l.state
    }

    @Test
    fun valoracionConPreciosCompletos() {
        val lookup = PriceLookup { id, _ -> if (id == ETF) PriceQuote(m("12"), d("2026-11-06")) else PriceQuote(m("9"), d("2026-11-06")) }
        val v = Valuation.of(stateConCartera(), d("2026-11-09"), lookup) { CATALOG[it] }
        assertEquals(m("500.00"), v.cash)
        assertEquals(m("540.00"), v.investedValue) // 30*12 + 20*9
        assertEquals(m("1040.00"), v.netWorth)
        assertEquals(m("40.00"), v.gain) // 540 - 500 de coste
        assertEquals(bd("8.00"), v.gainPct)
        assertEquals(bd("4.00"), v.totalReturnPct) // (1.040 - 1.000) / 1.000
        assertTrue(v.complete)
        val etf = v.rows.first { it.assetId == ETF }
        assertEquals(m("360.00"), etf.value)
        assertEquals(m("60.00"), etf.gain)
        assertEquals(bd("20.00"), etf.gainPct)
        assertEquals(m("10"), etf.averageCost)
        val total = v.allocationByAsset.fold(BigDecimal.ZERO) { a, s -> a + s.percent }
        assertTrue(total >= bd("99.98") && total <= bd("100.02"), total.toString())
        assertEquals("Efectivo", v.allocationByAsset.first().label) // 500 es lo mayor
        assertEquals(setOf("Efectivo", "ETF", "Fondos indexados"), v.allocationByCategory.map { it.label }.toSet())
    }

    @Test
    fun siFaltaUnPrecioNoSeInventaYSeAvisa() {
        val lookup = PriceLookup { id, _ -> if (id == ETF) PriceQuote(m("12"), d("2026-11-06")) else null }
        val v = Valuation.of(stateConCartera(), d("2026-11-09"), lookup) { CATALOG[it] }
        assertFalse(v.complete)
        assertEquals(listOf(FUND), v.missingPrices)
        assertEquals(m("360.00"), v.investedValue) // el fondo sin precio no suma
        assertNull(v.rows.first { it.assetId == FUND }.value)
        assertNull(v.rows.first { it.assetId == FUND }.gainPct)
        assertEquals("—", Fmt.eurOrDash(v.rows.first { it.assetId == FUND }.value))
    }

    @Test
    fun unPrecioAntiguoSeMarcaComoDesactualizado() {
        val lookup = PriceLookup { _, _ -> PriceQuote(m("12"), d("2026-10-20")) }
        val v = Valuation.of(stateConCartera(), d("2026-11-09"), lookup) { CATALOG[it] }
        assertTrue(v.rows.all { it.stale })
        val fresco = Valuation.of(stateConCartera(), d("2026-11-09"), { _, _ -> PriceQuote(m("12"), d("2026-11-06")) }) { CATALOG[it] }
        assertTrue(fresco.rows.none { it.stale })
    }

    @Test
    fun efectivoDisponibleDescuentaReservasYVentasSinLiquidar() {
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-11-02"), m("1000.00")))
        l.post(OrderPlaced(d("2026-11-03"), "o1", OrderSpec(OrderSide.BUY, OrderType.MARKET, ETF, amount = m("200.00"))))
        val v = Valuation.of(l.state, d("2026-11-03"), { _, _ -> null }) { CATALOG[it] }
        assertEquals(m("1000.00"), v.cash)
        assertEquals(m("200.00"), v.reserved)
        assertEquals(m("800.00"), v.availableCash)
    }

    // ---------------- historial y estadísticas ----------------

    @Test
    fun historialDePatrimonioReproduceElLibro() {
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-01-01"), m("1000.00")))
        l.post(Purchase(d("2026-01-03"), ETF, m("500.00"), u("50")))
        val series = PriceSeries.of(listOf(pt("2026-01-01", "10"), pt("2026-01-02", "10"), pt("2026-01-03", "10"), pt("2026-01-04", "12"), pt("2026-01-05", "11")))
        val pts = History.compute(l.records, SeriesPriceLookup { if (it == ETF) series else null }, d("2026-01-05"))
        assertEquals((1..5).map { d("2026-01-0$it") }, pts.map { it.date })
        assertEquals(listOf(m("1000"), m("1000"), m("1000"), m("1100"), m("1050")), pts.map { it.netWorth })
        assertEquals(m("500.00"), pts.last().cash)
        assertEquals(m("1000.00"), pts.last().contributions)
    }

    @Test
    fun historialMarcaLosPuntosSinPrecioSinInventarlos() {
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-01-01"), m("1000.00")))
        l.post(Purchase(d("2026-01-01"), ETF, m("500.00"), u("50")))
        val series = PriceSeries.of(listOf(pt("2026-01-03", "10")))
        val pts = History.compute(l.records, SeriesPriceLookup { series }, d("2026-01-03"))
        assertNull(pts[0].netWorth) // el 1 y el 2 aún no hay precio
        assertNull(pts[1].netWorth)
        assertEquals(m("1000"), pts[2].netWorth)
    }

    @Test
    fun muestreoNoSuperaElMaximoDePuntosYIncluyeElFinal() {
        val dates = History.sampleDates(d("2020-01-01"), d("2026-11-09"), 300)
        assertTrue(dates.size <= 301)
        assertEquals(d("2026-11-09"), dates.last())
        assertEquals(d("2020-01-01"), dates.first())
    }

    @Test
    fun rentabilidadPonderadaPorTiempoIgnoraLosIngresos() {
        val pts = listOf(
            NetWorthPoint(d("2026-01-01"), m("1000"), m("0"), m("1000"), m("1000")),
            NetWorthPoint(d("2026-02-01"), m("1000"), m("100"), m("1100"), m("1000")),
            NetWorthPoint(d("2026-03-01"), m("1500"), m("150"), m("1650"), m("1500")), // ingreso de 500
        )
        val s = Stats.of(pts, AccountState.EMPTY)
        assertEquals(bd("15.00"), s.twrPct) // 1,10 x (1.650 - 500) / 1.100
        assertEquals(59L, s.days)
    }

    @Test
    fun anualizadaSoloConAlMenos30DiasYCompararConElIndice() {
        val corto = listOf(
            NetWorthPoint(d("2026-01-01"), m("1000"), m("0"), m("1000"), m("1000")),
            NetWorthPoint(d("2026-01-10"), m("1000"), m("100"), m("1100"), m("1000")),
        )
        assertNull(Stats.of(corto, AccountState.EMPTY).annualizedPct)
        val largo = listOf(
            NetWorthPoint(d("2025-11-09"), m("1000"), m("0"), m("1000"), m("1000")),
            NetWorthPoint(d("2026-11-09"), m("1000"), m("100"), m("1100"), m("1000")),
        )
        val idx = PriceSeries.of(listOf(pt("2025-11-09", "100"), pt("2026-11-09", "108")))
        val s = Stats.of(largo, AccountState.EMPTY, "MSCI World" to idx)
        assertEquals(bd("10.00"), s.annualizedPct) // exactamente un año
        assertEquals(bd("8.00"), s.benchmark!!.benchmarkReturnPct)
        assertEquals(bd("10.00"), s.benchmark!!.accountTwrPct)
        assertEquals("MSCI World", s.benchmark!!.name)
    }

    // ---------------- mercados ----------------

    private fun storeConDatos(): InMemoryMarketStore {
        val store = InMemoryMarketStore()
        val pts = generateSequence(d("2024-01-01")) { it.plusDays(1) }.takeWhile { !it.isAfter(d("2026-11-06")) }
            .mapIndexed { i, day -> PricePoint(day, bd("100").add(BigDecimal(i).multiply(bd("0.05"))).add(if (i % 2 == 0) bd("0.3") else bd("-0.3"))) }.toList()
        store.saveSeries(StoredSeries(ETF, "SYM.DE", "yahoo", "EUR", pts, Instant.parse("2026-11-07T23:00:00Z"), Instant.parse("2026-11-07T23:00:00Z"), null))
        store.saveSeries(StoredSeries(FUND, "", "", "", emptyList(), null, Instant.parse("2026-11-07T23:00:00Z"), "Yahoo: HTTP 429"))
        return store
    }

    @Test
    fun listaDeMercadosConDatosYSinEllos() {
        val rows = MarketModels.rows(CATALOG.values.toList(), storeConDatos(), d("2026-11-09"))
        val etf = rows.first { it.def.id == ETF }
        assertTrue(etf.hasData)
        assertEquals(m(etf.price!!.amount.toPlainString()), etf.price)
        assertEquals(d("2026-11-06"), etf.priceDate)
        assertFalse(etf.stale) // el viernes 6 es el último cierre esperado un lunes 9
        assertTrue(etf.ytd is ReturnResult.Value)
        assertTrue(etf.riskClass != null)
        val fondo = rows.first { it.def.id == FUND }
        assertFalse(fondo.hasData)
        assertEquals("Yahoo: HTTP 429", fondo.note)
        assertNull(fondo.price)
    }

    @Test
    fun fichaDeActivoConRentabilidadesYRiesgoExplicado() {
        val det = MarketModels.detail(CATALOG.getValue(ETF), storeConDatos(), d("2026-11-09"))!!
        assertEquals(ReturnPeriod.values().size, det.returns.size)
        assertTrue(det.returns.getValue(ReturnPeriod.Y1) is ReturnResult.Value)
        assertTrue(det.returns.getValue(ReturnPeriod.Y5) is ReturnResult.Insufficient) // la serie empieza en 2024
        assertEquals("SYM.DE", det.symbol)
        assertEquals("EUR", det.originalCurrency)
        assertTrue(det.riskText.contains("volatilidad histórica"))
        assertTrue(det.riskText.contains("no una predicción"))
        assertNull(MarketModels.detail(CATALOG.getValue(FUND), storeConDatos(), d("2026-11-09")))
    }

    @Test
    fun seriesEnDolaresSeMuestranEnEurosConSuNota() {
        val store = InMemoryMarketStore()
        val pts = (1..20).map { PricePoint(d("2026-10-%02d".format(it)), bd("110")) }
        store.saveSeries(StoredSeries(ETF, "X", "yahoo", "USD", pts, Instant.parse("2026-11-07T23:00:00Z"), null, null))
        store.saveFx(StoredFx("USD", pts.associate { it.date to bd("1.10") }, Instant.parse("2026-11-07T23:00:00Z")))
        val det = MarketModels.detail(CATALOG.getValue(ETF), store, d("2026-11-09"))!!
        assertEquals("USD", det.originalCurrency)
        assertEquals(0, det.series.last.close.compareTo(bd("100")))
        assertTrue(det.conversionNote.contains("BCE"))
    }
}
