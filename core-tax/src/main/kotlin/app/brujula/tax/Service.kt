package app.brujula.tax

import app.brujula.engine.Bank
import app.brujula.engine.DividendReceived
import app.brujula.engine.Ledger
import app.brujula.engine.Sale
import app.brujula.engine.TaxSettlement
import app.brujula.engine.WithholdingPaid
import app.brujula.market.AssetDefinition
import app.brujula.market.AssetKind
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Dividendo por unidad declarado por un activo, en EUR, con su fecha ex-dividendo. */
data class DividendDeclaration(val exDate: LocalDate, val amountPerUnit: BigDecimal)

fun interface DividendFeed {
    fun declarations(assetId: AssetId): List<DividendDeclaration>
}

/**
 * Registra, día a día y en sus fechas reales, los hechos fiscales de una cuenta: dividendos (con retención), retención al
 * reembolsar fondos y los pagos o devoluciones de la declaración de la renta. Es idempotente.
 *
 * Simplificaciones documentadas: el dividendo se cobra el día ex-dividendo; solo se admiten dividendos cotizados en EUR; las
 * retenciones se practican como si el intermediario fuera español.
 */
class TaxService(
    private val bank: Bank,
    private val assets: (AssetId) -> AssetDefinition?,
    private val catalogIds: () -> List<AssetId>,
    private val feed: DividendFeed,
    private val rules: TaxRules = TaxRules(),
    private val settings: TaxSettings = TaxSettings(),
) {
    private val calculator = TaxCalculator(rules, settings, assets)

    fun processDay(accountId: AccountId, day: LocalDate): List<String> {
        val report = ArrayList<String>()
        processDividends(accountId, day, report)
        processRedemptionWithholdings(accountId, day, report)
        processSettlements(accountId, day, report)
        return report
    }

    private fun processDividends(accountId: AccountId, day: LocalDate, report: MutableList<String>) {
        val account = bank.get(accountId)
        for (assetId in catalogIds()) {
            val def = assets(assetId) ?: continue
            if (!def.distributes) continue
            for (decl in feed.declarations(assetId)) {
                if (decl.exDate != day) continue
                val state = account.state
                if (state.dividends.any { it.date == day && it.assetId == assetId }) continue
                // titular el día ex-dividendo: participaciones que se tenían al empezar ese día
                val before = Ledger.replay(accountId, account.ledger.records.filter { it.event.date.isBefore(day) }.map { it.event }).state
                val units = before.unitsHeld(assetId)
                if (!units.isPositive) continue
                val gross = Money.of(units.value.multiply(decl.amountPerUnit)).roundedToCents()
                if (!gross.isPositive) continue
                val foreignRate = settings.foreignWithholdingRate[assetId] ?: BigDecimal.ZERO
                val foreign = Money.of(gross.amount.multiply(foreignRate)).roundedToCents()
                val withholding = if (settings.withholdOnDividends) Money.of((gross - foreign).amount.multiply(rules.withholdingRate)).roundedToCents() else Money.ZERO
                bank.post(accountId, DividendReceived(day, assetId, gross, foreign, withholding, "Dividendo de ${def.name}"))
                report += "Dividendo de ${def.name}: $gross € brutos (retención $withholding €)"
            }
        }
    }

    /** Retención del 19 % sobre la ganancia al reembolsar un fondo; se practica al liquidarse el reembolso. */
    private fun processRedemptionWithholdings(accountId: AccountId, day: LocalDate, report: MutableList<String>) {
        if (!settings.withholdOnFundRedemptions) return
        val account = bank.get(accountId)
        val records = account.ledger.records
        val state = account.state
        for (r in records) {
            val sale = r.event as? Sale ?: continue
            val settlement = sale.settlementDate ?: sale.date
            if (settlement.isAfter(day)) continue
            val def = assets(sale.assetId) ?: continue
            if (def.kind != AssetKind.INDEX_FUND) continue
            if (state.withholdings.any { it.saleSeq == r.seq }) continue
            val realized = state.realizedSales.firstOrNull { it.saleSeq == r.seq } ?: continue
            val gain = realized.gain
            if (!gain.isPositive) continue
            val amount = Money.of(gain.amount.multiply(rules.withholdingRate)).roundedToCents()
            if (!amount.isPositive) continue
            bank.post(accountId, WithholdingPaid(day, sale.assetId, amount, r.seq, "Retención 19 % por reembolso de ${def.name}"))
            report += "Retención de $amount € por el reembolso de ${def.name}"
        }
    }

    private fun processSettlements(accountId: AccountId, day: LocalDate, report: MutableList<String>) {
        val account = bank.get(accountId)
        val firstYear = (account.ledger.records.firstOrNull()?.event?.date?.year ?: return).coerceAtLeast(2025)
        val taxYear = day.year - 1
        if (taxYear < firstYear) return
        val campaign = TaxCalendar.campaign(taxYear)
        val refundDate = campaign.end.plusMonths(settings.refundMonthsAfterCampaign.toLong())
        val dates = setOf(campaign.chargeDate, campaign.secondInstallment, refundDate)
        if (day !in dates) return

        val posted = account.state.taxPostings
        fun already(installment: Int) = posted.any { it.taxYear == taxYear && it.installment == installment }

        val result = calculator.compute(account.ledger.records.filter { !it.event.date.isAfter(campaign.end) }, taxYear)
            .lastOrNull { it.year == taxYear } ?: return
        val r = result.result
        if (r.isPositive) {
            val first = if (settings.payment == PaymentMode.SPLIT_60_40) Money.of(r.amount.multiply(BigDecimal("0.60"))).roundedToCents() else r
            if (day == campaign.chargeDate && !already(1)) {
                bank.post(accountId, TaxSettlement(day, taxYear, 1, -first, "Pago del IRPF $taxYear" + if (settings.payment == PaymentMode.SPLIT_60_40) " (1.er plazo, 60 %)" else ""))
                report += "IRPF $taxYear: pago de $first €"
            }
            val second = r - first
            if (second.isPositive && day == campaign.secondInstallment && !already(2)) {
                bank.post(accountId, TaxSettlement(day, taxYear, 2, -second, "Pago del IRPF $taxYear (2.º plazo, 40 %)"))
                report += "IRPF $taxYear: segundo pago de $second €"
            }
        } else if (r.isNegative && day == refundDate && !already(1)) {
            bank.post(accountId, TaxSettlement(day, taxYear, 1, -r, "Devolución del IRPF $taxYear"))
            report += "IRPF $taxYear: devolución de ${-r} €"
        }
    }
}

/** Avisos informativos (no se presenta ningún modelo): umbrales de los modelos 720 y 721. */
object InformativeNotices {
    const val THRESHOLD_EUR = 50_000

    fun forPortfolio(valuesByKind: Map<AssetKind, Money>): List<String> {
        val notices = ArrayList<String>()
        val limit = Money.of(THRESHOLD_EUR.toLong())
        val crypto = valuesByKind[AssetKind.CRYPTO] ?: Money.ZERO
        if (crypto > limit) {
            notices += "Tus criptomonedas superan 50.000 €: si estuvieran custodiadas en el extranjero habría que valorar el modelo 721."
        }
        val securities = (valuesByKind[AssetKind.ETF] ?: Money.ZERO) + (valuesByKind[AssetKind.INDEX_FUND] ?: Money.ZERO)
        if (securities > limit) {
            notices += "Tus fondos y ETF superan 50.000 €: si estuvieran depositados en el extranjero habría que valorar el modelo 720."
        }
        return notices
    }
}
