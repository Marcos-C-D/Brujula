package app.brujula.market

import app.brujula.model.AssetId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvidersAndUpdaterTest {

    private val now = Instant.parse("2026-11-09T10:00:00Z")

    private fun http(code: Int, body: String) = HttpFetcher { _, _ -> HttpResponse(code, body) }

    // 2026-01-01 08:00Z, 2026-01-02 08:00Z, 2026-01-05 08:00Z
    private val yahooOk = """{"chart":{"result":[{"meta":{"currency":"EUR","symbol":"X","gmtoffset":3600},
      "timestamp":[1767254400,1767340800,1767600000],
      "indicators":{"quote":[{"open":[10.0,10.5,null],"high":[11.0,11.5,null],"low":[9.5,10.0,null],"close":[10.5,null,11.25],"volume":[1,2,3]}],
      "adjclose":[{"adjclose":[10.4,null,11.1]}]}}],"error":null}}"""

    @Test
    fun yahooInterpretaFechasLocalesNulosYAjustados() {
        val f = YahooChartProvider(http(200, yahooOk)).fetchHistory("X", now)
        assertEquals("EUR", f.currency)
        assertEquals(listOf("2026-01-01", "2026-01-05"), f.points.map { it.date.toString() }) // el día sin cierre se omite
        assertEquals(bd("10.5"), f.points[0].close)
        assertEquals(bd("10.4"), f.points[0].adjClose)
        assertEquals(bd("9.5"), f.points[0].low)
    }

    @Test
    fun yahooLeeLosDividendosPorUnidad() {
        val body = """{"chart":{"result":[{"meta":{"currency":"EUR","gmtoffset":3600},"timestamp":[1767254400,1767600000],
          "indicators":{"quote":[{"close":[10.0,11.0]}]},
          "events":{"dividends":{"1767254400":{"amount":0.25,"date":1767254400},"1767600000":{"amount":0,"date":1767600000}}}}],"error":null}}"""
        val f = YahooChartProvider(http(200, body)).fetchHistory("X", now)
        assertEquals(listOf(DividendEvent(d("2026-01-01"), bd("0.25"))), f.dividends) // el de importe 0 se descarta
    }

    @Test
    fun yahooRechazaErroresYRespuestasMalas() {
        val err = """{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}"""
        val e = assertFailsWith<ProviderException> { YahooChartProvider(http(200, err)).fetchHistory("Z", now) }
        assertTrue(e.message!!.contains("delisted"))
        assertFailsWith<ProviderException> { YahooChartProvider(http(429, "")).fetchHistory("X", now) }
        assertFailsWith<ProviderException> { YahooChartProvider(http(200, "no es json")).fetchHistory("X", now) }
        val desajustado = """{"chart":{"result":[{"meta":{"currency":"EUR"},"timestamp":[1,2],"indicators":{"quote":[{"close":[1.0]}]}}],"error":null}}"""
        assertFailsWith<ProviderException> { YahooChartProvider(http(200, desajustado)).fetchHistory("X", now) }
        val sinRed = HttpFetcher { _, _ -> throw java.io.IOException("timeout") }
        assertTrue(assertFailsWith<ProviderException> { YahooChartProvider(sinRed).fetchHistory("X", now) }.message!!.contains("timeout"))
    }

    @Test
    fun yahooDescartaPreciosNoPositivosYLoAvisa() {
        val body = """{"chart":{"result":[{"meta":{"currency":"USD","gmtoffset":0},"timestamp":[1767254400,1767340800,1767600000],
          "indicators":{"quote":[{"close":[10.0,0.0,-3.0]}]}}],"error":null}}"""
        val f = YahooChartProvider(http(200, body)).fetchHistory("X", now)
        assertEquals(1, f.points.size)
        assertTrue(f.warnings.single().contains("2 puntos descartados"))
    }

    @Test
    fun coingeckoUnaCotizacionPorDiaYAvisaDelLimite() {
        // dos puntos el mismo día (gana el último) y uno inválido
        val body = """{"prices":[[1767254400000,100.5],[1767268800000,101.5],[1767340800000,102.0],[1767427200000,null]]}"""
        val f = CoinGeckoProvider(http(200, body)).fetchHistory("bitcoin", now)
        assertEquals(listOf("2026-01-01", "2026-01-02"), f.points.map { it.date.toString() })
        assertEquals(bd("101.5"), f.points[0].close)
        assertTrue(f.warnings.any { it.contains("365") })
        assertTrue(assertFailsWith<ProviderException> { CoinGeckoProvider(http(429, "")).fetchHistory("bitcoin", now) }.message!!.contains("429"))
    }

    @Test
    fun bceInterpretaElCsv() {
        val csv = "KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE,OBS_STATUS\r\n" +
            "EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-11-04,1.1500,A\r\n" +
            "EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-11-05,,A\r\n" +
            "EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-11-06,1.1600,A\r\n"
        val fx = EcbFxProvider(http(200, csv)).fetch("USD")
        assertEquals(2, fx.size)
        assertEquals(bd("1.1500"), fx.rateOn(d("2026-11-05"))!!.second) // el día sin dato usa el último publicado
        assertFailsWith<ProviderException> { EcbFxProvider(http(200, "otra,cosa\n1,2")).fetch("USD") }
        assertFailsWith<ProviderException> { EcbFxProvider(http(404, "")).fetch("USD") }
    }

    // ---------- actualizador ----------

    private class Fake(override val name: String, val script: (String) -> FetchedSeries) : HistoryProvider {
        val calls = ArrayList<String>()
        override fun fetchHistory(symbol: String, now: Instant): FetchedSeries { calls += symbol; return script(symbol) }
    }

    private fun fetched(symbol: String, currency: String, vararg p: PricePoint, provider: String = "yahoo") =
        FetchedSeries(symbol, currency, provider, p.toList(), emptyList(), now)

    private val etf = AssetDefinition(AssetId("a"), "A", "ETF", AssetKind.ETF, "x", listOf(SourceSpec(ProviderId.YAHOO, "A1"), SourceSpec(ProviderId.YAHOO, "A2")), "XETRA")

    @Test
    fun pruebaElSegundoSimboloSiFallaElPrimero() {
        val yahoo = Fake("yahoo") { s -> if (s == "A1") throw ProviderException("yahoo", "A1 no existe") else fetched(s, "EUR", pt("2026-11-05", "10"), pt("2026-11-06", "11")) }
        val store = InMemoryMarketStore()
        val out = MarketUpdater(store, mapOf(ProviderId.YAHOO to yahoo), null, { now }).updateAsset(etf) as UpdateOutcome.Updated
        assertEquals("A2", out.symbol)
        assertEquals(1, out.skippedErrors.size)
        assertEquals("A2", store.loadSeries(etf.id)!!.symbol)
        assertEquals(listOf("A1", "A2"), yahoo.calls)
    }

    @Test
    fun siTodoFallaSeConservaElUltimoPrecioValidoYSeAnotaElError() {
        val store = InMemoryMarketStore()
        val bueno = Fake("yahoo") { s -> fetched(s, "EUR", pt("2026-11-05", "10"), pt("2026-11-06", "11")) }
        MarketUpdater(store, mapOf(ProviderId.YAHOO to bueno), null, { now }).updateAsset(etf)

        val roto = Fake("yahoo") { throw ProviderException("yahoo", "sin conexión") }
        val out = MarketUpdater(store, mapOf(ProviderId.YAHOO to roto), null, { now.plusSeconds(3600) }).updateAsset(etf) as UpdateOutcome.Failed
        assertEquals(d("2026-11-06"), out.keptLastDate)
        val s = store.loadSeries(etf.id)!!
        assertEquals(2, s.points.size) // nada se borra ni se inventa
        assertEquals(bd("11"), s.points.last().close)
        assertTrue(s.lastError!!.contains("sin conexión"))
        assertEquals(now, s.fetchedAt) // la antigüedad de los datos sigue siendo la de la última actualización buena
    }

    @Test
    fun siNuncaHuboDatosNoSeFabricaNinguno() {
        val store = InMemoryMarketStore()
        val roto = Fake("yahoo") { throw ProviderException("yahoo", "caído") }
        val out = MarketUpdater(store, mapOf(ProviderId.YAHOO to roto), null, { now }).updateAsset(etf)
        assertTrue(out is UpdateOutcome.Failed)
        assertTrue(store.loadSeries(etf.id)!!.points.isEmpty())
        assertNull(store.lastSuccessfulUpdate())
    }

    @Test
    fun unaSerieNuevaSospechosamenteCortaSeRechaza() {
        val store = InMemoryMarketStore()
        val largo = (1..100).map { pt("2026-01-%02d".format(1 + (it - 1) % 28).replace("2026-01", "2026-" + "%02d".format(1 + (it - 1) / 28)), "10") }
        val y1 = Fake("yahoo") { s -> fetched(s, "EUR", *largo.toTypedArray()) }
        MarketUpdater(store, mapOf(ProviderId.YAHOO to y1), null, { now }).updateAsset(etf)
        val guardados = store.loadSeries(etf.id)!!.points.size

        val corto = Fake("yahoo") { s -> fetched(s, "EUR", pt("2026-04-01", "10"), pt("2026-04-02", "10")) }
        val out = MarketUpdater(store, mapOf(ProviderId.YAHOO to corto), null, { now }).updateAsset(etf)
        assertTrue(out is UpdateOutcome.Failed)
        assertEquals(guardados, store.loadSeries(etf.id)!!.points.size)
    }

    @Test
    fun noSaltaDeProxyPorUnFalloPasajeroPeroSiTrasDosSemanasSinDatos() {
        val store = InMemoryMarketStore()
        val a1 = Fake("yahoo") { s -> fetched(s, "EUR", pt("2026-11-05", "10"), pt("2026-11-06", "11")) }
        MarketUpdater(store, mapOf(ProviderId.YAHOO to a1), null, { now }).updateAsset(etf)

        // A1 falla pasados 3 días: solo se intenta A1 (no se salta a otro ETF)
        val rotoA1 = Fake("yahoo") { s -> if (s == "A1") throw ProviderException("yahoo", "caído") else fetched(s, "EUR", pt("2026-11-05", "500"), pt("2026-11-06", "501")) }
        val r1 = MarketUpdater(store, mapOf(ProviderId.YAHOO to rotoA1), null, { now.plusSeconds(3 * 86400) }).updateAsset(etf)
        assertTrue(r1 is UpdateOutcome.Failed)
        assertEquals(listOf("A1"), rotoA1.calls)
        assertEquals(bd("11"), store.loadSeries(etf.id)!!.points.last().close)

        // tras 20 días sin actualizar sí se prueba la alternativa, y la serie se sustituye entera (sin mezclar)
        val r2 = MarketUpdater(store, mapOf(ProviderId.YAHOO to rotoA1), null, { now.plusSeconds(20 * 86400) }).updateAsset(etf)
        assertTrue(r2 is UpdateOutcome.Updated)
        val s = store.loadSeries(etf.id)!!
        assertEquals("A2", s.symbol)
        assertEquals(listOf(bd("500"), bd("501")), s.points.map { it.close })
    }

    @Test
    fun bitcoinConRespaldoQueCompletaElHistoricoSinPerderLoAnterior() {
        val btc = AssetDefinition(AssetId("bitcoin"), "Bitcoin", "Cripto", AssetKind.CRYPTO, "Bitcoin",
            listOf(SourceSpec(ProviderId.YAHOO, "BTC-EUR"), SourceSpec(ProviderId.COINGECKO, "bitcoin")), null)
        val store = InMemoryMarketStore()
        val yahoo = Fake("yahoo") { s -> fetched(s, "EUR", pt("2020-01-01", "6000"), pt("2020-01-02", "6100")) }
        MarketUpdater(store, mapOf(ProviderId.YAHOO to yahoo, ProviderId.COINGECKO to Fake("coingecko") { error("no debería llamarse") }), null, { now }).updateAsset(btc)

        val yahooRoto = Fake("yahoo") { throw ProviderException("yahoo", "429") }
        val gecko = Fake("coingecko") { s -> fetched(s, "EUR", pt("2026-11-07", "90000"), pt("2026-11-08", "91000"), provider = "coingecko") }
        val out = MarketUpdater(store, mapOf(ProviderId.YAHOO to yahooRoto, ProviderId.COINGECKO to gecko), null, { now }).updateAsset(btc)
        assertTrue(out is UpdateOutcome.Updated)
        val s = store.loadSeries(btc.id)!!
        assertEquals(4, s.points.size) // histórico previo + datos nuevos
        assertEquals("coingecko", s.provider)
    }

    @Test
    fun obtieneElTipoDeCambioYConvierteAEuros() {
        val usdAsset = AssetDefinition(AssetId("u"), "U", "ETF", AssetKind.ETF, "x", listOf(SourceSpec(ProviderId.YAHOO, "U1")), "NYSE")
        val yahoo = Fake("yahoo") { s -> fetched(s, "USD", pt("2026-11-05", "110"), pt("2026-11-06", "121")) }
        val csv = "TIME_PERIOD,OBS_VALUE\n2026-11-05,1.10\n2026-11-06,1.10\n"
        val store = InMemoryMarketStore()
        val updater = MarketUpdater(store, mapOf(ProviderId.YAHOO to yahoo), EcbFxProvider(http(200, csv)), { now })
        updater.updateAll(listOf(usdAsset))
        val eur = EurView.of(store.loadSeries(usdAsset.id)!!, store)!!
        assertEquals(0, eur.series.last.close.compareTo(bd("110")))
        assertEquals("USD", eur.originalCurrency)
        assertEquals(now, store.lastSuccessfulUpdate())
    }

    @Test
    fun elCatalogoNoTieneMarcasYTodosLosActivosPiden()  {
        val marcas = listOf("ishares", "vanguard", "amundi", "xtrackers", "invesco", "vaneck", "fidelity", "spdr")
        for (a in AssetCatalog.DEFAULT) {
            val texto = (a.name + a.category + a.referenceIndex + a.proxyNote).lowercase()
            marcas.forEach { assertTrue(!texto.contains(it), "${a.id}: contiene $it") }
            assertTrue(a.sources.isNotEmpty())
            a.marketId?.let { assertTrue(Markets.byId(it) != null, "${a.id}: calendario $it") }
        }
        assertEquals(AssetCatalog.DEFAULT.size, AssetCatalog.DEFAULT.map { it.id }.toSet().size)
        assertTrue(AssetCatalog.DEFAULT.any { it.kind == AssetKind.CRYPTO })
    }
}
