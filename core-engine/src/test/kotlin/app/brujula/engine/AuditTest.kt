package app.brujula.engine

import app.brujula.model.AccountId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditTest {

    @Test
    fun unLibroSanoNoTieneProblemas() {
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-01-01"), m("1000.00")))
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("100.00"), units = u("3"), fees = m("0.99")))
        l.post(Sale(d("2026-01-03"), ASSET_A, units = u("1"), grossProceeds = m("35.00"), fees = m("0.10")))
        assertEquals(emptyList(), LedgerAuditor.audit(l.records))
    }

    @Test
    fun detectaSecuenciasManipuladas() {
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-01-01"), m("1000.00")))
        l.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("100.00"), units = u("3")))
        val manipulados = l.records.map { it.copy(seq = it.seq + 1) }
        assertTrue(LedgerAuditor.audit(manipulados).any { it.contains("Secuencia incorrecta") })
    }

    @Test
    fun detectaLibrosNoReproducibles() {
        val records = listOf(
            LedgerRecord(1, OpeningBalance(d("2026-01-01"), m("10.00"))),
            LedgerRecord(2, Purchase(d("2026-01-02"), ASSET_A, invested = m("50.00"), units = u("1"))),
        )
        assertTrue(LedgerAuditor.audit(records).any { it.contains("no se puede reproducir") })
    }

    /**
     * Prueba de propiedades: miles de operaciones aleatorias (compras, ventas parciales y totales,
     * ingresos, impuestos) y, al final y cada cierto tiempo, el efectivo, las unidades, los costes y
     * la identidad global cuadran al céntimo, y reproducir el libro da el mismo estado.
     */
    @Test
    fun milesDeOperacionesAleatoriasSiempreCuadranAlCentimo() {
        val rnd = Random(20261107L)
        val assets = listOf(ASSET_A, ASSET_B, app.brujula.model.AssetId("bitcoin"))
        val prices = assets.associateWith { BigDecimal(5 + rnd.nextInt(60000)).divide(BigDecimal(100), 4, RoundingMode.HALF_UP) }

        val l = Ledger(AccountId("aleatorio"))
        var date = d("2026-01-01")
        l.post(OpeningBalance(date, m("5000.00")))
        var period = YearMonth.of(2026, 1)
        var compras = 0
        var ventas = 0
        var rechazadas = 0

        for (step in 1..3000) {
            date = date.plusDays(rnd.nextInt(3).toLong())
            val asset = assets[rnd.nextInt(assets.size)]
            val price = prices.getValue(asset)
            try {
                when (rnd.nextInt(10)) {
                    in 0..4 -> {
                        val invested = Money.of(BigDecimal(100 + rnd.nextInt(30000)).divide(BigDecimal(100)))
                        val fees = if (rnd.nextBoolean()) Money.ZERO else Money.of(BigDecimal(rnd.nextInt(200)).divide(BigDecimal(100)))
                        val units = invested.amount.divide(price, 6, RoundingMode.DOWN)
                        if (units.signum() > 0) {
                            l.post(Purchase(date, asset, invested, Units.of(units), fees))
                            compras++
                        }
                    }
                    in 5..7 -> {
                        val held = l.state.unitsHeld(asset)
                        if (held.isPositive) {
                            val units = if (rnd.nextInt(5) == 0) held else
                                Units.of(held.value.multiply(BigDecimal(1 + rnd.nextInt(99)).divide(BigDecimal(100))).setScale(6, RoundingMode.DOWN))
                            if (units.isPositive) {
                                val gross = (Money.of(price) * units).roundedToCents()
                                val fees = if (gross > m("5.00") && rnd.nextBoolean()) m("0.75") else Money.ZERO
                                l.post(Sale(date, asset, units, gross, fees))
                                ventas++
                            }
                        }
                    }
                    8 -> {
                        l.post(MonthlyIncome(date, period, m("150.00")))
                        period = period.plusMonths(1)
                    }
                    else -> l.post(CashAdjustment(date, Money.of(BigDecimal(-rnd.nextInt(40000)).divide(BigDecimal(100))).let { if (it.isZero) m("-0.01") else it }, "impuesto"))
                }
            } catch (e: InsufficientCashException) {
                rechazadas++
            }
            if (step % 250 == 0) assertEquals(emptyList(), LedgerAuditor.audit(l.records), "Auditoría en el paso $step")
        }

        assertTrue(compras > 200, "compras=$compras")
        assertTrue(ventas > 200, "ventas=$ventas")
        assertEquals(emptyList(), LedgerAuditor.audit(l.records))
        val copia = Ledger.replay(AccountId("aleatorio"), l.records.map { it.event })
        assertEquals(l.state, copia.state)
    }
}
