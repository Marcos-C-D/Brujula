package app.brujula.engine

import app.brujula.model.AccountId
import app.brujula.model.Money
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
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
    archiveIntervals: List<ArchiveInterval> = emptyList(),
    schedules: List<ScheduledPlan> = emptyList(),
    taxOptions: Map<String, String> = emptyMap(),
) {
    var name: String = name
        internal set
    var incomeHistory: List<IncomeConfig> = incomeHistory
        internal set
    var archiveIntervals: List<ArchiveInterval> = archiveIntervals
        internal set

    var schedules: List<ScheduledPlan> = schedules
        internal set
    var taxOptions: Map<String, String> = taxOptions
        internal set

    val state: AccountState get() = ledger.state
    val isArchived: Boolean get() = archiveIntervals.lastOrNull()?.to == null && archiveIntervals.isNotEmpty()

    val config: AccountConfig get() = AccountConfig(id, name, createdOn, incomeHistory, archiveIntervals, schedules, taxOptions)
}

/** Conflicto al importar: ya existe una cuenta con el mismo identificador o nombre. */
class ImportConflictException(message: String) : BankException(message)

/**
 * Conjunto de cuentas independientes. Ninguna operación de una cuenta afecta a otra: cada una tiene su
 * propio libro. No existen transferencias entre cuentas.
 *
 * Si se indica un [store], cada cambio se guarda ANTES de confirmarse en memoria: si el guardado falla,
 * la operación falla y nada cambia.
 */
