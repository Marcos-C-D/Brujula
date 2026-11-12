package app.brujula.app

import app.brujula.engine.AccountState
import app.brujula.engine.CashAdjustment
import app.brujula.engine.DividendReceived
import app.brujula.engine.FundTransfer
import app.brujula.engine.LedgerRecord
import app.brujula.engine.MonthlyIncome
import app.brujula.engine.OpeningBalance
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
import app.brujula.engine.TaxSettlement
import app.brujula.engine.WithholdingPaid
import app.brujula.market.AssetDefinition
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.trading.OrderTimeline
import app.brujula.trading.TimelineEntry
import java.time.LocalDate

data class OrderRow(
    val orderId: String,
    val title: String,
    val detail: String,
    val statusLabel: String,
    val status: OrderStatus,
    val placedOn: LocalDate,
    val timeline: List<TimelineEntry>,
    val canCancel: Boolean,
)

data class MovementRow(val seq: Long, val date: LocalDate, val kind: String, val description: String, val cashEffect: Money?, val balance: Money)

object OrderViews {

    fun statusLabel(s: OrderStatus): String = when (s) {
        OrderStatus.ACCEPTED -> "Pendiente"
        OrderStatus.EXECUTED -> "Ejecutada (pendiente de liquidar)"
        OrderStatus.SETTLED -> "Liquidada"
        OrderStatus.CANCELLED -> "Cancelada"
        OrderStatus.EXPIRED -> "Caducada"
        OrderStatus.REJECTED -> "Rechazada"
    }

    private fun sideLabel(s: OrderSide) = when (s) { OrderSide.BUY -> "Compra"; OrderSide.SELL -> "Venta"; OrderSide.TRANSFER -> "Traspaso" }

    private fun typeLabel(s: OrderSpec) = when (s.type) {
        OrderType.MARKET -> "a mercado"
        OrderType.LIMIT -> "limitada a ${Fmt.price(s.limitPrice!!)}"
        OrderType.STOP -> "stop-loss a ${Fmt.price(s.limitPrice!!)}"
    }

    private fun name(id: AssetId, catalog: (AssetId) -> AssetDefinition?) = catalog(id)?.name ?: id.value

    /** Órdenes de la cuenta, de la más reciente a la más antigua. */
    fun rows(state: AccountState, catalog: (AssetId) -> AssetDefinition?): List<OrderRow> =
        state.orders.values.map { o -> row(o, catalog) }.reversed()

    private fun row(o: OrderState, catalog: (AssetId) -> AssetDefinition?): OrderRow {
        val sp = o.spec
        val asset = name(sp.assetId, catalog)
        val title = when (sp.side) {
            OrderSide.TRANSFER -> "Traspaso · $asset → ${name(sp.toAssetId!!, catalog)}"
            else -> "${sideLabel(sp.side)} · $asset"
        }
        val what = when (sp.side) {
            OrderSide.BUY -> "${Fmt.eur(sp.amount!!)}" + if (sp.fees.isPositive) " + ${Fmt.eur(sp.fees)} de comisión" else ""
            else -> "${Fmt.units(sp.units!!)} u."
        }
        val expiry = sp.expiresOn?.let { " · caduca el ${Fmt.date(it)}" } ?: if (o.status == OrderStatus.ACCEPTED && sp.type != OrderType.MARKET) " · hasta cancelar" else ""
        val plan = if (sp.scheduleId != null) " · plan periódico" else ""
        return OrderRow(
            o.orderId, title, "$what · ${typeLabel(sp)}$expiry$plan", statusLabel(o.status), o.status, o.placedOn,
            OrderTimeline.of(o), o.status == OrderStatus.ACCEPTED,
        )
    }

    /** Historial completo de movimientos del libro, con el efectivo tras cada uno (de más reciente a más antiguo). */
    fun movements(records: List<LedgerRecord>, catalog: (AssetId) -> AssetDefinition?): List<MovementRow> {
        var state = AccountState.EMPTY
        val out = ArrayList<MovementRow>()
        for (r in records.sortedBy { it.seq }) {
            val before = state.cash
            state = state.apply(r.seq, r.event)
            val delta = state.cash - before
            val (kind, text) = describe(r.event, catalog)
            out += MovementRow(r.seq, r.event.date, kind, text, if (delta.isZero) null else delta, state.cash)
        }
        return out.reversed()
    }

    private fun describe(e: app.brujula.engine.LedgerEvent, catalog: (AssetId) -> AssetDefinition?): Pair<String, String> = when (e) {
        is OpeningBalance -> "Apertura" to "Saldo inicial"
        is MonthlyIncome -> "Ingreso" to "Ingreso mensual ${e.period}"
        is Purchase -> "Compra" to "${name(e.assetId, catalog)}: ${Fmt.units(e.units)} u. por ${Fmt.eur(e.invested)}" + if (e.fees.isPositive) " (+${Fmt.eur(e.fees)} de comisión)" else ""
        is Sale -> "Venta" to "${name(e.assetId, catalog)}: ${Fmt.units(e.units)} u. por ${Fmt.eur(e.grossProceeds)}" + if (e.fees.isPositive) " (−${Fmt.eur(e.fees)} de comisión)" else ""
        is FundTransfer -> "Traspaso" to "${name(e.fromAsset, catalog)} → ${name(e.toAsset, catalog)}: ${Fmt.units(e.unitsOut)} u. salen, ${Fmt.units(e.unitsIn)} u. entran"
        is DividendReceived -> "Dividendo" to "${name(e.assetId, catalog)}: ${Fmt.eur(e.gross)} brutos, retención ${Fmt.eur(e.withholding + e.foreignTax)}"
        is WithholdingPaid -> "Retención" to "${name(e.assetId, catalog)}: ${e.note.ifBlank { "retención a cuenta" }}"
        is TaxSettlement -> "IRPF" to "IRPF ${e.taxYear} (plazo ${e.installment})" + if (e.amount.isPositive) ": devolución" else ": pago"
        is CashAdjustment -> "Ajuste" to e.reason
        is OrderPlaced -> "Orden" to "Orden aceptada: ${rowTitle(e.spec, catalog)}"
        is OrderRejected -> "Orden" to "Orden rechazada: ${e.reason}"
        is OrderClosed -> "Orden" to "${statusLabel(e.status)}: ${e.reason}"
        is OrderSettled -> "Liquidación" to "Operación liquidada"
    }

    private fun rowTitle(sp: OrderSpec, catalog: (AssetId) -> AssetDefinition?): String =
        "${sideLabel(sp.side)} de ${name(sp.assetId, catalog)}"
}
