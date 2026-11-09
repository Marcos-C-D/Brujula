package app.brujula.market

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Tipo de cambio diario del BCE: unidades de [currency] por 1 EUR. */
class FxSeries(val currency: String, rates: Map<LocalDate, BigDecimal>) {
    private val sorted = rates.toSortedMap()
    val isEmpty: Boolean get() = sorted.isEmpty()
    val first: LocalDate? get() = if (sorted.isEmpty()) null else sorted.firstKey()
    val last: LocalDate? get() = if (sorted.isEmpty()) null else sorted.lastKey()
    val size: Int get() = sorted.size
    val entries: Map<LocalDate, BigDecimal> get() = sorted

    /** Tipo vigente en [date]: el último publicado hasta esa fecha, con un máximo de [maxGapDays] de antigüedad. */
    fun rateOn(date: LocalDate, maxGapDays: Long = 7): Pair<LocalDate, BigDecimal>? {
        val e = sorted.headMap(date.plusDays(1)).entries.lastOrNull() ?: return null
        return if (ChronoUnit.DAYS.between(e.key, date) <= maxGapDays) e.key to e.value else null
    }
}

/** Resultado de convertir una serie a euros, con los tipos de cambio usados y lo que no se pudo convertir. */
data class EurSeries(
    val series: PriceSeries,
    val originalCurrency: String,
    val missingFxDates: List<LocalDate>,
    val note: String,
)

object FxConverter {
    /** Divisas cuya conversión a EUR sabemos hacer con datos del BCE. */
    val SUPPORTED = setOf("EUR", "USD", "GBP", "GBp", "CHF")

    fun toEur(series: PriceSeries, currency: String, fx: FxSeries?): EurSeries {
        require(currency in SUPPORTED) { "Divisa no soportada: $currency" }
        if (currency == "EUR") return EurSeries(series, "EUR", emptyList(), "Cotiza en EUR; sin conversión")
        val pence = currency == "GBp"
        val ccy = if (pence) "GBP" else currency
        val rates = fx?.takeIf { it.currency == ccy } ?: return EurSeries(PriceSeries.EMPTY, currency, series.points.map { it.date }, "Falta el tipo de cambio $ccy/EUR")
        val missing = ArrayList<LocalDate>()
        val out = ArrayList<PricePoint>()
        fun conv(v: BigDecimal?, rate: BigDecimal): BigDecimal? =
            v?.let { (if (pence) it.divide(BigDecimal(100)) else it).divide(rate, MathContext(12, RoundingMode.HALF_EVEN)) }
        for (p in series.points) {
            val r = rates.rateOn(p.date)
            if (r == null) { missing += p.date; continue }
            val rate = r.second
            out += PricePoint(p.date, conv(p.close, rate)!!, conv(p.open, rate), conv(p.high, rate), conv(p.low, rate), conv(p.adjClose, rate))
        }
        val note = "Convertido de $currency a EUR con el tipo de cambio diario del BCE (último publicado hasta cada fecha)"
        return EurSeries(PriceSeries.of(out), currency, missing, note)
    }
}
