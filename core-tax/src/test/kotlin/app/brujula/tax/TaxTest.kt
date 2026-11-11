package app.brujula.tax

import app.brujula.engine.*
import app.brujula.market.*
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import app.brujula.trading.TradingConfig
import app.brujula.trading.TradingService
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun d(s: String) = LocalDate.parse(s)
private fun m(s: String) = Money.of(s)
private fun u(s: String) = Units.of(s)

private val ETF = AssetId("etf")
private val DIV = AssetId("div")
private val FA = AssetId("fa")
private val FB = AssetId("fb")
private val BTC = AssetId("btc")

private val CATALOG = listOf(
    AssetDefinition(ETF, "ETF X", "ETF", AssetKind.ETF, "x", emptyList(), "XETRA"),
    AssetDefinition(DIV, "ETF Dividendos", "ETF", AssetKind.ETF, "d", emptyList(), "XETRA", distributes = true),
    AssetDefinition(FA, "Fondo A", "Fondos", AssetKind.INDEX_FUND, "a", emptyList(), "EURONEXT"),
    AssetDefinition(FB, "Fondo B", "Fondos", AssetKind.INDEX_FUND, "b", emptyList(), "EURONEXT"),
    AssetDefinition(BTC, "Bitcoin", "Cripto", AssetKind.CRYPTO, "btc", emptyList(), null),
).associateBy { it.id }

private fun ledger(vararg events: LedgerEvent): List<LedgerRecord> {
    val l = Ledger(AccountId("t"))
    events.forEach { l.post(it) }
    return l.records
}

private fun calc(settings: TaxSettings = TaxSettings()) = TaxCalculator(TaxRules(), settings) { CATALOG[it] }
private fun buy(date: String, asset: AssetId, invested: String, units: String) = Purchase(d(date), asset, m(invested), u(units))
private fun sell(date: String, asset: AssetId, units: String, gross: String) = Sale(d(date), asset, u(units), m(gross))
private fun open(amount: String = "20000.00") = OpeningBalance(d("2026-01-02"), m(amount))

class TaxTest {

    // ---------------- escala y calendario ----------------

    @Test
    fun laEscalaDelAhorroSeAplicaPorTramos() {
        val r = TaxRules()
        assertEquals(m("0"), r.tax(2026, m("0")))
        assertEquals(m("1140.00"), r.tax(2026, m("6000")))
        assertEquals(m("10380.00"), r.tax(2026, m("50000")))
        assertEquals(m("71880.00"), r.tax(2026, m("300000"))) // hasta 300.000 €: 19/21/23/27 %
        assertEquals(m("101880.00"), r.tax(2026, m("400000"))) // el exceso de 300.000 € al 30 %
        assertEquals(m("95.00"), r.tax(2026, m("500")))
        assertFailsWith24 { r.tax(2024, m("100")) }
    }

