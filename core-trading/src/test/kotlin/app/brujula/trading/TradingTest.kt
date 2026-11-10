package app.brujula.trading

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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun d(s: String) = LocalDate.parse(s)
private fun m(s: String) = Money.of(s)
private fun u(s: String) = Units.of(s)
private fun bar(date: String, o: String, h: String, l: String, c: String) =
    PricePoint(d(date), BigDecimal(c), open = BigDecimal(o), high = BigDecimal(h), low = BigDecimal(l))
private fun flat(date: String, p: String) = bar(date, p, p, p, p)

private val ETF = AssetId("etf")
private val FA = AssetId("fa")
private val FB = AssetId("fb")
private val BTC = AssetId("btc")

private val CATALOG = listOf(
    AssetDefinition(ETF, "ETF X", "ETF", AssetKind.ETF, "x", emptyList(), "XETRA"),
    AssetDefinition(FA, "Fondo A", "Fondos", AssetKind.INDEX_FUND, "a", emptyList(), "EURONEXT"),
    AssetDefinition(FB, "Fondo B", "Fondos", AssetKind.INDEX_FUND, "b", emptyList(), "EURONEXT"),
    AssetDefinition(BTC, "Bitcoin", "Cripto", AssetKind.CRYPTO, "btc", emptyList(), null),
).associateBy { it.id }

private class Env(config: TradingConfig = TradingConfig(), opening: String = "10000.00", income: String = "0.00") {
    // lunes 9-nov-2026, 10:00 en Madrid
    var now: Instant = Instant.parse("2026-11-09T09:00:00Z")
    val bank = Bank(Bank.sequentialIds())
    val series = HashMap<AssetId, PriceSeries>()
    private var n = 0
    val service = TradingService(bank, { CATALOG[it] }, { series[it] }, config, { now }, { "o${++n}" })
    val acc = bank.createAccount("Cuenta", m(opening), m(income), 10, d("2026-11-02"))
    val id: AccountId get() = acc.id
    val state: AccountState get() = acc.state

    fun prices(asset: AssetId, vararg pts: PricePoint) { series[asset] = PriceSeries.of(pts.toList()) }
    fun at(iso: String) { now = Instant.parse(iso) }
    fun run(known: String, today: String = known) = service.process(id, d(known), d(today))
    fun order(id: String) = state.order(id)!!
    fun audit() = LedgerAuditor.audit(acc.ledger.records)
}

class TradingTest {

    @Test
    fun compraDeEtfSeEjecutaEnLaSiguienteSesionALaAperturaYLiquidaEnTMas2() {
        val e = Env()
        e.prices(ETF, bar("2026-11-09", "99", "99", "99", "99"), bar("2026-11-10", "10.00", "10.5", "9.8", "10.2"),
            bar("2026-11-11", "10", "10", "10", "10"), bar("2026-11-12", "10", "10", "10", "10"), bar("2026-11-13", "10", "10", "10", "10"))
        val id = e.service.placeBuy(e.id, ETF, m("100.00"))
        assertEquals(m("100.00"), e.state.reservedCash)
        assertEquals(m("9900.00"), e.state.availableCash(d("2026-11-09")))
        assertEquals(m("10000.00"), e.state.cash) // todavía no se ha gastado

        e.run("2026-11-13")
        val o = e.order(id)
        assertEquals(OrderStatus.SETTLED, o.status)
        assertEquals(d("2026-11-10"), o.executedOn) // no se usa el precio del 9 (día de creación)
        assertEquals(m("10.00"), o.executionPrice)
        assertEquals(d("2026-11-12"), o.settledOn) // T+2 en Xetra
        assertEquals(u("10"), e.state.unitsHeld(ETF))
        assertEquals(m("9900.00"), e.state.cash)
        assertEquals(m("0"), e.state.reservedCash)
        assertEquals(emptyList(), e.audit())
        val pasos = OrderTimeline.of(o).map { it.label.substringBefore(" a ") }
        assertEquals(listOf("Orden solicitada", "Orden aceptada", "Orden ejecutada", "Operación liquidada"), pasos)
    }

