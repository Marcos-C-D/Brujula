package app.brujula.trading

import app.brujula.engine.OrderState
import app.brujula.engine.OrderStatus
import java.time.LocalDate

/** Un paso del ciclo de vida de una orden: solicitada → aceptada → ejecutada → liquidada. */
data class TimelineEntry(val date: LocalDate?, val label: String, val done: Boolean)

object OrderTimeline {
    fun of(o: OrderState): List<TimelineEntry> {
        val steps = ArrayList<TimelineEntry>()
        steps += TimelineEntry(o.placedOn, "Orden solicitada", true)
        if (o.status == OrderStatus.REJECTED && o.executedOn == null && o.closedOn == o.placedOn) {
            steps += TimelineEntry(o.closedOn, "Orden rechazada: ${o.closeReason}", true)
            return steps
        }
        steps += TimelineEntry(o.placedOn, "Orden aceptada", true)
        when (o.status) {
            OrderStatus.ACCEPTED -> steps += TimelineEntry(null, "Pendiente de ejecución", false)
            OrderStatus.CANCELLED, OrderStatus.EXPIRED, OrderStatus.REJECTED ->
                steps += TimelineEntry(o.closedOn, "${label(o.status)}: ${o.closeReason}", true)
            OrderStatus.EXECUTED, OrderStatus.SETTLED -> {
                val price = o.executionPrice?.let { " a $it €" } ?: ""
                steps += TimelineEntry(o.executedOn, "Orden ejecutada$price", true)
                if (o.status == OrderStatus.SETTLED) steps += TimelineEntry(o.settledOn, "Operación liquidada", true)
                else steps += TimelineEntry(o.settlesOn, "Liquidación prevista", false)
            }
        }
        return steps
    }

    private fun label(s: OrderStatus) = when (s) {
        OrderStatus.CANCELLED -> "Orden cancelada"
        OrderStatus.EXPIRED -> "Orden caducada"
        OrderStatus.REJECTED -> "Orden rechazada"
        else -> s.name
    }
}
