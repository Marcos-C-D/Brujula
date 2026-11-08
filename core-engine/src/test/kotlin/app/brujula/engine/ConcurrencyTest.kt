package app.brujula.engine

import app.brujula.model.AccountId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ConcurrencyTest {

    @Test
    fun comprasSimultaneasNuncaGastanDosVecesElMismoDinero() {
        val ledger = Ledger(AccountId("conc"))
        ledger.post(OpeningBalance(d("2026-01-01"), m("100.00")))

        val hilos = 8
        val intentosPorHilo = 25 // 200 intentos de 1,00 € con solo 100,00 € disponibles
        val ok = AtomicInteger()
        val rechazadas = AtomicInteger()
        val salida = CountDownLatch(1)
        val fin = CountDownLatch(hilos)

        repeat(hilos) {
            Thread {
                salida.await()
                repeat(intentosPorHilo) {
                    try {
                        ledger.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("1.00"), units = u("1")))
                        ok.incrementAndGet()
                    } catch (e: InsufficientCashException) {
                        rechazadas.incrementAndGet()
                    }
                }
                fin.countDown()
            }.start()
        }
        salida.countDown()
        fin.await()

        assertEquals(100, ok.get())
        assertEquals(100, rechazadas.get())
        assertEquals(m("0.00"), ledger.state.cash)
        assertEquals(u("100"), ledger.state.unitsHeld(ASSET_A))
        assertEquals(101, ledger.records.size)
        assertEquals(emptyList(), LedgerAuditor.audit(ledger.records))
    }

    @Test
    fun ventasSimultaneasNuncaVendenMasDeLoQueHay() {
        val ledger = Ledger(AccountId("conc2"))
        ledger.post(OpeningBalance(d("2026-01-01"), m("100.00")))
        ledger.post(Purchase(d("2026-01-02"), ASSET_A, invested = m("100.00"), units = u("50")))

        val ok = AtomicInteger()
        val fin = CountDownLatch(10)
        repeat(10) {
            Thread {
                repeat(20) {
                    try {
                        ledger.post(Sale(d("2026-01-03"), ASSET_A, units = u("1"), grossProceeds = m("2.00")))
                        ok.incrementAndGet()
                    } catch (e: InsufficientUnitsException) {
                    }
                }
                fin.countDown()
            }.start()
        }
        fin.await()
        assertEquals(50, ok.get())
        assertEquals(u("0"), ledger.state.unitsHeld(ASSET_A))
        assertEquals(m("100.00"), ledger.state.cash)
        assertEquals(emptyList(), LedgerAuditor.audit(ledger.records))
    }
}
