package app.brujula.ui

import androidx.compose.runtime.mutableStateOf
import app.brujula.AppContainer
import app.brujula.HomeData
import app.brujula.MarketModule
import app.brujula.Presenter
import app.brujula.PruebaPersistencia
import app.brujula.app.AssetDetail
import app.brujula.app.Fmt
import app.brujula.data.SqliteBankStore
import app.brujula.engine.Bank
import app.brujula.engine.LedgerAuditor
import app.brujula.model.AccountId
import app.brujula.model.AssetId
import app.brujula.SandboxView
import app.brujula.app.TimeMachine
import app.brujula.engine.BackupService
import app.brujula.engine.CsvExporter
import app.brujula.engine.StoredAccount
import app.brujula.market.AssetCatalog
import android.net.Uri
import app.brujula.TradingModule
import app.brujula.engine.Frequency
import app.brujula.engine.OrderSide
import app.brujula.engine.OrderType
import app.brujula.engine.ScheduledPlan
import app.brujula.model.Money
import app.brujula.model.Units
import app.brujula.tax.TaxSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Tab(val label: String, val glyph: String) {
    HOME("Inicio", "⌂"), PORTFOLIO("Cartera", "◔"), MARKETS("Mercados", "↗"), OPERATIONS("Operaciones", "⇄"), MORE("Más", "⋯"),
}

sealed class SubScreen {
    object None : SubScreen()
    data class Asset(val id: AssetId) : SubScreen()
    object Accounts : SubScreen()
    object Diagnostics : SubScreen()
    object Stats : SubScreen()
    object Tax : SubScreen()
    object Backup : SubScreen()
    object TimeTravel : SubScreen()
}

