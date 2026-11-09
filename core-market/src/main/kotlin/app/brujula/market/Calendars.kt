package app.brujula.market

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Domingo de Pascua (algoritmo gregoriano anónimo). */
object Easter {
    fun sunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
    fun goodFriday(year: Int): LocalDate = sunday(year).minusDays(2)
    fun easterMonday(year: Int): LocalDate = sunday(year).plusDays(1)
}

/** Calendario de negociación de un mercado: fines de semana + festivos. */
class MarketCalendar(
    val id: String,
    val zone: ZoneId,
    private val holidaysOf: (Int) -> Set<LocalDate>,
    /** Cierres extraordinarios actualizables sin tocar las reglas (duelos nacionales, etc.). */
    private val extraClosures: Set<LocalDate> = emptySet(),
) {
    fun isHoliday(date: LocalDate): Boolean = date in extraClosures || date in holidaysOf(date.year)

    fun isTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(date)

    fun withExtraClosures(more: Set<LocalDate>) = MarketCalendar(id, zone, holidaysOf, extraClosures + more)

    /** Primer día de negociación estrictamente posterior a [date]. */
    fun nextTradingDay(date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (!isTradingDay(d)) d = d.plusDays(1)
        return d
    }

    /** Último día de negociación estrictamente anterior a [date]. */
    fun previousTradingDay(date: LocalDate): LocalDate {
        var d = date.minusDays(1)
        while (!isTradingDay(d)) d = d.minusDays(1)
        return d
    }

    /** Suma [n] días hábiles (n >= 0) a [date]. */
    fun addTradingDays(date: LocalDate, n: Int): LocalDate {
        require(n >= 0)
        var d = date
        repeat(n) { d = nextTradingDay(d) }
        return d
    }
}

object Markets {
    private fun nth(year: Int, month: Int, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    private fun last(year: Int, month: Int, dow: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow))

    /** Sábado -> viernes anterior; domingo -> lunes siguiente. */
    private fun observed(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    /** NYSE. Si el 1 de enero cae en sábado NO se cierra el viernes 31 de diciembre anterior. */
    val NYSE = MarketCalendar("NYSE", ZoneId.of("America/New_York"), { y ->
        val newYear = LocalDate.of(y, 1, 1)
        buildSet {
            if (newYear.dayOfWeek != DayOfWeek.SATURDAY) add(observed(newYear))
            add(nth(y, 1, DayOfWeek.MONDAY, 3)) // Martin Luther King
            add(nth(y, 2, DayOfWeek.MONDAY, 3)) // Presidentes
            add(Easter.goodFriday(y))
            add(last(y, 5, DayOfWeek.MONDAY)) // Memorial Day
            if (y >= 2022) add(observed(LocalDate.of(y, 6, 19))) // Juneteenth
            add(observed(LocalDate.of(y, 7, 4)))
            add(nth(y, 9, DayOfWeek.MONDAY, 1)) // Labor Day
            add(nth(y, 11, DayOfWeek.THURSDAY, 4)) // Acción de Gracias
            add(observed(LocalDate.of(y, 12, 25)))
        }
    })

    /** Xetra (Frankfurt). */
    val XETRA = MarketCalendar("XETRA", ZoneId.of("Europe/Berlin"), { y ->
        setOf(
            LocalDate.of(y, 1, 1), Easter.goodFriday(y), Easter.easterMonday(y), LocalDate.of(y, 5, 1),
            LocalDate.of(y, 12, 24), LocalDate.of(y, 12, 25), LocalDate.of(y, 12, 26), LocalDate.of(y, 12, 31),
        )
    })

    /** Euronext (Ámsterdam, París…). */
    val EURONEXT = MarketCalendar("EURONEXT", ZoneId.of("Europe/Amsterdam"), { y ->
        setOf(
            LocalDate.of(y, 1, 1), Easter.goodFriday(y), Easter.easterMonday(y), LocalDate.of(y, 5, 1),
            LocalDate.of(y, 12, 25), LocalDate.of(y, 12, 26),
        )
    })

    /** Bolsa de Londres (festivos bancarios de Inglaterra y Gales con sustitución en fin de semana). */
    val LSE = MarketCalendar("LSE", ZoneId.of("Europe/London"), { y ->
        val newYear = LocalDate.of(y, 1, 1).let {
            if (it.dayOfWeek == DayOfWeek.SATURDAY) it.plusDays(2) else if (it.dayOfWeek == DayOfWeek.SUNDAY) it.plusDays(1) else it
        }
        val christmas = LocalDate.of(y, 12, 25)
        val christmasDays: Set<LocalDate> = when (christmas.dayOfWeek) {
            DayOfWeek.SATURDAY -> setOf(LocalDate.of(y, 12, 27), LocalDate.of(y, 12, 28))
            DayOfWeek.SUNDAY -> setOf(LocalDate.of(y, 12, 26), LocalDate.of(y, 12, 27))
            DayOfWeek.FRIDAY -> setOf(LocalDate.of(y, 12, 25), LocalDate.of(y, 12, 28))
            else -> setOf(LocalDate.of(y, 12, 25), LocalDate.of(y, 12, 26))
        }
        setOf(newYear, Easter.goodFriday(y), Easter.easterMonday(y), nth(y, 5, DayOfWeek.MONDAY, 1), last(y, 5, DayOfWeek.MONDAY), last(y, 8, DayOfWeek.MONDAY)) + christmasDays
    })

    fun byId(id: String): MarketCalendar? = listOf(NYSE, XETRA, EURONEXT, LSE).firstOrNull { it.id == id }
}

/**
 * Calendario de actualización: los precios se actualizan cada día a las 00:00 de Europe/Madrid
 * (la hora local, con cambio de horario de verano/invierno incluido).
 */
object UpdateSchedule {
    val MADRID: ZoneId = ZoneId.of("Europe/Madrid")

    fun today(now: java.time.Instant): LocalDate = now.atZone(MADRID).toLocalDate()

    /** Próxima ejecución: la siguiente medianoche en Madrid estrictamente posterior a [now]. */
    fun nextRun(now: java.time.Instant): ZonedDateTime =
        today(now).plusDays(1).atStartOfDay(MADRID)

    /**
     * Si Android retrasó la ejecución o el móvil estuvo apagado: hay que recuperar cuando la última
     * actualización correcta es de un día anterior a hoy (hora de Madrid).
     */
    fun needsCatchUp(lastSuccess: java.time.Instant?, now: java.time.Instant): Boolean =
        lastSuccess == null || today(lastSuccess).isBefore(today(now))
}

/** Antigüedad de un precio respecto a lo que cabría esperar según el calendario del mercado. */
data class PriceStatus(val lastPriceDate: LocalDate, val expectedDate: LocalDate, val isStale: Boolean, val daysBehind: Long)

object Freshness {
    /**
     * Cierre esperado: el último día de negociación anterior a hoy (hora de Madrid). Para activos 24/7 (calendario nulo)
     * es el día anterior.
     */
    fun status(lastPriceDate: LocalDate, today: LocalDate, calendar: MarketCalendar?): PriceStatus {
        val expected = calendar?.previousTradingDay(today) ?: today.minusDays(1)
        val behind = java.time.temporal.ChronoUnit.DAYS.between(lastPriceDate, expected).coerceAtLeast(0)
        return PriceStatus(lastPriceDate, expected, lastPriceDate.isBefore(expected), behind)
    }
}
