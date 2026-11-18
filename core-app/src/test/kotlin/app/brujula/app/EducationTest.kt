package app.brujula.app

import app.brujula.engine.*
import app.brujula.market.AssetCatalog
import app.brujula.market.AssetDefinition
import app.brujula.market.AssetKind
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import app.brujula.tax.TaxCalculator
import app.brujula.tax.TaxRules
import app.brujula.tax.TaxSettings
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun d(s: String) = LocalDate.parse(s)
private fun m(s: String) = Money.of(s)
private val ETF = AssetId("etf")
private val DEF = AssetDefinition(ETF, "ETF", "ETF", AssetKind.ETF, "x", emptyList(), "XETRA")

class EducationTest {

    @Test
    fun todosLosTemasTienenContenidoYSonUnicos() {
        assertEquals(Education.topics.size, Education.topics.map { it.id }.toSet().size)
        assertTrue(Education.topics.all { it.title.isNotBlank() && it.summary.isNotBlank() && it.sections.isNotEmpty() && it.sections.all { s -> s.paragraphs.isNotEmpty() } })
    }

    @Test
    fun losEjemplosNumericosDelTextoCoincidenConElMotor() {
        // FIFO: dos compras (1.000 € y 1.200 €), venta de 15 u. por 1.950 € -> coste 1.600 €, ganancia 350 €, cuota 66,50 €
        val l = Ledger(AccountId("t"))
        l.post(OpeningBalance(d("2026-01-02"), m("5000.00")))
        l.post(Purchase(d("2026-02-01"), ETF, m("1000.00"), Units.of("10")))
        l.post(Purchase(d("2026-04-01"), ETF, m("1200.00"), Units.of("10")))
        assertEquals(m("110"), l.state.position(ETF)!!.averageCost) // precio medio 110 €
        l.post(Sale(d("2026-06-01"), ETF, Units.of("15"), m("1950.00")))
        assertEquals(m("350.00"), l.state.realizedGain)
        val r = TaxCalculator(TaxRules(), TaxSettings()) { DEF }.compute(l.records, 2026).last()
        assertEquals(m("66.50"), r.grossTax)

        // escala: 500 € -> 95 €; 6.000 € -> 1.140 €
        assertEquals(m("95.00"), TaxRules().tax(2026, m("500")))
        assertEquals(m("1140.00"), TaxRules().tax(2026, m("6000")))

        // compensación: pérdida de 2.000 € y dividendos de 1.000 € -> compensa 250 €, base 750 €, cuota 142,50 €
        val c = Ledger(AccountId("c"))
        c.post(OpeningBalance(d("2026-01-02"), m("20000.00")))
        c.post(Purchase(d("2026-02-01"), ETF, m("5000.00"), Units.of("500")))
        c.post(DividendReceived(d("2026-03-01"), ETF, m("1000.00")))
        c.post(Sale(d("2026-06-01"), ETF, Units.of("500"), m("3000.00")))
        val y = TaxCalculator(TaxRules(), TaxSettings()) { DEF }.compute(c.records, 2026).last()
        assertEquals(m("250.00"), y.crossCompensation)
        assertEquals(m("750.00"), y.savingsBase)
        assertEquals(m("142.50"), y.grossTax)
        assertEquals(m("1750.00"), y.carryForward.single().remaining)

        // rentabilidad ponderada por tiempo: +10 % y luego (1.650 - 500)/1.100 -> +15 % en total
        val pts = listOf(
            NetWorthPoint(d("2026-01-01"), m("1000"), m("0"), m("1000"), m("1000")),
            NetWorthPoint(d("2026-02-01"), m("1000"), m("100"), m("1100"), m("1000")),
            NetWorthPoint(d("2026-03-01"), m("1500"), m("150"), m("1650"), m("1500")),
        )
        assertEquals(java.math.BigDecimal("15.00"), Stats.of(pts, AccountState.EMPTY).twrPct)
    }

    @Test
    fun elTextoSobreLasReglasCoincideConLaConfiguracionReal() {
        val texto = Education.topics.joinToString(" ") { t -> t.sections.joinToString(" ") { it.paragraphs.joinToString(" ") } }
        // plazos de fondos por defecto y escala: si cambian en el código, estas pruebas avisan de que el texto está desactualizado
        val s = app.brujula.trading.SettlementRules()
        assertEquals(1..2, s.fundRandomDays) // la guía dice «entre 1 y 2 días hábiles»
        assertTrue(texto.contains("entre 1 y 2 días hábiles"))
        assertTrue(texto.contains("30 % a partir de 300.000 €"))
        assertEquals(TaxRules().scale(2026).last().rate, java.math.BigDecimal("0.30"))
        assertTrue(AssetCatalog.DEFAULT.isNotEmpty())
    }
}
