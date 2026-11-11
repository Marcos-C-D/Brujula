package app.brujula.trading

import app.brujula.engine.Bank
import app.brujula.engine.FundTransfer
import app.brujula.engine.InsufficientCashException
import app.brujula.engine.InsufficientUnitsException
import app.brujula.engine.LedgerException
import app.brujula.engine.OrderClosed
import app.brujula.engine.OrderPlaced
import app.brujula.engine.OrderRejected
import app.brujula.engine.OrderSettled
import app.brujula.engine.OrderSide
import app.brujula.engine.OrderSpec
import app.brujula.engine.OrderState
import app.brujula.engine.OrderStatus
import app.brujula.engine.OrderType
import app.brujula.engine.Purchase
import app.brujula.engine.Sale
import app.brujula.market.AssetDefinition
import app.brujula.market.AssetKind
import app.brujula.market.MarketCalendar
import app.brujula.market.Markets
import app.brujula.market.PricePoint
import app.brujula.market.UpdateSchedule
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Contratación simulada. Reglas (con datos diarios, siempre del lado conservador):
 *
 * - Una orden de ETF o Bitcoin se ejecuta en la primera sesión POSTERIOR al día en que se crea; nunca con un precio
 *   anterior a su creación. A mercado, al precio de apertura de esa sesión.
 * - Limitada de compra: a la apertura si ya está por debajo del límite; si no, al límite solo si el mínimo de la sesión
 *   baja ESTRICTAMENTE del límite. Limitada de venta: simétrica con el máximo. Rozar el límite no ejecuta.
 * - Stop-loss (venta): se activa si el mínimo llega al precio de activación (rozarlo basta, lado adverso); si abre por debajo,
 *   se ejecuta a la apertura; si no, al precio de activación.
 * - Fondos: siempre a mercado, al valor liquidativo (cierre del proxy) de la siguiente valoración: el mismo día si la orden se
 *   creó en día hábil antes de la hora de corte; si no, el siguiente día hábil. Nunca se conoce el valor antes de crear la orden.
 * - Compras: el efectivo (importe + comisión) queda reservado desde que se acepta la orden. Ventas y traspasos reservan unidades.
 * - Si falta el dato de mercado de una sesión ya cerrada, las órdenes esperan: nunca se ejecutan con precios de otra fecha.
 */
