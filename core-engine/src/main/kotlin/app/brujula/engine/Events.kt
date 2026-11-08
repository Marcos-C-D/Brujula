package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.LocalDate
import java.time.YearMonth

/**
 * Hecho inmutable que modifica una cuenta. El estado de la cuenta es siempre el resultado de
 * aplicar sus eventos en orden. Todos los importes son céntimos exactos (sin redondeos ocultos).
 */
sealed interface LedgerEvent {
    val date: LocalDate
    val note: String
}

private fun Money.requireCents(campo: String) {
    if (!isWholeCents) throw InvalidEntryException("$campo debe estar en céntimos exactos (máx. 2 decimales): $this")
}

/** Saldo inicial al crear la cuenta. */
data class OpeningBalance(
    override val date: LocalDate,
    val amount: Money,
    override val note: String = "Saldo inicial",
) : LedgerEvent {
    init {
        if (amount.isNegative) throw InvalidEntryException("El saldo inicial no puede ser negativo")
        amount.requireCents("El saldo inicial")
    }
}

/** Ingreso mensual automático correspondiente a un periodo (año-mes). */
data class MonthlyIncome(
    override val date: LocalDate,
    val period: YearMonth,
    val amount: Money,
    override val note: String = "Ingreso mensual",
) : LedgerEvent {
    init {
        if (!amount.isPositive) throw InvalidEntryException("El ingreso mensual debe ser positivo")
        amount.requireCents("El ingreso mensual")
    }
}

/**
 * Compra: se invierte [invested] y se pagan [fees] aparte. El efectivo baja en invested + fees y ese
 * total es el coste fiscal del lote (las comisiones forman parte del valor de adquisición).
 */
data class Purchase(
    override val date: LocalDate,
    val assetId: AssetId,
    val invested: Money,
    val units: Units,
    val fees: Money = Money.ZERO,
    override val note: String = "",
) : LedgerEvent {
    init {
        if (!invested.isPositive) throw InvalidEntryException("El importe invertido debe ser positivo")
        if (fees.isNegative) throw InvalidEntryException("Las comisiones no pueden ser negativas")
        if (!units.isPositive) throw InvalidEntryException("Las unidades compradas deben ser positivas")
        invested.requireCents("El importe invertido")
        fees.requireCents("Las comisiones")
    }
    val totalCost: Money get() = invested + fees
}

/**
 * Venta: se reciben [grossProceeds] y se pagan [fees]. El efectivo sube en gross - fees
 * (valor de transmisión neto).
 */
data class Sale(
    override val date: LocalDate,
    val assetId: AssetId,
    val units: Units,
    val grossProceeds: Money,
    val fees: Money = Money.ZERO,
    override val note: String = "",
) : LedgerEvent {
    init {
        if (!units.isPositive) throw InvalidEntryException("Las unidades vendidas deben ser positivas")
        if (grossProceeds.isNegative) throw InvalidEntryException("El importe de la venta no puede ser negativo")
        if (fees.isNegative) throw InvalidEntryException("Las comisiones no pueden ser negativas")
        if (fees > grossProceeds) throw InvalidEntryException("Las comisiones no pueden superar el importe de la venta")
        grossProceeds.requireCents("El importe de la venta")
        fees.requireCents("Las comisiones")
    }
    val netProceeds: Money get() = grossProceeds - fees
}

/**
 * Ajuste de efectivo con signo (por ejemplo, pago de impuestos). Es el único movimiento que puede
 * dejar el efectivo en negativo: no se bloquea un pago ni se inventa dinero.
 */
data class CashAdjustment(
    override val date: LocalDate,
    val amount: Money,
    val reason: String,
    override val note: String = reason,
) : LedgerEvent {
    init {
        if (amount.isZero) throw InvalidEntryException("Un ajuste de efectivo no puede ser cero")
        if (reason.isBlank()) throw InvalidEntryException("Un ajuste de efectivo necesita un motivo")
        amount.requireCents("El ajuste de efectivo")
    }
}
