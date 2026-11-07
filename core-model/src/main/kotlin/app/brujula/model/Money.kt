package app.brujula.model

import java.math.BigDecimal

/**
 * Cantidad de dinero en euros, exacta (BigDecimal), sin redondeo implícito.
 *
 * Nunca se construye a partir de Double: 0,1 + 0,2 debe ser exactamente 0,3.
 * Las reglas de redondeo (a céntimos, por operación o por periodo) se definirán
 * en la fase 1 junto con el libro de movimientos; aquí solo se fija la base.
 */
@JvmInline
value class Money private constructor(val amount: BigDecimal) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(amount.add(other.amount))

    operator fun minus(other: Money): Money = Money(amount.subtract(other.amount))

    operator fun unaryMinus(): Money = Money(amount.negate())

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    val isNegative: Boolean get() = amount.signum() < 0

    override fun toString(): String = amount.toPlainString()

    companion object {
        val ZERO: Money = Money(BigDecimal.ZERO)

        /** Acepta "12.50" (punto decimal). Lanza NumberFormatException si no es válido. */
        fun of(text: String): Money = Money(BigDecimal(text.trim()))

        fun of(units: Long): Money = Money(BigDecimal.valueOf(units))
    }
}