    @Test
    fun laReservaEvitaGastarDosVecesElMismoDineroYCancelarLaLibera() {
        val e = Env(opening = "150.00")
        val a = e.service.placeBuy(e.id, ETF, m("100.00"))
        assertFailsWith<InsufficientCashException> { e.service.placeBuy(e.id, ETF, m("100.00")) }
        e.service.cancel(e.id, a)
        assertEquals(OrderStatus.CANCELLED, e.order(a).status)
        assertEquals(m("150.00"), e.state.availableCash(d("2026-11-09")))
        e.service.placeBuy(e.id, ETF, m("100.00")) // ahora sí
        assertFailsWith<TradingException> { e.service.cancel(e.id, a) } // ya no está pendiente
    }

    @Test
    fun limitadaDeCompraNoSeEjecutaAlRozarElLimiteSiAlSuperarloYRespetaLaAperturaConGap() {
        val e = Env()
        e.prices(ETF, flat("2026-11-09", "10"), bar("2026-11-10", "10", "10.2", "9.50", "10"), bar("2026-11-11", "10", "10", "9.49", "9.8"),
            bar("2026-11-12", "9.20", "9.5", "9.1", "9.4"))
        val id = e.service.placeBuy(e.id, ETF, m("100.00"), OrderType.LIMIT, m("9.50"))
        e.run("2026-11-10")
        assertEquals(OrderStatus.ACCEPTED, e.order(id).status) // mínimo == límite: no se ejecuta
        e.run("2026-11-11")
        val o = e.order(id)
        assertEquals(d("2026-11-11"), o.executedOn)
        assertEquals(m("9.50"), o.executionPrice)
        assertEquals(u("10.526315"), e.state.unitsHeld(ETF))

        e.at("2026-11-11T09:00:00Z")
        val gap = e.service.placeBuy(e.id, ETF, m("100.00"), OrderType.LIMIT, m("9.50"))
        e.run("2026-11-12")
        assertEquals(m("9.20"), e.order(gap).executionPrice) // abre por debajo del límite: precio de apertura
    }

    @Test
    fun limitadaDeVentaYLiquidacionDelEfectivo() {
        val e = Env()
        e.prices(ETF, flat("2026-11-09", "10"), flat("2026-11-10", "10"), bar("2026-11-11", "10", "10.5", "10", "10.2"),
            bar("2026-11-12", "10.1", "11.2", "10", "11"), flat("2026-11-13", "11"), flat("2026-11-16", "11"))
        e.service.placeBuy(e.id, ETF, m("100.00"))
        e.run("2026-11-10")
        e.at("2026-11-10T10:00:00Z")
        val s = e.service.placeSell(e.id, ETF, u("10"), OrderType.LIMIT, m("11.00"))
        assertEquals(u("10"), e.state.reservedUnits(ETF))
        assertEquals(u("0"), e.state.unitsAvailable(ETF, d("2026-11-10"))) // reservadas
        e.run("2026-11-11")
        assertEquals(OrderStatus.ACCEPTED, e.order(s).status) // máx 10,5 < 11
        e.run("2026-11-12")
        val o = e.order(s)
        assertEquals(m("11.00"), o.executionPrice) // máx 11,2 > 11: al límite
        assertEquals(d("2026-11-12"), o.executedOn)
        // vendido el 12: el efectivo está en la cuenta pero no disponible hasta la liquidación (T+2 = 16)
        assertEquals(m("110.00"), e.state.unsettledCash(d("2026-11-12")))
        assertEquals(m("9900.00"), e.state.availableCash(d("2026-11-12")))
        e.run("2026-11-16")
        assertEquals(OrderStatus.SETTLED, e.order(s).status)
        assertEquals(m("10010.00"), e.state.availableCash(d("2026-11-16")))
        assertEquals(emptyList(), e.audit())
    }

