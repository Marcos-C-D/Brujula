package app.brujula.app

import app.brujula.engine.*
import app.brujula.market.*
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import app.brujula.tax.PaymentMode
import app.brujula.tax.TaxSettings
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun d(s: String) = LocalDate.parse(s)
private fun m(s: String) = Money.of(s)
private fun u(s: String) = Units.of(s)
private val ETF = AssetId("etf")
private val CATALOG = mapOf(ETF to AssetDefinition(ETF, "ETF Mundo", "ETF", AssetKind.ETF, "x", emptyList(), "XETRA"))

class OrdersAndTaxViewsTest {

    private fun ledger(vararg e: LedgerEvent): Ledger = Ledger(AccountId("t")).also { l -> e.forEach { l.post(it) } }

    @Test
    fun ordenesConEstadoYCronologia() {
        val spec = OrderSpec(OrderSide.BUY, OrderType.MARKET, ETF, amount = m("100.00"))
        val limit = OrderSpec(OrderSide.BUY, OrderType.LIMIT, ETF, amount = m("50.00"), limitPrice = m("9.5"), expiresOn = d("2026-12-31"))
        val l = ledger(
            OpeningBalance(d("2026-11-02"), m("1000.00")),
            OrderPlaced(d("2026-11-03"), "o1", spec),
            OrderPlaced(d("2026-11-03"), "o2", limit),
        )
        var rows = OrderViews.rows(l.state, { CATALOG[it] })
        assertEquals(listOf("o2", "o1"), rows.map { it.orderId }) // la más reciente primero
        assertEquals("Compra · ETF Mundo", rows[1].title)
        assertEquals("100,00 € · a mercado", rows[1].detail)
        assertEquals("Pendiente", rows[1].statusLabel)
        assertTrue(rows[1].canCancel)
        assertTrue(rows[0].detail.contains("limitada a 9,50 €") && rows[0].detail.contains("caduca el 31/12/2026"))

        l.post(Purchase(d("2026-11-04"), ETF, m("100.00"), u("10"), orderId = "o1", price = m("10"), settlementDate = d("2026-11-06")))
        l.post(OrderClosed(d("2026-11-04"), "o2", OrderStatus.CANCELLED, "Cancelada por el usuario"))
        rows = OrderViews.rows(l.state, { CATALOG[it] })
        val o1 = rows.first { it.orderId == "o1" }
        assertEquals("Ejecutada (pendiente de liquidar)", o1.statusLabel)
        assertFalse(o1.canCancel)
        assertEquals("Cancelada", rows.first { it.orderId == "o2" }.statusLabel)

        l.post(OrderSettled(d("2026-11-06"), "o1"))
        val pasos = OrderViews.rows(l.state, { CATALOG[it] }).first { it.orderId == "o1" }.timeline.map { it.label.substringBefore(" a ") }
        assertEquals(listOf("Orden solicitada", "Orden aceptada", "Orden ejecutada", "Operación liquidada"), pasos)
    }

    @Test
    fun historialDeMovimientosConSaldoTrasCadaUno() {
        val l = ledger(
            OpeningBalance(d("2026-11-02"), m("1000.00")),
            Purchase(d("2026-11-03"), ETF, m("100.00"), u("10"), m("1.00")),
            CashAdjustment(d("2026-11-04"), m("-20.00"), "Pago de impuestos"),
        )
        val mv = OrderViews.movements(l.records, { CATALOG[it] })
        assertEquals(listOf("Ajuste", "Compra", "Apertura"), mv.map { it.kind }) // más reciente primero
        assertEquals(listOf(m("879.00"), m("899.00"), m("1000.00")), mv.map { it.balance })
        assertEquals(m("-101.00"), mv[1].cashEffect)
        assertTrue(mv[1].description.contains("ETF Mundo") && mv[1].description.contains("comisión"))
    }

