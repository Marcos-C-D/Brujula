package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.LocalDate
import java.time.YearMonth

/**
 * Estado de una cuenta, derivado por completo de sus eventos. Es inmutable: [apply] devuelve un
 * estado nuevo o lanza una [LedgerException] sin tocar el original (operación atómica).
 */
data class AccountState(
    val cash: Money,
    val lotsByAsset: Map<AssetId, List<Lot>>,
    val realizedSales: List<RealizedSale>,
    val contributions: Money,
    val adjustmentsTotal: Money,
    val creditedPeriods: Set<YearMonth>,
    val lastDate: LocalDate?,
    val entryCount: Int,
) {
    /** Posiciones abiertas (unidades > 0), ordenadas por identificador. */
    val positions: List<Position>
        get() = lotsByAsset.entries
            .sortedBy { it.key.value }
            .mapNotNull { (asset, lots) ->
                if (lots.isEmpty()) null
                else Position(
                    asset,
                    lots.fold(Units.ZERO) { acc, l -> acc + l.unitsRemaining },
                    lots.fold(Money.ZERO) { acc, l -> acc + l.costRemaining },
                )
            }

    fun position(assetId: AssetId): Position? = positions.firstOrNull { it.assetId == assetId }

    fun unitsHeld(assetId: AssetId): Units = position(assetId)?.units ?: Units.ZERO

    /** Coste fiscal de todo lo que sigue en cartera. */
    val investedCost: Money get() = positions.fold(Money.ZERO) { acc, p -> acc + p.costBasis }

    /** Suma de ganancias y pérdidas patrimoniales ya realizadas (antes de impuestos). */
    val realizedGain: Money get() = realizedSales.fold(Money.ZERO) { acc, s -> acc + s.gain }

    /**
     * Valor de las inversiones a los precios dados. Si falta el precio de algún activo en cartera
     * lanza [MissingPriceException]: nunca se estima ni se rellena con datos inventados.
     */
    fun portfolioValue(prices: Map<AssetId, Money>): Money =
        positions.fold(Money.ZERO) { acc, p ->
            val price = prices[p.assetId] ?: throw MissingPriceException(p.assetId)
            acc + p.marketValue(price)
        }

    /** Patrimonio = efectivo (puede ser negativo) + valor de las inversiones. */
    fun netWorth(prices: Map<AssetId, Money>): Money = cash + portfolioValue(prices)

    fun apply(seq: Long, event: LedgerEvent): AccountState {
        if (entryCount == 0) {
            if (event !is OpeningBalance) throw OpeningBalanceException("El primer movimiento de una cuenta debe ser el saldo inicial")
        } else if (event is OpeningBalance) {
            throw OpeningBalanceException("El saldo inicial solo puede registrarse una vez, al principio")
        }
        lastDate?.let { if (event.date.isBefore(it)) throw OutOfOrderEntryException(event.date, it) }

        val base = copy(lastDate = event.date, entryCount = entryCount + 1)
        return when (event) {
            is OpeningBalance -> base.copy(cash = cash + event.amount, contributions = contributions + event.amount)

            is MonthlyIncome -> {
                if (event.period in creditedPeriods) throw DuplicateIncomePeriodException(event.period)
                base.copy(
                    cash = cash + event.amount,
                    contributions = contributions + event.amount,
                    creditedPeriods = creditedPeriods + event.period,
                )
            }

            is Purchase -> {
                val total = event.totalCost
                if (cash < total) throw InsufficientCashException(cash, total)
                val lot = Lot(event.assetId, event.date, seq, event.units, event.units, total, total)
                base.copy(
                    cash = cash - total,
                    lotsByAsset = lotsByAsset + (event.assetId to ((lotsByAsset[event.assetId] ?: emptyList()) + lot)),
                )
            }

            is Sale -> {
                val lots = lotsByAsset[event.assetId] ?: emptyList()
                val held = lots.fold(Units.ZERO) { acc, l -> acc + l.unitsRemaining }
                if (event.units > held) throw InsufficientUnitsException(event.assetId, held, event.units)
                val result = Fifo.consume(lots, event.units)
                val cost = result.consumptions.fold(Money.ZERO) { acc, c -> acc + c.cost }
                val realized = RealizedSale(seq, event.date, event.assetId, event.units, event.netProceeds, cost, result.consumptions)
                base.copy(
                    cash = cash + event.netProceeds,
                    lotsByAsset = lotsByAsset + (event.assetId to result.remainingLots),
                    realizedSales = realizedSales + realized,
                )
            }

            is CashAdjustment -> base.copy(
                cash = cash + event.amount,
                adjustmentsTotal = adjustmentsTotal + event.amount,
            )
        }
    }

    companion object {
        val EMPTY = AccountState(
            cash = Money.ZERO,
            lotsByAsset = emptyMap(),
            realizedSales = emptyList(),
            contributions = Money.ZERO,
            adjustmentsTotal = Money.ZERO,
            creditedPeriods = emptySet(),
            lastDate = null,
            entryCount = 0,
        )
    }
}