    @Test
    fun stopLossSeActivaAlRozarYSiAbreMuyPorDebajoSeEjecutaALaApertura() {
        val e = Env()
        e.prices(ETF, flat("2026-11-09", "10"), flat("2026-11-10", "10"), bar("2026-11-11", "9.8", "9.9", "9.00", "9.3"),
            flat("2026-11-12", "10"), bar("2026-11-13", "8.5", "8.6", "8.0", "8.2"))
        e.service.placeBuy(e.id, ETF, m("200.00"))
        e.run("2026-11-10")
        e.at("2026-11-10T10:00:00Z")
        val s1 = e.service.placeSell(e.id, ETF, u("10"), OrderType.STOP, m("9.00"))
        e.run("2026-11-11")
        assertEquals(m("9.00"), e.order(s1).executionPrice) // mínimo == activación: se dispara, al precio de activación
        e.at("2026-11-12T10:00:00Z")
        val s2 = e.service.placeSell(e.id, ETF, u("10"), OrderType.STOP, m("9.00"))
        e.run("2026-11-13")
        assertEquals(m("8.50"), e.order(s2).executionPrice) // gap: abre por debajo
    }

    @Test
    fun laOrdenCaducaYLiberaLaReserva() {
        val e = Env()
        e.prices(ETF, flat("2026-11-09", "10"), flat("2026-11-10", "10"), flat("2026-11-11", "10"), flat("2026-11-12", "10"), flat("2026-11-13", "10"))
        val id = e.service.placeBuy(e.id, ETF, m("100.00"), OrderType.LIMIT, m("5.00"), expiresOn = d("2026-11-11"))
        e.run("2026-11-13")
        assertEquals(OrderStatus.EXPIRED, e.order(id).status)
        assertEquals(d("2026-11-12"), e.order(id).closedOn) // válida hasta el 11 inclusive
        assertEquals(m("10000.00"), e.state.availableCash(d("2026-11-13")))
        e.at("2026-11-13T09:00:00Z")
        e.service.placeBuy(e.id, ETF, m("10.00"), expiresOn = d("2026-12-13")) // 30 días
        assertFailsWith<TradingException> { e.service.placeBuy(e.id, ETF, m("10.00"), expiresOn = d("2026-11-01")) }
    }

    @Test
    fun fondosSeValoranSegunLaHoraDeCorteYLasParticipacionesQuedanBloqueadasHastaLiquidar() {
        val e = Env()
        e.prices(FA, flat("2026-11-09", "10"), flat("2026-11-10", "11"), flat("2026-11-11", "12"), flat("2026-11-12", "13"), flat("2026-11-13", "14"))
        // lunes 10:00 (antes del corte de las 14:00): valor liquidativo del propio lunes
        val antes = e.service.placeBuy(e.id, FA, m("100.00"))
        // lunes 14:30: se valora el martes
        e.at("2026-11-09T13:30:00Z")
        val despues = e.service.placeBuy(e.id, FA, m("110.00"))
        e.run("2026-11-09")
        assertEquals(OrderStatus.EXECUTED, e.order(antes).status)
        assertEquals(m("10"), e.order(antes).executionPrice)
        assertEquals(OrderStatus.ACCEPTED, e.order(despues).status) // aún no hay valoración del martes
        e.run("2026-11-10")
        assertEquals(m("11"), e.order(despues).executionPrice)
        assertEquals(u("20"), e.state.unitsHeld(FA)) // 100 / 10 + 110 / 11
        // bloqueadas: el martes aún no se pueden vender las del lunes (liquidan el miércoles 11)
        e.at("2026-11-10T10:00:00Z")
        assertFailsWith<InsufficientUnitsException> { e.service.placeSell(e.id, FA, u("1")) }
        e.at("2026-11-11T10:00:00Z")
        e.run("2026-11-11")
        val venta = e.service.placeSell(e.id, FA, u("10"))
        e.run("2026-11-12")
        val o = e.order(venta)
        assertEquals(m("12"), o.executionPrice) // creada el miércoles antes del corte: valor liquidativo de ese día
        assertEquals(d("2026-11-11"), o.executedOn)
        assertEquals(d("2026-11-16"), o.settlesOn) // reembolso: 3 días hábiles (jue 12, vie 13, lun 16)
        assertEquals(m("120.00"), e.state.unsettledCash(d("2026-11-12")))
        assertEquals(emptyList(), e.audit())
    }