    private fun assertFailsWith24(block: () -> Unit) {
        var thrown = false
        try { block() } catch (e: UnsupportedTaxYearException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun calendarioOficialYEstimado() {
        val c = TaxCalendar.campaign(2025)
        assertTrue(c.official)
        assertEquals(d("2026-04-08"), c.start)
        assertEquals(d("2026-06-25"), c.directDebitDeadline)
        assertEquals(d("2026-06-30"), c.chargeDate)
        assertEquals(d("2026-11-05"), c.secondInstallment)
        val e = TaxCalendar.campaign(2026)
        assertFalse(e.official)
        assertEquals(d("2027-06-30"), e.end)
        assertEquals(d("2027-11-05"), e.secondInstallment)
        assertEquals(d("2029-07-02"), TaxCalendar.campaign(2028).end) // 30-jun-2029 es sábado
    }

    // ---------------- cálculo ----------------

    @Test
    fun gananciaSencillaTributaAl19PorCiento() {
        val r = calc().compute(ledger(open(), buy("2026-01-05", ETF, "1000.00", "100"), sell("2026-09-01", ETF, "100", "1500.00")), 2026).last()
        assertEquals(m("500.00"), r.gainsLossesBalance)
        assertEquals(m("500.00"), r.savingsBase)
        assertEquals(m("95.00"), r.grossTax)
        assertEquals(m("95.00"), r.result)
    }

    @Test
    fun otrasRentasDelAhorroOcupanTramosDeLaEscala() {
        val s = TaxSettings(otherSavingsBase = mapOf(2026 to m("5800")))
        val r = calc(s).compute(ledger(open(), buy("2026-01-05", ETF, "1000.00", "100"), sell("2026-09-01", ETF, "100", "1500.00")), 2026).last()
        // 500 € ocupan 200 € del tramo al 19 % y 300 € del tramo al 21 %: 38 + 63
        assertEquals(m("101.00"), r.grossTax)
    }

    @Test
    fun perdidasSeCompensanConDividendosHastaEl25PorCientoYElRestoSeArrastra() {
        val recs = ledger(
            open(), buy("2026-02-01", ETF, "5000.00", "500"),
            DividendReceived(d("2026-03-01"), DIV, m("1000.00"), withholding = m("190.00")),
            sell("2026-06-01", ETF, "500", "3000.00"),
        )
        val r = calc().compute(recs, 2026).last()
        assertEquals(m("-2000.00"), r.gainsLossesBalance)
        assertEquals(m("250.00"), r.crossCompensation)
        assertEquals(m("750.00"), r.savingsBase)
        assertEquals(m("142.50"), r.grossTax)
        assertEquals(m("-47.50"), r.result) // la retención de 190 supera la cuota: devolución
        assertEquals(listOf(Carry(2026, m("1750.00"))), r.carryForward)
    }

    @Test
    fun lasPerdidasArrastradasSeUsanContraGananciasFuturasYCaducanAlCuartoAno() {
        fun conGananciaEn(year: Int): TaxYearResult {
            val recs = ledger(
                open(), buy("2026-02-01", ETF, "5000.00", "500"), sell("2026-06-01", ETF, "500", "3000.00"), // pérdida de 2.000 en 2026
                buy("$year-02-01", ETF, "1000.00", "100"), sell("$year-03-01", ETF, "100", "4000.00"), // ganancia de 3.000
            )
            return calc().compute(recs, year).last()
        }
        val r2030 = conGananciaEn(2030) // 2030 - 2026 = 4: aún se puede
        assertEquals(m("2000.00"), r2030.carriedUsed)
        assertEquals(m("1000.00"), r2030.savingsBase)
        val r2031 = conGananciaEn(2031) // 5 años: caducada
        assertEquals(m("0"), r2031.carriedUsed)
        assertEquals(m("3000.00"), r2031.savingsBase)
    }

    @Test
    fun laRecompraDentroDeDosMesesAplazaLaPerdidaDeUnEtfYSeIntegraAlVenderLoRecomprado() {
        val recs = ledger(
            open(), buy("2026-01-05", ETF, "1000.00", "100"), sell("2026-02-01", ETF, "100", "800.00"), // pérdida de 200
            buy("2026-02-20", ETF, "400.00", "50"), // recompra dentro de 2 meses
            sell("2026-08-01", ETF, "50", "600.00"), // gana 200 y libera 100 de pérdida aplazada
        )
        val r = calc().compute(recs, 2026).last()
        val primera = r.sales[0]
        assertEquals(m("100.00"), primera.deferredLoss)
        assertEquals(m("-100.00"), primera.computable)
        assertEquals(m("-100.00"), r.integratedDeferred)
        assertEquals(m("0.00"), r.gainsLossesBalance) // -100 + 200 - 100
    }

    @Test
    fun sinRecompraEnVentanaLaPerdidaComputaEntera() {
        val recs = ledger(open(), buy("2026-01-05", ETF, "1000.00", "100"), sell("2026-02-01", ETF, "100", "800.00"), buy("2026-06-01", ETF, "400.00", "50"))
        val r = calc().compute(recs, 2026).last()
        assertEquals(m("-200.00"), r.gainsLossesBalance) // 4 meses después: fuera de la ventana de 2 meses
    }

    @Test
    fun siLoRecompradoYaNoSeTieneAlCerrarLaVentanaNoHayAplazamiento() {
        val recs = ledger(
            open(), buy("2026-01-05", ETF, "1000.00", "100"), sell("2026-02-01", ETF, "100", "800.00"),
            buy("2026-02-20", ETF, "400.00", "50"), sell("2026-03-01", ETF, "50", "420.00"), // se vende antes de cerrarse la ventana
        )
        val r = calc().compute(recs, 2026).last()
        assertEquals(m("0.00"), r.sales[0].deferredLoss)
        assertEquals(m("-180.00"), r.gainsLossesBalance) // -200 + 20
    }

    @Test
    fun losFondosTienenVentanaDeUnAnoYLosEtfSoloDeDosMeses() {
        fun perdida(asset: AssetId): Money {
            val recs = ledger(open(), buy("2026-01-05", asset, "1000.00", "100"), sell("2026-02-01", asset, "100", "800.00"), buy("2026-09-01", asset, "400.00", "50"))
            return calc().compute(recs, 2026).last().gainsLossesBalance
        }
        assertEquals(m("-200.00"), perdida(ETF)) // 7 meses: fuera de la ventana de 2
        assertEquals(m("-100.00"), perdida(FA)) // dentro del año: aplazada la parte recomprada (50 de 100 u.)
    }

    @Test
    fun bitcoinNoAplicaLaReglaDeRecompraSalvoQueSeActive() {
        val recs = ledger(open(), buy("2026-01-05", BTC, "1000.00", "1"), sell("2026-02-01", BTC, "1", "800.00"), buy("2026-02-10", BTC, "400.00", "0.5"))
        assertEquals(m("-200.00"), calc().compute(recs, 2026).last().gainsLossesBalance)
        assertEquals(m("-100.00"), calc(TaxSettings(applyRepurchaseToCrypto = true)).compute(recs, 2026).last().gainsLossesBalance)
    }

    @Test
    fun elTraspasoConservaCosteYFechaYNoTributaHastaElReembolso() {
        val spec = OrderSpec(OrderSide.TRANSFER, OrderType.MARKET, FA, toAssetId = FB, units = u("100"))
        val recs = ledger(
            open(), buy("2026-01-05", FA, "1000.00", "100"),
            OrderPlaced(d("2026-02-10"), "t1", spec),
            FundTransfer(d("2026-02-10"), FA, FB, u("100"), u("50"), "t1", d("2026-02-16")),
            sell("2026-03-01", FB, "50", "1500.00"),
        )
        val r = calc().compute(recs, 2026).last()
        assertEquals(1, r.sales.size) // el traspaso no genera ninguna venta fiscal
        assertEquals(m("500.00"), r.gainsLossesBalance) // 1.500 - coste original 1.000
        assertEquals(m("95.00"), r.result)
    }

    @Test
    fun laDeduccionPorDobleImposicionSeLimitaALaCuotaAtribuible() {
        val recs = ledger(open(), buy("2026-01-05", DIV, "1000.00", "100"),
            DividendReceived(d("2026-06-01"), DIV, m("1000.00"), foreignTax = m("150.00"), withholding = m("161.50")))
        val r = calc().compute(recs, 2026).last()
        assertEquals(m("190.00"), r.grossTax) // 19 % de 1.000
        assertEquals(m("150.00"), r.doubleTaxDeduction) // lo pagado fuera (150) no supera la cuota atribuible (190)
        assertEquals(m("-121.50"), r.result) // 190 - 150 - 161,50
    }

    // ---------------- hechos fiscales en sus fechas ----------------

    private class Env(settings: TaxSettings = TaxSettings(), val feed: List<DividendDeclaration> = emptyList()) {
        val bank = Bank(Bank.sequentialIds())
        val acc = bank.createAccount("Cuenta", m("10000.00"), m("0.00"), 1, d("2026-11-02"))
        val service = TaxService(bank, { CATALOG[it] }, { CATALOG.keys.toList() }, { id -> if (id == DIV) feed else emptyList() }, TaxRules(), settings)
        val state: AccountState get() = acc.state
        fun post(e: LedgerEvent) = bank.post(acc.id, e)
        fun day(date: String) = service.processDay(acc.id, d(date))
        fun audit() = LedgerAuditor.audit(acc.ledger.records)
    }

    @Test
    fun dividendosSeCobranElDiaExConRetencionYSinDuplicar() {
        val e = Env(feed = listOf(DividendDeclaration(d("2026-11-20"), BigDecimal("0.50"))))
        e.post(buy("2026-11-10", DIV, "1000.00", "100"))
        e.day("2026-11-19")
        assertEquals(0, e.state.dividends.size)
        e.day("2026-11-20")
        val dv = e.state.dividends.single()
        assertEquals(m("50.00"), dv.gross)
        assertEquals(m("9.50"), dv.withholding)
        assertEquals(m("9040.50"), e.state.cash) // 9.000 + 50 - 9,50
        e.day("2026-11-20")
        assertEquals(1, e.state.dividends.size)
        assertEquals(emptyList(), e.audit())
    }

    @Test
    fun quienCompraElDiaExNoCobraElDividendo() {
        val e = Env(feed = listOf(DividendDeclaration(d("2026-11-20"), BigDecimal("0.50"))))
        e.post(buy("2026-11-20", DIV, "1000.00", "100"))
        e.day("2026-11-20")
        assertEquals(0, e.state.dividends.size)
    }

    @Test
    fun reembolsarUnFondoConGananciaPracticaRetencionAlLiquidarYUnEtfNo() {
        val e = Env()
        e.post(buy("2026-11-03", FA, "1000.00", "100"))
        e.post(Sale(d("2026-11-10"), FA, u("100"), m("1500.00"), settlementDate = d("2026-11-13")))
        e.day("2026-11-12")
        assertEquals(0, e.state.withholdings.size)
        e.day("2026-11-13")
        assertEquals(m("95.00"), e.state.withholdings.single().amount) // 19 % de la ganancia de 500
        e.day("2026-11-13")
        assertEquals(1, e.state.withholdings.size)
        assertEquals(emptyList(), e.audit())

        val etf = Env()
        etf.post(buy("2026-11-03", ETF, "1000.00", "100"))
        etf.post(Sale(d("2026-11-10"), ETF, u("100"), m("1500.00")))
        etf.day("2026-11-13")
        assertEquals(0, etf.state.withholdings.size) // los ETF no sufren retención al vender
    }

    private fun conGanancia2026(settings: TaxSettings = TaxSettings()): Env {
        val e = Env(settings)
        e.post(buy("2026-11-03", ETF, "1000.00", "100"))
        e.post(sell("2026-11-10", ETF, "100", "1500.00"))
        return e
    }

    @Test
    fun elIrpfSePagaEnLaFechaDeLaCampanaYNoAntesNiDosVeces() {
        val e = conGanancia2026()
        e.day("2027-06-29")
        assertEquals(0, e.state.taxPostings.size)
        e.day("2027-06-30")
        val p = e.state.taxPostings.single()
        assertEquals(d("2027-06-30"), p.date)
        assertEquals(m("-95.00"), p.amount)
        e.day("2027-06-30")
        e.day("2027-11-05")
        assertEquals(1, e.state.taxPostings.size)
        assertEquals(m("10405.00"), e.state.cash) // 10.000 + 500 ganancia - 95
        assertEquals(emptyList(), e.audit())
    }

    @Test
    fun pagoFraccionado60y40EnSusFechas() {
        val e = conGanancia2026(TaxSettings(payment = PaymentMode.SPLIT_60_40))
        e.day("2027-06-30")
        assertEquals(m("-57.00"), e.state.taxPostings.single().amount)
        e.day("2027-11-05")
        assertEquals(listOf(m("-57.00"), m("-38.00")), e.state.taxPostings.map { it.amount })
        assertEquals(listOf(1, 2), e.state.taxPostings.map { it.installment })
    }

    @Test
    fun elEfectivoPuedeQuedarNegativoAlPagarImpuestos() {
        val e = conGanancia2026()
        e.post(CashAdjustment(d("2026-12-01"), -e.state.cash, "Retirada de fondos"))
        e.day("2027-06-30")
        assertEquals(m("-95.00"), e.state.cash)
        assertTrue(e.state.cash.isNegative)
    }

    @Test
    fun laDevolucionSeAbonaSeisMesesDespuesDelFinDeCampana() {
        val e = Env(feed = listOf(DividendDeclaration(d("2026-11-20"), BigDecimal("1.00"))))
        e.post(buy("2026-11-10", DIV, "1000.00", "100"))
        e.day("2026-11-20") // dividendo de 100, retención 19
        e.post(Sale(d("2026-12-01"), DIV, u("100"), m("500.00"))) // pérdida de 500
        e.day("2027-06-30")
        assertEquals(0, e.state.taxPostings.size) // sale a devolver: no se paga nada
        e.day("2027-12-30")
        // base: 100 de dividendos - 25 compensados = 75 -> cuota 14,25 - retención 19 = -4,75
        assertEquals(m("4.75"), e.state.taxPostings.single().amount)
        e.day("2027-12-30")
        assertEquals(1, e.state.taxPostings.size)
    }

    // ---------------- integración con las órdenes ----------------

    @Test
    fun elCicloDiarioDeContratacionRegistraElImpuestoSolo() {
        val bank = Bank(Bank.sequentialIds())
        val acc = bank.createAccount("Cuenta", m("10000.00"), m("0.00"), 1, d("2026-11-02"))
        val tax = TaxService(bank, { CATALOG[it] }, { CATALOG.keys.toList() }, { emptyList() })
        val start = d("2026-11-09")
        val pts = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(d("2027-07-01")) }.map { day ->
            val p = if (day.isBefore(d("2026-11-16"))) "10" else "15"
            PricePoint(day, BigDecimal(p), BigDecimal(p), BigDecimal(p), BigDecimal(p))
        }.toList()
        val series = PriceSeries.of(pts)
        var now = Instant.parse("2026-11-09T09:00:00Z")
        var n = 0
        val trading = TradingService(bank, { CATALOG[it] }, { if (it == ETF) series else null }, TradingConfig(), { now }, { "o${++n}" }) { id, day -> tax.processDay(id, day) }
        trading.placeBuy(acc.id, ETF, m("1000.00"))
        trading.process(acc.id, d("2026-11-15"), d("2026-11-15"))
        now = Instant.parse("2026-11-16T09:00:00Z")
        trading.placeSell(acc.id, ETF, u("100"))
        trading.process(acc.id, d("2027-06-30"), d("2027-06-30"))

        assertEquals(m("-95.00"), acc.state.taxPostings.single().amount) // 19 % de 500
        assertEquals(m("10405.00"), acc.state.cash)
        assertEquals(emptyList(), LedgerAuditor.audit(acc.ledger.records))
    }

    // ---------------- guardado y avisos ----------------

    @Test
    fun losEventosFiscalesSobrevivenAlGuardadoYALaCopiaDeSeguridad() {
        val e = Env(feed = listOf(DividendDeclaration(d("2026-11-20"), BigDecimal("0.50"))))
        e.post(buy("2026-11-10", DIV, "1000.00", "100"))
        e.day("2026-11-20")
        e.post(buy("2026-11-21", FA, "1000.00", "100"))
        e.post(Sale(d("2026-11-25"), FA, u("100"), m("1500.00"), settlementDate = d("2026-11-30")))
        e.day("2026-11-30")
        e.day("2027-06-30")
        for (rec in e.acc.ledger.records) assertEquals(rec.event, EventCodec.fromJson(EventCodec.toJson(rec.event)))
        val otro = Bank()
        otro.importAccounts(BackupService.parse(BackupService.export(e.bank, d("2027-07-01"))))
        assertEquals(e.state, otro.get(e.acc.id).state)
        val csv = CsvExporter.ledger(e.acc.ledger.records)
        assertTrue(csv.contains("DIVIDENDO") && csv.contains("RETENCION"))
        // el pago del IRPF también viaja intacto (aquí la retención ya cubre la cuota y no hay pago)
        val pago = TaxSettlement(d("2027-06-30"), 2026, 1, m("-95.00"), "Pago del IRPF 2026")
        assertEquals(pago, EventCodec.fromJson(EventCodec.toJson(pago)))
        assertTrue(CsvExporter.ledger(listOf(LedgerRecord(1, OpeningBalance(d("2026-01-02"), m("1000.00"))), LedgerRecord(2, pago))).contains("IRPF 2026 plazo 1"))
    }

    @Test
    fun avisosInformativosSoloSobreElUmbralDe50000() {
        assertEquals(emptyList(), InformativeNotices.forPortfolio(mapOf(AssetKind.CRYPTO to m("50000"))))
        assertEquals(1, InformativeNotices.forPortfolio(mapOf(AssetKind.CRYPTO to m("50001"))).size)
        assertEquals(1, InformativeNotices.forPortfolio(mapOf(AssetKind.ETF to m("30000"), AssetKind.INDEX_FUND to m("30000"))).size)
    }
}
