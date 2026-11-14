package app.brujula

import app.brujula.app.AccountStats
import app.brujula.app.TimeMachine
import app.brujula.app.AccountValuation
import app.brujula.app.MovementRow
import app.brujula.app.OrderRow
import app.brujula.app.OrderViews
import app.brujula.app.Stats
import app.brujula.app.TaxOverview
import app.brujula.app.TaxViews
import app.brujula.engine.ScheduledPlan
import app.brujula.market.AssetKind
import app.brujula.model.Units
import app.brujula.tax.TaxSettings
import app.brujula.app.AssetDetail
import app.brujula.app.Fmt
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
    val orders: List<OrderRow> = emptyList(),
    val movements: List<MovementRow> = emptyList(),
    val plans: List<ScheduledPlan> = emptyList(),
    /** Unidades vendibles hoy por activo (liquidadas y no reservadas). */
    val sellable: Map<AssetId, Units> = emptyMap(),
    val stats: AccountStats? = null,
    val benchmarkId: AssetId = AssetId("msci-world"),
    val tax: TaxOverview? = null,
)

data class SandboxView(
    val today: LocalDate,
    val accountName: String,
    val valuation: AccountValuation,
    val movements: List<MovementRow>,
    val taxPostings: List<String>,
    val pendingOrders: Int,
    val dataThrough: LocalDate?,
)

object Presenter {

    /** Precios en EUR de los activos, cargados una sola vez por cálculo. */
    fun lookupFor(container: AppContainer): PriceLookup {
        val cache = HashMap<AssetId, PriceSeries?>()
        return SeriesPriceLookup { id ->
            cache.getOrPut(id) {
                container.marketStore.loadSeries(id)?.let { EurView.of(it, container.marketStore) }?.series?.takeIf { !it.isEmpty }
            }
        }
    }

    fun load(container: AppContainer, requested: AccountId?, benchmarkId: AssetId = AssetId("msci-world")): HomeData {
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

        val benchSeries = container.marketStore.loadSeries(benchmarkId)?.let { EurView.of(it, container.marketStore) }?.series?.takeIf { !it.isEmpty }
        val benchmark = benchSeries?.let { (AssetCatalog.byId(benchmarkId)?.name ?: benchmarkId.value) to it }
        val stats = account?.let { Stats.of(history, it.state, benchmark) }
        val valuesByKind: Map<AssetKind, Money> = valuation?.rows.orEmpty()
            .mapNotNull { r -> r.value?.let { v -> AssetCatalog.byId(r.assetId)?.kind?.let { k -> k to v } } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.fold(Money.ZERO) { a, b -> a + b } }
        val tax = account?.let {
            TaxViews.overview(it.ledger.records, today, TaxSettings.fromOptions(it.taxOptions), catalog, valuesByKind)
        }
        val sellable = account?.let { acc -> acc.state.positions.associate { p -> p.assetId to acc.state.unitsAvailable(p.assetId, today) } } ?: emptyMap()
        return HomeData(
            loading = false, today = today, accounts = accounts, selectedId = selected?.id, valuation = valuation, history = history,
            markets = MarketModels.rows(AssetCatalog.DEFAULT, container.marketStore, today),
            lastMarketUpdate = container.marketStore.lastSuccessfulUpdate(),
            orders = account?.let { OrderViews.rows(it.state, catalog) } ?: emptyList(),
            movements = account?.let { OrderViews.movements(it.ledger.records, catalog) } ?: emptyList(),
            plans = account?.schedules ?: emptyList(),
            sellable = sellable, stats = stats, benchmarkId = benchmarkId, tax = tax,
        )
    }

    /** Resumen de una cuenta dentro del simulador de tiempo. */
    fun sandboxView(container: AppContainer, tm: TimeMachine, selected: AccountId?): SandboxView? {
        val acc = tm.bank.all().firstOrNull { it.id == selected } ?: tm.bank.active().firstOrNull() ?: return null
        val lookup = lookupFor(container)
        val catalog = { id: AssetId -> AssetCatalog.byId(id) }
        val dataThrough = AssetCatalog.DEFAULT.mapNotNull { container.marketStore.loadSeries(it.id)?.points?.lastOrNull()?.date }.maxOrNull()
        return SandboxView(
            today = tm.today, accountName = acc.name,
            valuation = Valuation.of(acc.state, tm.today, lookup, catalog),
            movements = OrderViews.movements(acc.ledger.records, catalog).take(12),
            taxPostings = acc.state.taxPostings.map { "IRPF ${it.taxYear} (plazo ${it.installment}) · ${Fmt.date(it.date)} · ${Fmt.eur(it.amount)}" },
            pendingOrders = acc.state.pendingOrders.size, dataThrough = dataThrough,
        )
    }

    fun assetDetail(container: AppContainer, assetId: AssetId): AssetDetail? {
        val def = AssetCatalog.byId(assetId) ?: return null
        return MarketModels.detail(def, container.marketStore, container.today())
    }
}
