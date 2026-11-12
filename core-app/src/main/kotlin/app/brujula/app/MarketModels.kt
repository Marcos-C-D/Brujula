package app.brujula.app

import app.brujula.market.AssetDefinition
import app.brujula.market.EurView
import app.brujula.market.Freshness
import app.brujula.market.MarketStore
import app.brujula.market.Markets
import app.brujula.market.Performance
import app.brujula.market.PriceSeries
import app.brujula.market.PriceStatus
import app.brujula.market.ReturnPeriod
import app.brujula.market.ReturnResult
import app.brujula.market.Risk
import app.brujula.market.VolatilityResult
import app.brujula.model.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

data class MarketRow(
    val def: AssetDefinition,
    val price: Money?,
    val priceDate: LocalDate?,
    val dayChangePct: BigDecimal?,
    val ytd: ReturnResult?,
    val year1: ReturnResult?,
    val riskClass: Int?,
    val stale: Boolean,
    val hasData: Boolean,
    val note: String?,
)

data class AssetDetail(
    val def: AssetDefinition,
    val series: PriceSeries,
    val originalCurrency: String,
    val conversionNote: String,
    val returns: Map<ReturnPeriod, ReturnResult>,
    val volatility1y: VolatilityResult,
    val volatility3y: VolatilityResult,
    val riskClass: Int?,
    val riskText: String,
    val symbol: String,
    val provider: String,
    val fetchedAt: Instant?,
    val lastError: String?,
    val status: PriceStatus,
)

object MarketModels {

    fun rows(catalog: List<AssetDefinition>, store: MarketStore, today: LocalDate): List<MarketRow> = catalog.map { def ->
        val stored = store.loadSeries(def.id)
        val eur = stored?.let { EurView.of(it, store) }
        if (stored == null || eur == null || eur.series.isEmpty) {
            return@map MarketRow(def, null, null, null, null, null, null, false, false, stored?.lastError ?: eur?.note ?: "Aún sin datos: actualiza los precios")
        }
        val s = eur.series
        val last = s.last
        val prev = s.points.getOrNull(s.points.size - 2)
        val change = prev?.let {
            Money.of(last.close).amount.divide(it.close, MathContext(20)).subtract(BigDecimal.ONE).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        }
        val vol = Risk.volatility(s, 1)
        MarketRow(
            def, Money.of(last.close), last.date, change,
            Performance.calculate(s, ReturnPeriod.YTD), Performance.calculate(s, ReturnPeriod.Y1),
            (vol as? VolatilityResult.Value)?.let { Risk.riskClass(it.annualizedPercent) },
            Freshness.status(last.date, today, def.marketId?.let { Markets.byId(it) }).isStale,
            true, stored.lastError,
        )
    }

    fun detail(def: AssetDefinition, store: MarketStore, today: LocalDate): AssetDetail? {
        val stored = store.loadSeries(def.id) ?: return null
        val eur = EurView.of(stored, store) ?: return null
        if (eur.series.isEmpty) return null
        val s = eur.series
        val v1 = Risk.volatility(s, 1)
        val v3 = Risk.volatility(s, 3)
        val riskClass = (v1 as? VolatilityResult.Value)?.let { Risk.riskClass(it.annualizedPercent) }
        val note = buildString {
            append(eur.note)
            if (eur.missingFxDates.isNotEmpty()) append(". ${eur.missingFxDates.size} fechas sin tipo de cambio no se muestran")
        }
        return AssetDetail(
            def, s, eur.originalCurrency, note, Performance.returns(s), v1, v3, riskClass, riskText(riskClass, v1),
            stored.symbol, stored.provider, stored.fetchedAt, stored.lastError,
            Freshness.status(s.last.date, today, def.marketId?.let { Markets.byId(it) }),
        )
    }

    private val CLASS_MEANING = mapOf(
        1 to "muy baja: los precios casi no se mueven",
        2 to "baja",
        3 to "baja-media",
        4 to "media",
        5 to "media-alta",
        6 to "alta: son habituales caídas y subidas fuertes",
        7 to "muy alta: son posibles caídas muy importantes",
    )

    fun riskText(riskClass: Int?, vol: VolatilityResult): String = when (vol) {
        is VolatilityResult.Insufficient -> "Sin datos suficientes para calcular el riesgo (${vol.reason})."
        is VolatilityResult.Value ->
            "Clase de riesgo ${riskClass ?: "?"} de 7 (${CLASS_MEANING[riskClass] ?: ""}). Se calcula con la volatilidad histórica: " +
                "la desviación típica de las variaciones diarias del último año, anualizada (${Fmt.pct(vol.annualizedPercent)}). " +
                "Es un dato del pasado, no una predicción."
    }
}
