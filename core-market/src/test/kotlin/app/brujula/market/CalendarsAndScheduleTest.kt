package app.brujula.market

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarsAndScheduleTest {

    @Test
    fun pascuaCorrecta() {
        assertEquals(d("2024-03-31"), Easter.sunday(2024))
        assertEquals(d("2025-04-20"), Easter.sunday(2025))
        assertEquals(d("2026-04-05"), Easter.sunday(2026))
        assertEquals(d("2027-03-28"), Easter.sunday(2027))
    }

    @Test
    fun festivosDeNyse2026() {
        val cerrados = listOf("2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25")
        cerrados.forEach { assertFalse(Markets.NYSE.isTradingDay(d(it)), it) }
        assertTrue(Markets.NYSE.isTradingDay(d("2026-07-02")))
        assertTrue(Markets.NYSE.isTradingDay(d("2026-11-27"))) // viernes tras Acción de Gracias
    }

    @Test
    fun nyseNoCierraElViernes31SiElUnoDeEneroEsSabado() {
        assertTrue(Markets.NYSE.isTradingDay(d("2027-12-31")))
        assertTrue(Markets.NYSE.isTradingDay(d("2028-01-03")))
    }

    @Test
    fun xetraYEuronextDifierenEnNochebuena() {
        assertFalse(Markets.XETRA.isTradingDay(d("2026-12-24")))
        assertTrue(Markets.EURONEXT.isTradingDay(d("2026-12-24")))
        assertFalse(Markets.XETRA.isTradingDay(d("2026-04-06")))
        assertFalse(Markets.EURONEXT.isTradingDay(d("2026-04-06")))
        assertFalse(Markets.XETRA.isTradingDay(d("2026-05-01")))
    }

    @Test
    fun bolsaDeLondres2026() {
        listOf("2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25", "2026-08-31", "2026-12-25", "2026-12-28")
            .forEach { assertFalse(Markets.LSE.isTradingDay(d(it)), it) }
        assertTrue(Markets.LSE.isTradingDay(d("2026-12-24")))
    }

    @Test
    fun diasHabilesSaltanFinesDeSemanaYFestivos() {
        val c = Markets.XETRA
        assertEquals(d("2026-04-07"), c.nextTradingDay(d("2026-04-02")))
        assertEquals(d("2026-11-09"), c.nextTradingDay(d("2026-11-06"))) // viernes -> lunes
        assertEquals(d("2026-11-11"), c.addTradingDays(d("2026-11-06"), 3))
        assertEquals(d("2026-11-06"), c.previousTradingDay(d("2026-11-09")))
    }

    @Test
    fun cierresExtraordinariosSePuedenAnadirSinTocarLasReglas() {
        val c = Markets.NYSE.withExtraClosures(setOf(d("2026-03-10")))
        assertFalse(c.isTradingDay(d("2026-03-10")))
        assertTrue(Markets.NYSE.isTradingDay(d("2026-03-10")))
    }

    @Test
    fun actualizacionADiarioALas0000DeMadridConCambioDeHorario() {
        // 23:30 hora de Madrid (CET) del 28-mar-2026 -> la siguiente medianoche es el 29-mar 00:00 (+01:00)
        assertEquals(Instant.parse("2026-03-28T23:00:00Z"), UpdateSchedule.nextRun(Instant.parse("2026-03-28T22:30:00Z")).toInstant())
        // el día del cambio a horario de verano: la siguiente es el 30-mar 00:00 (+02:00)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z"), UpdateSchedule.nextRun(Instant.parse("2026-03-29T10:00:00Z")).toInstant())
        // fin del horario de verano (25-oct-2026): 00:00 del 26-oct es +01:00
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), UpdateSchedule.nextRun(Instant.parse("2026-10-24T23:30:00Z")).toInstant())
    }

    @Test
    fun recuperacionAlAbrirLaAppSiSeHaPerdidoLaEjecucion() {
        val ahora = Instant.parse("2026-11-08T10:00:00Z")
        assertTrue(UpdateSchedule.needsCatchUp(null, ahora))
        assertTrue(UpdateSchedule.needsCatchUp(Instant.parse("2026-11-07T22:30:00Z"), ahora)) // 23:30 Madrid del día 7
        assertFalse(UpdateSchedule.needsCatchUp(Instant.parse("2026-11-07T23:30:00Z"), ahora)) // 00:30 Madrid del día 8
    }

    @Test
    fun antiguedadDelPrecioSegunElCalendario() {
        val hoy = d("2026-11-09") // lunes
        assertFalse(Freshness.status(d("2026-11-06"), hoy, Markets.NYSE).isStale) // cierre del viernes
        val s = Freshness.status(d("2026-11-05"), hoy, Markets.NYSE)
        assertTrue(s.isStale)
        assertEquals(1, s.daysBehind)
        // criptomonedas: se espera el día anterior
        assertTrue(Freshness.status(d("2026-11-07"), hoy, null).isStale)
        assertFalse(Freshness.status(d("2026-11-08"), hoy, null).isStale)
    }
}
