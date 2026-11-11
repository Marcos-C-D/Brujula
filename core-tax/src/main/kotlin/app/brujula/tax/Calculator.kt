package app.brujula.tax

import app.brujula.engine.AccountState
import app.brujula.engine.Ledger
import app.brujula.engine.LedgerRecord
import app.brujula.engine.Purchase
import app.brujula.engine.RealizedSale
import app.brujula.market.AssetDefinition
import app.brujula.market.AssetKind
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Pérdida de pasado que todavía puede compensarse (en positivo). Válida hasta cuatro ejercicios después del de origen. */
data class Carry(val originYear: Int, val remaining: Money)

/** Una venta tal y como computa en el IRPF. */
data class SaleTax(
    val saleSeq: Long,
    val date: LocalDate,
    val assetId: AssetId,
    val gain: Money,
    /** Parte del resultado que se computa este ejercicio (una pérdida aplazada por recompra no computa). */
    val computable: Money,
    val deferredLoss: Money,
    val note: String,
)

data class TaxYearResult(
    val year: Int,
    val sales: List<SaleTax>,
    /** Pérdidas aplazadas que se integran este año por haberse vendido las participaciones recompradas (en negativo). */
    val integratedDeferred: Money,
    val gainsLossesBalance: Money,
    val dividendsGross: Money,
    val foreignTaxPaid: Money,
    /** Pérdida del año compensada con dividendos (límite 25 %). */
    val crossCompensation: Money,
    /** Pérdidas de años anteriores compensadas este año. */
    val carriedUsed: Money,
    val savingsBase: Money,
    val otherSavingsBase: Money,
    /** Cuota íntegra atribuible a la simulación (diferencia respecto a las otras rentas del ahorro). */
    val grossTax: Money,
    val doubleTaxDeduction: Money,
    val withholdings: Money,
    /** Positivo = a pagar; negativo = a devolver. */
    val result: Money,
    val carryForward: List<Carry>,
)

/**
 * Cálculo del IRPF de la base del ahorro de una cuenta, ejercicio a ejercicio, a partir de su libro de movimientos.
 * Es una función pura del libro: el resultado de un ejercicio solo usa información anterior al fin de su campaña, de modo que
 * una vez presentada no cambia aunque luego haya más movimientos (no se simulan declaraciones complementarias).
 */
