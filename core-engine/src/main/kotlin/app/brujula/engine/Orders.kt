package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class OrderSide { BUY, SELL, TRANSFER }

enum class OrderType { MARKET, LIMIT, STOP }

/** Estado de una orden. SOLICITADA y ACEPTADA coinciden en el momento de registrarla (ACCEPTED). */
enum class OrderStatus { ACCEPTED, EXECUTED, SETTLED, CANCELLED, EXPIRED, REJECTED }

/**
 * Condiciones de una orden.
 * - BUY: [amount] a invertir (importe en euros). SELL y TRANSFER: [units].
 * - LIMIT: [limitPrice] es el precio máximo (compra) o mínimo (venta). STOP (solo venta): [limitPrice] es el precio de activación.
 * - [expiresOn]: última fecha en que puede ejecutarse; null = hasta cancelar.
 * - [fees]: comisión cotizada en la compra (se reserva junto con el importe).
 */
data class OrderSpec(
    val side: OrderSide,
    val type: OrderType,
    val assetId: AssetId,
    val toAssetId: AssetId? = null,
    val amount: Money? = null,
    val units: Units? = null,
    val limitPrice: Money? = null,
    val expiresOn: LocalDate? = null,
    val fees: Money = Money.ZERO,
    val placedAt: Instant? = null,
    val scheduleId: String? = null,
    val periodKey: String? = null,
) {
    init {
        if (fees.isNegative || !fees.isWholeCents) throw InvalidEntryException("Comisión de orden no válida")
        when (side) {
            OrderSide.BUY -> {
                if (amount == null || !amount.isPositive || !amount.isWholeCents) throw InvalidEntryException("La compra necesita un importe positivo en céntimos")
                if (units != null || toAssetId != null) throw InvalidEntryException("Una compra se indica por importe")
            }
            OrderSide.SELL -> {
                if (units == null || !units.isPositive) throw InvalidEntryException("La venta necesita unidades positivas")
                if (amount != null || toAssetId != null) throw InvalidEntryException("Una venta se indica por unidades")
            }
            OrderSide.TRANSFER -> {
                if (units == null || !units.isPositive) throw InvalidEntryException("El traspaso necesita unidades positivas")
                if (toAssetId == null || toAssetId == assetId) throw InvalidEntryException("El traspaso necesita un fondo de destino distinto")
                if (type != OrderType.MARKET) throw InvalidEntryException("Los traspasos son siempre a valor liquidativo")
                if (amount != null) throw InvalidEntryException("Un traspaso se indica por unidades")
            }
        }
        when (type) {
            OrderType.MARKET -> if (limitPrice != null) throw InvalidEntryException("Una orden a mercado no lleva precio")
            OrderType.LIMIT -> if (limitPrice == null || !limitPrice.isPositive) throw InvalidEntryException("La orden limitada necesita un precio positivo")
            OrderType.STOP -> {
                if (limitPrice == null || !limitPrice.isPositive) throw InvalidEntryException("El stop-loss necesita un precio positivo")
                if (side != OrderSide.SELL) throw InvalidEntryException("El stop-loss solo existe para ventas")
            }
        }
    }

    /** Efectivo que queda reservado mientras la orden está pendiente (solo compras). */
    val reservedCash: Money get() = if (side == OrderSide.BUY) amount!! + fees else Money.ZERO
}

data class OrderState(
    val orderId: String,
    val spec: OrderSpec,
    val placedOn: LocalDate,
    val status: OrderStatus,
    val executedOn: LocalDate? = null,
    val executionSeq: Long? = null,
    val executionPrice: Money? = null,
    val settlesOn: LocalDate? = null,
    val settledOn: LocalDate? = null,
    val closedOn: LocalDate? = null,
    val closeReason: String? = null,
)

/** Importe de una venta ya cobrado pero aún no disponible hasta [availableOn] (liquidación). */
data class UnsettledProceeds(val saleSeq: Long, val amount: Money, val availableOn: LocalDate)

/** Traspaso entre fondos: no realiza ganancia; el coste y las fechas de adquisición pasan al destino. */
data class TransferRecord(
    val seq: Long, val from: AssetId, val to: AssetId, val unitsOut: Units, val unitsIn: Units, val cost: Money,
)

enum class Frequency { WEEKLY, MONTHLY }

/**
 * Plan periódico: compra de un importe fijo o venta de unidades fijas, siempre a mercado.
 * Semanal: [dayOfWeek] (1 = lunes … 7 = domingo). Mensual: [dayOfMonth] (1-31; en meses cortos, el último día).
 */
data class ScheduledPlan(
    val id: String,
    val side: OrderSide,
    val assetId: AssetId,
    val amount: Money? = null,
    val units: Units? = null,
    val frequency: Frequency,
    val dayOfWeek: Int = 1,
    val dayOfMonth: Int = 1,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val paused: Boolean = false,
) {
    init {
        if (id.isBlank()) throw InvalidAccountException("El plan necesita un identificador")
        when (side) {
            OrderSide.BUY -> if (amount == null || !amount.isPositive || !amount.isWholeCents || units != null)
                throw InvalidAccountException("La compra periódica necesita un importe positivo en céntimos")
            OrderSide.SELL -> if (units == null || !units.isPositive || amount != null)
                throw InvalidAccountException("La venta periódica necesita unidades positivas")
            OrderSide.TRANSFER -> throw InvalidAccountException("Los traspasos no se programan")
        }
        if (dayOfWeek !in 1..7) throw InvalidAccountException("Día de la semana no válido")
        if (dayOfMonth !in 1..31) throw InvalidAccountException("Día del mes no válido")
        if (endDate != null && endDate.isBefore(startDate)) throw InvalidAccountException("El plan termina antes de empezar")
    }

    /** Fechas de ejecución del plan hasta [until] (incluida), en orden, con su clave de periodo. */
    fun occurrences(until: LocalDate): List<Pair<String, LocalDate>> {
        val last = if (endDate != null && endDate.isBefore(until)) endDate else until
        val out = ArrayList<Pair<String, LocalDate>>()
        when (frequency) {
            Frequency.MONTHLY -> {
                var p = YearMonth.from(startDate)
                while (!p.atDay(1).isAfter(last)) {
                    val d = p.atDay(minOf(dayOfMonth, p.lengthOfMonth()))
                    if (!d.isBefore(startDate) && !d.isAfter(last)) out += p.toString() to d
                    p = p.plusMonths(1)
                }
            }
            Frequency.WEEKLY -> {
                var d = startDate
                while (d.dayOfWeek != DayOfWeek.of(dayOfWeek)) d = d.plusDays(1)
                while (!d.isAfter(last)) { out += d.toString() to d; d = d.plusDays(7) }
            }
        }
        return out
    }

    fun orderId(periodKey: String): String = "plan-$id-$periodKey"
}
