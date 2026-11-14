package app.brujula.app

import app.brujula.engine.*
import app.brujula.market.*
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun d(s: String) = LocalDate.parse(s)
private fun m(s: String) = Money.of(s)
private val ETF = AssetId("etf")
private val DEF = AssetDefinition(ETF, "ETF Mundo", "ETF", AssetKind.ETF, "x", emptyList(), "XETRA")

class TimeMachineAndNotificationsTest {

    @Test
    fun elAvanceDeTiempoTrabajaSobreUnaCopiaYNuncaTocaLosDatosReales() {
        val store = InMemoryBankStore()
        val bank = Bank(store = store)
        val acc = bank.createAccount("Cuenta", m("5000.00"), m("100.00"), 1, d("2026-11-02"))
        bank.post(acc.id, Purchase(d("2026-11-03"), ETF, m("1000.00"), Units.of("100")))
        bank.post(acc.id, Sale(d("2026-11-10"), ETF, Units.of("100"), m("1500.00")))
        val realRecords = acc.ledger.records.size
        val realCash = acc.state.cash

        val tm = TimeMachine(bank.snapshot(), d("2026-11-12"), { DEF }, { null }, { emptyList() }, { listOf(ETF) })
        val report = tm.advance(231) // hasta el 1-jul-2027: ingresos mensuales y IRPF 2026 del 30 de junio
        val sandbox = tm.bank.get(acc.id)

        assertEquals(d("2027-07-01"), tm.today)
        assertEquals(m("-95.00"), sandbox.state.taxPostings.single().amount) // 19 % de 500
        assertTrue(sandbox.state.cash > realCash.let { it - m("95.00") }) // se abonaron los ingresos y se pagó el impuesto
        assertTrue(report.any { it.contains("IRPF 2026") })
        // lo real, intacto: ni memoria ni almacén
        assertEquals(realRecords, acc.ledger.records.size)
        assertEquals(realCash, acc.state.cash)
        assertEquals(realRecords, store.loadAccounts().single().events.size)
        assertEquals(0, acc.state.taxPostings.size)
    }

    @Test
    fun avisosDeMovimientosNuevosYResumenSiSonMuchos() {
        val l = Ledger(app.brujula.model.AccountId("t"))
        l.post(OpeningBalance(d("2026-11-02"), m("1000.00")))
        l.post(OrderPlaced(d("2026-11-03"), "o1", OrderSpec(OrderSide.BUY, OrderType.MARKET, ETF, amount = m("100.00"))))
        l.post(Purchase(d("2026-11-04"), ETF, m("100.00"), Units.of("10"), orderId = "o1", settlementDate = d("2026-11-06")))
        l.post(OrderPlaced(d("2026-11-04"), "o2", OrderSpec(OrderSide.BUY, OrderType.LIMIT, ETF, amount = m("50.00"), limitPrice = m("5"))))
        l.post(OrderClosed(d("2026-11-04"), "o2", OrderStatus.CANCELLED, "Cancelada por el usuario"))
        l.post(MonthlyIncome(d("2026-12-01"), java.time.YearMonth.of(2026, 12), m("200.00")))
        val nuevos = l.records.drop(2)
        val textos = Notifications.fromRecords("Principal", nuevos, { DEF })
        assertEquals(2, textos.size) // compra ejecutada e ingreso; la orden colocada y la cancelada por el usuario no avisan
        assertTrue(textos[0].text.contains("Compra ejecutada") && textos[1].text.contains("Ingreso mensual de 200,00 €"))
        assertEquals(1, Notifications.fromRecords("Principal", nuevos + nuevos + nuevos, { DEF }).size) // 6 avisos -> un resumen
    }

    @Test
    fun avisoDeImpuestosSoloSiFaltanSieteDiasOMenos() {
        val u = listOf(UpcomingTax(d("2027-06-30"), "IRPF 2026: pago estimado de 95,00 €", true))
        assertEquals(0, Notifications.upcomingTax("Principal", u, d("2027-06-01")).size)
        val aviso = Notifications.upcomingTax("Principal", u, d("2027-06-25")).single()
        assertTrue(aviso.text.contains("30/06/2027") && aviso.text.contains("95,00 €"))
        assertEquals(0, Notifications.upcomingTax("Principal", u, d("2027-07-01")).size)
    }
}
