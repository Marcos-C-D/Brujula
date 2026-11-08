package app.brujula.engine

import app.brujula.engine.json.Json
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class CodecException(message: String) : RuntimeException(message)

/** Conversión exacta de eventos y configuraciones de cuenta a/desde estructuras JSON. */
object EventCodec {

    fun toMap(e: LedgerEvent): Map<String, Any?> = when (e) {
        is OpeningBalance -> linkedMapOf("type" to "OPENING", "date" to e.date.toString(), "amount" to e.amount.toString(), "note" to e.note)
        is MonthlyIncome -> linkedMapOf("type" to "INCOME", "date" to e.date.toString(), "period" to e.period.toString(), "amount" to e.amount.toString(), "note" to e.note)
        is Purchase -> linkedMapOf(
            "type" to "PURCHASE", "date" to e.date.toString(), "asset" to e.assetId.value,
            "invested" to e.invested.toString(), "units" to e.units.toString(), "fees" to e.fees.toString(), "note" to e.note,
        )
        is Sale -> linkedMapOf(
            "type" to "SALE", "date" to e.date.toString(), "asset" to e.assetId.value,
            "units" to e.units.toString(), "gross" to e.grossProceeds.toString(), "fees" to e.fees.toString(), "note" to e.note,
        )
        is CashAdjustment -> linkedMapOf("type" to "ADJUSTMENT", "date" to e.date.toString(), "amount" to e.amount.toString(), "reason" to e.reason, "note" to e.note)
    }

    fun toJson(e: LedgerEvent): String = Json.write(toMap(e))

    fun fromJson(text: String): LedgerEvent = fromAny(Json.parse(text))

    fun fromAny(any: Any?): LedgerEvent {
        val m = asMap(any, "evento")
        try {
            return when (val type = text(m, "type")) {
                "OPENING" -> OpeningBalance(date(m, "date"), money(m, "amount"), optText(m, "note") ?: "Saldo inicial")
                "INCOME" -> MonthlyIncome(date(m, "date"), period(m, "period"), money(m, "amount"), optText(m, "note") ?: "Ingreso mensual")
                "PURCHASE" -> Purchase(date(m, "date"), AssetId(text(m, "asset")), money(m, "invested"), units(m, "units"), money(m, "fees"), optText(m, "note") ?: "")
                "SALE" -> Sale(date(m, "date"), AssetId(text(m, "asset")), units(m, "units"), money(m, "gross"), money(m, "fees"), optText(m, "note") ?: "")
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
            if (history.isEmpty()) throw CodecException("La cuenta no tiene configuración de ingresos")
            return AccountConfig(AccountId(text(m, "id")), text(m, "name"), date(m, "createdOn"), history, intervals)
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