class Bank(
    private val newId: () -> String = randomIds(),
    private val store: BankStore? = null,
) {
    private val accounts = LinkedHashMap<AccountId, Account>()

    @Synchronized
    fun all(): List<Account> = accounts.values.toList()

    @Synchronized
    fun active(): List<Account> = accounts.values.filter { !it.isArchived }

    @Synchronized
    fun get(id: AccountId): Account = accounts[id] ?: throw NoSuchAccountException(id.value)

    /** Copia coherente de todas las cuentas (incluidas las archivadas) para copias de seguridad. */
    @Synchronized
    fun snapshot(): List<StoredAccount> =
        accounts.values.map { StoredAccount(it.config, it.ledger.records.map { r -> r.event }) }

    private fun journalFor(id: AccountId): ((LedgerRecord) -> Unit)? =
        store?.let { s -> { record: LedgerRecord -> s.appendEvent(id, record) } }

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
        val income = IncomeConfig(YearMonth.from(today), monthlyIncome, incomeDay) // valida importe y día
        val id = AccountId(newId())
        val ledger = Ledger(id)
        val opening = ledger.post(OpeningBalance(today, openingBalance)) // valida saldo >= 0 y céntimos
        val config = AccountConfig(id, cleanName, today, listOf(income), emptyList())
        store?.createAccounts(listOf(config to listOf(opening))) // si falla, la cuenta no se registra
        ledger.journal = journalFor(id)
        val account = Account(id, cleanName, today, ledger, listOf(income))
        accounts[id] = account
        return account
    }

    /**
     * Importa cuentas ya validadas (copia de seguridad). Todo o nada: si alguna choca por identificador o
     * nombre con una existente, o entre sí, no se importa ninguna.
     */
    @Synchronized
    fun importAccounts(items: List<StoredAccount>): List<Account> {
        val ids = HashSet<AccountId>(accounts.keys)
        val names = accounts.values.map { it.name.lowercase() }.toHashSet()
        val prepared = items.map { stored ->
            val c = stored.config
            if (!ids.add(c.id)) throw ImportConflictException("Ya existe una cuenta con el identificador ${c.id}")
            if (!names.add(c.name.lowercase())) throw ImportConflictException("Ya existe una cuenta llamada \"${c.name}\"")
            val ledger = replayChecked(c.id, stored.events)
            Triple(c, ledger, ledger.records)
        }
        store?.createAccounts(prepared.map { (c, _, r) -> c to r })
        return prepared.map { (c, ledger, _) ->
            ledger.journal = journalFor(c.id)
            Account(c.id, c.name, c.createdOn, ledger, c.incomeHistory, c.archiveIntervals, c.schedules, c.taxOptions).also { accounts[c.id] = it }
        }
    }

    @Synchronized
    fun rename(id: AccountId, newName: String) {
        val account = get(id)
        val clean = newName.trim()
        if (clean.isEmpty()) throw InvalidAccountException("La cuenta necesita un nombre")
        if (accounts.values.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) {
            throw InvalidAccountException("Ya existe una cuenta llamada \"$clean\"")
        }
        store?.saveConfig(account.config.copy(name = clean))
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

    /** Abona los ingresos pendientes de UNA cuenta activa hasta [today]. */
    @Synchronized
    fun creditDueIncomes(id: AccountId, today: LocalDate): List<LedgerRecord> {
        val account = get(id)
        return if (account.isArchived) emptyList() else creditDueIncomes(account, today)
    }

    /** Sustituye los planes periódicos de la cuenta (se guardan antes de aplicarse). */
    @Synchronized
    fun setSchedules(id: AccountId, plans: List<ScheduledPlan>) {
        val account = get(id)
        if (account.isArchived) throw AccountArchivedException(id.value)
        if (plans.map { it.id }.toSet().size != plans.size) throw InvalidAccountException("Hay planes con el mismo identificador")
        store?.saveConfig(account.config.copy(schedules = plans))
        account.schedules = plans
    }

    /** Sustituye las opciones fiscales de la cuenta (se guardan antes de aplicarse). */
    @Synchronized
    fun setTaxOptions(id: AccountId, options: Map<String, String>) {
        val account = get(id)
        store?.saveConfig(account.config.copy(taxOptions = options))
        account.taxOptions = options
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
        val newHistory = (account.incomeHistory.filter { it.fromPeriod != from } + newConfig).sortedBy { it.fromPeriod }
        store?.saveConfig(account.config.copy(incomeHistory = newHistory))
        account.incomeHistory = newHistory
    }

    /** Archivar: la cuenta deja de operar y de recibir ingresos, pero se conserva y se puede restaurar. */
    @Synchronized
    fun archive(id: AccountId, today: LocalDate) {
        val account = get(id)
        if (account.isArchived) return
        creditDueIncomes(account, today)
        val intervals = account.archiveIntervals + ArchiveInterval(today, null)
        store?.saveConfig(account.config.copy(archiveIntervals = intervals))
        account.archiveIntervals = intervals
    }

    @Synchronized
    fun restore(id: AccountId, today: LocalDate) {
        val account = get(id)
        if (!account.isArchived) return
        val open = account.archiveIntervals.last()
        val intervals = account.archiveIntervals.dropLast(1) + open.copy(to = today)
        store?.saveConfig(account.config.copy(archiveIntervals = intervals))
        account.archiveIntervals = intervals
    }

    /** Eliminación definitiva: solo para cuentas archivadas. */
    @Synchronized
    fun deletePermanently(id: AccountId) {
        val account = get(id)
        if (!account.isArchived) throw InvalidAccountException("Solo se pueden eliminar definitivamente cuentas archivadas")
        store?.deleteAccount(id)
        accounts.remove(id)
    }

    companion object {
        /** Identificadores aleatorios (producción): nunca colisionan entre dispositivos ni tras restaurar. */
        fun randomIds(): () -> String = { UUID.randomUUID().toString() }

        /** Identificadores secuenciales, solo para pruebas deterministas. */
        fun sequentialIds(): () -> String {
            val counter = AtomicLong()
            return { "cuenta-${counter.incrementAndGet()}" }
        }

        /** Reconstruye el banco desde un almacén. Si algo no cuadra lanza [CorruptStoreException]: nunca descarta datos. */
        fun restore(store: BankStore, newId: () -> String = randomIds()): Bank {
            val bank = Bank(newId, store)
            for (stored in store.loadAccounts()) {
                val c = stored.config
                val ledger = try {
                    replayChecked(c.id, stored.events)
                } catch (e: LedgerException) {
                    throw CorruptStoreException(c.id, listOf(e.message ?: "movimientos no válidos"))
                }
                ledger.journal = bank.journalFor(c.id)
                bank.accounts[c.id] = Account(c.id, c.name, c.createdOn, ledger, c.incomeHistory, c.archiveIntervals, c.schedules, c.taxOptions)
            }
            return bank
        }

        /** Reproduce los eventos y comprueba la auditoría; lanza LedgerException si no cuadra. */
        internal fun replayChecked(id: AccountId, events: List<LedgerEvent>): Ledger {
            val ledger = Ledger.replay(id, events)
            val problems = LedgerAuditor.audit(ledger.records)
            if (problems.isNotEmpty()) throw InvalidEntryException("La auditoría no cuadra: ${problems.joinToString("; ")}")
            return ledger
        }
    }
}