    @Test
    fun traspasoConservaCosteYFechasNoRealizaGananciaYNoMueveEfectivo() {
        val e = Env()
        val dias = listOf("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-16")
        e.prices(FA, *dias.map { flat(it, if (it == "2026-11-16") "11" else "10") }.toTypedArray())
        e.prices(FB, *dias.map { flat(it, if (it == "2026-11-16") "22" else "20") }.toTypedArray())
        e.service.placeBuy(e.id, FA, m("1000.00")) // lunes 9: 100 u
        e.run("2026-11-09")
        e.at("2026-11-10T09:00:00Z")
        e.service.placeBuy(e.id, FA, m("500.00")) // martes 10: 50 u
        e.run("2026-11-12")
        e.at("2026-11-16T09:00:00Z")
        val cashAntes = e.state.cash
        val t = e.service.placeTransfer(e.id, FA, FB, u("120"))
        e.run("2026-11-16")

        assertEquals(OrderStatus.EXECUTED, e.order(t).status)
        assertEquals(cashAntes, e.state.cash) // sin efectivo
        assertEquals(emptyList(), e.state.realizedSales) // sin ganancia
        assertEquals(u("30"), e.state.unitsHeld(FA))
        assertEquals(m("300.00"), e.state.position(FA)!!.costBasis)
        val lotesB = e.state.lotsByAsset.getValue(FB)
        assertEquals(listOf(d("2026-11-09"), d("2026-11-10")), lotesB.map { it.acquisitionDate }) // fechas de adquisición originales
        assertEquals(listOf(m("1000.00"), m("200.00")), lotesB.map { it.costOriginal })
        assertEquals(u("60"), e.state.unitsHeld(FB)) // 120 u * 11 = 1.320 € / 22
        assertEquals(m("1200.00"), e.state.position(FB)!!.costBasis)
        assertTrue(lotesB.all { it.settlesOn == d("2026-11-20") }) // bloqueadas 4 días hábiles
        assertEquals(emptyList(), e.audit())
    }

    @Test
    fun ordenesNoPermitidasSeRechazan() {
        val e = Env()
        assertFailsWith<TradingException> { e.service.placeTransfer(e.id, ETF, FB, u("1")) }
        assertFailsWith<TradingException> { e.service.placeTransfer(e.id, FA, BTC, u("1")) }
        assertFailsWith<TradingException> { e.service.placeBuy(e.id, FA, m("100.00"), OrderType.LIMIT, m("5")) }
        assertFailsWith<TradingException> { e.service.placeBuy(e.id, ETF, m("0.99")) }
        assertFailsWith<TradingException> { e.service.placeBuy(e.id, AssetId("nada"), m("10.00")) }
        assertFailsWith<InvalidEntryException> { e.service.placeBuy(e.id, ETF, m("10.005")) }
        assertFailsWith<InvalidEntryException> { e.service.placeSell(e.id, ETF, u("1"), OrderType.LIMIT, null) }
    }

