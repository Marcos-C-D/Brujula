package app.brujula.app

import app.brujula.engine.AccountState
import app.brujula.engine.LedgerRecord
import app.brujula.market.PriceSeries
import app.brujula.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Patrimonio en una fecha. [holdings] y [netWorth] son null si faltaba el precio de algún activo en cartera (no se inventa). */
data class NetWorthPoint(val date: LocalDate, val cash: Money, val holdings: Money?, val netWorth: Money?, val contributions: Money)

object History {

    fun sampleDates(from: LocalDate, to: LocalDate, maxPoints: Int = 300): List<LocalDate> {
        if (to.isBefore(from)) return listOf(to)
        val days = ChronoUnit.DAYS.between(from, to)
        val step = maxOf(1L, (days + maxPoints - 1) / maxPoints)
        val out = ArrayList<LocalDate>()
        var d = from
        while (d.isBefore(to)) { out += d; d = d.plusDays(step) }
        out += to
        return out
    }

    /** Evolución del patrimonio reproduciendo el libro una sola vez, sin saltarse ningún movimiento. */
    fun compute(records: List<LedgerRecord>, lookup: PriceLookup, to: LocalDate, maxPoints: Int = 300): List<NetWorthPoint> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedBy { it.seq }
        val from = sorted.first().event.date
        var state = AccountState.EMPTY
        var next = 0
        val out = ArrayList<NetWorthPoint>()
        for (date in sampleDates(from, to, maxPoints)) {
            while (next < sorted.size && !sorted[next].event.date.isAfter(date)) {
                state = state.apply(sorted[next].seq, sorted[next].event)
                next++
            }
            var holdings: Money? = Money.ZERO
            for (p in state.positions) {
                val quote = lookup.priceOn(p.assetId, date)
                holdings = if (quote == null || holdings == null) null else holdings + p.marketValue(quote.price)
            }
            out += NetWorthPoint(date, state.cash, holdings, holdings?.let { state.cash + it }, state.contributions)
        }
        return out
    }
}

data class BenchmarkComparison(
    val name: String,
    val benchmarkReturnPct: BigDecimal?,
    val accountTwrPct: BigDecimal?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
)

data class AccountStats(
    val days: Long,
    val contributions: Money,
    val netWorth: Money?,
    /** (patrimonio - aportaciones) / aportaciones. No pondera por el momento de cada aportación. */
    val totalReturnPct: BigDecimal?,
    /** Rentabilidad ponderada por tiempo: elimina el efecto de los ingresos mensuales. */
    val twrPct: BigDecimal?,
    val annualizedPct: BigDecimal?,
    val realizedGain: Money,
    val dividendsNet: Money,
    val taxesPaid: Money,
    val benchmark: BenchmarkComparison?,
)

object Stats {
    private fun pct(r: Double): BigDecimal = BigDecimal(r * 100.0).setScale(2, RoundingMode.HALF_UP)

    fun of(points: List<NetWorthPoint>, state: AccountState, benchmark: Pair<String, PriceSeries>? = null): AccountStats {
        val complete = points.filter { it.netWorth != null }
        val first = complete.firstOrNull()
        val last = complete.lastOrNull()
        val days = if (first != null && last != null) ChronoUnit.DAYS.between(first.date, last.date) else 0L

        var chain = 1.0
        var usable = false
        for (i in 1 until complete.size) {
            val prev = complete[i - 1].netWorth!!
            val cur = complete[i]
            if (!prev.isPositive) continue
            val flow = cur.contributions - complete[i - 1].contributions
            val ratio = (cur.netWorth!! - flow).amount.divide(prev.amount, MathContext(20)).toDouble()
            chain *= ratio
            usable = true
        }
        val twr = if (usable) chain - 1.0 else null
        val annualized = if (twr != null && days >= 30 && chain > 0.0) Math.pow(chain, 365.0 / days) - 1.0 else null

        val bench = if (benchmark != null && first != null && last != null) {
            val b = benchmark.second
            val a = b.onOrBefore(first.date)
            val z = b.onOrBefore(last.date)
            val r = if (a != null && z != null && a.date.isBefore(z.date)) {
                z.close.divide(a.close, MathContext(20)).subtract(BigDecimal.ONE).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
            } else null
            BenchmarkComparison(benchmark.first, r, twr?.let { pct(it) }, first.date, last.date)
        } else null

        val netWorth = last?.netWorth
        val contrib = state.contributions
        val total = if (netWorth != null && contrib.isPositive) {
            (netWorth - contrib).amount.multiply(BigDecimal(100)).divide(contrib.amount, MathContext(20)).setScale(2, RoundingMode.HALF_UP)
        } else null
        return AccountStats(
            days, contrib, netWorth, total, twr?.let { pct(it) }, annualized?.let { pct(it) },
            state.realizedGain, state.dividendsNet,
            -(state.taxPostings.fold(Money.ZERO) { acc, p -> acc + p.amount }),
            bench,
        )
    }
}
