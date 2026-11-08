package app.brujula.engine

import app.brujula.model.AccountId
import app.brujula.model.Money
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicLong

/**
 * Una cuenta: efectivo, cartera, historial y configuración propios. No comparte nada con otras.
 * Los cambios de configuración pasan siempre por [Bank] (que es seguro entre hilos).
 */
class Account internal constructor(
    val id: AccountId,
    name: String,
    val createdOn: LocalDate,
    val ledger: Ledger,
    incomeHistory: List<IncomeConfig>,
) {
    var name: String = name
        internal set
    var incomeHistory: List<IncomeConfig> = incomeHistory
        internal set
    var archiveIntervals: List<ArchiveInterval> = emptyList()
        internal set

    val state: AccountState get() = ledger.state
    val isArchived: Boolean get() = archiveIntervals.lastOrNull()?.to == null && archiveIntervals.isNotEmpty()
}

/**
 * Conjunto de cuentas independientes. Ninguna operación de una cuenta afecta a otra: cada una tiene su
 * propio libro. No existen transferencias entre cuentas.
 */
class Bank(private val newId: () -> String = sequentialIds()) {
    private val accounts = LinkedHashMap<AccountId, Account>()

    @Synchronized
    fun all(): List<Account> = accounts.values.toList()

    @Synchronized
    fun active(): List<Account> = accounts.values.filter { !it.isArchived }

    @Synchronized
    fun get(id: AccountId): Account = accounts[id] ?: throw NoSuchAccountException(id.value)

    @Synchronized
    fun createAccount(
        name: String,
        openingBalance: Money,
        monthlyIncome: Money,
        incomeDay: Int = 1,
        today: LocalDate,
    ): Account {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) throw InvalidAccountException("La cuenta necesita un nombre")
        if (accounts.values.any { it.name.equals(cleanName, ignoreCase = true) }) {
            throw InvalidAccountException("Ya existe una cuenta llamada \"$cleanName\"")
        }
        val config = IncomeConfig(YearMonth.from(today), monthlyIncome, incomeDay) // valida importe y día
        val id = AccountId(newId())
        val ledger = Ledger(id)
        ledger.post(OpeningBalance(today, openingBalance)) // valida saldo >= 0 y céntimos
        val account = Account(id, cleanName, today, ledger, listOf(config))
        accounts[id] = account
        return account
    }

    @Synchronized
    fun rename(id: AccountId, newName: String) {
        val account = get(id)
        val clean = newName.trim()
        if (clean.isEmpty()) throw InvalidAccountException("La cuenta necesita un nombre")
        if (accounts.values.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) {
            throw InvalidAccountException("Ya existe una cuenta llamada \"$clean\"")
        }
        account.name = clean
    }

    /** Registra un movimiento en una cuenta activa, abonando antes los ingresos que ya vencían. */
    @Synchronized
    fun post(id: AccountId, event: LedgerEvent): LedgerRecord {
        val account = get(id)
        if (account.isArchived) throw AccountArchivedException(id.value)
        creditDueIncomes(account, event.date)
        return account.ledger.post(event)
    }

    /** Abona los ingresos mensuales pendientes de todas las cuentas activas hasta [today]. */
    @Synchronized
    fun creditDueIncomes(today: LocalDate): List<Pair<AccountId, LedgerRecord>> =
        accounts.values.filter { !it.isArchived }.flatMap { account ->
            creditDueIncomes(account, today).map { account.id to it }
        }

    private fun creditDueIncomes(account: Account, today: LocalDate): List<LedgerRecord> {
        val state = account.ledger.state
        val due = IncomeScheduler.pending(
            account.createdOn, account.incomeHistory, state.creditedPeriods, account.archiveIntervals, today,
        )
        return due.map { d ->
            val last = account.ledger.state.lastDate
            // Si ya hay movimientos posteriores a la fecha de vencimiento, se abona con la fecha del
            // último movimiento para respetar el orden cronológico del libro.
            val date = if (last != null && d.date.isBefore(last)) last else d.date
            val note = if (date != d.date) "Ingreso mensual ${d.period} (vencía el ${d.date})" else "Ingreso mensual ${d.period}"
            account.ledger.post(MonthlyIncome(date, d.period, d.amount, note))
        }
    }

    /**
     * Cambia el ingreso mensual. Se aplica desde [fromPeriod] (por defecto, el primer periodo cuyo
     * ingreso aún no se ha abonado). Nunca reescribe periodos ya abonados.
     */
    @Synchronized
    fun changeIncome(
        id: AccountId,
        today: LocalDate,
        amount: Money,
        dayOfMonth: Int,
        fromPeriod: YearMonth? = null,
    ) {
        val account = get(id)
        if (account.isArchived) throw AccountArchivedException(id.value)
        creditDueIncomes(account, today) // primero lo ya vencido, con la configuración antigua

        val credited = account.ledger.state.creditedPeriods
        val current = YearMonth.from(today)
        val currentCfg = IncomeScheduler.configFor(account.incomeHistory, current)
        val currentDuePassed = currentCfg != null &&
            !IncomeScheduler.dueDate(current, currentCfg.dayOfMonth).isAfter(today)
        val minAllowed = maxOf(
            credited.maxOrNull()?.plusMonths(1) ?: YearMonth.from(account.createdOn),
            account.incomeHistory.maxOf { it.fromPeriod },
        )
        val default = if (current in credited || currentDuePassed) current.plusMonths(1) else current
        val from = fromPeriod ?: maxOf(default, minAllowed)
        if (from < minAllowed) {
            throw InvalidAccountException("No se pueden modificar periodos ya abonados (mínimo $minAllowed)")
        }
        val newConfig = IncomeConfig(from, amount, dayOfMonth)
        account.incomeHistory = (account.incomeHistory.filter { it.fromPeriod != from } + newConfig)
            .sortedBy { it.fromPeriod }
    }

    /** Archivar: la cuenta deja de operar y de recibir ingresos, pero se conserva y se puede restaurar. */
    @Synchronized
    fun archive(id: AccountId, today: LocalDate) {
        val account = get(id)
        if (account.isArchived) return
        creditDueIncomes(account, today)
        account.archiveIntervals = account.archiveIntervals + ArchiveInterval(today, null)
    }

    @Synchronized
    fun restore(id: AccountId, today: LocalDate) {
        val account = get(id)
        if (!account.isArchived) return
        val open = account.archiveIntervals.last()
        account.archiveIntervals = account.archiveIntervals.dropLast(1) + open.copy(to = today)
    }

    /** Eliminación definitiva: solo para cuentas archivadas. */
    @Synchronized
    fun deletePermanently(id: AccountId) {
        val account = get(id)
        if (!account.isArchived) throw InvalidAccountException("Solo se pueden eliminar definitivamente cuentas archivadas")
        accounts.remove(id)
    }

    companion object {
        fun sequentialIds(): () -> String {
            val counter = AtomicLong()
            return { "cuenta-${counter.incrementAndGet()}" }
        }
    }
}
