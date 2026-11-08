package app.brujula.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Cantidad de dinero en euros, exacta (BigDecimal), sin redondeo implícito.
 *
 * - Nunca se construye a partir de Double: 0,1 + 0,2 es exactamente 0,3.
 * - La igualdad ignora la escala: 1.0 == 1.00 (a diferencia de BigDecimal.equals).
 * - Los importes que entran en el libro de movimientos deben ser céntimos exactos
 *   ([isWholeCents]); el redondeo se hace de forma explícita con [roundedToCents].
 */
class Money private constructor(val amount: BigDecimal) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(amount.add(other.amount))

    operator fun minus(other: Money): Money = Money(amount.subtract(other.amount))

    operator fun unaryMinus(): Money = Money(amount.negate())

    operator fun times(factor: BigDecimal): Money = Money(amount.multiply(factor))

    operator fun times(units: Units): Money = Money(amount.multiply(units.value))

    fun abs(): Money = Money(amount.abs())

    val signum: Int get() = amount.signum()
    val isZero: Boolean get() = amount.signum() == 0
    val isPositive: Boolean get() = amount.signum() > 0
    val isNegative: Boolean get() = amount.signum() < 0

    /** true si el importe no tiene más de 2 decimales significativos (céntimos exactos). */
    val isWholeCents: Boolean get() = amount.stripTrailingZeros().scale() <= 2

    /** Redondeo explícito a céntimos (por defecto, mitad hacia arriba). */
    fun roundedToCents(mode: RoundingMode = RoundingMode.HALF_UP): Money =
        Money(amount.setScale(2, mode))

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    override fun equals(other: Any?): Boolean = other is Money && amount.compareTo(other.amount) == 0

    override fun hashCode(): Int = amount.stripTrailingZeros().hashCode()

    override fun toString(): String = amount.toPlainString()

    companion object {
        val ZERO: Money = Money(BigDecimal.ZERO)

        /** Acepta "12.50" (punto decimal). Lanza NumberFormatException si no es válido. */
        fun of(text: String): Money = Money(BigDecimal(text.trim()))

        fun of(units: Long): Money = Money(BigDecimal.valueOf(units))

        fun of(value: BigDecimal): Money = Money(value)
    }
}
