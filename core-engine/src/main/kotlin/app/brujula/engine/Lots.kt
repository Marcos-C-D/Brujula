package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Lote de compra. Conserva fecha y coste original para la fiscalidad (FIFO, regla de recompra,
 * traspasos) y el coste/unidades que quedan tras ventas parciales.
 */
data class Lot(
    val assetId: AssetId,
    val acquisitionDate: LocalDate,
    val acquisitionSeq: Long,
    val unitsOriginal: Units,
    val unitsRemaining: Units,
    val costOriginal: Money,
    val costRemaining: Money,
    /** Hasta esta fecha las participaciones están bloqueadas (liquidación de fondos); null = disponibles. */
    val settlesOn: LocalDate? = null,
) {
    fun isSettled(asOf: LocalDate): Boolean = settlesOn == null || !settlesOn.isAfter(asOf)
}

/** Parte de un lote consumida por una venta. */
data class LotConsumption(
    val acquisitionSeq: Long,
    val acquisitionDate: LocalDate,
    val units: Units,
    val cost: Money,
)

/** Resultado de una venta: valor de transmisión neto, coste fiscal consumido (FIFO) y ganancia. */
data class RealizedSale(
    val saleSeq: Long,
    val date: LocalDate,
    val assetId: AssetId,
    val units: Units,
    val netProceeds: Money,
    val cost: Money,
    val consumptions: List<LotConsumption>,
) {
    val gain: Money get() = netProceeds - cost
}

data class FifoResult(val consumptions: List<LotConsumption>, val remainingLots: List<Lot>)

/**
 * Consumo FIFO de lotes (el más antiguo primero; el orden de la lista ya es cronológico).
 *
 * Invariante clave: nunca se pierde ni se crea un céntimo. En ventas parciales el coste se reparte
 * redondeando a céntimos y, cuando un lote se agota, se toma TODO el coste restante, de modo que la
 * suma de los costes consumidos de un lote es exactamente su coste original.
 */
object Fifo {
    fun consume(lots: List<Lot>, toSell: Units, eligible: (Lot) -> Boolean = { true }): FifoResult {
        var pending = toSell.value
        val consumptions = ArrayList<LotConsumption>()
        val remaining = ArrayList<Lot>()
        for (lot in lots) {
            if (pending.signum() == 0 || !eligible(lot)) {
                remaining += lot
                continue
            }
            val available = lot.unitsRemaining.value
            if (available.compareTo(pending) <= 0) {
                consumptions += LotConsumption(lot.acquisitionSeq, lot.acquisitionDate, lot.unitsRemaining, lot.costRemaining)
                pending = pending.subtract(available)
            } else {
                val cost = Money.of(
                    lot.costRemaining.amount.multiply(pending).divide(available, 2, RoundingMode.HALF_UP),
                )
                consumptions += LotConsumption(lot.acquisitionSeq, lot.acquisitionDate, Units.of(pending), cost)
                remaining += lot.copy(
                    unitsRemaining = Units.of(available.subtract(pending)),
                    costRemaining = lot.costRemaining - cost,
                )
                pending = java.math.BigDecimal.ZERO
            }
        }
        check(pending.signum() == 0) { "Se intentó consumir más unidades de las disponibles" }
        return FifoResult(consumptions, remaining)
    }
}

/** Posición agregada de un activo en una cuenta. */
data class Position(val assetId: AssetId, val units: Units, val costBasis: Money) {
    /** Precio medio de compra (coste fiscal / unidades), con 6 decimales; solo para mostrar. */
    val averageCost: Money
        get() = Money.of(costBasis.amount.divide(units.value, 6, RoundingMode.HALF_UP))

    fun marketValue(price: Money): Money = (price * units).roundedToCents()

    fun unrealizedGain(price: Money): Money = marketValue(price) - costBasis
}