fun formatInstant(i: Instant?): String =
    i?.let { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Europe/Madrid")).format(it) } ?: "—"

/** Estado de la interfaz. Los cálculos pesados se hacen en el hilo de datos; aquí solo se guardan resultados. */
class UiController(val container: AppContainer) {
    val data = mutableStateOf(HomeData())
    val selected = mutableStateOf<AccountId?>(null)
    val tab = mutableStateOf(Tab.HOME)
    val sub = mutableStateOf<SubScreen>(SubScreen.None)
    val detail = mutableStateOf<AssetDetail?>(null)
    val message = mutableStateOf<String?>(null)
    val info = mutableStateOf<String?>(null)

    // copias de seguridad
    val pendingImport = mutableStateOf<List<StoredAccount>?>(null)

    // modo de avance de tiempo (trabaja sobre una copia en memoria: nunca toca los datos reales)
    val sandbox = mutableStateOf<TimeMachine?>(null)
    val sandboxView = mutableStateOf<SandboxView?>(null)
    val sandboxReport = mutableStateOf("")
    val sandboxBusy = mutableStateOf(false)
    val benchmark = mutableStateOf(AssetId("msci-world"))

    // diagnóstico
    val dataSummary = mutableStateOf("Leyendo datos guardados…")
    val persistenceTest = mutableStateOf("")
    val persistenceRunning = mutableStateOf(false)
    val marketReport = mutableStateOf("")
    val marketRunning = mutableStateOf(false)

    fun reload() {
        val requested = selected.value
        val bench = benchmark.value
        container.submit {
            val d = Presenter.load(container, requested, bench)
            data.value = d
            if (selected.value == null || d.accounts.none { it.id == selected.value }) selected.value = d.selectedId
        }
    }

    fun openAsset(id: AssetId) {
        detail.value = null
        sub.value = SubScreen.Asset(id)
        container.submit { detail.value = Presenter.assetDetail(container, id) }
    }

    fun select(id: AccountId) {
        selected.value = id
        sandbox.value?.let { tm -> container.submit { sandboxView.value = Presenter.sandboxView(container, tm, id) } }
        container.notifyChanged()
    }

    /** Ejecuta una operación sobre las cuentas; si falla, muestra el motivo y no cambia nada. */
    private fun operate(block: (app.brujula.engine.Bank) -> Unit) {
        container.runBackground {
            try {
                block(container.awaitBank())
                message.value = null
            } catch (e: Exception) {
                message.value = e.message ?: "No se pudo completar la operación"
            }
        }
    }

    fun createAccount(name: String, opening: Money, income: Money, day: Int) = operate { bank ->
        val acc = bank.createAccount(name, opening, income, day, container.today())
        selected.value = acc.id
    }

    fun rename(id: AccountId, name: String) = operate { it.rename(id, name) }

    fun changeIncome(id: AccountId, amount: Money, day: Int) = operate { it.changeIncome(id, container.today(), amount, day) }

    fun archive(id: AccountId) = operate { it.archive(id, container.today()) }

    fun restore(id: AccountId) = operate { it.restore(id, container.today()) }

    fun delete(id: AccountId) = operate { it.deletePermanently(id) }

    // ---- operaciones ----

    /** Ejecuta una acción de contratación sobre la cuenta seleccionada y muestra el resultado o el motivo del error. */
    private fun trade(okText: String, action: (app.brujula.trading.TradingService, AccountId) -> Unit) {
        val id = selected.value
        if (id == null) { message.value = "Crea o selecciona una cuenta primero"; return }
        container.runBackground {
            try {
                action(TradingModule.service(container), id)
                message.value = null
                info.value = okText
            } catch (e: Exception) {
                info.value = null
                message.value = e.message ?: "No se pudo crear la orden"
            }
        }
    }

    fun placeBuy(asset: AssetId, amount: Money, type: OrderType, price: Money?, expiresOn: LocalDate?) =
        trade("Orden de compra aceptada: el efectivo queda reservado hasta que se ejecute.") { s, id -> s.placeBuy(id, asset, amount, type, price, expiresOn) }

    fun placeSell(asset: AssetId, units: Units, type: OrderType, price: Money?, expiresOn: LocalDate?) =
        trade("Orden de venta aceptada: las unidades quedan reservadas hasta que se ejecute.") { s, id -> s.placeSell(id, asset, units, type, price, expiresOn) }

    fun placeTransfer(from: AssetId, to: AssetId, units: Units) =
        trade("Traspaso aceptado: se ejecutará al valor liquidativo y no tributa hasta el reembolso.") { s, id -> s.placeTransfer(id, from, to, units) }

    fun cancelOrder(orderId: String) =
        trade("Orden cancelada: la reserva se ha liberado.") { s, id -> s.cancel(id, orderId) }

    private fun withPlans(change: (List<ScheduledPlan>) -> List<ScheduledPlan>, okText: String) {
        val id = selected.value ?: return
        container.runBackground {
            try {
                val bank = container.awaitBank()
                bank.setSchedules(id, change(bank.get(id).schedules))
                message.value = null
                info.value = okText
            } catch (e: Exception) {
                info.value = null
                message.value = e.message ?: "No se pudo guardar el plan"
            }
        }
    }

    fun addPlan(plan: ScheduledPlan) = withPlans({ it + plan }, "Plan periódico creado.")

    fun removePlan(planId: String) = withPlans({ list -> list.filter { it.id != planId } }, "Plan eliminado.")

    /** Al reanudar un plan no se recuperan las ocurrencias perdidas: empieza de nuevo desde hoy. */
    fun setPlanPaused(planId: String, paused: Boolean) {
        val today = container.today()
        withPlans(
            { list -> list.map { if (it.id == planId) it.copy(paused = paused, startDate = if (paused) it.startDate else maxOf(it.startDate, today)) else it } },
            if (paused) "Plan en pausa." else "Plan reanudado desde hoy.",
        )
    }

    fun saveTaxSettings(settings: TaxSettings) {
        val id = selected.value ?: return
        container.runBackground {
            try {
                container.awaitBank().setTaxOptions(id, settings.toOptions())
                message.value = null
                info.value = "Opciones fiscales guardadas."
            } catch (e: Exception) {
                message.value = e.message ?: "No se pudieron guardar las opciones"
            }
        }
    }

    fun selectBenchmark(id: AssetId) {
        benchmark.value = id
        container.notifyChanged()
    }

    // ---- copias de seguridad ----

    private fun write(context: android.content.Context, uri: Uri, text: String) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("No se pudo abrir el archivo para escribir")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    fun exportBackup(context: android.content.Context, uri: Uri) {
        container.submit {
            try {
                val bank = container.awaitBank()
                write(context, uri, BackupService.export(bank, container.today()))
                message.value = null
                info.value = "Copia de seguridad guardada (${bank.all().size} cuentas, incluidas las archivadas)."
            } catch (e: Exception) {
                info.value = null
                message.value = "No se pudo guardar la copia: ${e.message}"
            }
        }
    }

    /** CSV de la cuenta seleccionada, con BOM para que Excel respete los acentos. */
    fun exportCsv(context: android.content.Context, uri: Uri) {
        val id = selected.value
        container.submit {
            try {
                if (id == null) throw IllegalStateException("Selecciona una cuenta")
                val acc = container.awaitBank().get(id)
                write(context, uri, "\uFEFF" + CsvExporter.ledger(acc.ledger.records))
                message.value = null
                info.value = "CSV de «${acc.name}» guardado."
            } catch (e: Exception) {
                info.value = null
                message.value = "No se pudo guardar el CSV: ${e.message}"
            }
        }
    }

    /** Lee y valida una copia: no se toca nada hasta que el usuario confirme. */
    fun readImport(context: android.content.Context, uri: Uri) {
        container.submit {
            try {
                val bytes = (context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("No se pudo abrir el archivo")).use { it.readBytes() }
                if (bytes.size > 20_000_000) throw IllegalStateException("El archivo es demasiado grande para ser una copia de Brújula")
                val accounts = BackupService.parse(bytes.toString(Charsets.UTF_8))
                if (accounts.isEmpty()) throw IllegalStateException("La copia no contiene cuentas")
                message.value = null
                pendingImport.value = accounts
            } catch (e: Exception) {
                pendingImport.value = null
                message.value = "No se pudo leer la copia: ${e.message}"
            }
        }
    }

    fun cancelImport() { pendingImport.value = null }

    fun confirmImport() = operate { bank ->
        val list = pendingImport.value ?: return@operate
        bank.importAccounts(list)
        pendingImport.value = null
        info.value = "Importadas ${list.size} cuenta(s) correctamente."
    }

    // ---- avance de tiempo ----

    fun startSandbox() {
        container.submit {
            try {
                val bank = container.awaitBank()
                val tm = TimeMachine(
                    bank.snapshot(), container.today(), { AssetCatalog.byId(it) },
                    TradingModule.priceSource(container), TradingModule.dividendFeed(container), { AssetCatalog.DEFAULT.map { a -> a.id } },
                )
                sandbox.value = tm
                sandboxReport.value = ""
                sandboxView.value = Presenter.sandboxView(container, tm, selected.value)
                message.value = null
            } catch (e: Exception) {
                message.value = "No se pudo iniciar la simulación: ${e.message}"
            }
        }
    }

    fun advanceSandbox(days: Int) {
        val tm = sandbox.value ?: return
        if (sandboxBusy.value) return
        sandboxBusy.value = true
        container.submit {
            try {
                val lines = tm.advance(days)
                sandboxReport.value = lines.joinToString("\n").ifBlank { "Sin novedades en este periodo." }
                sandboxView.value = Presenter.sandboxView(container, tm, selected.value)
            } catch (e: Exception) {
                sandboxReport.value = "✘ ${e.message}"
            } finally {
                sandboxBusy.value = false
            }
        }
    }

    fun stopSandbox() {
        sandbox.value = null
        sandboxView.value = null
        sandboxReport.value = ""
    }

    // ---- diagnóstico ----

    fun refreshDataSummary() {
        container.submit {
            dataSummary.value = try {
                val cuentas = container.awaitBank().all()
                val movimientos = cuentas.sumOf { it.ledger.records.size }
                val problemas = cuentas.flatMap { LedgerAuditor.audit(it.ledger.records) }
                "Cuentas guardadas: ${cuentas.size} · movimientos: $movimientos\n" +
                    "Auditoría de datos: " + if (problemas.isEmpty()) "correcta" else "${problemas.size} problemas"
            } catch (e: Exception) {
                "Error al leer los datos guardados: ${e.message}"
            }
        }
    }

    fun runPersistenceTest(context: android.content.Context) {
        if (persistenceRunning.value) return
        persistenceRunning.value = true
        persistenceTest.value = "Ejecutando prueba…"
        Thread {
            persistenceTest.value = try {
                PruebaPersistencia.ejecutar(context.applicationContext, "brujula-prueba.db")
            } catch (e: Exception) {
                "✘ La prueba falló: ${e.message}"
            }
            persistenceRunning.value = false
        }.start()
    }

    fun updatePrices() {
        if (marketRunning.value) return
        marketRunning.value = true
        marketReport.value = "Actualizando precios… (puede tardar un minuto)"
        Thread {
            marketReport.value = try {
                MarketModule.updateAndReport(container).joinToString("\n")
            } catch (e: Exception) {
                "✘ La actualización falló: ${e.message}"
            }
            marketRunning.value = false
        }.start()
    }
}
