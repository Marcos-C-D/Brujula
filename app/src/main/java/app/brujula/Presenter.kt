package app.brujula

import app.brujula.app.AccountValuation
import app.brujula.app.AssetDetail
import app.brujula.app.History
import app.brujula.app.MarketModels
import app.brujula.app.MarketRow
import app.brujula.app.NetWorthPoint
import app.brujula.app.PriceLookup
import app.brujula.app.SeriesPriceLookup
import app.brujula.app.Valuation
import app.brujula.market.AssetCatalog
import app.brujula.market.EurView
import app.brujula.market.PriceSeries
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.model.Money
import java.time.Instant
import java.time.LocalDate

data class AccountItem(
    val id: AccountId,
    val name: String,
    val archived: Boolean,
    val netWorth: Money,
    val cash: Money,
    val complete: Boolean,
    val pendingOrders: Int,
)

/** Todo lo que necesitan las pantallas, calculado fuera del hilo principal. */
data class HomeData(
    val loading: Boolean = true,
    val error: String? = null,
    val today: LocalDate = LocalDate.now(),
    val accounts: List<AccountItem> = emptyList(),
    val selectedId: AccountId? = null,
    val valuation: AccountValuation? = null,
    val history: List<NetWorthPoint> = emptyList(),
    val markets: List<MarketRow> = emptyList(),
    val lastMarketUpdate: Instant? = null,
)

object Presenter {

    /** Precios en EUR de los activos, cargados una sola vez por cálculo. */
    private fun lookupFor(container: AppContainer): PriceLookup {
        val cache = HashMap<AssetId, PriceSeries?>()
        return SeriesPriceLookup { id ->
            cache.getOrPut(id) {
                container.marketStore.loadSeries(id)?.let { EurView.of(it, container.marketStore) }?.series?.takeIf { !it.isEmpty }
            }
        }
    }

    fun load(container: AppContainer, requested: AccountId?): HomeData {
        val bank = container.bank
        if (bank == null) {
            return if (container.startupError != null) HomeData(loading = false, error = container.startupError) else HomeData(loading = true)
        }
        val today = container.today()
        val lookup = lookupFor(container)
        val catalog = { id: AssetId -> AssetCatalog.byId(id) }

        val accounts = bank.all().map { acc ->
            val v = Valuation.of(acc.state, today, lookup, catalog)
            AccountItem(acc.id, acc.name, acc.isArchived, v.netWorth, v.cash, v.complete, acc.state.pendingOrders.size)
        }
        val selected = accounts.firstOrNull { it.id == requested } ?: accounts.firstOrNull { !it.archived } ?: accounts.firstOrNull()
        val account = selected?.let { bank.get(it.id) }
        val valuation = account?.let { Valuation.of(it.state, today, lookup, catalog) }
        val history = account?.let { History.compute(it.ledger.records, lookup, today) } ?: emptyList()
        return HomeData(
            loading = false, today = today, accounts = accounts, selectedId = selected?.id, valuation = valuation, history = history,
            markets = MarketModels.rows(AssetCatalog.DEFAULT, container.marketStore, today),
            lastMarketUpdate = container.marketStore.lastSuccessfulUpdate(),
        )
    }

    fun assetDetail(container: AppContainer, assetId: AssetId): AssetDetail? {
        val def = AssetCatalog.byId(assetId) ?: return null
        return MarketModels.detail(def, container.marketStore, container.today())
    }
}
