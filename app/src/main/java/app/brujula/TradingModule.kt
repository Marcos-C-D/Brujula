package app.brujula

import android.content.Context
import app.brujula.data.SqliteBankStore
import app.brujula.data.SqliteMarketStore
import app.brujula.engine.Bank
import app.brujula.market.AssetCatalog
import app.brujula.market.EurView
import app.brujula.market.UpdateSchedule
import app.brujula.trading.PriceSource
import app.brujula.trading.TradingService
import java.time.Instant

/**
 * Procesa las órdenes de todas las cuentas activas con los precios guardados: ejecuciones, liquidaciones, caducidades,
 * planes periódicos e ingresos mensuales, día a día y en orden. Se ejecuta tras actualizar los precios (00:00 de Madrid).
 *
 * Nota (fase 5): cuando exista la interfaz habrá una única instancia de [Bank] compartida con este proceso.
 */
object TradingModule {

    fun processAll(context: Context): List<String> {
        val marketStore = SqliteMarketStore(context)
        val bankStore = SqliteBankStore(context)
        try {
            val bank = Bank.restore(bankStore)
            val prices = PriceSource { id ->
                marketStore.loadSeries(id)?.let { EurView.of(it, marketStore) }?.series?.takeIf { !it.isEmpty }
            }
            val service = TradingService(bank, { AssetCatalog.byId(it) }, prices, clock = { Instant.now() })
            val today = UpdateSchedule.today(Instant.now())
            // Los datos de mercado de hoy no existen aún a las 00:00: se conocen hasta el día anterior.
            return bank.active().flatMap { service.process(it.id, today.minusDays(1), today) }
        } finally {
            marketStore.close()
            bankStore.close()
        }
    }
}