class TradingService(
    private val bank: Bank,
    private val catalog: (AssetId) -> AssetDefinition?,
    private val prices: PriceSource,
    private val config: TradingConfig = TradingConfig(),
    private val clock: () -> Instant,
    private val newOrderId: () -> String = { UUID.randomUUID().toString() },
    /** Se ejecuta al final de cada día procesado (por ejemplo, los hechos fiscales). Devuelve líneas de informe. */
    private val afterDay: ((AccountId, LocalDate) -> List<String>)? = null,
) {
    private fun today(): LocalDate = UpdateSchedule.today(clock())

    private fun def(id: AssetId): AssetDefinition = catalog(id) ?: throw TradingException("Activo desconocido: $id")

    // ------------------------------------------------------------------ crear y cancelar

    fun placeBuy(
        accountId: AccountId, assetId: AssetId, amount: Money,
        type: OrderType = OrderType.MARKET, limitPrice: Money? = null, expiresOn: LocalDate? = null,
    ): String {
        val d = def(assetId)
        if (amount < config.minOrder) throw TradingException("El importe mínimo por operación es ${config.minOrder} €")
        checkTypeAllowed(d, type)
        checkExpiry(expiresOn)
        val spec = OrderSpec(
            OrderSide.BUY, type, assetId, amount = amount, limitPrice = limitPrice, expiresOn = expiresOn,
            fees = config.fees.fee(d.kind, OrderSide.BUY, amount), placedAt = clock(),
        )
        return place(accountId, spec)
    }

    fun placeSell(
        accountId: AccountId, assetId: AssetId, units: Units,
        type: OrderType = OrderType.MARKET, price: Money? = null, expiresOn: LocalDate? = null,
    ): String {
        val d = def(assetId)
        checkTypeAllowed(d, type)
        checkExpiry(expiresOn)
        return place(accountId, OrderSpec(OrderSide.SELL, type, assetId, units = units, limitPrice = price, expiresOn = expiresOn, placedAt = clock()))
    }

    /** Traspaso entre fondos con diferimiento fiscal. Los ETF y Bitcoin no pueden traspasarse. */
    fun placeTransfer(accountId: AccountId, from: AssetId, to: AssetId, units: Units): String {
        if (def(from).kind != AssetKind.INDEX_FUND || def(to).kind != AssetKind.INDEX_FUND) {
            throw TradingException("Solo los fondos de inversión se pueden traspasar con diferimiento fiscal; los ETF y Bitcoin no")
        }
        return place(accountId, OrderSpec(OrderSide.TRANSFER, OrderType.MARKET, from, toAssetId = to, units = units, placedAt = clock()))
    }

    fun cancel(accountId: AccountId, orderId: String) {
        val o = bank.get(accountId).state.order(orderId) ?: throw TradingException("No existe la orden $orderId")
        if (o.status != OrderStatus.ACCEPTED) throw TradingException("Solo se pueden cancelar órdenes pendientes (estado: ${o.status})")
        bank.post(accountId, OrderClosed(today(), orderId, OrderStatus.CANCELLED, "Cancelada por el usuario"))
    }

    private fun place(accountId: AccountId, spec: OrderSpec): String {
        val id = newOrderId()
        bank.post(accountId, OrderPlaced(today(), id, spec))
        return id
    }

    private fun checkTypeAllowed(d: AssetDefinition, type: OrderType) {
        if (d.kind == AssetKind.INDEX_FUND && type != OrderType.MARKET) {
            throw TradingException("Los fondos solo admiten órdenes a mercado (se ejecutan al valor liquidativo)")
        }
    }

    private fun checkExpiry(expiresOn: LocalDate?) {
        if (expiresOn != null && expiresOn.isBefore(today())) throw TradingException("La fecha de caducidad ya ha pasado")
    }

    // ------------------------------------------------------------------ procesado diario

    /**
     * Procesa día a día, en orden cronológico, desde el último movimiento hasta [today]: ingresos mensuales, planes periódicos,
     * ejecuciones (con datos hasta [knownThrough]), liquidaciones y caducidades. Es idempotente: repetirlo no duplica nada.
     * Devuelve un informe legible. Si falta un dato de mercado, se detiene en ese día y las órdenes esperan.
     */
    fun process(accountId: AccountId, knownThrough: LocalDate, today: LocalDate): List<String> {
        val account = bank.get(accountId)
        val report = ArrayList<String>()
        if (account.isArchived) return report
        var day = account.state.lastDate ?: account.createdOn
        while (!day.isAfter(today)) {
            bank.creditDueIncomes(accountId, day)
            placeScheduled(accountId, day, report)
            if (!executeOrders(accountId, day, knownThrough, report)) break
            settleAndExpire(accountId, day, report)
            afterDay?.let { report += it(accountId, day) }
            day = day.plusDays(1)
        }
        return report
    }

    private fun placeScheduled(accountId: AccountId, day: LocalDate, report: MutableList<String>) {
        val account = bank.get(accountId)
        for (plan in account.schedules) {
            if (plan.paused) continue
            val d = catalog(plan.assetId) ?: continue
            for ((key, _) in plan.occurrences(day)) {
                val id = plan.orderId(key)
                if (account.state.order(id) != null) continue
                val placedAt = day.atStartOfDay(UpdateSchedule.MADRID).toInstant()
                val spec = if (plan.side == OrderSide.BUY) {
                    OrderSpec(OrderSide.BUY, OrderType.MARKET, plan.assetId, amount = plan.amount, fees = config.fees.fee(d.kind, OrderSide.BUY, plan.amount!!),
                        placedAt = placedAt, scheduleId = plan.id, periodKey = key)
                } else {
                    OrderSpec(OrderSide.SELL, OrderType.MARKET, plan.assetId, units = plan.units, placedAt = placedAt, scheduleId = plan.id, periodKey = key)
                }
                try {
                    bank.post(accountId, OrderPlaced(day, id, spec, "Plan periódico ${plan.id} · $key"))
                    report += "Plan ${plan.id}: orden $id creada"
                } catch (e: InsufficientCashException) {
                    bank.post(accountId, OrderRejected(day, id, spec, "Efectivo insuficiente"))
                    report += "Plan ${plan.id}: $key rechazada por falta de efectivo"
                } catch (e: InsufficientUnitsException) {
                    bank.post(accountId, OrderRejected(day, id, spec, "Unidades insuficientes"))
                    report += "Plan ${plan.id}: $key rechazada por falta de unidades"
                }
            }
        }
    }

    private enum class Attempt { SKIP, BLOCKED, DONE }

    /** @return false si hay que detenerse porque falta un dato de mercado. */
    private fun executeOrders(accountId: AccountId, day: LocalDate, knownThrough: LocalDate, report: MutableList<String>): Boolean {
        if (day.isAfter(knownThrough)) return true
        val pending = bank.get(accountId).state.pendingOrders
        for (o in pending) {
            when (attempt(accountId, o, day, report)) {
                Attempt.BLOCKED -> return false
                else -> Unit
            }
        }
        return true
    }

    private fun attempt(accountId: AccountId, o: OrderState, day: LocalDate, report: MutableList<String>): Attempt {
        val spec = o.spec
        val d = def(spec.assetId)
        val isFund = d.kind == AssetKind.INDEX_FUND
        if (isFund) {
            if (day.isBefore(valuationDay(o, d))) return Attempt.SKIP
        } else if (!day.isAfter(o.placedOn)) {
            return Attempt.SKIP // una orden solo se ejecuta en sesiones posteriores a su creación
        }

        val series = prices.series(spec.assetId) ?: return Attempt.BLOCKED
        val point = series.onOrBefore(day)?.takeIf { it.date == day }
        if (point == null) return if (series.last.date.isBefore(day)) Attempt.BLOCKED else Attempt.SKIP

        if (spec.side == OrderSide.TRANSFER) {
            val toSeries = prices.series(spec.toAssetId!!) ?: return Attempt.BLOCKED
            val toPoint = toSeries.onOrBefore(day)?.takeIf { it.date == day }
                ?: return if (toSeries.last.date.isBefore(day)) Attempt.BLOCKED else Attempt.SKIP
            return executeTransfer(accountId, o, day, point, toPoint, report)
        }

        val price = fillPrice(spec, point, isFund) ?: return Attempt.SKIP
        return if (spec.side == OrderSide.BUY) executeBuy(accountId, o, d, day, price, report) else executeSell(accountId, o, d, day, price, report)
    }

    private fun fillPrice(spec: OrderSpec, p: PricePoint, isFund: Boolean): Money? {
        if (isFund) return Money.of(p.close) // valor liquidativo
        val open = p.open ?: p.close
        val low = p.low ?: p.close
        val high = p.high ?: p.close
        val limit = spec.limitPrice
        return when (spec.type) {
            OrderType.MARKET -> Money.of(open)
            OrderType.LIMIT -> if (spec.side == OrderSide.BUY) {
                when {
                    Money.of(open) <= limit!! -> Money.of(open)
                    Money.of(low) < limit -> limit
                    else -> null
                }
            } else {
                when {
                    Money.of(open) >= limit!! -> Money.of(open)
                    Money.of(high) > limit -> limit
                    else -> null
                }
            }
            OrderType.STOP -> when {
                Money.of(open) <= limit!! -> Money.of(open)
                Money.of(low) <= limit -> limit
                else -> null
            }
        }
    }

    private fun executeBuy(accountId: AccountId, o: OrderState, d: AssetDefinition, day: LocalDate, price: Money, report: MutableList<String>): Attempt {
        val spec = o.spec
        val amount = spec.amount!!
        val units = amount.amount.divide(price.amount, config.unitScale(d.kind), RoundingMode.DOWN)
        if (units.signum() <= 0) {
            closeRejected(accountId, o, day, "Importe demasiado pequeño para este precio", report)
            return Attempt.DONE
        }
        val sd = config.settlement.settlementDate(d, OrderSide.BUY, day)
        try {
            bank.post(
                accountId,
                Purchase(day, spec.assetId, amount, Units.of(units), spec.fees, "Ejecución de la orden ${o.orderId}",
                    orderId = o.orderId, price = price, settlementDate = sd, lockUnits = d.kind == AssetKind.INDEX_FUND),
            )
            report += "Compra ejecutada: ${d.name} · $amount € a $price (liquida el $sd)"
        } catch (e: LedgerException) {
            closeRejected(accountId, o, day, e.message ?: "No se pudo ejecutar", report)
        }
        return Attempt.DONE
    }

    private fun executeSell(accountId: AccountId, o: OrderState, d: AssetDefinition, day: LocalDate, price: Money, report: MutableList<String>): Attempt {
        val spec = o.spec
        val units = spec.units!!
        val gross = (price * units).roundedToCents()
        val fee = config.fees.fee(d.kind, OrderSide.SELL, gross).let { if (it > gross) gross else it }
        val sd = config.settlement.settlementDate(d, OrderSide.SELL, day)
        try {
            bank.post(
                accountId,
                Sale(day, spec.assetId, units, gross, fee, "Ejecución de la orden ${o.orderId}", orderId = o.orderId, price = price, settlementDate = sd),
            )
            report += "Venta ejecutada: ${d.name} · $units u. a $price (efectivo disponible el $sd)"
        } catch (e: LedgerException) {
            closeRejected(accountId, o, day, e.message ?: "No se pudo ejecutar", report)
        }
        return Attempt.DONE
    }

    private fun executeTransfer(accountId: AccountId, o: OrderState, day: LocalDate, from: PricePoint, to: PricePoint, report: MutableList<String>): Attempt {
        val spec = o.spec
        val dFrom = def(spec.assetId)
        val unitsOut = spec.units!!
        val toAsset = spec.toAssetId!!
        val value = (Money.of(from.close) * unitsOut).roundedToCents()
        val unitsIn = value.amount.divide(to.close, config.unitScale(AssetKind.INDEX_FUND), RoundingMode.DOWN)
        if (unitsIn.signum() <= 0) {
            closeRejected(accountId, o, day, "Importe demasiado pequeño para el traspaso", report)
            return Attempt.DONE
        }
        val sd = config.settlement.settlementDate(dFrom, OrderSide.TRANSFER, day)
        try {
            bank.post(
                accountId,
                FundTransfer(day, spec.assetId, toAsset, unitsOut, Units.of(unitsIn), o.orderId, sd, "Traspaso de ${value} € de valor"),
            )
            report += "Traspaso ejecutado: $unitsOut u. de ${dFrom.name} → ${def(toAsset).name} (${unitsIn.toPlainString()} u.)"
        } catch (e: LedgerException) {
            closeRejected(accountId, o, day, e.message ?: "No se pudo ejecutar", report)
        }
        return Attempt.DONE
    }

    private fun closeRejected(accountId: AccountId, o: OrderState, day: LocalDate, reason: String, report: MutableList<String>) {
        bank.post(accountId, OrderClosed(day, o.orderId, OrderStatus.REJECTED, reason))
        report += "Orden ${o.orderId} rechazada: $reason"
    }

    private fun settleAndExpire(accountId: AccountId, day: LocalDate, report: MutableList<String>) {
        val state = bank.get(accountId).state
        for (o in state.orders.values) {
            val settlesOn = o.settlesOn
            if (o.status == OrderStatus.EXECUTED && settlesOn != null && !settlesOn.isAfter(day)) {
                bank.post(accountId, OrderSettled(day, o.orderId))
                report += "Orden ${o.orderId} liquidada"
            }
        }
        for (o in bank.get(accountId).state.pendingOrders) {
            val exp = o.spec.expiresOn
            if (exp != null && day.isAfter(exp)) {
                bank.post(accountId, OrderClosed(day, o.orderId, OrderStatus.EXPIRED, "Caducó el $exp"))
                report += "Orden ${o.orderId} caducada"
            }
        }
    }

    /** Día de valoración de un fondo: mismo día hábil si la orden se creó antes de la hora de corte; si no, el siguiente. */
    fun valuationDay(o: OrderState, d: AssetDefinition): LocalDate {
        val cal: MarketCalendar? = d.marketId?.let { Markets.byId(it) }
        val placed = o.spec.placedAt
        fun next(date: LocalDate): LocalDate = cal?.nextTradingDay(date) ?: generateSequence(date.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek.value <= 5 }
        fun isTrading(date: LocalDate) = cal?.isTradingDay(date) ?: (date.dayOfWeek.value <= 5)
        if (placed == null) return next(o.placedOn)
        val z = placed.atZone(UpdateSchedule.MADRID)
        val date = z.toLocalDate()
        return if (isTrading(date) && z.toLocalTime().isBefore(config.fundCutoff)) date else next(date)
    }
}
