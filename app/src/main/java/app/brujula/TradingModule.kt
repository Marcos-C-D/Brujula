package app.brujula

import app.brujula.app.Notifications
import app.brujula.app.TaxViews
import app.brujula.model.AssetId
import app.brujula.market.AssetCatalog
import app.brujula.market.EurView
import app.brujula.market.UpdateSchedule
import app.brujula.tax.DividendDeclaration
import app.brujula.tax.DividendFeed
import app.brujula.model.AccountId
import app.brujula.tax.TaxRules
import app.brujula.tax.TaxService
import app.brujula.tax.TaxSettings
import app.brujula.trading.PriceSource
import app.brujula.trading.TradingService
import java.time.Instant

/**
 * Procesa las órdenes de todas las cuentas activas con los precios guardados: ejecuciones, liquidaciones, caducidades,
 * planes periódicos, ingresos mensuales y hechos fiscales (dividendos, retenciones, IRPF), día a día y en orden.
 * Se ejecuta tras actualizar los precios (00:00 de Madrid) y usa la misma instancia de [app.brujula.engine.Bank] que la interfaz.
 */
object TradingModule {

    /** Precios guardados, en EUR. */
    fun priceSource(container: AppContainer): PriceSource {
        val marketStore = container.marketStore
        return PriceSource { id -> marketStore.loadSeries(id)?.let { EurView.of(it, marketStore) }?.series?.takeIf { !it.isEmpty } }
    }

    /** Dividendos guardados: solo se usan si el proxy cotiza en EUR (los demás necesitarían convertir cada pago). */
    fun dividendFeed(container: AppContainer): DividendFeed {
        val marketStore = container.marketStore
        return DividendFeed { id ->
            marketStore.loadSeries(id)?.takeIf { it.currency == "EUR" }?.dividends?.map { DividendDeclaration(it.date, it.amount) } ?: emptyList()
        }
    }

    /** Servicio de contratación con los precios guardados (en EUR). */
    fun service(container: AppContainer, afterDay: ((AccountId, java.time.LocalDate) -> List<String>)? = null): TradingService =
        TradingService(container.awaitBank(), { AssetCatalog.byId(it) }, priceSource(container), clock = { Instant.now() }, afterDay = afterDay)

    fun processAll(container: AppContainer): List<String> {
        val bank = container.awaitBank()
        val feed = dividendFeed(container)
        // Cada cuenta tiene sus propias opciones fiscales.
        val taxByAccount = HashMap<AccountId, TaxService>()
        fun taxFor(id: AccountId): TaxService = taxByAccount.getOrPut(id) {
            TaxService(bank, { AssetCatalog.byId(it) }, { AssetCatalog.DEFAULT.map { a -> a.id } }, feed, TaxRules(), TaxSettings.fromOptions(bank.get(id).taxOptions))
        }
        val service = service(container) { id, day -> taxFor(id).processDay(id, day) }
        val today = UpdateSchedule.today(Instant.now())
        val before = bank.active().associate { it.id to it.ledger.records.size }
        // Los datos de mercado de hoy no existen aún a las 00:00: se conocen hasta el día anterior.
        val report = bank.active().flatMap { service.process(it.id, today.minusDays(1), today) }
        sendNotifications(container, before, today)
        return report
    }

    /** Avisos de lo que ha cambiado (ingresos, órdenes, dividendos, impuestos) y de las fechas de impuestos próximas. */
    private fun sendNotifications(container: AppContainer, before: Map<AccountId, Int>, today: java.time.LocalDate) {
        val bank = container.awaitBank()
        val ctx = container.context
        val catalog = { id: AssetId -> AssetCatalog.byId(id) }
        for (acc in bank.active()) {
            val records = acc.ledger.records
            Notifications.fromRecords(acc.name, records.drop(before[acc.id] ?: records.size), catalog)
                .forEach { NotificationHelper.notify(ctx, it.key, it.title, it.text) }
            val overview = TaxViews.overview(records, today, TaxSettings.fromOptions(acc.taxOptions), catalog)
            Notifications.upcomingTax(acc.name, overview.upcoming, today)
                .forEach { if (NotificationHelper.once(ctx, it.key)) NotificationHelper.notify(ctx, it.key, it.title, it.text) }
        }
    }
}
