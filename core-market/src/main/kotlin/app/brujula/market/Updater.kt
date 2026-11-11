package app.brujula.market

import app.brujula.model.AssetId
import java.time.Instant
import java.time.LocalDate

/** Serie guardada de un activo, con su procedencia y el resultado del último intento de actualización. */
data class StoredSeries(
    val assetId: AssetId,
    val symbol: String,
    val provider: String,
    val currency: String,
    val points: List<PricePoint>,
    /** Cuándo se obtuvieron estos precios (última actualización correcta). */
    val fetchedAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastError: String?,
    /** Dividendos por unidad en la divisa de la serie (solo se usan los de series en EUR). */
    val dividends: List<DividendEvent> = emptyList(),
)

data class StoredFx(val currency: String, val rates: Map<LocalDate, java.math.BigDecimal>, val fetchedAt: Instant)

/** Almacén de datos de mercado (en Android: SQLite). Sobrescribe por activo/divisa. */
interface MarketStore {
    fun loadSeries(assetId: AssetId): StoredSeries?
    fun saveSeries(series: StoredSeries)
    fun loadFx(currency: String): StoredFx?
    fun saveFx(fx: StoredFx)
    fun lastSuccessfulUpdate(): Instant?
    fun saveLastSuccessfulUpdate(at: Instant)
}

class InMemoryMarketStore : MarketStore {
    private val series = HashMap<AssetId, StoredSeries>()
    private val fx = HashMap<String, StoredFx>()
    private var last: Instant? = null
    @Synchronized override fun loadSeries(assetId: AssetId) = series[assetId]
    @Synchronized override fun saveSeries(series: StoredSeries) { this.series[series.assetId] = series }
    @Synchronized override fun loadFx(currency: String) = fx[currency]
    @Synchronized override fun saveFx(fx: StoredFx) { this.fx[fx.currency] = fx }
    @Synchronized override fun lastSuccessfulUpdate() = last
    @Synchronized override fun saveLastSuccessfulUpdate(at: Instant) { last = at }
}

sealed class UpdateOutcome {
    abstract val assetId: AssetId

    data class Updated(
        override val assetId: AssetId, val symbol: String, val provider: String, val points: Int, val warnings: List<String>,
        /** Intentos fallidos previos con otros símbolos o proveedores. */
        val skippedErrors: List<String>,
    ) : UpdateOutcome()

    /** No se pudo actualizar: se conserva el último precio válido (si existía) y se informa de su antigüedad. */
    data class Failed(override val assetId: AssetId, val errors: List<String>, val keptLastDate: LocalDate?) : UpdateOutcome()
}

/**
 * Actualiza los precios probando, en orden, los proveedores y símbolos de cada activo.
 * Reglas: nunca se inventan datos; si falla todo se conserva lo guardado; si una serie nueva es sospechosamente
 * más corta que la guardada se rechaza; las peticiones se espacian para respetar los límites de las APIs gratuitas.
 */
const val SWITCH_AFTER_DAYS = 14L