    private fun conGanancia(): List<LedgerRecord> = ledger(
        OpeningBalance(d("2026-11-02"), m("10000.00")),
        Purchase(d("2026-11-03"), ETF, m("1000.00"), u("100")),
        Sale(d("2026-11-10"), ETF, u("100"), m("1500.00")),
    ).records

    @Test
    fun resumenFiscalConCalendarioYAvisos() {
        val o = TaxViews.overview(conGanancia(), d("2026-11-12"), TaxSettings(), { CATALOG[it] }, mapOf(AssetKind.CRYPTO to m("60000")))
        val y = o.years.first()
        assertEquals(2026, y.result.year)
        assertTrue(y.provisional) // el ejercicio aún no ha terminado
        assertEquals(m("95.00"), y.result.result)
        val pago = o.upcoming.single()
        assertEquals(d("2027-06-30"), pago.date)
        assertTrue(pago.label.contains("95,00 €") && pago.estimated)
        assertEquals(1, o.notices.size) // 721

        val despues = TaxViews.overview(conGanancia(), d("2027-07-01"), TaxSettings(), { CATALOG[it] })
        assertFalse(despues.years.first { it.result.year == 2026 }.provisional)
        assertTrue(despues.upcoming.isEmpty()) // ya pasó la fecha
    }

    @Test
    fun pagoFraccionadoMuestraAmbosPlazos() {
        val o = TaxViews.overview(conGanancia(), d("2026-11-12"), TaxSettings(payment = PaymentMode.SPLIT_60_40), { CATALOG[it] })
        assertEquals(listOf(d("2027-06-30"), d("2027-11-05")), o.upcoming.map { it.date })
        assertTrue(o.upcoming[0].label.contains("57,00 €") && o.upcoming[1].label.contains("38,00 €"))
    }

    @Test
    fun sinMovimientosNoHayResumen() {
        val o = TaxViews.overview(emptyList(), d("2026-11-12"), TaxSettings(), { CATALOG[it] })
        assertTrue(o.years.isEmpty() && o.upcoming.isEmpty())
    }

    // ---------------- opciones fiscales por cuenta ----------------

    @Test
    fun opcionesFiscalesViajanComoTexto() {
        val s = TaxSettings(
            otherSavingsBase = mapOf(2026 to m("5800")), payment = PaymentMode.SPLIT_60_40, refundMonthsAfterCampaign = 3,
            withholdOnFundRedemptions = false, withholdOnDividends = false, applyRepurchaseToCrypto = true,
        )
        val back = TaxSettings.fromOptions(s.toOptions())
        assertEquals(s, back)
        assertEquals(TaxSettings(), TaxSettings.fromOptions(emptyMap()))
        // valores inválidos se ignoran y quedan los de por defecto
        val raros = TaxSettings.fromOptions(mapOf("payment" to "X", "refundMonths" to "99", "withholdFunds" to "quizá", "otherBase.2026" to "abc", "otherBase.2027" to "-5"))
        assertEquals(TaxSettings(), raros)
    }

    @Test
    fun opcionesFiscalesSeGuardanYSobrevivenAlReinicioYALaCopia() {
        val store = InMemoryBankStore()
        val bank = Bank(store = store)
        val acc = bank.createAccount("Cuenta", m("100.00"), m("0.00"), 1, d("2026-11-02"))
        val options = TaxSettings(payment = PaymentMode.SPLIT_60_40, otherSavingsBase = mapOf(2026 to m("1000"))).toOptions()
        bank.setTaxOptions(acc.id, options)
        assertEquals(options, Bank.restore(store).get(acc.id).taxOptions)
        val otro = Bank()
        otro.importAccounts(BackupService.parse(BackupService.export(bank, d("2026-11-12"))))
        assertEquals(options, otro.get(acc.id).taxOptions)
        // cuentas independientes: otra cuenta conserva sus opciones por defecto
        val b = bank.createAccount("Otra", m("10.00"), m("0.00"), 1, d("2026-11-02"))
        assertEquals(emptyMap(), b.taxOptions)
    }
}