    @Test
    fun siFaltaElDatoDeMercadoLasOrdenesEsperanYNuncaSeEjecutanConPreciosDeOtraFecha() {
        val e = Env()
        e.prices(ETF, flat("2026-11-09", "10"), bar("2026-11-10", "10", "10", "9.9", "10"))
        val id = e.service.placeBuy(e.id, ETF, m("100.00"), OrderType.LIMIT, m("9.50"))
        e.run("2026-11-13", "2026-11-13") // faltan los datos del 11, 12 y 13: se detiene
        assertEquals(OrderStatus.ACCEPTED, e.order(id).status)
        assertTrue(e.state.lastDate!! <= d("2026-11-11"))
        // llegan los datos: el 11 baja de 9,50 -> se ejecuta el 11 (no retroactivamente el 10)
        e.prices(ETF, flat("2026-11-09", "10"), bar("2026-11-10", "10", "10", "9.9", "10"), bar("2026-11-11", "10", "10", "9.40", "9.6"),
            flat("2026-11-12", "10"), flat("2026-11-13", "10"))
        e.run("2026-11-13")
        assertEquals(d("2026-11-11"), e.order(id).executedOn)
    }

    @Test
    fun unaFechaSinSesionPeroConDatosPosterioresSeSalta() {
        val e = Env()
        e.prices(ETF, flat("2026-11-09", "10"), flat("2026-11-10", "10"), bar("2026-11-12", "10", "10", "8.90", "9.2")) // falta el 11
        val id = e.service.placeBuy(e.id, ETF, m("100.00"), OrderType.LIMIT, m("9.00"))
        e.run("2026-11-12")
        // el hueco del 11 no bloquea porque hay datos posteriores: la orden se ejecuta el 12 (mínimo 8,90 < 9,00)
        assertEquals(d("2026-11-12"), e.order(id).executedOn)
        assertEquals(m("9.00"), e.order(id).executionPrice)
    }

    @Test
    fun bitcoinSeEjecutaEnLaSiguienteBarraDiariaYLiquidaAlInstante() {
        val e = Env()
        e.prices(BTC, flat("2026-11-09", "90000"), bar("2026-11-10", "91000", "92000", "90500", "91500"))
        val id = e.service.placeBuy(e.id, BTC, m("910.00"))
        e.run("2026-11-10")
        assertEquals(OrderStatus.SETTLED, e.order(id).status)
        assertEquals(m("91000"), e.order(id).executionPrice)
        assertEquals(u("0.01"), e.state.unitsHeld(BTC))
    }

    @Test
    fun comisionesSeReservanYFormanParteDelCoste() {
        val fees = FeeSchedule(mapOf((AssetKind.ETF to OrderSide.BUY) to FeeRule(fixed = m("1.00"), percent = BigDecimal("0.1"))))
        val e = Env(TradingConfig(fees = fees))
        e.prices(ETF, flat("2026-11-09", "10"), flat("2026-11-10", "10"))
        val id = e.service.placeBuy(e.id, ETF, m("1000.00"))
        assertEquals(m("1002.00"), e.state.reservedCash) // 1.000 + (1,00 + 0,1 %)
        e.run("2026-11-10")
        assertEquals(m("8998.00"), e.state.cash)
        assertEquals(m("1002.00"), e.state.position(ETF)!!.costBasis)
        assertEquals(m("2.00"), (e.acc.ledger.records.map { it.event }.filterIsInstance<Purchase>().single()).fees)
        assertEquals(OrderStatus.EXECUTED, e.order(id).status)
    }

    @Test
    fun siAlEjecutarYaNoHayEfectivoLaOrdenSeRechazaSinTocarNada() {
        val e = Env(opening = "150.00")
        e.prices(ETF, flat("2026-11-09", "10"), flat("2026-11-10", "10"))
        val id = e.service.placeBuy(e.id, ETF, m("100.00"))
        e.bank.post(e.id, CashAdjustment(d("2026-11-09"), m("-100.00"), "Pago de impuestos")) // quedan 50 < 100
        e.run("2026-11-10")
        assertEquals(OrderStatus.REJECTED, e.order(id).status)
        assertEquals(m("50.00"), e.state.cash)
        assertEquals(m("0"), e.state.reservedCash)
    }

