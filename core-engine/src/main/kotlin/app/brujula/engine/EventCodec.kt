package app.brujula.engine

import app.brujula.engine.json.Json
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

class CodecException(message: String) : RuntimeException(message)

/** Conversión exacta de eventos y configuraciones de cuenta a/desde estructuras JSON. */
object EventCodec {

    fun toMap(e: LedgerEvent): Map<String, Any?> = when (e) {
        is OpeningBalance -> linkedMapOf("type" to "OPENING", "date" to e.date.toString(), "amount" to e.amount.toString(), "note" to e.note)
        is MonthlyIncome -> linkedMapOf("type" to "INCOME", "date" to e.date.toString(), "period" to e.period.toString(), "amount" to e.amount.toString(), "note" to e.note)
        is Purchase -> linkedMapOf<String, Any?>(
            "type" to "PURCHASE", "date" to e.date.toString(), "asset" to e.assetId.value,
            "invested" to e.invested.toString(), "units" to e.units.toString(), "fees" to e.fees.toString(), "note" to e.note,
        ).also { m ->
            e.orderId?.let { m["orderId"] = it }
            e.price?.let { m["price"] = it.toString() }
            e.settlementDate?.let { m["settlementDate"] = it.toString() }
            if (e.lockUnits) m["lockUnits"] = true
        }
        is Sale -> linkedMapOf<String, Any?>(
            "type" to "SALE", "date" to e.date.toString(), "asset" to e.assetId.value,
            "units" to e.units.toString(), "gross" to e.grossProceeds.toString(), "fees" to e.fees.toString(), "note" to e.note,
        ).also { m ->
            e.orderId?.let { m["orderId"] = it }
            e.price?.let { m["price"] = it.toString() }
            e.settlementDate?.let { m["settlementDate"] = it.toString() }
        }
        is CashAdjustment -> linkedMapOf("type" to "ADJUSTMENT", "date" to e.date.toString(), "amount" to e.amount.toString(), "reason" to e.reason, "note" to e.note)
        is DividendReceived -> linkedMapOf("type" to "DIVIDEND", "date" to e.date.toString(), "asset" to e.assetId.value, "gross" to e.gross.toString(),
            "foreignTax" to e.foreignTax.toString(), "withholding" to e.withholding.toString(), "note" to e.note)
        is WithholdingPaid -> linkedMapOf<String, Any?>("type" to "WITHHOLDING", "date" to e.date.toString(), "asset" to e.assetId.value,
            "amount" to e.amount.toString(), "note" to e.note).also { m -> e.saleSeq?.let { m["saleSeq"] = it } }
        is TaxSettlement -> linkedMapOf("type" to "TAX_SETTLEMENT", "date" to e.date.toString(), "taxYear" to e.taxYear, "installment" to e.installment,
            "amount" to e.amount.toString(), "note" to e.note)
        is OrderPlaced -> linkedMapOf("type" to "ORDER_PLACED", "date" to e.date.toString(), "orderId" to e.orderId, "spec" to specMap(e.spec), "note" to e.note)
        is OrderRejected -> linkedMapOf("type" to "ORDER_REJECTED", "date" to e.date.toString(), "orderId" to e.orderId, "spec" to specMap(e.spec), "reason" to e.reason, "note" to e.note)
        is OrderClosed -> linkedMapOf("type" to "ORDER_CLOSED", "date" to e.date.toString(), "orderId" to e.orderId, "status" to e.status.name, "reason" to e.reason, "note" to e.note)
        is OrderSettled -> linkedMapOf("type" to "ORDER_SETTLED", "date" to e.date.toString(), "orderId" to e.orderId, "note" to e.note)
        is FundTransfer -> linkedMapOf(
            "type" to "TRANSFER", "date" to e.date.toString(), "from" to e.fromAsset.value, "to" to e.toAsset.value,
            "unitsOut" to e.unitsOut.toString(), "unitsIn" to e.unitsIn.toString(), "orderId" to e.orderId,
            "settlementDate" to e.settlementDate.toString(), "note" to e.note,
        )
    }

