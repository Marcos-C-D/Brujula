package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Estado de una cuenta, derivado por completo de sus eventos. Es inmutable: [apply] devuelve un
 * estado nuevo o lanza una [LedgerException] sin tocar el original (operación atómica).
 *
 * `cash` es el efectivo total (incluye lo vendido pendiente de liquidar). Lo que se puede gastar es
 * [availableCash]: efectivo - reservado por órdenes de compra pendientes - ventas aún sin liquidar.
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
    val orders: Map<String, OrderState> = emptyMap(),
    val unsettled: List<UnsettledProceeds> = emptyList(),
    val transfers: List<TransferRecord> = emptyList(),
    val dividends: List<DividendRecord> = emptyList(),
    val withholdings: List<WithholdingRecord> = emptyList(),
    val taxPostings: List<TaxPosting> = emptyList(),
) {
    /** Dividendos netos cobrados (bruto - impuesto en origen - retención). */
    val dividendsNet: Money get() = dividends.fold(Money.ZERO) { acc, d -> acc + d.gross - d.foreignTax - d.withholding }

    /** Retenciones practicadas al reembolsar fondos. */
    val withholdingsTotal: Money get() = withholdings.fold(Money.ZERO) { acc, w -> acc + w.amount }

    /** Posiciones abiertas (unidades > 0), ordenadas por identificador. Incluyen las unidades aún bloqueadas. */
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

    fun order(orderId: String): OrderState? = orders[orderId]

    val pendingOrders: List<OrderState> get() = orders.values.filter { it.status == OrderStatus.ACCEPTED }

    /** Efectivo reservado por órdenes de compra pendientes. */
    val reservedCash: Money
        get() = pendingOrders.fold(Money.ZERO) { acc, o -> acc + o.spec.reservedCash }

    fun reservedUnits(assetId: AssetId): Units =
        pendingOrders.filter { it.spec.side != OrderSide.BUY && it.spec.assetId == assetId }
            .fold(Units.ZERO) { acc, o -> acc + o.spec.units!! }

    /** Importe vendido que todavía no está disponible en [asOf]. */
    fun unsettledCash(asOf: LocalDate): Money =
        unsettled.filter { it.availableOn.isAfter(asOf) }.fold(Money.ZERO) { acc, u -> acc + u.amount }

    /** Efectivo que se puede gastar en [asOf]. */
    fun availableCash(asOf: LocalDate): Money = cash - reservedCash - unsettledCash(asOf)

    private fun settledUnits(assetId: AssetId, asOf: LocalDate): Units =
        (lotsByAsset[assetId] ?: emptyList()).filter { it.isSettled(asOf) }.fold(Units.ZERO) { acc, l -> acc + l.unitsRemaining }

    /** Unidades vendibles en [asOf]: liquidadas y no reservadas por otras órdenes. */
    fun unitsAvailable(assetId: AssetId, asOf: LocalDate): Units = settledUnits(assetId, asOf) - reservedUnits(assetId)

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

    private fun pendingOrder(id: String, side: OrderSide, asset: AssetId): OrderState {
        val o = orders[id] ?: throw InvalidEntryException("No existe la orden $id")
        if (o.status != OrderStatus.ACCEPTED) throw InvalidEntryException("La orden $id no está pendiente (${o.status})")
        if (o.spec.side != side || o.spec.assetId != asset) throw InvalidEntryException("La operación no corresponde a la orden $id")
        return o
    }

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
                val order = event.orderId?.let { pendingOrder(it, OrderSide.BUY, event.assetId) }
                if (order != null && (event.invested != order.spec.amount || event.fees != order.spec.fees)) {
                    throw InvalidEntryException("El importe de la compra no coincide con la orden ${order.orderId}")
                }
                val available = cash - (reservedCash - (order?.spec?.reservedCash ?: Money.ZERO)) - unsettledCash(event.date)
                if (available < total) throw InsufficientCashException(available, total)
                val lot = Lot(
                    event.assetId, event.date, seq, event.units, event.units, total, total,
                    settlesOn = if (event.lockUnits) event.settlementDate else null,
                )
                val lots = ((lotsByAsset[event.assetId] ?: emptyList()) + lot)
                base.copy(
                    cash = cash - total,
                    lotsByAsset = lotsByAsset + (event.assetId to lots),
                    orders = executed(order, event.date, seq, event.price, event.settlementDate),
                )
            }

            is Sale -> {
                val order = event.orderId?.let { pendingOrder(it, OrderSide.SELL, event.assetId) }
                if (order != null && event.units != order.spec.units) {
                    throw InvalidEntryException("Las unidades de la venta no coinciden con la orden ${order.orderId}")
                }
                val lots = lotsByAsset[event.assetId] ?: emptyList()
                val available = settledUnits(event.assetId, event.date) -
                    (reservedUnits(event.assetId) - (order?.spec?.units ?: Units.ZERO))
                if (event.units > available) throw InsufficientUnitsException(event.assetId, available, event.units)
                val result = Fifo.consume(lots, event.units) { it.isSettled(event.date) }
                val cost = result.consumptions.fold(Money.ZERO) { acc, c -> acc + c.cost }
                val realized = RealizedSale(seq, event.date, event.assetId, event.units, event.netProceeds, cost, result.consumptions)
                val pending = unsettled.filter { it.availableOn.isAfter(event.date) }.toMutableList()
                event.settlementDate?.let { sd ->
                    if (sd.isAfter(event.date) && event.netProceeds.isPositive) pending += UnsettledProceeds(seq, event.netProceeds, sd)
                }
                base.copy(
                    cash = cash + event.netProceeds,
                    lotsByAsset = lotsByAsset + (event.assetId to result.remainingLots),
                    realizedSales = realizedSales + realized,
                    unsettled = pending,
                    orders = executed(order, event.date, seq, event.price, event.settlementDate),
                )
            }

            is FundTransfer -> {
                val order = pendingOrder(event.orderId, OrderSide.TRANSFER, event.fromAsset)
                if (event.toAsset != order.spec.toAssetId || event.unitsOut != order.spec.units) {
                    throw InvalidEntryException("El traspaso no coincide con la orden ${order.orderId}")
                }
                val lots = lotsByAsset[event.fromAsset] ?: emptyList()
                val available = settledUnits(event.fromAsset, event.date) -
                    (reservedUnits(event.fromAsset) - order.spec.units!!)
                if (event.unitsOut > available) throw InsufficientUnitsException(event.fromAsset, available, event.unitsOut)
                val result = Fifo.consume(lots, event.unitsOut) { it.isSettled(event.date) }
                val pieces = result.consumptions
                var allocated = Units.ZERO
                val newLots = pieces.mapIndexed { i, c ->
                    val share = if (i == pieces.lastIndex) event.unitsIn - allocated
                    else Units.of(event.unitsIn.value.multiply(c.units.value).divide(event.unitsOut.value, 6, RoundingMode.DOWN))
                    allocated += share
                    if (!share.isPositive) throw InvalidEntryException("Traspaso demasiado pequeño para repartirlo entre los lotes")
                    Lot(event.toAsset, c.acquisitionDate, seq, share, share, c.cost, c.cost, settlesOn = event.settlementDate)
                }
                val dest = ((lotsByAsset[event.toAsset] ?: emptyList()) + newLots)
                    .sortedWith(compareBy({ it.acquisitionDate }, { it.acquisitionSeq }))
                val cost = pieces.fold(Money.ZERO) { acc, c -> acc + c.cost }
                base.copy(
                    lotsByAsset = lotsByAsset + (event.fromAsset to result.remainingLots) + (event.toAsset to dest),
                    transfers = transfers + TransferRecord(seq, event.fromAsset, event.toAsset, event.unitsOut, event.unitsIn, cost),
                    orders = executed(order, event.date, seq, null, event.settlementDate),
                )
            }

            is CashAdjustment -> base.copy(
                cash = cash + event.amount,
                adjustmentsTotal = adjustmentsTotal + event.amount,
            )

            is DividendReceived -> base.copy(
                cash = cash + event.net,
                dividends = dividends + DividendRecord(seq, event.date, event.assetId, event.gross, event.foreignTax, event.withholding),
            )

            is WithholdingPaid -> base.copy(
                cash = cash - event.amount,
                withholdings = withholdings + WithholdingRecord(seq, event.date, event.assetId, event.amount, event.saleSeq),
            )

            is TaxSettlement -> {
                if (taxPostings.any { it.taxYear == event.taxYear && it.installment == event.installment }) {
                    throw InvalidEntryException("La liquidación del IRPF ${event.taxYear} (plazo ${event.installment}) ya está registrada")
                }
                base.copy(
                    cash = cash + event.amount,
                    adjustmentsTotal = adjustmentsTotal + event.amount,
                    taxPostings = taxPostings + TaxPosting(seq, event.date, event.taxYear, event.installment, event.amount),
                )
            }

            is OrderPlaced -> {
                if (event.orderId in orders) throw InvalidEntryException("Ya existe la orden ${event.orderId}")
                val spec = event.spec
                if (spec.side == OrderSide.BUY) {
                    val available = availableCash(event.date)
                    if (available < spec.reservedCash) throw InsufficientCashException(available, spec.reservedCash)
                } else {
                    val available = unitsAvailable(spec.assetId, event.date)
                    if (spec.units!! > available) throw InsufficientUnitsException(spec.assetId, available, spec.units)
                }
                base.copy(orders = orders + (event.orderId to OrderState(event.orderId, spec, event.date, OrderStatus.ACCEPTED)))
            }

            is OrderRejected -> {
                if (event.orderId in orders) throw InvalidEntryException("Ya existe la orden ${event.orderId}")
                base.copy(
                    orders = orders + (event.orderId to OrderState(
                        event.orderId, event.spec, event.date, OrderStatus.REJECTED, closedOn = event.date, closeReason = event.reason,
                    )),
                )
            }

            is OrderClosed -> {
                val o = orders[event.orderId] ?: throw InvalidEntryException("No existe la orden ${event.orderId}")
                if (o.status != OrderStatus.ACCEPTED) throw InvalidEntryException("La orden ${event.orderId} no está pendiente (${o.status})")
                base.copy(orders = orders + (o.orderId to o.copy(status = event.status, closedOn = event.date, closeReason = event.reason)))
            }

            is OrderSettled -> {
                val o = orders[event.orderId] ?: throw InvalidEntryException("No existe la orden ${event.orderId}")
                if (o.status != OrderStatus.EXECUTED) throw InvalidEntryException("La orden ${event.orderId} no está ejecutada (${o.status})")
                if (o.settlesOn != null && event.date.isBefore(o.settlesOn)) {
                    throw InvalidEntryException("La orden ${event.orderId} se liquida el ${o.settlesOn}, no antes")
                }
                base.copy(orders = orders + (o.orderId to o.copy(status = OrderStatus.SETTLED, settledOn = event.date)))
            }
        }
    }

    private fun executed(order: OrderState?, date: LocalDate, seq: Long, price: Money?, settlesOn: LocalDate?): Map<String, OrderState> =
        if (order == null) orders
        else orders + (order.orderId to order.copy(
            status = OrderStatus.EXECUTED, executedOn = date, executionSeq = seq, executionPrice = price, settlesOn = settlesOn,
        ))

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
