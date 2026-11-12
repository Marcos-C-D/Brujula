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
import app.brujula.model.Money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Tab(val label: String, val glyph: String) {
    HOME("Inicio", "⌂"), PORTFOLIO("Cartera", "◔"), MARKETS("Mercados", "↗"), MORE("Más", "⋯"),
}

sealed class SubScreen {
    object None : SubScreen()
    data class Asset(val id: AssetId) : SubScreen()
    object Accounts : SubScreen()
    object Diagnostics : SubScreen()
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

    // diagnóstico
    val dataSummary = mutableStateOf("Leyendo datos guardados…")
    val persistenceTest = mutableStateOf("")
    val persistenceRunning = mutableStateOf(false)
    val marketReport = mutableStateOf("")
    val marketRunning = mutableStateOf(false)

    fun reload() {
        val requested = selected.value
        container.submit {
            val d = Presenter.load(container, requested)
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
