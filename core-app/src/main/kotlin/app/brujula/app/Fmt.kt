package app.brujula.app

import app.brujula.model.Money
import app.brujula.model.Units
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formato es-ES: coma decimal, punto de millares, euros y fechas dd/MM/yyyy. */
object Fmt {
    private fun symbols() = DecimalFormatSymbols(Locale("es", "ES")).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }

    private fun format(value: BigDecimal, pattern: String): String =
        DecimalFormat(pattern, symbols()).apply { roundingMode = RoundingMode.HALF_UP }.format(value)

    fun eur(m: Money): String = format(m.amount, "#,##0.00") + " €"

    fun eurOrDash(m: Money?): String = m?.let { eur(it) } ?: "—"

    /** Precio con hasta 4 decimales (los precios de activos no siempre son céntimos). */
    fun price(m: Money): String = format(m.amount, "#,##0.00##") + " €"

    fun units(u: Units): String = format(u.value, "#,##0.######")

    fun pct(p: BigDecimal, signed: Boolean = false): String {
        val body = format(p, "#,##0.00") + " %"
        return if (signed && p.signum() > 0) "+$body" else body
    }

    fun pctOrDash(p: BigDecimal?, signed: Boolean = true): String = p?.let { pct(it, signed) } ?: "—"

    fun date(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

    /** Convierte lo que escribe el usuario («1.234,50» o «1234,5») en un importe; null si no es válido. */
    fun parseMoney(text: String): Money? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return try { Money.of(normalized) } catch (e: NumberFormatException) { null }
    }

    fun parseUnits(text: String): Units? {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return null
        val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
        return try { Units.of(normalized) } catch (e: NumberFormatException) { null }
    }
}
