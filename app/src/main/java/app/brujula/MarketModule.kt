package app.brujula

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.brujula.data.HttpUrlFetcher
import app.brujula.data.SqliteMarketStore
import app.brujula.market.AssetCatalog
import app.brujula.market.CoinGeckoProvider
import app.brujula.market.EcbFxProvider
import app.brujula.market.EurView
import app.brujula.market.MarketStore
import app.brujula.market.MarketUpdater
import app.brujula.market.Performance
import app.brujula.market.ProviderId
import app.brujula.market.ReturnPeriod
import app.brujula.market.ReturnResult
import app.brujula.market.Risk
import app.brujula.market.UpdateOutcome
import app.brujula.market.UpdateSchedule
import app.brujula.market.VolatilityResult
import app.brujula.market.YahooChartProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Montaje de la capa de datos de mercado para Android. */
object MarketModule {

    fun newUpdater(store: MarketStore): MarketUpdater {
        val http = HttpUrlFetcher()
        return MarketUpdater(
            store = store,
            providers = mapOf(ProviderId.YAHOO to YahooChartProvider(http), ProviderId.COINGECKO to CoinGeckoProvider(http)),
            ecb = EcbFxProvider(http),
            clock = { Instant.now() },
            pause = { Thread.sleep(400) }, // se espacian las peticiones para respetar los límites gratuitos
        )
    }

    /** Actualiza todos los activos y devuelve un informe legible, una línea por activo. */
    fun updateAndReport(context: Context): List<String> {
        val store = SqliteMarketStore(context)
        try {
            val outcomes = newUpdater(store).updateAll(AssetCatalog.DEFAULT)
            return outcomes.map { describe(it, store) }
        } finally {
            store.close()
        }
    }

    /** Si se perdió la ejecución de las 00:00 (móvil apagado o retraso de Android), recupera al abrir la app. */
    fun catchUpIfNeeded(context: Context) {
        val store = SqliteMarketStore(context)
        try {
            if (UpdateSchedule.needsCatchUp(store.lastSuccessfulUpdate(), Instant.now())) {
                newUpdater(store).updateAll(AssetCatalog.DEFAULT)
            }
        } finally {
            store.close()
        }
    }

    private fun pct(r: ReturnResult): String = when (r) {
        is ReturnResult.Value -> "${r.percent}%"
        is ReturnResult.Insufficient -> "s/d"
    }

    private fun describe(o: UpdateOutcome, store: MarketStore): String {
        val def = AssetCatalog.byId(o.assetId)
        val name = def?.name ?: o.assetId.value
        return when (o) {
            is UpdateOutcome.Updated -> {
                val stored = store.loadSeries(o.assetId)
                val eur = stored?.let { EurView.of(it, store) }
                val detail = if (eur != null && !eur.series.isEmpty) {
                    val ytd = pct(Performance.calculate(eur.series, ReturnPeriod.YTD))
                    val y1 = pct(Performance.calculate(eur.series, ReturnPeriod.Y1))
                    val vol = when (val v = Risk.volatility(eur.series, 1)) {
                        is VolatilityResult.Value -> "vol ${v.annualizedPercent}% (riesgo ${Risk.riskClass(v.annualizedPercent)}/7)"
                        is VolatilityResult.Insufficient -> "vol s/d"
                    }
                    "último ${eur.series.last.date} · YTD $ytd · 1a $y1 · $vol" +
                        (if (eur.originalCurrency != "EUR") " · divisa ${eur.originalCurrency}→EUR" else "") +
                        (if (eur.missingFxDates.isNotEmpty()) " · ${eur.missingFxDates.size} fechas sin tipo de cambio" else "")
                } else {
                    "guardado, pero sin conversión a EUR (${eur?.note ?: "sin datos"})"
                }
                "✔ $name · ${o.symbol} (${o.provider}) · ${o.points} precios\n    $detail"
            }
            is UpdateOutcome.Failed -> {
                val kept = o.keptLastDate?.let { "se conserva el último precio, del $it" } ?: "sin datos guardados"
                "✘ $name · $kept\n    ${o.errors.joinToString(" | ").take(300)}"
            }
        }
    }
}

/** Reprograma la siguiente actualización a las 00:00 de Madrid. */
object UpdateScheduler {
    private const val WORK_NAME = "actualizacion-diaria-precios"

    fun scheduleNext(context: Context) {
        val now = Instant.now()
        val delay = Duration.between(now, UpdateSchedule.nextRun(now).toInstant())
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = SqliteMarketStore(applicationContext)
        try {
            MarketModule.newUpdater(store).updateAll(AssetCatalog.DEFAULT)
        } catch (e: Exception) {
            // Un fallo no debe romper la cadena diaria: se conserva lo guardado y se reintenta mañana.
        } finally {
            store.close()
        }
        UpdateScheduler.scheduleNext(applicationContext)
        return Result.success()
    }
}
