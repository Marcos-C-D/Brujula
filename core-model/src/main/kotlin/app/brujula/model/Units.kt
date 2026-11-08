package app.brujula.model

import java.math.BigDecimal

/**
 * Número de participaciones, unidades de ETF o fracciones de Bitcoin. Exacto y con
 * igualdad independiente de la escala (como [Money]).
 */
class Units private constructor(val value: BigDecimal) : Comparable<Units> {

    operator fun plus(other: Units): Units = Units(value.add(other.value))

    operator fun minus(other: Units): Units = Units(value.subtract(other.value))

    val signum: Int get() = value.signum()
    val isZero: Boolean get() = value.signum() == 0
    val isPositive: Boolean get() = value.signum() > 0
    val isNegative: Boolean get() = value.signum() < 0

    override fun compareTo(other: Units): Int = value.compareTo(other.value)

    override fun equals(other: Any?): Boolean = other is Units && value.compareTo(other.value) == 0

    override fun hashCode(): Int = value.stripTrailingZeros().hashCode()

    override fun toString(): String = value.toPlainString()

    companion object {
        val ZERO: Units = Units(BigDecimal.ZERO)

        fun of(text: String): Units = Units(BigDecimal(text.trim()))

        fun of(value: BigDecimal): Units = Units(value)

        fun of(whole: Long): Units = Units(BigDecimal.valueOf(whole))
    }
}