    @Test
    fun planPeriodicoMensualCreaUnaOrdenPorPeriodoYNoSeDuplica() {
        val e = Env(opening = "1000.00")
        val dias = (9..30).map { "2026-11-%02d".format(it) } + (1..20).map { "2026-12-%02d".format(it) }
        e.prices(ETF, *dias.map { flat(it, "10") }.toTypedArray())
        e.bank.setSchedules(e.id, listOf(ScheduledPlan("p1", OrderSide.BUY, ETF, amount = m("200.00"), frequency = Frequency.MONTHLY, dayOfMonth = 15, startDate = d("2026-11-02"))))
        e.run("2026-12-19", "2026-12-20")
        val ordenes = e.state.orders.values.filter { it.spec.scheduleId == "p1" }
        assertEquals(listOf("plan-p1-2026-11", "plan-p1-2026-12"), ordenes.map { it.orderId })
        assertTrue(ordenes.all { it.status == OrderStatus.SETTLED || it.status == OrderStatus.EXECUTED })
        assertEquals(m("600.00"), e.state.cash) // 1.000 - 2 x 200
        val antes = e.acc.ledger.records.size
        e.run("2026-12-19", "2026-12-20") // repetir no cambia nada
        assertEquals(antes, e.acc.ledger.records.size)
    }

    @Test
    fun planSinEfectivoQuedaRegistradoComoRechazadoYNoSeReintenta() {
        val e = Env(opening = "100.00")
        e.prices(ETF, *(9..20).map { flat("2026-11-%02d".format(it), "10") }.toTypedArray())
        e.bank.setSchedules(e.id, listOf(ScheduledPlan("p1", OrderSide.BUY, ETF, amount = m("200.00"), frequency = Frequency.MONTHLY, dayOfMonth = 10, startDate = d("2026-11-02"))))
        e.run("2026-11-20")
        val o = e.order("plan-p1-2026-11")
        assertEquals(OrderStatus.REJECTED, o.status)
        assertEquals("Efectivo insuficiente", o.closeReason)
        e.run("2026-11-20")
        assertEquals(1, e.state.orders.size)
    }

