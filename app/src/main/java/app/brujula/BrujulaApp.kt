package app.brujula

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import app.brujula.data.SqliteBankStore
import app.brujula.data.SqliteMarketStore
import app.brujula.engine.Bank
import app.brujula.market.UpdateSchedule
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Contenedor único de la app: UNA instancia de [Bank] y de los almacenes, compartida por la interfaz y por la tarea diaria de
 * las 00:00. Así no puede haber dos copias del libro escribiendo a la vez.
 */
class AppContainer(private val app: Application) {
    val context: android.content.Context get() = app
    val bankStore = SqliteBankStore(app)
    val marketStore = SqliteMarketStore(app)

    @Volatile var bank: Bank? = null
        private set
    @Volatile var startupError: String? = null
        private set

    private val ready = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor()

    /** Se incrementa cada vez que cambian los datos: la interfaz se vuelve a calcular. */
    val version = mutableStateOf(0)

    fun start() {
        executor.execute {
            try {
                val b = Bank.restore(bankStore)
                bank = b
                b.creditDueIncomes(today()) // ingresos mensuales atrasados, una sola vez cada uno
            } catch (e: Exception) {
                startupError = e.message ?: e.javaClass.simpleName
            } finally {
                ready.countDown()
                version.value = version.value + 1
            }
        }
    }

    fun awaitBank(): Bank {
        ready.await()
        return bank ?: throw IllegalStateException(startupError ?: "La base de datos no se pudo abrir")
    }

    /** Ejecuta trabajo en un único hilo: las operaciones sobre los datos son secuenciales. */
    fun runBackground(block: () -> Unit) {
        executor.execute {
            try { block() } finally { version.value = version.value + 1 }
        }
    }

    /** Como [runBackground] pero sin avisar de cambios (para cálculos de solo lectura). */
    fun submit(block: () -> Unit) { executor.execute(block) }

    fun notifyChanged() { version.value = version.value + 1 }

    fun today(): LocalDate = UpdateSchedule.today(Instant.now())
}

class BrujulaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
