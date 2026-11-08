package app.brujula.engine

import app.brujula.model.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * Configuración de ingreso mensual válida desde un periodo. El histórico de configuraciones permite
 * recuperar ingresos atrasados con el importe y el día que correspondían en su momento.
 */
data class IncomeConfig(val fromPeriod: YearMonth, val amount: Money, val dayOfMonth: Int) {
    init {
        if (dayOfMonth !in 1..31) throw InvalidAccountException("El día de ingreso debe estar entre 1 y 31")
        if (amount.isNegative) throw InvalidAccountException("El ingreso mensual no puede ser negativo")
        if (!amount.isWholeCents) throw InvalidAccountException("El ingreso mensual debe estar en céntimos exactos")
    }
}

/** Intervalo en el que la cuenta estuvo archivada: [from, to). `to == null` = sigue archivada. */
data class ArchiveInterval(val from: LocalDate, val to: LocalDate?) {
    fun contains(date: LocalDate): Boolean = !date.isBefore(from) && (to == null || date.isBefore(to))
}

data class DueIncome(val period: YearMonth, val date: LocalDate, val amount: Money)

/**
 * Calcula qué ingresos mensuales están pendientes. Es una función pura: el resultado depende solo de
 * sus argumentos, y al excluir los periodos ya abonados es idempotente (nunca duplica).
 *
 * Reglas:
 * - El ingreso de un periodo vence el día configurado (si el mes es más corto, el último día del mes).
 * - Solo se abonan ingresos cuya fecha es POSTERIOR a la creación de la cuenta y no futura.
 * - No se abonan ingresos que vencerían mientras la cuenta estaba archivada.
 * - Cada periodo usa la configuración vigente para ese periodo (la última con fromPeriod <= periodo).
 */
object IncomeScheduler {

    fun dueDate(period: YearMonth, dayOfMonth: Int): LocalDate =
        period.atDay(minOf(dayOfMonth, period.lengthOfMonth()))

    fun configFor(history: List<IncomeConfig>, period: YearMonth): IncomeConfig? =
        history.filter { it.fromPeriod <= period }.maxByOrNull { it.fromPeriod }

    fun pending(
        createdOn: LocalDate,
        history: List<IncomeConfig>,
        credited: Set<YearMonth>,
        archived: List<ArchiveInterval>,
        today: LocalDate,
    ): List<DueIncome> {
        val result = ArrayList<DueIncome>()
        var period = YearMonth.from(createdOn)
        val last = YearMonth.from(today)
        while (period <= last) {
            val cfg = configFor(history, period)
            if (cfg != null && cfg.amount.isPositive && period !in credited) {
                val date = dueDate(period, cfg.dayOfMonth)
                if (date.isAfter(createdOn) && !date.isAfter(today) && archived.none { it.contains(date) }) {
                    result += DueIncome(period, date, cfg.amount)
                }
            }
            period = period.plusMonths(1)
        }
        return result
    }
}