    @Test
    fun ocurrenciasSemanalesYMensualesDeUnPlan() {
        val sem = ScheduledPlan("s", OrderSide.BUY, ETF, amount = m("10.00"), frequency = Frequency.WEEKLY, dayOfWeek = 1, startDate = d("2026-11-04"))
        assertEquals(listOf("2026-11-09", "2026-11-16", "2026-11-23"), sem.occurrences(d("2026-11-25")).map { it.first })
        val mes = ScheduledPlan("m", OrderSide.BUY, ETF, amount = m("10.00"), frequency = Frequency.MONTHLY, dayOfMonth = 31, startDate = d("2026-01-15"), endDate = d("2026-03-31"))
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), mes.occurrences(d("2026-12-31")).map { it.second.toString() })
    }

    @Test
    fun losPlazosDeLiquidacionTienenVigenciaYRespetanFestivos() {
        val r = SettlementRules()
        val xetra = CATALOG.getValue(ETF)
        assertEquals(d("2026-11-11"), r.settlementDate(xetra, OrderSide.BUY, d("2026-11-09"))) // T+2
        assertEquals(d("2027-10-12"), r.settlementDate(xetra, OrderSide.BUY, d("2027-10-11"))) // T+1 desde el 11-10-2027
        assertEquals(d("2027-10-08"), r.settlementDate(xetra, OrderSide.BUY, d("2027-10-06"))) // sigue T+2 antes
        assertEquals(d("2026-04-08"), r.settlementDate(xetra, OrderSide.BUY, d("2026-04-02"))) // Viernes Santo y Lunes de Pascua
        val nyse = xetra.copy(marketId = "NYSE")
        assertEquals(d("2026-11-10"), r.settlementDate(nyse, OrderSide.SELL, d("2026-11-09"))) // EE. UU.: T+1
        assertEquals(d("2026-11-09"), r.settlementDate(CATALOG.getValue(BTC), OrderSide.BUY, d("2026-11-09")))
        val fa = CATALOG.getValue(FA)
        assertEquals(d("2026-11-11"), r.settlementDate(fa, OrderSide.BUY, d("2026-11-09")))
        assertEquals(d("2026-11-12"), r.settlementDate(fa, OrderSide.SELL, d("2026-11-09")))
        assertEquals(d("2026-11-13"), r.settlementDate(fa, OrderSide.TRANSFER, d("2026-11-09")))
    }

    @Test
    fun losIngresosSeAbonanEnOrdenAntesDeEjecutarYLasCuentasArchivadasNoSeProcesan() {
        val e = Env(opening = "1000.00", income = "300.00")
        e.prices(ETF, *(9..13).map { flat("2026-11-%02d".format(it), "10") }.toTypedArray())
        e.service.placeBuy(e.id, ETF, m("100.00"))
        e.run("2026-11-13")
        val fechas = e.acc.ledger.records.map { it.event.date }
        assertEquals(fechas.sorted(), fechas)
        assertTrue(e.acc.ledger.records.any { it.event is MonthlyIncome })
        assertEquals(emptyList(), e.audit())
        e.bank.archive(e.id, d("2026-11-13"))
        assertEquals(emptyList(), e.service.process(e.id, d("2026-11-20"), d("2026-11-20")))
    }

    @Test
    fun lasOrdenesYPlanesSobrevivenAlGuardadoYALaCopiaDeSeguridad() {
        val store = InMemoryBankStore()
        val bank = Bank(store = store)
        val series = HashMap<AssetId, PriceSeries>()
        series[ETF] = PriceSeries.of((9..16).map { flat("2026-11-%02d".format(it), "10") })
        series[FA] = PriceSeries.of((9..16).map { flat("2026-11-%02d".format(it), "10") })
        series[FB] = PriceSeries.of((9..16).map { flat("2026-11-%02d".format(it), "20") })
        var now = Instant.parse("2026-11-09T09:00:00Z")
        var n = 0
        val svc = TradingService(bank, { CATALOG[it] }, { series[it] }, TradingConfig(), { now }, { "o${++n}" })
        val acc = bank.createAccount("Cuenta", m("5000.00"), m("0.00"), 1, d("2026-11-02"))
        bank.setSchedules(acc.id, listOf(ScheduledPlan("p1", OrderSide.BUY, ETF, amount = m("50.00"), frequency = Frequency.WEEKLY, dayOfWeek = 3, startDate = d("2026-11-09"))))
        svc.placeBuy(acc.id, FA, m("1000.00"))
        svc.placeBuy(acc.id, ETF, m("100.00"), OrderType.LIMIT, m("5.00"), expiresOn = d("2026-12-31"))
        svc.process(acc.id, d("2026-11-12"), d("2026-11-12"))
        now = Instant.parse("2026-11-12T09:00:00Z")
        svc.placeTransfer(acc.id, FA, FB, u("50"))
        svc.process(acc.id, d("2026-11-16"), d("2026-11-16"))

        assertEquals(emptyList(), LedgerAuditor.audit(acc.ledger.records))
        val restored = Bank.restore(store).get(acc.id)
        assertEquals(acc.state, restored.state)
        assertEquals(acc.config, restored.config)

        val copia = BackupService.parse(BackupService.export(bank, d("2026-11-17")))
        val otro = Bank()
        otro.importAccounts(copia)
        assertEquals(acc.state, otro.get(acc.id).state)
        assertEquals(acc.config.schedules, otro.get(acc.id).config.schedules)
    }
}