    fun specMap(sp: OrderSpec): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("side" to sp.side.name, "type" to sp.type.name, "asset" to sp.assetId.value, "fees" to sp.fees.toString())
        sp.toAssetId?.let { m["toAsset"] = it.value }
        sp.amount?.let { m["amount"] = it.toString() }
        sp.units?.let { m["units"] = it.toString() }
        sp.limitPrice?.let { m["limitPrice"] = it.toString() }
        sp.expiresOn?.let { m["expiresOn"] = it.toString() }
        sp.placedAt?.let { m["placedAt"] = it.toString() }
        sp.scheduleId?.let { m["scheduleId"] = it }
        sp.periodKey?.let { m["periodKey"] = it }
        return m
    }

    fun specFrom(any: Any?): OrderSpec {
        val m = asMap(any, "orden")
        return OrderSpec(
            side = enumOf<OrderSide>(text(m, "side")),
            type = enumOf<OrderType>(text(m, "type")),
            assetId = AssetId(text(m, "asset")),
            toAssetId = optText(m, "toAsset")?.let { AssetId(it) },
            amount = optText(m, "amount")?.let { moneyOf(it, "amount") },
            units = optText(m, "units")?.let { unitsOf(it, "units") },
            limitPrice = optText(m, "limitPrice")?.let { moneyOf(it, "limitPrice") },
            expiresOn = optText(m, "expiresOn")?.let { parseDate(it) },
            fees = optText(m, "fees")?.let { moneyOf(it, "fees") } ?: Money.ZERO,
            placedAt = optText(m, "placedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() ?: throw CodecException("Instante no válido: $it") },
            scheduleId = optText(m, "scheduleId"),
            periodKey = optText(m, "periodKey"),
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String): E =
        enumValues<E>().firstOrNull { it.name == name } ?: throw CodecException("Valor no válido: $name")

    private fun moneyOf(t: String, key: String): Money =
        try { Money.of(t) } catch (e: NumberFormatException) { throw CodecException("Importe no válido en '$key': $t") }

    private fun unitsOf(t: String, key: String): Units =
        try { Units.of(t) } catch (e: NumberFormatException) { throw CodecException("Cantidad no válida en '$key': $t") }

    fun toJson(e: LedgerEvent): String = Json.write(toMap(e))

    fun fromJson(text: String): LedgerEvent = fromAny(Json.parse(text))

    fun fromAny(any: Any?): LedgerEvent {
        val m = asMap(any, "evento")
        try {
            return when (val type = text(m, "type")) {
                "OPENING" -> OpeningBalance(date(m, "date"), money(m, "amount"), optText(m, "note") ?: "Saldo inicial")
                "INCOME" -> MonthlyIncome(date(m, "date"), period(m, "period"), money(m, "amount"), optText(m, "note") ?: "Ingreso mensual")
                "PURCHASE" -> Purchase(
                    date(m, "date"), AssetId(text(m, "asset")), money(m, "invested"), units(m, "units"), money(m, "fees"), optText(m, "note") ?: "",
                    orderId = optText(m, "orderId"), price = optText(m, "price")?.let { moneyOf(it, "price") },
                    settlementDate = optText(m, "settlementDate")?.let { parseDate(it) }, lockUnits = m["lockUnits"] == true,
                )
                "SALE" -> Sale(
                    date(m, "date"), AssetId(text(m, "asset")), units(m, "units"), money(m, "gross"), money(m, "fees"), optText(m, "note") ?: "",
                    orderId = optText(m, "orderId"), price = optText(m, "price")?.let { moneyOf(it, "price") },
                    settlementDate = optText(m, "settlementDate")?.let { parseDate(it) },
                )
                "DIVIDEND" -> DividendReceived(
                    date(m, "date"), AssetId(text(m, "asset")), money(m, "gross"), money(m, "foreignTax"), money(m, "withholding"), optText(m, "note") ?: "",
                )
                "WITHHOLDING" -> WithholdingPaid(
                    date(m, "date"), AssetId(text(m, "asset")), money(m, "amount"), (m["saleSeq"] as? BigDecimal)?.toLong(), optText(m, "note") ?: "",
                )
                "TAX_SETTLEMENT" -> TaxSettlement(date(m, "date"), int(m, "taxYear"), int(m, "installment"), money(m, "amount"), optText(m, "note") ?: "")
                "ORDER_PLACED" -> OrderPlaced(date(m, "date"), text(m, "orderId"), specFrom(m["spec"]), optText(m, "note") ?: "")
                "ORDER_REJECTED" -> {
                    val reason = text(m, "reason")
                    OrderRejected(date(m, "date"), text(m, "orderId"), specFrom(m["spec"]), reason, optText(m, "note") ?: reason)
                }
                "ORDER_CLOSED" -> {
                    val reason = text(m, "reason")
                    OrderClosed(date(m, "date"), text(m, "orderId"), enumOf<OrderStatus>(text(m, "status")), reason, optText(m, "note") ?: reason)
                }
                "ORDER_SETTLED" -> OrderSettled(date(m, "date"), text(m, "orderId"), optText(m, "note") ?: "Operación liquidada")
                "TRANSFER" -> FundTransfer(
                    date(m, "date"), AssetId(text(m, "from")), AssetId(text(m, "to")), units(m, "unitsOut"), units(m, "unitsIn"),
                    text(m, "orderId"), parseDate(text(m, "settlementDate")), optText(m, "note") ?: "",
                )
                "ADJUSTMENT" -> {
                    val reason = text(m, "reason")
                    CashAdjustment(date(m, "date"), money(m, "amount"), reason, optText(m, "note") ?: reason)
                }
                else -> throw CodecException("Tipo de evento desconocido: $type")
            }
        } catch (e: CodecException) {
            throw e
        } catch (e: LedgerException) {
            throw CodecException("Evento no válido: ${e.message}")
        } catch (e: IllegalArgumentException) { // identificadores vacíos
            throw CodecException("Evento no válido: ${e.message}")
        }
    }

    // ----- configuración de cuenta -----

    fun toMap(c: AccountConfig): Map<String, Any?> = linkedMapOf(
        "id" to c.id.value,
        "name" to c.name,
        "createdOn" to c.createdOn.toString(),
        "incomeHistory" to c.incomeHistory.map {
            linkedMapOf("fromPeriod" to it.fromPeriod.toString(), "amount" to it.amount.toString(), "dayOfMonth" to it.dayOfMonth)
        },
        "archiveIntervals" to c.archiveIntervals.map {
            linkedMapOf("from" to it.from.toString(), "to" to it.to?.toString())
        },
        "taxOptions" to LinkedHashMap<String, Any?>(c.taxOptions),
        "schedules" to c.schedules.map { p ->
            linkedMapOf<String, Any?>(
                "id" to p.id, "side" to p.side.name, "asset" to p.assetId.value,
                "amount" to p.amount?.toString(), "units" to p.units?.toString(),
                "frequency" to p.frequency.name, "dayOfWeek" to p.dayOfWeek, "dayOfMonth" to p.dayOfMonth,
                "startDate" to p.startDate.toString(), "endDate" to p.endDate?.toString(), "paused" to p.paused,
            )
        },
    )

    fun configToJson(c: AccountConfig): String = Json.write(toMap(c))

    fun configFromJson(text: String): AccountConfig = configFromAny(Json.parse(text))

    fun configFromAny(any: Any?): AccountConfig {
        val m = asMap(any, "cuenta")
        try {
            val history = asList(m["incomeHistory"], "incomeHistory").map {
                val h = asMap(it, "ingreso")
                IncomeConfig(period(h, "fromPeriod"), money(h, "amount"), int(h, "dayOfMonth"))
            }
            val intervals = asList(m["archiveIntervals"], "archiveIntervals").map {
                val a = asMap(it, "archivado")
                ArchiveInterval(date(a, "from"), optText(a, "to")?.let { t -> parseDate(t) })
            }
            val schedules = ((m["schedules"] as? List<*>) ?: emptyList<Any?>()).map {
                val p = asMap(it, "plan")
                ScheduledPlan(
                    id = text(p, "id"), side = enumOf<OrderSide>(text(p, "side")), assetId = AssetId(text(p, "asset")),
                    amount = optText(p, "amount")?.let { a -> moneyOf(a, "amount") }, units = optText(p, "units")?.let { u -> unitsOf(u, "units") },
                    frequency = enumOf<Frequency>(text(p, "frequency")), dayOfWeek = int(p, "dayOfWeek"), dayOfMonth = int(p, "dayOfMonth"),
                    startDate = date(p, "startDate"), endDate = optText(p, "endDate")?.let { d -> parseDate(d) }, paused = p["paused"] == true,
                )
            }
            val taxOptions = (m["taxOptions"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                (k as? String ?: throw CodecException("Opción fiscal no válida")) to (v as? String ?: throw CodecException("Opción fiscal no válida: $k"))
            } ?: emptyMap()
            if (history.isEmpty()) throw CodecException("La cuenta no tiene configuración de ingresos")
            return AccountConfig(AccountId(text(m, "id")), text(m, "name"), date(m, "createdOn"), history, intervals, schedules, taxOptions)
        } catch (e: CodecException) {
            throw e
        } catch (e: BankException) {
            throw CodecException("Cuenta no válida: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw CodecException("Cuenta no válida: ${e.message}")
        }
    }

    // ----- ayudas -----

    private fun asMap(any: Any?, what: String): Map<*, *> = any as? Map<*, *> ?: throw CodecException("Se esperaba un objeto para $what")
    private fun asList(any: Any?, what: String): List<*> = any as? List<*> ?: throw CodecException("Se esperaba una lista en $what")

    private fun text(m: Map<*, *>, key: String): String =
        m[key] as? String ?: throw CodecException("Falta el campo de texto '$key'")

    private fun optText(m: Map<*, *>, key: String): String? = when (val v = m[key]) {
        null -> null
        is String -> v
        else -> throw CodecException("El campo '$key' debe ser texto")
    }

    private fun parseDate(t: String): LocalDate =
        try { LocalDate.parse(t) } catch (e: java.time.format.DateTimeParseException) { throw CodecException("Fecha no válida: $t") }

    private fun date(m: Map<*, *>, key: String): LocalDate = parseDate(text(m, key))

    private fun period(m: Map<*, *>, key: String): YearMonth {
        val t = text(m, key)
        return try { YearMonth.parse(t) } catch (e: java.time.format.DateTimeParseException) { throw CodecException("Periodo no válido: $t") }
    }

    private fun money(m: Map<*, *>, key: String): Money {
        val t = text(m, key)
        return try { Money.of(t) } catch (e: NumberFormatException) { throw CodecException("Importe no válido en '$key': $t") }
    }

    private fun units(m: Map<*, *>, key: String): Units {
        val t = text(m, key)
        return try { Units.of(t) } catch (e: NumberFormatException) { throw CodecException("Cantidad no válida en '$key': $t") }
    }

    private fun int(m: Map<*, *>, key: String): Int {
        val v = m[key] as? BigDecimal ?: throw CodecException("Falta el campo numérico '$key'")
        return try { v.intValueExact() } catch (e: ArithmeticException) { throw CodecException("Número no válido en '$key'") }
    }
}
