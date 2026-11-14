package app.brujula.app

import app.brujula.engine.Bank
import app.brujula.engine.StoredAccount
import app.brujula.market.AssetDefinition
import app.brujula.market.UpdateSchedule
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.tax.DividendFeed
import app.brujula.tax.TaxRules
import app.brujula.tax.TaxService
import app.brujula.tax.TaxSettings
import app.brujula.trading.PriceSource
import app.brujula.trading.TradingConfig
import app.brujula.trading.TradingService
import java.time.Instant
import java.time.LocalDate

/**
 * Modo de avance de tiempo: trabaja sobre una COPIA en memoria de las cuentas (sin almacén), así que nunca modifica los datos
 * reales. Avanza un reloj virtual y procesa, día a día y en orden, ingresos mensuales, planes periódicos, órdenes, liquidaciones,
 * dividendos y calendario fiscal. Solo hay precios reales hasta el último dato descargado: más allá, las órdenes esperan.
 */
class TimeMachine(
    source: List<StoredAccount>,
    val startDate: LocalDate,
    private val catalog: (AssetId) -> AssetDefinition?,
    private val prices: PriceSource,
    private val feed: DividendFeed,
    private val catalogIds: () -> List<AssetId>,
) {
    val bank: Bank = Bank().also { it.importAccounts(source) }

    var today: LocalDate = startDate
        private set

    private val taxByAccount = HashMap<AccountId, TaxService>()

    private fun taxFor(id: AccountId): TaxService = taxByAccount.getOrPut(id) {
        TaxService(bank, catalog, catalogIds, feed, TaxRules(), TaxSettings.fromOptions(bank.get(id).taxOptions))
    }

    private val clock: () -> Instant = { today.atStartOfDay(UpdateSchedule.MADRID).toInstant().plusSeconds(10 * 3600L) }

    private val service = TradingService(bank, catalog, prices, TradingConfig(), clock) { id, day -> taxFor(id).processDay(id, day) }

    /** Avanza el reloj virtual y procesa todas las cuentas activas. Devuelve lo ocurrido, en texto. */
    fun advance(days: Int): List<String> {
        require(days > 0) { "Hay que avanzar al menos un día" }
        today = today.plusDays(days.toLong())
        val report = ArrayList<String>()
        for (acc in bank.active()) {
            val lines = service.process(acc.id, today.minusDays(1), today)
            if (lines.isNotEmpty()) {
                report += "${acc.name}:"
                lines.forEach { report += "  $it" }
            }
        }
        return report
    }
}
