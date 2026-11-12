package app.brujula.tax

import app.brujula.model.AssetId
import app.brujula.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

class UnsupportedTaxYearException(val year: Int) : RuntimeException("No hay reglas fiscales cargadas para el ejercicio $year")

/** Tramo de la escala del ahorro: hasta [upTo] (acumulado) al [rate]; `upTo == null` = el resto. */
data class Bracket(val upTo: Money?, val rate: BigDecimal)

/**
 * Reglas del IRPF para la base del ahorro, con vigencia por ejercicio. Los tipos y plazos están en datos, no fijados en el código:
 * cuando cambie la ley basta con añadir una escala nueva.
 *
 * Fuentes: LIRPF art. 33.5 (pérdidas no computables por recompra: 2 meses valores cotizados, 1 año no cotizados), art. 37.2 (FIFO),
 * art. 49 (integración y compensación: 25 % entre bloques, 4 años) y art. 66 (escala del ahorro; Ley 7/2024: 30 % desde 300.000 €).
 * Los años posteriores al último con datos oficiales se calculan suponiendo que no cambian.
 */
class TaxRules(
    val withholdingRate: BigDecimal = BigDecimal("0.19"),
    val compensationLimit: BigDecimal = BigDecimal("0.25"),
    val carryYears: Int = 4,
    val repurchaseMonthsListed: Int = 2,
    val repurchaseYearsUnlisted: Int = 1,
    private val scales: List<Pair<Int, List<Bracket>>> = DEFAULT_SCALES,
) {
    fun scale(year: Int): List<Bracket> =
        scales.filter { it.first <= year }.maxByOrNull { it.first }?.second ?: throw UnsupportedTaxYearException(year)

    /** Cuota íntegra de una base del ahorro, por la escala del ejercicio. */
    fun tax(year: Int, base: Money): Money {
        if (!base.isPositive) return Money.ZERO
        var remaining = base.amount
        var lower = BigDecimal.ZERO
        var total = BigDecimal.ZERO
        for (b in scale(year)) {
            val upper = b.upTo?.amount
            val slice = if (upper == null) remaining else remaining.min(upper.subtract(lower))
            if (slice.signum() > 0) total = total.add(slice.multiply(b.rate))
            remaining = remaining.subtract(slice)
            if (upper != null) lower = upper
            if (remaining.signum() <= 0) break
        }
        return Money.of(total).roundedToCents()
    }

    companion object {
        val DEFAULT_SCALES: List<Pair<Int, List<Bracket>>> = listOf(
            2025 to listOf(
                Bracket(Money.of("6000"), BigDecimal("0.19")),
                Bracket(Money.of("50000"), BigDecimal("0.21")),
                Bracket(Money.of("200000"), BigDecimal("0.23")),
                Bracket(Money.of("300000"), BigDecimal("0.27")),
                Bracket(null, BigDecimal("0.30")),
            ),
        )
    }
}

enum class PaymentMode { SINGLE, SPLIT_60_40 }

/**
 * Opciones fiscales de la cuenta (la interfaz para editarlas llega en la fase 5).
 * - [otherSavingsBase]: otras rentas del ahorro del año (fuera de la simulación) que ya ocupan tramos de la escala.
 * - Se asume que el mínimo personal y familiar se aplica a la base general (no reduce la base del ahorro).
 */