class MarketUpdater(
    private val store: MarketStore,
    private val providers: Map<ProviderId, HistoryProvider>,
    private val ecb: EcbFxProvider?,
    private val clock: () -> Instant,
    private val pause: () -> Unit = {},
) {
    fun updateAll(catalog: List<AssetDefinition>): List<UpdateOutcome> {
        val outcomes = ArrayList<UpdateOutcome>()
        for ((i, def) in catalog.withIndex()) {
            if (i > 0) pause()
            outcomes += updateAsset(def)
        }
        updateFx(outcomes)
        if (outcomes.any { it is UpdateOutcome.Updated }) store.saveLastSuccessfulUpdate(clock())
        return outcomes
    }

    fun updateAsset(def: AssetDefinition): UpdateOutcome {
        val now = clock()
        val previous = store.loadSeries(def.id)
        val errors = ArrayList<String>()
        for (src in candidates(def, previous, now)) {
            val provider = providers[src.provider]
            if (provider == null) { errors += "Proveedor ${src.provider} no disponible"; continue }
            try {
                val fetched = provider.fetchHistory(src.symbol, now)
                if (fetched.points.size < 2) { errors += "[${provider.name}] ${src.symbol}: menos de 2 precios"; continue }
                val merged = merge(def, previous, fetched, errors, src.symbol, provider.name) ?: continue
                store.saveSeries(
                    StoredSeries(def.id, src.symbol, fetched.provider, fetched.currency, merged, now, now, null, fetched.dividends),
                )
                return UpdateOutcome.Updated(def.id, src.symbol, fetched.provider, merged.size, fetched.warnings, errors.toList())
            } catch (e: ProviderException) {
                errors += e.message ?: "Error de proveedor"
            }
            pause()
        }
        val message = errors.joinToString(" | ")
        // Se conserva lo guardado y se anota el intento fallido.
        if (previous != null) store.saveSeries(previous.copy(lastAttemptAt = now, lastError = message))
        else store.saveSeries(StoredSeries(def.id, "", "", "", emptyList(), null, now, message))
        return UpdateOutcome.Failed(def.id, errors, previous?.points?.lastOrNull()?.date)
    }

    /**
     * Orden de fuentes. Un activo mantiene su proxy guardado (no se salta entre ETF distintos por un fallo
     * pasajero): solo se prueban alternativas si no hay datos, si el símbolo guardado ya no está en el
     * catálogo o si la última actualización buena tiene más de [SWITCH_AFTER_DAYS] días. Bitcoin sí puede
     * alternar entre proveedores porque es el mismo activo.
     */
    private fun candidates(def: AssetDefinition, previous: StoredSeries?, now: Instant): List<SourceSpec> {
        if (def.kind == AssetKind.CRYPTO) return def.sources
        if (previous == null || previous.points.isEmpty() || def.sources.none { it.symbol == previous.symbol }) return def.sources
        val stale = previous.fetchedAt == null ||
            java.time.Duration.between(previous.fetchedAt, now).toDays() >= SWITCH_AFTER_DAYS
        return if (stale) def.sources else def.sources.filter { it.symbol == previous.symbol }
    }

    private fun merge(
        def: AssetDefinition, previous: StoredSeries?, fetched: FetchedSeries,
        errors: MutableList<String>, symbol: String, provider: String,
    ): List<PricePoint>? {
        val old = previous?.points.orEmpty()
        if (old.isEmpty()) return PriceSeries.of(fetched.points).points
        if (def.kind == AssetKind.CRYPTO) {
            if (previous!!.currency != fetched.currency) {
                errors += "[$provider] $symbol: divisa ${fetched.currency} distinta de la guardada (${previous.currency}); no se mezclan"
                return null
            }
            // Mismo activo en otro proveedor: se conserva el histórico y las fechas nuevas pisan a las antiguas.
            if (previous.provider == provider && fetched.points.size < old.size * 0.9) {
                errors += "[$provider] $symbol: la serie recibida (${fetched.points.size}) es mucho más corta que la guardada (${old.size}); se rechaza"
                return null
            }
            return PriceSeries.of(old + fetched.points).points
        }
        if (previous!!.symbol == symbol && previous.currency == fetched.currency && fetched.points.size < old.size * 0.9) {
            errors += "[$provider] $symbol: la serie recibida (${fetched.points.size}) es mucho más corta que la guardada (${old.size}); se rechaza"
            return null
        }
        // Mismo símbolo: serie completa y coherente (cierres ajustados). Símbolo distinto (cambio de proxy): se
        // sustituye entera; nunca se mezclan precios de instrumentos diferentes.
        return PriceSeries.of(fetched.points).points
    }

    private fun updateFx(outcomes: List<UpdateOutcome>) {
        val ecb = ecb ?: return
        val needed = outcomes.filterIsInstance<UpdateOutcome.Updated>()
            .mapNotNull { store.loadSeries(it.assetId)?.currency }
            .map { if (it == "GBp") "GBP" else it }
            .filter { it != "EUR" && it in FxConverter.SUPPORTED }
            .toSet()
        for (ccy in needed) {
            try {
                val fx = ecb.fetch(ccy)
                store.saveFx(StoredFx(ccy, fx.entries, clock()))
            } catch (e: ProviderException) {
                // Se conserva el tipo guardado, si lo hay.
            }
        }
    }
}

/** Vista en euros de un activo guardado: convierte con el tipo de cambio guardado y explica lo que no se pudo convertir. */
object EurView {
    fun of(stored: StoredSeries, store: MarketStore): EurSeries? {
        if (stored.points.isEmpty()) return null
        val currency = stored.currency
        if (currency !in FxConverter.SUPPORTED) {
            return EurSeries(PriceSeries.EMPTY, currency, stored.points.map { it.date }, "Divisa $currency no soportada")
        }
        val ccy = if (currency == "GBp") "GBP" else currency
        val fx = store.loadFx(ccy)?.let { FxSeries(it.currency, it.rates) }
        return FxConverter.toEur(PriceSeries.of(stored.points), currency, fx)
    }
}
