package app.brujula.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class FifoTest {

    private fun lot(seq: Long, date: String, units: String, cost: String) = Lot(
        ASSET_A, d(date), seq, u(units), u(units), m(cost), m(cost),
    )

    @Test
    fun consumeElMasAntiguoPrimero() {
        val lots = listOf(lot(1, "2026-01-10", "10", "100.00"), lot(2, "2026-02-10", "10", "200.00"))
        val result = Fifo.consume(lots, u("15"))

        assertEquals(2, result.consumptions.size)
        assertEquals(m("100.00"), result.consumptions[0].cost)
        assertEquals(u("10"), result.consumptions[0].units)
        assertEquals(u("5"), result.consumptions[1].units)
        assertEquals(m("100.00"), result.consumptions[1].cost)

        assertEquals(1, result.remainingLots.size)
        assertEquals(u("5"), result.remainingLots[0].unitsRemaining)
        assertEquals(m("100.00"), result.remainingLots[0].costRemaining)
    }

    @Test
    fun ventaParcialRepartiendoCosteNoPierdeNingunCentimo() {
        // 3 unidades por 100,00 €: 33,33 + 33,34 + 33,33 = 100,00 exactos
        var lots = listOf(lot(1, "2026-01-10", "3", "100.00"))
        var total = m("0")
        repeat(3) {
            val r = Fifo.consume(lots, u("1"))
            total += r.consumptions.single().cost
            lots = r.remainingLots
        }
        assertEquals(m("100.00"), total)
        assertEquals(true, lots.isEmpty())
    }

    @Test
    fun ventaTotalConsumeTodoElCosteRestante() {
        val r = Fifo.consume(listOf(lot(1, "2026-01-10", "7", "99.99")), u("7"))
        assertEquals(m("99.99"), r.consumptions.single().cost)
        assertEquals(true, r.remainingLots.isEmpty())
    }

    @Test
    fun unidadesFraccionariasSeRepartenProporcionalmente() {
        val r = Fifo.consume(listOf(lot(1, "2026-01-10", "0.5", "50.00")), u("0.125"))
        assertEquals(m("12.50"), r.consumptions.single().cost)
        assertEquals(u("0.375"), r.remainingLots.single().unitsRemaining)
        assertEquals(m("37.50"), r.remainingLots.single().costRemaining)
    }

    @Test
    fun noModificaLosLotesOriginales() {
        val lots = listOf(lot(1, "2026-01-10", "10", "100.00"))
        Fifo.consume(lots, u("4"))
        assertEquals(u("10"), lots[0].unitsRemaining)
        assertEquals(m("100.00"), lots[0].costRemaining)
    }
}
