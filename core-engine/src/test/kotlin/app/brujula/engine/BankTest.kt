package app.brujula.engine

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BankTest {

    private fun bank() = Bank()

    @Test
    fun crearCuentaRegistraSaldoInicial() {
        val b = bank()
        val a = b.createAccount("Principal", m("1000.00"), m("200.00"), 1, d("2026-03-15"))
        assertEquals(m("1000.00"), a.state.cash)
        assertEquals(m("1000.00"), a.state.contributions)
        assertEquals(1, a.ledger.records.size)
    }

    @Test
    fun alAbrirLaAppSeAbonanLosIngresosAtrasadosUnaSolaVez() {
        val b = bank()
        val a = b.createAccount("Principal", m("1000.00"), m("200.00"), 1, d("2026-03-15"))
        val abonados = b.creditDueIncomes(d("2026-06-10"))
        assertEquals(3, abonados.size) // abril, mayo y junio
        assertEquals(m("1600.00"), a.state.cash)
        // repetirlo (reabrir la app) no duplica nada
        assertEquals(0, b.creditDueIncomes(d("2026-06-10")).size)
        assertEquals(0, b.creditDueIncomes(d("2026-06-30")).size)
        assertEquals(m("1600.00"), a.state.cash)
        // llega julio
        assertEquals(1, b.creditDueIncomes(d("2026-07-01")).size)
        assertEquals(m("1800.00"), a.state.cash)
        assertEquals(emptyList(), LedgerAuditor.audit(a.ledger.records))
    }

    @Test
    fun lasCuentasSonCompletamenteIndependientes() {
        val b = bank()
        val a1 = b.createAccount("Cuenta A", m("1000.00"), m("100.00"), 1, d("2026-01-10"))
        val a2 = b.createAccount("Cuenta B", m("500.00"), m("50.00"), 20, d("2026-01-10"))

        b.post(a1.id, Purchase(d("2026-03-05"), ASSET_A, invested = m("300.00"), units = u("3")))
        b.post(a1.id, Sale(d("2026-03-06"), ASSET_A, units = u("1"), grossProceeds = m("120.00")))
        b.post(a1.id, CashAdjustment(d("2026-03-07"), m("-10.00"), "impuesto"))

        // B no ha cambiado: ni efectivo, ni cartera, ni historial, ni ventas, ni ingresos de A
        assertEquals(m("500.00"), a2.state.cash)
        assertEquals(emptyList(), a2.state.positions)
        assertEquals(emptyList(), a2.state.realizedSales)
        assertEquals(1, a2.ledger.records.size)

        b.post(a2.id, Purchase(d("2026-03-08"), ASSET_B, invested = m("100.00"), units = u("2")))
        assertEquals(u("2"), a1.state.unitsHeld(ASSET_A))
        assertEquals(u("0"), a1.state.unitsHeld(ASSET_B))
        assertEquals(u("0"), a2.state.unitsHeld(ASSET_A))

        // ingresos con importes y días distintos
        val ingresosA1 = a1.ledger.records.map { it.event }.filterIsInstance<MonthlyIncome>()
        val ingresosA2 = a2.ledger.records.map { it.event }.filterIsInstance<MonthlyIncome>()
        assertEquals(setOf(m("100.00")), ingresosA1.map { it.amount }.toSet())
        assertEquals(setOf(m("50.00")), ingresosA2.map { it.amount }.toSet())
        assertTrue(ingresosA2.all { it.date.dayOfMonth == 20 })

        assertEquals(emptyList(), LedgerAuditor.audit(a1.ledger.records))
        assertEquals(emptyList(), LedgerAuditor.audit(a2.ledger.records))
    }

    @Test
    fun noExisteNingunaTransferenciaAutomaticaAlAbonarIngresos() {
        val b = bank()
        val a1 = b.createAccount("A", m("0.00"), m("100.00"), 1, d("2026-01-01"))
        val a2 = b.createAccount("B", m("0.00"), m("0.00"), 1, d("2026-01-01"))
        b.creditDueIncomes(d("2026-04-01"))
        assertEquals(m("300.00"), a1.state.cash)
        assertEquals(m("0.00"), a2.state.cash)
    }

    @Test
    fun nombresVaciosOrepetidosSeRechazan() {
        val b = bank()
        b.createAccount("Principal", m("10.00"), m("0"), 1, d("2026-01-01"))
        assertFailsWith<InvalidAccountException> { b.createAccount("  ", m("10.00"), m("0"), 1, d("2026-01-01")) }
        assertFailsWith<InvalidAccountException> { b.createAccount("principal", m("10.00"), m("0"), 1, d("2026-01-01")) }
        val otra = b.createAccount("Otra", m("10.00"), m("0"), 1, d("2026-01-01"))
        assertFailsWith<InvalidAccountException> { b.rename(otra.id, "PRINCIPAL") }
        b.rename(otra.id, "Ahorro")
        assertEquals("Ahorro", otra.name)
    }

    @Test
    fun datosInvalidosNoCreanLaCuenta() {
        val b = bank()
        assertFailsWith<LedgerException> { b.createAccount("A", m("-1.00"), m("0"), 1, d("2026-01-01")) }
        assertFailsWith<InvalidAccountException> { b.createAccount("B", m("1.00"), m("-5.00"), 1, d("2026-01-01")) }
        assertFailsWith<InvalidAccountException> { b.createAccount("C", m("1.00"), m("5.00"), 40, d("2026-01-01")) }
        assertEquals(0, b.all().size)
    }

    @Test
    fun archivarBloqueaOperacionesYSeConservaYRestaura() {
        val b = bank()
        val a = b.createAccount("Principal", m("1000.00"), m("100.00"), 1, d("2026-01-10"))
        b.post(a.id, Purchase(d("2026-01-11"), ASSET_A, invested = m("100.00"), units = u("1")))
        b.archive(a.id, d("2026-03-10"))
        assertTrue(a.isArchived)
        assertEquals(0, b.active().size)
        assertEquals(1, b.all().size)
        assertFailsWith<AccountArchivedException> {
            b.post(a.id, Purchase(d("2026-03-11"), ASSET_A, invested = m("10.00"), units = u("1")))
        }
        // mientras está archivada no recibe ingresos
        assertEquals(0, b.creditDueIncomes(d("2026-05-20")).size)
        b.restore(a.id, d("2026-05-20"))
        assertFalse(a.isArchived)
        b.creditDueIncomes(d("2026-06-01"))
        val periodos = a.state.creditedPeriods.map { it.toString() }.sorted()
        // feb y mar (hasta el archivado); abr y may se saltan; jun ya vuelve a abonarse
        assertEquals(listOf("2026-02", "2026-03", "2026-06"), periodos)
        assertEquals(u("1"), a.state.unitsHeld(ASSET_A))
    }

    @Test
    fun soloSePuedenEliminarDefinitivamenteLasCuentasArchivadas() {
        val b = bank()
        val a = b.createAccount("Principal", m("10.00"), m("0"), 1, d("2026-01-01"))
        assertFailsWith<InvalidAccountException> { b.deletePermanently(a.id) }
        b.archive(a.id, d("2026-01-02"))
        b.deletePermanently(a.id)
        assertFailsWith<NoSuchAccountException> { b.get(a.id) }
        assertEquals(0, b.all().size)
    }

    @Test
    fun cambiarElIngresoSoloAfectaAPeriodosFuturos() {
        val b = bank()
        val a = b.createAccount("Principal", m("0.00"), m("100.00"), 1, d("2026-03-15"))
        b.creditDueIncomes(d("2026-06-10")) // abr, may, jun a 100
        b.changeIncome(a.id, d("2026-06-10"), m("300.00"), 5)
        b.creditDueIncomes(d("2026-07-05"))
        val ingresos = a.ledger.records.map { it.event }.filterIsInstance<MonthlyIncome>()
        assertEquals(listOf(100, 100, 100, 300).map { m("$it.00") }, ingresos.map { it.amount })
        assertEquals(d("2026-07-05"), ingresos.last().date)
        assertEquals(m("600.00"), a.state.cash)
    }

    @Test
    fun cambiarElIngresoDelMesEnCursoNoAbonadoSeAplicaEseMes() {
        val b = bank()
        val a = b.createAccount("Principal", m("0.00"), m("100.00"), 20, d("2026-03-01"))
        // el 10 de marzo todavía no ha vencido el ingreso del 20
        b.changeIncome(a.id, d("2026-03-10"), m("250.00"), 20)
        b.creditDueIncomes(d("2026-03-20"))
        assertEquals(m("250.00"), a.state.cash)
    }

    @Test
    fun noSePuedenReescribirPeriodosYaAbonados() {
        val b = bank()
        val a = b.createAccount("Principal", m("0.00"), m("100.00"), 1, d("2026-03-15"))
        b.creditDueIncomes(d("2026-06-10"))
        assertFailsWith<InvalidAccountException> {
            b.changeIncome(a.id, d("2026-06-10"), m("999.00"), 1, fromPeriod = YearMonth.of(2026, 4))
        }
        assertEquals(m("300.00"), a.state.cash)
    }

    @Test
    fun postAbonaPrimeroLosIngresosVencidosYMantieneElOrden() {
        val b = bank()
        val a = b.createAccount("Principal", m("1000.00"), m("100.00"), 1, d("2026-03-15"))
        b.post(a.id, Purchase(d("2026-06-10"), ASSET_A, invested = m("50.00"), units = u("1")))
        val tipos = a.ledger.records.map { it.event::class.simpleName }
        assertEquals(listOf("OpeningBalance", "MonthlyIncome", "MonthlyIncome", "MonthlyIncome", "Purchase"), tipos)
        val fechas = a.ledger.records.map { it.event.date }
        assertEquals(fechas.sorted(), fechas)
        assertEquals(m("1250.00"), a.state.cash)
    }

    @Test
    fun siYaHayMovimientosPosterioresElIngresoSeAbonaConLaFechaDelUltimoMovimiento() {
        val b = bank()
        val a = b.createAccount("Principal", m("1000.00"), m("100.00"), 1, d("2026-03-15"))
        a.ledger.post(CashAdjustment(d("2026-06-20"), m("-5.00"), "ajuste directo")) // salta Bank.post
        b.creditDueIncomes(d("2026-06-25"))
        val ingresos = a.ledger.records.map { it.event }.filterIsInstance<MonthlyIncome>()
        assertEquals(3, ingresos.size)
        assertTrue(ingresos.all { it.date == d("2026-06-20") })
        assertEquals(emptyList(), LedgerAuditor.audit(a.ledger.records))
    }
}
