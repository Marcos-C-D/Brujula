package app.brujula.trading

import app.brujula.engine.OrderSide
import app.brujula.market.AssetDefinition
import app.brujula.market.AssetKind
import app.brujula.market.MarketCalendar
import app.brujula.market.Markets
import app.brujula.market.PriceSeries
import app.brujula.model.AssetId
import app.brujula.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime

class TradingException(message: String) : RuntimeException(message)

/** Precios en EUR por activo (la capa de datos convierte divisas antes). */
fun interface PriceSource {
    fun series(assetId: AssetId): PriceSeries?
}

/** Comisión = fija + porcentaje del importe, con un mínimo. Por defecto no hay comisiones (configurable). */
data class FeeRule(val fixed: Money = Money.ZERO, val percent: BigDecimal = BigDecimal.ZERO, val min: Money = Money.ZERO)

class FeeSchedule(private val rules: Map<Pair<AssetKind, OrderSide>, FeeRule> = emptyMap()) {
    fun fee(kind: AssetKind, side: OrderSide, amount: Money): Money {
        val rule = rules[kind to side] ?: return Money.ZERO
        val pct = Money.of(amount.amount.multiply(rule.percent).divide(BigDecimal(100), 10, RoundingMode.HALF_UP))
        val raw = (rule.fixed + pct).let { if (it < rule.min) rule.min else it }
        return raw.roundedToCents()
    }
}

/** Plazo de liquidación en días hábiles de un mercado a partir de una fecha. */
data class LagRule(val marketId: String, val from: LocalDate, val days: Int)

/**
 * Plazos de liquidación configurables y con fecha de vigencia:
 * - ETF: EE. UU. T+1 desde el 28-05-2024; Europa (Xetra, Euronext, Londres) T+2 hasta el 10-10-2027 y T+1 desde el 11-10-2027.
 * - Fondos: compra 2 días, reembolso 3 y traspaso 4 (días hábiles; configurables).
 * - Bitcoin: sin plazo.
 */
class SettlementRules(
    val fundBuyDays: Int = 2,
    val fundRedeemDays: Int = 3,
    val fundTransferDays: Int = 4,
    private val etfRules: List<LagRule> = DEFAULT_ETF_RULES,
) {
    fun etfLagDays(marketId: String?, tradeDate: LocalDate): Int =
        etfRules.filter { it.marketId == marketId && !it.from.isAfter(tradeDate) }.maxByOrNull { it.from }?.days ?: 2

    fun settlementDate(def: AssetDefinition, side: OrderSide, tradeDate: LocalDate): LocalDate {
        val calendar: MarketCalendar? = def.marketId?.let { Markets.byId(it) }
        val days = when (def.kind) {
            AssetKind.CRYPTO -> return tradeDate
            AssetKind.ETF -> etfLagDays(def.marketId, tradeDate)
            AssetKind.INDEX_FUND -> when (side) {
                OrderSide.BUY -> fundBuyDays
                OrderSide.SELL -> fundRedeemDays
                OrderSide.TRANSFER -> fundTransferDays
            }
        }
        return calendar?.addTradingDays(tradeDate, days) ?: addWeekdays(tradeDate, days)
    }

    private fun addWeekdays(date: LocalDate, n: Int): LocalDate {
        var d = date
        repeat(n) { do { d = d.plusDays(1) } while (d.dayOfWeek.value > 5) }
        return d
    }

    companion object {
        val DEFAULT_ETF_RULES = listOf(
            LagRule("NYSE", LocalDate.of(1900, 1, 1), 2),
            LagRule("NYSE", LocalDate.of(2024, 5, 28), 1),
        ) + listOf("XETRA", "EURONEXT", "LSE").flatMap {
            listOf(LagRule(it, LocalDate.of(1900, 1, 1), 2), LagRule(it, LocalDate.of(2027, 10, 11), 1))
        }
    }
}

data class TradingConfig(
    val fees: FeeSchedule = FeeSchedule(),
    val settlement: SettlementRules = SettlementRules(),
    /** Hora de corte de los fondos (hora de Madrid): antes se valora el mismo día; después, el siguiente día hábil. */
    val fundCutoff: LocalTime = LocalTime.of(14, 0),
    val minOrder: Money = Money.of("1.00"),
    val unitScaleEtf: Int = 6,
    val unitScaleFund: Int = 6,
    val unitScaleCrypto: Int = 8,
) {
    fun unitScale(kind: AssetKind): Int = when (kind) {
        AssetKind.ETF -> unitScaleEtf
        AssetKind.INDEX_FUND -> unitScaleFund
        AssetKind.CRYPTO -> unitScaleCrypto
    }
}
