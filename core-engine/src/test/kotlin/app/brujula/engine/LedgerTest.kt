package app.brujula.engine

import app.brujula.model.AccountId
import app.brujula.model.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerTest {

    private fun ledger(opening: String = "1000.00"): Ledger =
        Ledger(AccountId("t")).also { it.post(OpeningBalance(d("2026-01-01"), m(opening))) }

    @Test
    fun compraConDecimalesYPrecioMedio() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("25.50"), units = u("0.255")))
        val s = l.state
        assertEquals(m("974.50"), s.cash)
        val p = s.position(ASSET_A)!!
        assertEquals(u("0.255"), p.units)
        assertEquals(m("25.50"), p.costBasis)
        assertEquals(m("100"), p.averageCost)
    }

    @Test
    fun lasComisionesDeCompraFormanParteDelCoste() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("100.00"), units = u("2"), fees = m("1.50")))
        assertEquals(m("898.50"), l.state.cash)
        assertEquals(m("101.50"), l.state.position(ASSET_A)!!.costBasis)
    }

    @Test
    fun ventaParcialCalculaGananciaYEfectivoNeto() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("25.50"), units = u("0.255")))
        l.post(Sale(d("2026-02-01"), ASSET_A, units = u("0.1"), grossProceeds = m("11.00"), fees = m("0.50")))
        val s = l.state
        assertEquals(m("985.00"), s.cash) // 974,50 + (11,00 - 0,50)
        val sale = s.realizedSales.single()
        assertEquals(m("10.50"), sale.netProceeds)
        assertEquals(m("10.00"), sale.cost) // 25,50 * 0,1 / 0,255
        assertEquals(m("0.50"), sale.gain)
        assertEquals(u("0.155"), s.unitsHeld(ASSET_A))
        assertEquals(m("15.50"), s.position(ASSET_A)!!.costBasis)
    }

    @Test
    fun ventaTotalCierraLaPosicion() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("50.00"), units = u("5")))
        l.post(Sale(d("2026-01-03"), ASSET_A, units = u("5"), grossProceeds = m("45.00")))
        assertNull(l.state.position(ASSET_A))
        assertEquals(m("-5.00"), l.state.realizedGain)
        assertEquals(m("995.00"), l.state.cash)
    }

    @Test
    fun elPrimerMovimientoDebeSerElSaldoInicialYSoloUno() {
        val l = Ledger(AccountId("t"))
        assertFailsWith<OpeningBalanceException> {
            l.post(MonthlyIncome(d("2026-01-01"), java.time.YearMonth.of(2026, 1), m("10.00")))
        }
        l.post(OpeningBalance(d("2026-01-01"), m("10.00")))
        assertFailsWith<OpeningBalanceException> { l.post(OpeningBalance(d("2026-01-02"), m("5.00"))) }
    }

    @Test
    fun efectivoInsuficienteNoModificaNada() {
        val l = ledger("100.00")
        val antes = l.state
        val registros = l.records.size
        assertFailsWith<InsufficientCashException> {
            l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("100.01"), units = u("1")))
        }
        assertEquals(antes, l.state)
        assertEquals(registros, l.records.size)
    }

    @Test
    fun unidadesInsuficientesSeRechazan() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("10.00"), units = u("1")))
        assertFailsWith<InsufficientUnitsException> {
            l.post(Sale(d("2026-01-03"), ASSET_A, units = u("1.000001"), grossProceeds = m("10.00")))
        }
        assertFailsWith<InsufficientUnitsException> {
            l.post(Sale(d("2026-01-03"), ASSET_B, units = u("1"), grossProceeds = m("10.00")))
        }
        assertEquals(u("1"), l.state.unitsHeld(ASSET_A))
    }

    @Test
    fun importesFueraDeCentimosSeRechazan() {
        assertFailsWith<InvalidEntryException> { Purchase(d("2026-01-02"), ASSET_A, invested = m("10.005"), units = u("1")) }
        assertFailsWith<InvalidEntryException> { Purchase(d("2026-01-02"), ASSET_A, invested = m("10.00"), units = u("1"), fees = m("0.001")) }
        assertFailsWith<InvalidEntryException> { Sale(d("2026-01-02"), ASSET_A, units = u("1"), grossProceeds = m("1.234")) }
        assertFailsWith<InvalidEntryException> { OpeningBalance(d("2026-01-02"), m("1.001")) }
        assertFailsWith<InvalidEntryException> { CashAdjustment(d("2026-01-02"), m("-1.001"), "impuesto") }
    }

    @Test
    fun valoresInvalidosSeRechazan() {
        assertFailsWith<InvalidEntryException> { Purchase(d("2026-01-02"), ASSET_A, invested = m("0"), units = u("1")) }
        assertFailsWith<InvalidEntryException> { Purchase(d("2026-01-02"), ASSET_A, invested = m("1"), units = u("0")) }
        assertFailsWith<InvalidEntryException> { Purchase(d("2026-01-02"), ASSET_A, invested = m("1"), units = u("1"), fees = m("-0.01")) }
        assertFailsWith<InvalidEntryException> { Sale(d("2026-01-02"), ASSET_A, units = u("1"), grossProceeds = m("1.00"), fees = m("1.01")) }
        assertFailsWith<InvalidEntryException> { OpeningBalance(d("2026-01-02"), m("-1.00")) }
        assertFailsWith<InvalidEntryException> { CashAdjustment(d("2026-01-02"), m("0"), "x") }
        assertFailsWith<InvalidEntryException> { CashAdjustment(d("2026-01-02"), m("-5.00"), " ") }
        assertFailsWith<InvalidEntryException> { MonthlyIncome(d("2026-01-02"), java.time.YearMonth.of(2026, 1), m("0")) }
    }

    @Test
    fun noSePermitenMovimientosAnterioresAlUltimo() {
        val l = ledger()
        l.post(Purchase(d("2026-03-10"), ASSET_A, invested = m("10.00"), units = u("1")))
        assertFailsWith<OutOfOrderEntryException> {
            l.post(Purchase(d("2026-03-09"), ASSET_A, invested = m("10.00"), units = u("1")))
        }
        // misma fecha sí
        l.post(Purchase(d("2026-03-10"), ASSET_A, invested = m("10.00"), units = u("1")))
        assertEquals(3, l.records.size)
    }

    @Test
    fun ingresoMensualNoSePuedeAbonarDosVeces() {
        val l = ledger()
        val p = java.time.YearMonth.of(2026, 2)
        l.post(MonthlyIncome(d("2026-02-01"), p, m("200.00")))
        assertFailsWith<DuplicateIncomePeriodException> { l.post(MonthlyIncome(d("2026-02-02"), p, m("200.00"))) }
        assertEquals(m("1200.00"), l.state.cash)
    }

    @Test
    fun losImpuestosPuedenDejarElEfectivoEnNegativoYEntoncesNoSePuedeComprar() {
        val l = ledger("100.00")
        l.post(CashAdjustment(d("2026-06-30"), m("-150.00"), "Pago IRPF"))
        assertEquals(m("-50.00"), l.state.cash)
        assertTrue(l.state.cash.isNegative)
        assertFailsWith<InsufficientCashException> {
            l.post(Purchase(d("2026-07-01"), ASSET_A, invested = m("1.00"), units = u("1")))
        }
        l.post(MonthlyIncome(d("2026-07-01"), java.time.YearMonth.of(2026, 7), m("200.00")))
        l.post(Purchase(d("2026-07-02"), ASSET_A, invested = m("100.00"), units = u("1")))
        assertEquals(m("50.00"), l.state.cash)
    }

    @Test
    fun patrimonioUsaPreciosRealesYNuncaInventaUnoQueFalta() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("100.00"), units = u("2.5")))
        val precios = mapOf(ASSET_A to m("41.234567"))
        // 2,5 * 41,234567 = 103,0864175 -> 103,09
        assertEquals(m("103.09"), l.state.portfolioValue(precios))
        assertEquals(m("1003.09"), l.state.netWorth(precios))
        assertFailsWith<MissingPriceException> { l.state.netWorth(emptyMap<AssetId, app.brujula.model.Money>()) }
    }

    @Test
    fun reproducirLosEventosDaExactamenteElMismoEstado() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("25.50"), units = u("0.255")))
        l.post(Purchase(d("2026-01-05"), ASSET_B, invested = m("300.00"), units = u("0.6"), fees = m("1.00")))
        l.post(Sale(d("2026-02-01"), ASSET_A, units = u("0.1"), grossProceeds = m("11.00"), fees = m("0.50")))
        l.post(CashAdjustment(d("2026-02-02"), m("-3.21"), "ajuste"))
        val copia = Ledger.replay(AccountId("t"), l.records.map { it.event })
        assertEquals(l.state, copia.state)
        assertEquals(l.records, copia.records)
    }

    @Test
    fun cadaMovimientoRecibeSecuenciaConsecutiva() {
        val l = ledger()
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("10.00"), units = u("1")))
        assertEquals(listOf(1L, 2L), l.records.map { it.seq })
        assertEquals(2L, l.state.lotsByAsset.getValue(ASSET_A).single().acquisitionSeq)
    }
}
