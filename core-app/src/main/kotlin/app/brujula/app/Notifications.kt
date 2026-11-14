package app.brujula.app

import app.brujula.engine.DividendReceived
import app.brujula.engine.FundTransfer
import app.brujula.engine.LedgerRecord
import app.brujula.engine.MonthlyIncome
import app.brujula.engine.OrderClosed
import app.brujula.engine.OrderSettled
import app.brujula.engine.OrderStatus
import app.brujula.engine.Purchase
import app.brujula.engine.Sale
import app.brujula.engine.TaxSettlement
import app.brujula.market.AssetDefinition
import app.brujula.model.AssetId
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class NotificationText(val key: String, val title: String, val text: String)

/** Textos de los avisos a partir de lo que ha cambiado en el libro. */
object Notifications {

    private const val SUMMARY_FROM = 5

    /** Avisos de los movimientos nuevos de una cuenta. Si hay muchos (p. ej. tras días sin abrir la app) se resumen en uno. */
    fun fromRecords(accountName: String, records: List<LedgerRecord>, catalog: (AssetId) -> AssetDefinition?): List<NotificationText> {
        val items = records.mapNotNull { r -> describe(accountName, r, catalog) }
        if (items.size >= SUMMARY_FROM) {
            return listOf(NotificationText("resumen:$accountName:${records.last().seq}", "Brújula · $accountName", "${items.size} novedades en tu cuenta. Ábrela para ver el detalle."))
        }
        return items
    }

    private fun name(id: AssetId, catalog: (AssetId) -> AssetDefinition?) = catalog(id)?.name ?: id.value

    private fun describe(account: String, r: LedgerRecord, catalog: (AssetId) -> AssetDefinition?): NotificationText? {
        val title = "Brújula · $account"
        return when (val e = r.event) {
            is MonthlyIncome -> NotificationText("ing:$account:${r.seq}", title, "Ingreso mensual de ${Fmt.eur(e.amount)} abonado.")
            is Purchase -> if (e.orderId != null) NotificationText("ord:$account:${r.seq}", title, "Compra ejecutada: ${name(e.assetId, catalog)} por ${Fmt.eur(e.invested)}.") else null
            is Sale -> if (e.orderId != null) NotificationText("ord:$account:${r.seq}", title, "Venta ejecutada: ${name(e.assetId, catalog)} por ${Fmt.eur(e.grossProceeds)}.") else null
            is FundTransfer -> NotificationText("ord:$account:${r.seq}", title, "Traspaso ejecutado: ${name(e.fromAsset, catalog)} → ${name(e.toAsset, catalog)}.")
            is OrderSettled -> NotificationText("liq:$account:${r.seq}", title, "Una operación se ha liquidado: el efectivo o las participaciones ya están disponibles.")
            is OrderClosed -> when (e.status) {
                OrderStatus.EXPIRED -> NotificationText("cad:$account:${r.seq}", title, "Una orden ha caducado sin ejecutarse.")
                OrderStatus.REJECTED -> NotificationText("rech:$account:${r.seq}", title, "Una orden se ha rechazado: ${e.reason}")
                else -> null // cancelada por el usuario: no hace falta avisar
            }
            is DividendReceived -> NotificationText("div:$account:${r.seq}", title, "Dividendo de ${name(e.assetId, catalog)}: ${Fmt.eur(e.gross)} brutos.")
            is TaxSettlement -> NotificationText(
                "irpf:$account:${r.seq}", title,
                if (e.amount.isNegative) "Pago del IRPF ${e.taxYear}: ${Fmt.eur(-e.amount)}." else "Devolución del IRPF ${e.taxYear}: ${Fmt.eur(e.amount)}.",
            )
            else -> null
        }
    }

    /** Avisos de pagos o devoluciones del IRPF que llegan en los próximos [daysAhead] días. */
    fun upcomingTax(accountName: String, upcoming: List<UpcomingTax>, today: LocalDate, daysAhead: Long = 7): List<NotificationText> =
        upcoming.filter { u ->
            val days = ChronoUnit.DAYS.between(today, u.date)
            days in 0..daysAhead
        }.map { u ->
            NotificationText("fecha:$accountName:${u.date}:${u.label}", "Brújula · $accountName", "${Fmt.date(u.date)}: ${u.label}" + if (u.estimated) " (fecha estimada)" else "")
        }
}
