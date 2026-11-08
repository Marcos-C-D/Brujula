package app.brujula.engine

import app.brujula.engine.json.Json
import app.brujula.engine.json.JsonException
import java.time.LocalDate

class BackupException(message: String) : RuntimeException(message)

/**
 * Copia de seguridad completa en JSON: cuentas (también archivadas), configuración, historial de ingresos
 * y todos los movimientos, con importes como texto para no perder ni un céntimo.
 * Al leerla se reproduce y se audita cada cuenta; si algo no cuadra se rechaza TODA la copia.
 */
object BackupService {
    const val FORMAT = "brujula-backup"
    const val VERSION = 1

    fun export(accounts: List<StoredAccount>, exportedOn: LocalDate): String =
        Json.write(
            linkedMapOf(
                "format" to FORMAT,
                "version" to VERSION,
                "exportedOn" to exportedOn.toString(),
                "accounts" to accounts.map { a ->
                    EventCodec.toMap(a.config).toMutableMap().also {
                        it["events"] = a.events.map { e -> EventCodec.toMap(e) }
                    }
                },
            ),
        )

    fun export(bank: Bank, exportedOn: LocalDate): String = export(bank.snapshot(), exportedOn)

    /** Valida y devuelve las cuentas de una copia. No toca ningún dato existente. */
    fun parse(json: String): List<StoredAccount> {
        try {
            val root = Json.parse(json) as? Map<*, *> ?: throw BackupException("La copia no es un objeto JSON")
            if (root["format"] != FORMAT) throw BackupException("No es una copia de seguridad de Brújula")
            val version = (root["version"] as? java.math.BigDecimal)?.toInt()
            if (version != VERSION) throw BackupException("Versión de copia no soportada: $version (se esperaba $VERSION)")
            val list = root["accounts"] as? List<*> ?: throw BackupException("La copia no contiene cuentas")
            val result = list.map { raw ->
                val m = raw as? Map<*, *> ?: throw BackupException("Cuenta con formato incorrecto")
                val config = EventCodec.configFromAny(m)
                val events = (m["events"] as? List<*> ?: throw BackupException("Cuenta ${config.id} sin movimientos"))
                    .map { EventCodec.fromAny(it) }
                try {
                    Bank.replayChecked(config.id, events)
                } catch (e: LedgerException) {
                    throw BackupException("La cuenta \"${config.name}\" no es coherente: ${e.message}")
                }
                StoredAccount(config, events)
            }
            if (result.map { it.config.id }.toSet().size != result.size) throw BackupException("La copia repite identificadores de cuenta")
            return result
        } catch (e: BackupException) {
            throw e
        } catch (e: JsonException) {
            throw BackupException("JSON no válido: ${e.message}")
        } catch (e: CodecException) {
            throw BackupException(e.message ?: "Datos no válidos")
        } catch (e: ClassCastException) {
            throw BackupException("Estructura de la copia incorrecta")
        }
    }
}

/** Opciones de exportación CSV. Por defecto, formato para Excel en español: ';' y coma decimal. */
data class CsvOptions(val separator: Char = ';', val decimalComma: Boolean = true)

object CsvExporter {
    private val HEADER = listOf("n", "fecha", "tipo", "activo", "unidades", "importe_efectivo", "comisiones", "saldo_efectivo", "nota")

    /**
     * Un movimiento por fila. `importe_efectivo` es el efecto en el efectivo con signo (compras negativas, ventas
     * netas positivas) y `saldo_efectivo` el efectivo tras el movimiento.
     */
    fun ledger(records: List<LedgerRecord>, options: CsvOptions = CsvOptions()): String {
        val sb = StringBuilder()
        sb.append(HEADER.joinToString(options.separator.toString())).append("\r\n")
        var balance = app.brujula.model.Money.ZERO
        for (r in records) {
            val e = r.event
            val row: List<String> = when (e) {
                is OpeningBalance -> { balance += e.amount; listOf("APERTURA", "", "", cash(e.amount), "", cash(balance), text(e.note)) }
                is MonthlyIncome -> { balance += e.amount; listOf("INGRESO", "", "", cash(e.amount), "", cash(balance), text(e.note)) }
                is Purchase -> { balance -= e.totalCost; listOf("COMPRA", text(e.assetId.value), e.units.toString(), cash(-e.totalCost), cash(e.fees), cash(balance), text(e.note)) }
                is Sale -> { balance += e.netProceeds; listOf("VENTA", text(e.assetId.value), e.units.toString(), cash(e.netProceeds), cash(e.fees), cash(balance), text(e.note)) }
                is CashAdjustment -> { balance += e.amount; listOf("AJUSTE", "", "", cash(e.amount), "", cash(balance), text(e.note)) }
            }
            val fields = listOf(r.seq.toString(), e.date.toString()) + row
            // columnas numéricas: unidades, importe_efectivo, comisiones, saldo_efectivo (posiciones 4 a 7)
            val line = fields.mapIndexed { i, f -> escape(if (i in 4..7) localize(f, options) else f, options.separator) }
            sb.append(line.joinToString(options.separator.toString())).append("\r\n")
        }
        return sb.toString()
    }

    private fun cash(m: app.brujula.model.Money): String = m.roundedToCents().toString()

    // Texto libre: neutraliza fórmulas de hoja de cálculo (=, +, -, @ al principio).
    private fun text(s: String): String = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s

    // Solo se cambia el punto decimal en campos puramente numéricos.
    private fun localize(field: String, o: CsvOptions): String =
        if (o.decimalComma && Regex("-?\\d+\\.\\d+").matches(field)) field.replace('.', ',') else field

    private fun escape(field: String, sep: Char): String =
        if (field.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}
