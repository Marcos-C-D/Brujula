package app.brujula.engine

import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IncomeSchedulerTest {

    private fun cfg(from: String, amount: String, day: Int = 1) = IncomeConfig(YearMonth.parse(from), m(amount), day)

    @Test
    fun elDiaSeAjustaAlUltimoDiaDeMesesCortos() {
        assertEquals(d("2026-02-28"), IncomeScheduler.dueDate(YearMonth.of(2026, 2), 31))
        assertEquals(d("2028-02-29"), IncomeScheduler.dueDate(YearMonth.of(2028, 2), 31))
        assertEquals(d("2026-04-30"), IncomeScheduler.dueDate(YearMonth.of(2026, 4), 31))
        assertEquals(d("2026-04-15"), IncomeScheduler.dueDate(YearMonth.of(2026, 4), 15))
    }

    @Test
    fun recuperaTodosLosIngresosAtrasadosSinContarElMesDeCreacionYaPasado() {
        val due = IncomeScheduler.pending(d("2026-03-15"), listOf(cfg("2026-03", "100.00")), emptySet(), emptyList(), d("2026-06-10"))
        assertEquals(listOf(d("2026-04-01"), d("2026-05-01"), d("2026-06-01")), due.map { it.date })
    }

    @Test
    fun elIngresoDelMismoDiaDeCreacionNoSeAbona() {
        val due = IncomeScheduler.pending(d("2026-03-01"), listOf(cfg("2026-03", "100.00")), emptySet(), emptyList(), d("2026-03-31"))
        assertEquals(emptyList(), due)
        val due2 = IncomeScheduler.pending(d("2026-03-01"), listOf(cfg("2026-03", "100.00")), emptySet(), emptyList(), d("2026-04-01"))
        assertEquals(listOf(d("2026-04-01")), due2.map { it.date })
    }

    @Test
    fun noAbonaIngresosFuturos() {
        val h = listOf(cfg("2026-03", "100.00", day = 20))
        assertEquals(emptyList(), IncomeScheduler.pending(d("2026-03-01"), h, emptySet(), emptyList(), d("2026-03-19")))
        assertEquals(1, IncomeScheduler.pending(d("2026-03-01"), h, emptySet(), emptyList(), d("2026-03-20")).size)
    }

    @Test
    fun esIdempotenteLosPeriodosYaAbonadosNoSeRepiten() {
        val h = listOf(cfg("2026-03", "100.00"))
        val creditados = setOf(YearMonth.of(2026, 4), YearMonth.of(2026, 5))
        val due = IncomeScheduler.pending(d("2026-03-15"), h, creditados, emptyList(), d("2026-06-10"))
        assertEquals(listOf(YearMonth.of(2026, 6)), due.map { it.period })
    }

    @Test
    fun cadaPeriodoUsaLaConfiguracionVigenteEnEseMomento() {
        val h = listOf(cfg("2026-03", "100.00"), cfg("2026-05", "150.00", day = 10))
        val due = IncomeScheduler.pending(d("2026-03-15"), h, emptySet(), emptyList(), d("2026-06-30"))
        assertEquals(listOf(m("100.00"), m("150.00"), m("150.00")), due.map { it.amount })
        assertEquals(listOf(d("2026-04-01"), d("2026-05-10"), d("2026-06-10")), due.map { it.date })
    }

    @Test
    fun noAbonaLoQueVencioConLaCuentaArchivada() {
        val h = listOf(cfg("2026-01", "100.00"))
        val archivada = listOf(ArchiveInterval(d("2026-05-10"), d("2026-07-05")))
        val due = IncomeScheduler.pending(d("2026-01-01"), h, emptySet(), archivada, d("2026-08-31"))
        assertEquals(
            listOf("2026-02", "2026-03", "2026-04", "2026-05", "2026-08"),
            due.map { it.period.toString() },
        )
    }

    @Test
    fun importeCeroNoGeneraIngresos() {
        val h = listOf(cfg("2026-01", "0"))
        assertEquals(emptyList(), IncomeScheduler.pending(d("2026-01-01"), h, emptySet(), emptyList(), d("2026-12-31")))
    }

    @Test
    fun configuracionInvalidaSeRechaza() {
        assertFailsWith<InvalidAccountException> { cfg("2026-01", "100.00", day = 0) }
        assertFailsWith<InvalidAccountException> { cfg("2026-01", "100.00", day = 32) }
        assertFailsWith<InvalidAccountException> { cfg("2026-01", "-1.00") }
        assertFailsWith<InvalidAccountException> { cfg("2026-01", "10.001") }
    }
}
