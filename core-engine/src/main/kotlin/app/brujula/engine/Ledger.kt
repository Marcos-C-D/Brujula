package app.brujula.engine

import app.brujula.model.AccountId

/** Movimiento ya registrado: número de secuencia (1, 2, 3…) + hecho. */
data class LedgerRecord(val seq: Long, val event: LedgerEvent)

/**
 * Libro de movimientos de UNA cuenta: solo se añaden hechos, nunca se editan ni se borran.
 * [post] es atómico y seguro entre hilos: si un movimiento no es válido, no cambia nada.
 */
class Ledger(val accountId: AccountId) {
    private val lock = Any()
    private val _records = ArrayList<LedgerRecord>()
    private var _state: AccountState = AccountState.EMPTY

    /**
     * Gancho de persistencia: se llama con el movimiento YA validado y ANTES de confirmarlo en memoria.
     * Si lanza una excepción, el movimiento no se registra (memoria y disco no se desincronizan).
     */
    internal var journal: ((LedgerRecord) -> Unit)? = null

    val records: List<LedgerRecord> get() = synchronized(lock) { _records.toList() }

    val state: AccountState get() = synchronized(lock) { _state }

    fun post(event: LedgerEvent): LedgerRecord = synchronized(lock) {
        val seq = _records.size + 1L
        val newState = _state.apply(seq, event) // si falla, no se modifica nada
        val record = LedgerRecord(seq, event)
        journal?.invoke(record)
        _records += record
        _state = newState
        record
    }

    companion object {
        /** Reconstruye un libro aplicando los eventos en orden (recuperación desde copia de seguridad). */
        fun replay(accountId: AccountId, events: List<LedgerEvent>): Ledger =
            Ledger(accountId).also { ledger -> events.forEach { ledger.post(it) } }
    }
}
