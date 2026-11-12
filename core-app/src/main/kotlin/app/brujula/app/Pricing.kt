package app.brujula.app

import app.brujula.market.PriceSeries
import app.brujula.model.AssetId
import app.brujula.model.Money
import java.time.LocalDate

data class PriceQuote(val price: Money, val date: LocalDate)

/** Último precio en EUR igual o anterior a una fecha. Nunca se inventa uno: si no hay dato, devuelve null. */
fun interface PriceLookup {
    fun priceOn(assetId: AssetId, date: LocalDate): PriceQuote?
}

class SeriesPriceLookup(private val series: (AssetId) -> PriceSeries?) : PriceLookup {
    override fun priceOn(assetId: AssetId, date: LocalDate): PriceQuote? {
        val s = series(assetId) ?: return null
        val p = s.onOrBefore(date) ?: return null
        return PriceQuote(Money.of(p.close), p.date)
    }
}
