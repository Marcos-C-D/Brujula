package app.brujula.app

import app.brujula.engine.LedgerRecord
import app.brujula.market.AssetDefinition
import app.brujula.market.AssetKind
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.tax.InformativeNotices
import app.brujula.tax.PaymentMode
import app.brujula.tax.TaxCalculator
import app.brujula.tax.TaxCalendar
import app.brujula.tax.TaxRules
import app.brujula.tax.TaxSettings
import app.brujula.tax.TaxYearResult
import java.time.LocalDate

data class UpcomingTax(val date: LocalDate, val label: String, val estimated: Boolean)

data class TaxYearView(val result: TaxYearResult, val provisional: Boolean, val campaignOfficial: Boolean, val chargeDate: LocalDate)

data class TaxOverview(
    val years: List<TaxYearView>,
    val upcoming: List<UpcomingTax>,
    val notices: List<String>,
    val settings: TaxSettings,
)

object TaxViews {
    fun overview(
        records: List<LedgerRecord>, today: LocalDate, settings: TaxSettings, catalog: (AssetId) -> AssetDefinition?,
        valuesByKind: Map<AssetKind, Money> = emptyMap(),
    ): TaxOverview {
        if (records.isEmpty()) return TaxOverview(emptyList(), emptyList(), InformativeNotices.forPortfolio(valuesByKind), settings)
        val results = TaxCalculator(TaxRules(), settings, catalog).compute(records, today.year)
        val views = results.map {
            val c = TaxCalendar.campaign(it.year)
            TaxYearView(it, provisional = !today.isAfter(c.end), campaignOfficial = c.official, chargeDate = c.chargeDate)
        }.reversed()

        val upcoming = ArrayList<UpcomingTax>()
        for (v in views) {
            val r = v.result.result
            val c = TaxCalendar.campaign(v.result.year)
            val label = "IRPF ${v.result.year}"
            val refund = c.end.plusMonths(settings.refundMonthsAfterCampaign.toLong())
            if (r.isPositive) {
                val first = if (settings.payment == PaymentMode.SPLIT_60_40) Money.of(r.amount.multiply(java.math.BigDecimal("0.60"))).roundedToCents() else r
                if (!c.chargeDate.isBefore(today)) upcoming += UpcomingTax(c.chargeDate, "$label: pago estimado de ${Fmt.eur(first)}", !c.official)
                if (settings.payment == PaymentMode.SPLIT_60_40 && !c.secondInstallment.isBefore(today)) {
                    upcoming += UpcomingTax(c.secondInstallment, "$label: segundo plazo estimado de ${Fmt.eur(r - first)}", !c.official)
                }
            } else if (r.isNegative && !refund.isBefore(today)) {
                upcoming += UpcomingTax(refund, "$label: devolución estimada de ${Fmt.eur(-r)} (a más tardar)", !c.official)
            }
        }
        return TaxOverview(views, upcoming.sortedBy { it.date }, InformativeNotices.forPortfolio(valuesByKind), settings)
    }
}