data class TaxSettings(
    val otherSavingsBase: Map<Int, Money> = emptyMap(),
    val payment: PaymentMode = PaymentMode.SINGLE,
    /** La ley da hasta 6 meses desde el fin de campaña para devolver; se simula en esa fecha (la más desfavorable). */
    val refundMonthsAfterCampaign: Int = 6,
    val withholdOnFundRedemptions: Boolean = true,
    val withholdOnDividends: Boolean = true,
    val applyRepurchaseToCrypto: Boolean = false,
    /** Impuesto retenido en el extranjero sobre dividendos por activo (0 por defecto). */
    val foreignWithholdingRate: Map<AssetId, BigDecimal> = emptyMap(),
) {
    /** Opciones como texto clave-valor para guardarlas en la cuenta. */
    fun toOptions(): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["payment"] = payment.name
        m["refundMonths"] = refundMonthsAfterCampaign.toString()
        m["withholdFunds"] = withholdOnFundRedemptions.toString()
        m["withholdDividends"] = withholdOnDividends.toString()
        m["cryptoRepurchase"] = applyRepurchaseToCrypto.toString()
        otherSavingsBase.forEach { (year, amount) -> m["otherBase.$year"] = amount.toString() }
        return m
    }

    companion object {
        /** Lee las opciones guardadas; lo que falta o no es válido usa el valor por defecto. */
        fun fromOptions(options: Map<String, String>): TaxSettings {
            val d = TaxSettings()
            val other = HashMap<Int, Money>()
            for ((k, v) in options) {
                if (!k.startsWith("otherBase.")) continue
                val year = k.removePrefix("otherBase.").toIntOrNull() ?: continue
                val amount = try { Money.of(v) } catch (e: NumberFormatException) { continue }
                if (!amount.isNegative) other[year] = amount
            }
            return TaxSettings(
                otherSavingsBase = other,
                payment = options["payment"]?.let { p -> PaymentMode.values().firstOrNull { it.name == p } } ?: d.payment,
                refundMonthsAfterCampaign = options["refundMonths"]?.toIntOrNull()?.takeIf { it in 0..12 } ?: d.refundMonthsAfterCampaign,
                withholdOnFundRedemptions = options["withholdFunds"]?.toBooleanStrictOrNull() ?: d.withholdOnFundRedemptions,
                withholdOnDividends = options["withholdDividends"]?.toBooleanStrictOrNull() ?: d.withholdOnDividends,
                applyRepurchaseToCrypto = options["cryptoRepurchase"]?.toBooleanStrictOrNull() ?: d.applyRepurchaseToCrypto,
            )
        }
    }
}

/** Calendario de una campaña de la renta. */
data class Campaign(
    val taxYear: Int,
    val start: LocalDate,
    val directDebitDeadline: LocalDate,
    val end: LocalDate,
    /** Fecha del cargo del pago único o del primer plazo (60 %). */
    val chargeDate: LocalDate,
    val secondInstallment: LocalDate,
    /** true si las fechas proceden de la orden ministerial; false si son una estimación. */
    val official: Boolean,
)

object TaxCalendar {
    /** Renta 2025 (campaña 2026): Orden HAC/277/2026 y manual de la AEAT. */
    private val OFFICIAL = mapOf(
        2025 to Campaign(
            taxYear = 2025, start = LocalDate.of(2026, 4, 8), directDebitDeadline = LocalDate.of(2026, 6, 25),
            end = LocalDate.of(2026, 6, 30), chargeDate = LocalDate.of(2026, 6, 30), secondInstallment = LocalDate.of(2026, 11, 5), official = true,
        ),
    )

    fun campaign(taxYear: Int): Campaign = OFFICIAL[taxYear] ?: estimate(taxYear)

    /** Estimación para ejercicios sin orden publicada: abril-30 de junio, domiciliación 5 días antes, segundo plazo el 5 de noviembre. */
    private fun estimate(taxYear: Int): Campaign {
        val y = taxYear + 1
        val end = nextBusinessDay(LocalDate.of(y, 6, 30))
        return Campaign(
            taxYear, nextBusinessDay(LocalDate.of(y, 4, 8)), previousBusinessDay(end.minusDays(5)), end, end,
            nextBusinessDay(LocalDate.of(y, 11, 5)), official = false,
        )
    }

    private fun nextBusinessDay(d: LocalDate): LocalDate {
        var x = d
        while (x.dayOfWeek == DayOfWeek.SATURDAY || x.dayOfWeek == DayOfWeek.SUNDAY) x = x.plusDays(1)
        return x
    }

    private fun previousBusinessDay(d: LocalDate): LocalDate {
        var x = d
        while (x.dayOfWeek == DayOfWeek.SATURDAY || x.dayOfWeek == DayOfWeek.SUNDAY) x = x.minusDays(1)
        return x
    }
}

internal fun BigDecimal.cents(): Money = Money.of(this).roundedToCents(RoundingMode.HALF_UP)
