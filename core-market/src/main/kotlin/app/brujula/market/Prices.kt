package app.brujula.market

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Un precio diario. Los precios están en la divisa de origen de la serie. */
data class PricePoint(
    val date: LocalDate,
    val close: BigDecimal,
    val open: BigDecimal? = null,
    val high: BigDecimal? = null,
    val low: BigDecimal? = null,
    /** Cierre ajustado por dividendos y splits (si el proveedor lo da). */
    val adjClose: BigDecimal? = null,
)

/** Serie ordenada por fecha, sin fechas repetidas y con precios positivos. */
class PriceSeries private constructor(val points: List<PricePoint>) {
    val isEmpty: Boolean get() = points.isEmpty()
    val first: PricePoint get() = points.first()
    val last: PricePoint get() = points.last()

    /** Último punto con fecha igual o anterior a [date]. */
    fun onOrBefore(date: LocalDate): PricePoint? {
        var lo = 0
        var hi = points.size - 1
        var found: PricePoint? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (!points[mid].date.isAfter(date)) { found = points[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** true si todos los puntos traen cierre ajustado: entonces las rentabilidades usan el ajustado. */
    val hasAdjusted: Boolean get() = points.isNotEmpty() && points.all { it.adjClose != null }

    fun value(p: PricePoint): BigDecimal = if (hasAdjusted) p.adjClose!! else p.close

    companion object {
        val EMPTY = PriceSeries(emptyList())

        /** Ordena, elimina fechas repetidas (gana la última) y rechaza precios no positivos. */
        fun of(points: List<PricePoint>): PriceSeries {
            for (p in points) {
                require(p.close.signum() > 0) { "Precio de cierre no positivo en ${p.date}" }
                require(p.adjClose == null || p.adjClose.signum() > 0) { "Cierre ajustado no positivo en ${p.date}" }
            }
            val byDate = LinkedHashMap<LocalDate, PricePoint>()
            for (p in points) byDate[p.date] = p
            return PriceSeries(byDate.values.sortedBy { it.date })
        }
    }
}

/** Resultado crudo de un proveedor, antes de guardarse. */
data class FetchedSeries(
    val symbol: String,
    val currency: String,
    val provider: String,
    val points: List<PricePoint>,
    /** Incidencias no fatales (por ejemplo, puntos descartados por no ser válidos). */
    val warnings: List<String>,
    val fetchedAt: Instant,
)
