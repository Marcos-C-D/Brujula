package app.brujula.market

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.sqrt

enum class ReturnPeriod(val label: String) {
    YTD("YTD"), M3("3 meses"), M6("6 meses"), Y1("1 año"), Y2("2 años"), Y5("5 años"), Y10("10 años"), ALL("Desde el inicio"),
}

sealed class ReturnResult {
    /** Rentabilidad acumulada en %, entre dos fechas reales de la serie. */
    data class Value(val percent: BigDecimal, val fromDate: LocalDate, val toDate: LocalDate) : ReturnResult()

    /** No hay datos suficientes: se indica claramente y nunca se rellena con datos inventados. */
    data class Insufficient(val reason: String) : ReturnResult()
}

/**
 * Rentabilidades históricas calculadas SOLO con datos reales de la serie.
 * Para cada periodo se toma como base el último cierre igual o anterior a la fecha de inicio del periodo, y se exige
 * que la serie cubra esa fecha (tolerancia [TOLERANCE_DAYS] por fines de semana y festivos).
 * Si la serie trae cierre ajustado en todos sus puntos se usa ese (incluye dividendos); si no, el cierre.
 */
object Performance {
    const val TOLERANCE_DAYS = 7L

    fun returns(series: PriceSeries, asOf: LocalDate? = null): Map<ReturnPeriod, ReturnResult> =
        ReturnPeriod.values().associateWith { period -> calculate(series, period, asOf) }

    fun calculate(series: PriceSeries, period: ReturnPeriod, asOf: LocalDate? = null): ReturnResult {
        if (series.isEmpty) return ReturnResult.Insufficient("Sin datos")
        val end = series.onOrBefore(asOf ?: series.last.date) ?: return ReturnResult.Insufficient("Sin datos hasta esa fecha")
        val startDate: LocalDate = when (period) {
            ReturnPeriod.YTD -> LocalDate.of(end.date.year - 1, 12, 31)
            ReturnPeriod.M3 -> end.date.minusMonths(3)
            ReturnPeriod.M6 -> end.date.minusMonths(6)
            ReturnPeriod.Y1 -> end.date.minusYears(1)
            ReturnPeriod.Y2 -> end.date.minusYears(2)
            ReturnPeriod.Y5 -> end.date.minusYears(5)
            ReturnPeriod.Y10 -> end.date.minusYears(10)
            ReturnPeriod.ALL -> {
                if (series.points.size < 2) return ReturnResult.Insufficient("Se necesitan al menos 2 precios")
                val base = series.first
                return result(series, base, end)
            }
        }
        val base = series.onOrBefore(startDate)
            ?: return ReturnResult.Insufficient("Sin datos suficientes: la serie empieza el ${series.first.date}")
        if (ChronoUnit.DAYS.between(base.date, startDate) > TOLERANCE_DAYS) {
            return ReturnResult.Insufficient("Sin datos suficientes: falta el precio de ${startDate}")
        }
        if (!base.date.isBefore(end.date)) return ReturnResult.Insufficient("Periodo sin datos")
        return result(series, base, end)
    }

    private fun result(series: PriceSeries, base: PricePoint, end: PricePoint): ReturnResult {
        val ratio = series.value(end).divide(series.value(base), MathContext(20, RoundingMode.HALF_EVEN))
        val percent = ratio.subtract(BigDecimal.ONE).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        return ReturnResult.Value(percent, base.date, end.date)
    }
}

sealed class VolatilityResult {
    data class Value(val annualizedPercent: BigDecimal, val observations: Int, val fromDate: LocalDate, val toDate: LocalDate) : VolatilityResult()
    data class Insufficient(val reason: String) : VolatilityResult()
}

/**
 * Riesgo: volatilidad histórica anualizada (desviación típica muestral de los rendimientos logarítmicos diarios
 * por la raíz de 252) y clasificación 1-7 con los tramos de volatilidad del indicador sintético de riesgo UCITS (SRRI).
 * No es una predicción ni garantiza resultados futuros.
 */
object Risk {
    private const val TRADING_DAYS = 252

    fun volatility(series: PriceSeries, years: Int = 1, asOf: LocalDate? = null): VolatilityResult {
        if (series.isEmpty) return VolatilityResult.Insufficient("Sin datos")
        val end = series.onOrBefore(asOf ?: series.last.date) ?: return VolatilityResult.Insufficient("Sin datos")
        val start = end.date.minusYears(years.toLong())
        val window = series.points.filter { !it.date.isBefore(start) && !it.date.isAfter(end.date) }
        val minPoints = (TRADING_DAYS * years * 0.8).toInt()
        if (window.size < minPoints) {
            return VolatilityResult.Insufficient("Sin datos suficientes: ${window.size} precios en $years año(s), se necesitan al menos $minPoints")
        }
        val rets = window.zipWithNext { a, b -> ln(series.value(b).toDouble() / series.value(a).toDouble()) }
        val mean = rets.average()
        val variance = rets.sumOf { (it - mean) * (it - mean) } / (rets.size - 1)
        val annual = sqrt(variance) * sqrt(TRADING_DAYS.toDouble()) * 100.0
        return VolatilityResult.Value(BigDecimal(annual).setScale(2, RoundingMode.HALF_UP), rets.size, window.first().date, window.last().date)
    }

    /** Clase de riesgo 1 (muy bajo) a 7 (muy alto) según la volatilidad anualizada en %. */
    fun riskClass(annualizedPercent: BigDecimal): Int {
        val v = annualizedPercent.toDouble()
        return when {
            v < 0.5 -> 1
            v < 2.0 -> 2
            v < 5.0 -> 3
            v < 10.0 -> 4
            v < 15.0 -> 5
            v < 25.0 -> 6
            else -> 7
        }
    }
}
