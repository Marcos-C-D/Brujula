package app.brujula.app

import app.brujula.engine.AccountState
import app.brujula.market.AssetDefinition
import app.brujula.market.Freshness
import app.brujula.market.Markets
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

data class PositionRow(
    val assetId: AssetId,
    val name: String,
    val category: String,
    val units: Units,
    val averageCost: Money,
    val costBasis: Money,
    val price: Money?,
    val priceDate: LocalDate?,
    val value: Money?,
    val gain: Money?,
    val gainPct: BigDecimal?,
    /** El precio es más antiguo de lo esperado según el calendario del mercado. */
    val stale: Boolean,
)

data class Slice(val label: String, val value: Money, val percent: BigDecimal)

data class AccountValuation(
    val cash: Money,
    val availableCash: Money,
    val reserved: Money,
    val unsettled: Money,
    val investedValue: Money,
    val netWorth: Money,
    val costBasis: Money,
    val gain: Money,
    val gainPct: BigDecimal?,
    val contributions: Money,
    /** Rentabilidad total frente a lo aportado (no pondera por tiempo). */
    val totalReturnPct: BigDecimal?,
    val rows: List<PositionRow>,
    /** Activos en cartera sin precio: su valor NO está incluido en el patrimonio. */
    val missingPrices: List<AssetId>,
    val allocationByAsset: List<Slice>,
    val allocationByCategory: List<Slice>,
) {
    val complete: Boolean get() = missingPrices.isEmpty()
}

object Valuation {
    private fun pct(numerator: Money, denominator: Money): BigDecimal? =
        if (!denominator.isPositive) null
        else numerator.amount.multiply(BigDecimal(100)).divide(denominator.amount, MathContext(20)).setScale(2, RoundingMode.HALF_UP)

    fun of(state: AccountState, today: LocalDate, lookup: PriceLookup, catalog: (AssetId) -> AssetDefinition?): AccountValuation {
        val rows = ArrayList<PositionRow>()
        val missing = ArrayList<AssetId>()
        for (p in state.positions) {
            val def = catalog(p.assetId)
            val quote = lookup.priceOn(p.assetId, today)
            if (quote == null) missing += p.assetId
            val value = quote?.let { p.marketValue(it.price) }
            val gain = value?.let { it - p.costBasis }
            val calendar = def?.marketId?.let { Markets.byId(it) }
            rows += PositionRow(
                p.assetId, def?.name ?: p.assetId.value, def?.category ?: "Otros", p.units, p.averageCost, p.costBasis,
                quote?.price, quote?.date, value, gain, gain?.let { pct(it, p.costBasis) },
                stale = quote != null && Freshness.status(quote.date, today, calendar).isStale,
            )
        }
        val invested = rows.fold(Money.ZERO) { acc, r -> acc + (r.value ?: Money.ZERO) }
        val netWorth = state.cash + invested
        val knownCost = rows.filter { it.value != null }.fold(Money.ZERO) { acc, r -> acc + r.costBasis }
        val gain = invested - knownCost

        fun slices(groups: Map<String, Money>): List<Slice> {
            val total = groups.values.fold(Money.ZERO) { acc, v -> acc + v }
            if (!total.isPositive) return emptyList()
            return groups.entries.filter { it.value.isPositive }.sortedByDescending { it.value }
                .map { Slice(it.key, it.value, pct(it.value, total) ?: BigDecimal.ZERO) }
        }
        val byAsset = LinkedHashMap<String, Money>()
        val byCategory = LinkedHashMap<String, Money>()
        if (state.cash.isPositive) { byAsset["Efectivo"] = state.cash; byCategory["Efectivo"] = state.cash }
        for (r in rows) {
            val v = r.value ?: continue
            byAsset[r.name] = (byAsset[r.name] ?: Money.ZERO) + v
            byCategory[r.category] = (byCategory[r.category] ?: Money.ZERO) + v
        }
        return AccountValuation(
            cash = state.cash,
            availableCash = state.availableCash(today),
            reserved = state.reservedCash,
            unsettled = state.unsettledCash(today),
            investedValue = invested,
            netWorth = netWorth,
            costBasis = knownCost,
            gain = gain,
            gainPct = pct(gain, knownCost),
            contributions = state.contributions,
            totalReturnPct = pct(netWorth - state.contributions, state.contributions),
            rows = rows,
            missingPrices = missing,
            allocationByAsset = slices(byAsset),
            allocationByCategory = slices(byCategory),
        )
    }
}
