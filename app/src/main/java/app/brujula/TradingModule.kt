package app.brujula

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

    /** Servicio de contratación con los precios guardados (en EUR). */
    fun service(container: AppContainer, afterDay: ((AccountId, java.time.LocalDate) -> List<String>)? = null): TradingService {
        val marketStore = container.marketStore
        val prices = PriceSource { id ->
            marketStore.loadSeries(id)?.let { EurView.of(it, marketStore) }?.series?.takeIf { !it.isEmpty }
        }
        return TradingService(container.awaitBank(), { AssetCatalog.byId(it) }, prices, clock = { Instant.now() }, afterDay = afterDay)
    }

    fun processAll(container: AppContainer): List<String> {
        val marketStore = container.marketStore
        val bank = container.awaitBank()
        // Los dividendos solo se usan si el proxy cotiza en EUR (los demás necesitarían convertir cada pago).
        val feed = DividendFeed { id ->
            marketStore.loadSeries(id)?.takeIf { it.currency == "EUR" }?.dividends?.map { DividendDeclaration(it.date, it.amount) } ?: emptyList()
        }
        // Cada cuenta tiene sus propias opciones fiscales.
        val taxByAccount = HashMap<AccountId, TaxService>()
        fun taxFor(id: AccountId): TaxService = taxByAccount.getOrPut(id) {
            TaxService(bank, { AssetCatalog.byId(it) }, { AssetCatalog.DEFAULT.map { a -> a.id } }, feed, TaxRules(), TaxSettings.fromOptions(bank.get(id).taxOptions))
        }
        val service = service(container) { id, day -> taxFor(id).processDay(id, day) }
        val today = UpdateSchedule.today(Instant.now())
        // Los datos de mercado de hoy no existen aún a las 00:00: se conocen hasta el día anterior.
        return bank.active().flatMap { service.process(it.id, today.minusDays(1), today) }
    }
}