class TaxCalculator(
    private val rules: TaxRules,
    private val settings: TaxSettings,
    private val assets: (AssetId) -> AssetDefinition?,
) {
    private class Deferral(
        val saleSeq: Long, val saleDate: LocalDate, val assetId: AssetId,
        val total: Money, val unitsProtected: Units, val lotUnits: MutableMap<Long, Units>, var integrated: Money = Money.ZERO,
    )

    fun compute(records: List<LedgerRecord>, throughYear: Int): List<TaxYearResult> {
        if (records.isEmpty()) return emptyList()
        val firstYear = records.first().event.date.year.coerceAtLeast(2025)
        val states = HashMap<LocalDate, AccountState>()
        fun stateAt(date: LocalDate): AccountState = states.getOrPut(date) {
            Ledger.replay(AccountId("irpf"), records.filter { !it.event.date.isAfter(date) }.map { it.event }).state
        }
        val purchaseSeqsByAsset: Map<AssetId, List<LedgerRecord>> =
            records.filter { it.event is Purchase }.groupBy { (it.event as Purchase).assetId }

        val deferrals = ArrayList<Deferral>()
        val results = ArrayList<TaxYearResult>()
        var carries: List<Carry> = emptyList()

        for (year in firstYear..throughYear) {
            val asOf = TaxCalendar.campaign(year).end
            val state = stateAt(asOf)
            val yearSales = state.realizedSales.filter { it.date.year == year }.sortedBy { it.saleSeq }

            // --- 1. ganancias y pérdidas de las ventas del año (con aplazamiento por recompra) y pérdidas aplazadas que se liberan ---
            val saleTaxes = ArrayList<SaleTax>()
            var integratedTotal = Money.ZERO
            for (s in yearSales) {
                // pérdidas aplazadas anteriores que se liberan por esta venta
                for (d in deferrals) {
                    if (d.assetId != s.assetId || !s.date.isAfter(d.saleDate)) continue
                    for (c in s.consumptions) {
                        val protectedUnits = d.lotUnits[c.acquisitionSeq] ?: continue
                        val part = if (c.units < protectedUnits) c.units else protectedUnits
                        if (!part.isPositive) continue
                        val amount = d.total.amount.multiply(part.value).divide(d.unitsProtected.value, 2, RoundingMode.HALF_UP).cents()
                        val remainingDeferred = d.total - d.integrated
                        val take = if (amount > remainingDeferred) remainingDeferred else amount
                        d.integrated = d.integrated + take
                        d.lotUnits[c.acquisitionSeq] = protectedUnits - part
                        integratedTotal = integratedTotal - take
                    }
                }
                saleTaxes += analyseSale(s, asOf, ::stateAt, purchaseSeqsByAsset, deferrals)
            }

            val computableSum = saleTaxes.fold(Money.ZERO) { acc, t -> acc + t.computable }
            val gpBalance = computableSum + integratedTotal

            // --- 2. dividendos y retenciones del año ---
            val divs = state.dividends.filter { it.date.year == year }
            val dividendsGross = divs.fold(Money.ZERO) { acc, d -> acc + d.gross }
            val foreignTax = divs.fold(Money.ZERO) { acc, d -> acc + d.foreignTax }
            val withholdings = divs.fold(Money.ZERO) { acc, d -> acc + d.withholding } +
                state.withholdings.filter { it.date.year == year }.fold(Money.ZERO) { acc, w -> acc + w.amount }

            // --- 3. integración y compensación (art. 49 LIRPF) ---
            val valid = carries.filter { year - it.originYear <= rules.carryYears }.sortedBy { it.originYear }
            val gpPositive = if (gpBalance.isPositive) gpBalance else Money.ZERO
            val gpNegative = if (gpBalance.isNegative) -gpBalance else Money.ZERO
            val crossCap = Money.of(dividendsGross.amount.multiply(rules.compensationLimit)).roundedToCents()

            val crossUsed = if (gpNegative < crossCap) gpNegative else crossCap
            val newCarryAmount = gpNegative - crossUsed
            var rcmLeft = dividendsGross - crossUsed
            var gpLeft = gpPositive
            var capLeft = crossCap - crossUsed
            var carriedUsed = Money.ZERO
            val updated = ArrayList<Carry>()
            // primero contra ganancias del año; después contra dividendos con el cupo del 25 % que quede
            for (c in valid) {
                var remaining = c.remaining
                val againstGp = if (remaining < gpLeft) remaining else gpLeft
                remaining = remaining - againstGp
                gpLeft = gpLeft - againstGp
                carriedUsed = carriedUsed + againstGp
                if (remaining.isPositive) {
                    var againstRcm = remaining
                    if (againstRcm > capLeft) againstRcm = capLeft
                    if (againstRcm > rcmLeft) againstRcm = rcmLeft
                    remaining = remaining - againstRcm
                    capLeft = capLeft - againstRcm
                    rcmLeft = rcmLeft - againstRcm
                    carriedUsed = carriedUsed + againstRcm
                }
                if (remaining.isPositive) updated += Carry(c.originYear, remaining)
            }
            if (newCarryAmount.isPositive) updated += Carry(year, newCarryAmount)
            carries = updated

            val base = gpLeft + rcmLeft
            val other = settings.otherSavingsBase[year] ?: Money.ZERO
            val grossTax = rules.tax(year, other + base) - rules.tax(year, other)
            val deduction = if (foreignTax.isPositive && base.isPositive && grossTax.isPositive) {
                val limit = Money.of(grossTax.amount.multiply(dividendsGross.amount).divide(base.amount, 2, RoundingMode.HALF_UP)).roundedToCents()
                val capped = if (limit > grossTax) grossTax else limit
                if (foreignTax < capped) foreignTax else capped
            } else Money.ZERO

            results += TaxYearResult(
                year, saleTaxes, integratedTotal, gpBalance, dividendsGross, foreignTax, crossUsed, carriedUsed, base, other,
                grossTax, deduction, withholdings, grossTax - deduction - withholdings, carries,
            )
        }
        return results
    }

    private fun analyseSale(
        s: RealizedSale, asOf: LocalDate, stateAt: (LocalDate) -> AccountState,
        purchases: Map<AssetId, List<LedgerRecord>>, deferrals: MutableList<Deferral>,
    ): SaleTax {
        val gain = s.gain
        if (!gain.isNegative) return SaleTax(s.saleSeq, s.date, s.assetId, gain, gain, Money.ZERO, "Ganancia patrimonial")

        val loss = -gain
        val window = windowFor(s.assetId, s.date) ?: return SaleTax(s.saleSeq, s.date, s.assetId, gain, gain, Money.ZERO, "Pérdida patrimonial")
        val (from, to) = window
        val evalDate = if (to.isAfter(asOf)) asOf else to
        val atEval = stateAt(evalDate)

        val windowSeqs = (purchases[s.assetId] ?: emptyList())
            .filter { !it.event.date.isBefore(from) && !it.event.date.isAfter(evalDate) }
            .map { it.seq }.toSet()
        val heldLots = (atEval.lotsByAsset[s.assetId] ?: emptyList())
            .filter { it.acquisitionSeq in windowSeqs }
            .sortedWith(compareBy({ it.acquisitionDate }, { it.acquisitionSeq }))
        val held = heldLots.fold(Units.ZERO) { acc, l -> acc + l.unitsRemaining }
        if (!held.isPositive) return SaleTax(s.saleSeq, s.date, s.assetId, gain, gain, Money.ZERO, "Pérdida patrimonial")

        val protectedUnits = if (held < s.units) held else s.units
        val deferred = Money.of(loss.amount.multiply(protectedUnits.value).divide(s.units.value, 2, RoundingMode.HALF_UP)).roundedToCents()
        val alloc = LinkedHashMap<Long, Units>()
        var toAllocate = protectedUnits
        for (lot in heldLots) {
            if (!toAllocate.isPositive) break
            val take = if (lot.unitsRemaining < toAllocate) lot.unitsRemaining else toAllocate
            alloc[lot.acquisitionSeq] = take
            toAllocate = toAllocate - take
        }
        deferrals += Deferral(s.saleSeq, s.date, s.assetId, deferred, protectedUnits, alloc)
        val computable = -(loss - deferred)
        return SaleTax(
            s.saleSeq, s.date, s.assetId, gain, computable, deferred,
            "Pérdida parcialmente no computable por recompra (art. 33.5 LIRPF): ${deferred} € aplazados hasta vender las participaciones recompradas",
        )
    }

    /** Ventana de la regla de recompra: 2 meses (valores cotizados) o 1 año (no cotizados); null si no se aplica. */
    private fun windowFor(assetId: AssetId, date: LocalDate): Pair<LocalDate, LocalDate>? {
        val def = assets(assetId) ?: return null
        return when (def.kind) {
            AssetKind.ETF -> date.minusMonths(rules.repurchaseMonthsListed.toLong()) to date.plusMonths(rules.repurchaseMonthsListed.toLong())
            AssetKind.INDEX_FUND -> date.minusYears(rules.repurchaseYearsUnlisted.toLong()) to date.plusYears(rules.repurchaseYearsUnlisted.toLong())
            AssetKind.CRYPTO -> if (settings.applyRepurchaseToCrypto) {
                date.minusMonths(rules.repurchaseMonthsListed.toLong()) to date.plusMonths(rules.repurchaseMonthsListed.toLong())
            } else null
        }
    }
}
