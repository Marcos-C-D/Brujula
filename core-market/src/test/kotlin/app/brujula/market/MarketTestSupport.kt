package app.brujula.market

import java.math.BigDecimal
import java.time.LocalDate

internal fun d(s: String): LocalDate = LocalDate.parse(s)
internal fun bd(s: String) = BigDecimal(s)
internal fun pt(date: String, close: String, adj: String? = null) = PricePoint(d(date), bd(close), adjClose = adj?.let { bd(it) })
