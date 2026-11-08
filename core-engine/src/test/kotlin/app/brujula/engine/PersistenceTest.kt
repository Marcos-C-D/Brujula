package app.brujula.engine

import app.brujula.model.AccountId
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Almacén en memoria con fallos de "disco" simulados. */
class FailingStore : InMemoryBankStore() {
    var failAppend = false
    var failSave = false
    var failCreate = false
    var failDelete = false
    override fun appendEvent(accountId: AccountId, record: LedgerRecord) {
        if (failAppend) throw IllegalStateException("fallo simulado de disco")
        super.appendEvent(accountId, record)
    }
    override fun saveConfig(config: AccountConfig) {
        if (failSave) throw IllegalStateException("fallo simulado de disco")
        super.saveConfig(config)
    }
    override fun createAccounts(items: List<Pair<AccountConfig, List<LedgerRecord>>>) {
        if (failCreate) throw IllegalStateException("fallo simulado de disco")
        super.createAccounts(items)
    }
    override fun deleteAccount(accountId: AccountId) {
        if (failDelete) throw IllegalStateException("fallo simulado de disco")
        super.deleteAccount(accountId)
    }
}

class PersistenceTest {

    private fun assertIguales(a: Bank, b: Bank) {
        assertEquals(a.all().map { it.id }, b.all().map { it.id })
        for (x in a.all()) {
            val y = b.get(x.id)
            assertEquals(x.config, y.config)
            assertEquals(x.state, y.state)
            assertEquals(x.ledger.records, y.ledger.records)
        }
    }

    @Test
    fun todoLoQueSeHaceSeGuardaYSeRecuperaIdentico() {
        val store = InMemoryBankStore()
        val bank = Bank(store = store)
        val a = bank.createAccount("Principal", m("1000.00"), m("200.00"), 1, d("2026-01-10"))
        bank.post(a.id, Purchase(d("2026-03-05"), ASSET_A, m("300.00"), u("3")))
        bank.post(a.id, Sale(d("2026-03-06"), ASSET_A, u("1"), m("120.00")))
        bank.rename(a.id, "Cuenta principal")
        bank.changeIncome(a.id, d("2026-03-10"), m("350.00"), 7)
        bank.creditDueIncomes(d("2026-06-01"))
        val b = bank.createAccount("Otra", m("10.00"), m("0.00"), 1, d("2026-02-01"))
        bank.archive(b.id, d("2026-02-02"))

        assertIguales(bank, Bank.restore(store))
    }

    @Test
    fun alReabrirSePuedeSeguirOperandoSinPerderNiDuplicarNada() {
        val store = InMemoryBankStore()
        val primera = Bank(store = store)
        val a = primera.createAccount("Principal", m("1000.00"), m("100.00"), 1, d("2026-01-10"))
        primera.creditDueIncomes(d("2026-03-15"))

        val segunda = Bank.restore(store)
        assertEquals(0, segunda.creditDueIncomes(d("2026-03-15")).size) // nada se abona dos veces
        segunda.post(a.id, Purchase(d("2026-04-02"), ASSET_A, m("50.00"), u("1")))

        val tercera = Bank.restore(store)
        assertIguales(segunda, tercera)
        assertEquals(u("1"), tercera.get(a.id).state.unitsHeld(ASSET_A))
        assertEquals(emptyList(), LedgerAuditor.audit(tercera.get(a.id).ledger.records))
    }

    @Test
    fun siElDiscoFallaAlRegistrarUnMovimientoNoCambiaNadaYPuedeReintentarse() {
        val store = FailingStore()
        val bank = Bank(store = store)
        val a = bank.createAccount("Principal", m("100.00"), m("0.00"), 1, d("2026-01-01"))
        val antes = a.state
        store.failAppend = true
        assertFailsWith<IllegalStateException> {
            bank.post(a.id, Purchase(d("2026-01-02"), ASSET_A, m("10.00"), u("1")))
        }
        assertEquals(antes, a.state)
        assertEquals(1, a.ledger.records.size)
        store.failAppend = false
        bank.post(a.id, Purchase(d("2026-01-02"), ASSET_A, m("10.00"), u("1")))
        assertEquals(listOf(1L, 2L), a.ledger.records.map { it.seq }) // sin huecos
        assertIguales(bank, Bank.restore(store))
    }

    @Test
    fun siElDiscoFallaAlCrearLaCuentaNoQuedaMediaCuenta() {
        val store = FailingStore()
        val bank = Bank(store = store)
        store.failCreate = true
        assertFailsWith<IllegalStateException> { bank.createAccount("Principal", m("100.00"), m("0.00"), 1, d("2026-01-01")) }
        assertEquals(0, bank.all().size)
        assertEquals(0, store.loadAccounts().size)
        store.failCreate = false
        bank.createAccount("Principal", m("100.00"), m("0.00"), 1, d("2026-01-01")) // el nombre sigue libre
        assertEquals(1, bank.all().size)
    }

    @Test
    fun siElDiscoFallaAlCambiarLaConfiguracionNoSeCambiaEnMemoria() {
        val store = FailingStore()
        val bank = Bank(store = store)
        val a = bank.createAccount("Principal", m("100.00"), m("50.00"), 1, d("2026-01-01"))
        store.failSave = true
        assertFailsWith<IllegalStateException> { bank.rename(a.id, "Otro") }
        assertFailsWith<IllegalStateException> { bank.changeIncome(a.id, d("2026-01-05"), m("99.00"), 3) }
        assertFailsWith<IllegalStateException> { bank.archive(a.id, d("2026-01-06")) }
        assertEquals("Principal", a.name)
        assertEquals(1, a.incomeHistory.size)
        assertFalse(a.isArchived)
        store.failSave = false
        bank.archive(a.id, d("2026-01-06"))
        assertTrue(Bank.restore(store).get(a.id).isArchived)
    }

    @Test
    fun siElDiscoFallaAlEliminarLaCuentaSigueExistiendo() {
        val store = FailingStore()
        val bank = Bank(store = store)
        val a = bank.createAccount("Principal", m("100.00"), m("0.00"), 1, d("2026-01-01"))
        bank.archive(a.id, d("2026-01-02"))
        store.failDelete = true
        assertFailsWith<IllegalStateException> { bank.deletePermanently(a.id) }
        assertEquals(1, bank.all().size)
        store.failDelete = false
        bank.deletePermanently(a.id)
        assertEquals(0, Bank.restore(store).all().size)
    }

    @Test
    fun importarGuardaLasCuentasYSobreviveAlReinicio() {
        val origen = Bank()
        val o = origen.createAccount("Importada", m("100.00"), m("10.00"), 1, d("2026-01-01"))
        origen.post(o.id, Purchase(d("2026-02-02"), ASSET_A, m("20.00"), u("2")))
        val copia = BackupService.parse(BackupService.export(origen, d("2026-03-01")))

        val store = InMemoryBankStore()
        val destino = Bank(store = store)
        destino.importAccounts(copia)
        assertIguales(destino, Bank.restore(store))
        // y se puede seguir operando con el journal activo
        destino.post(o.id, Sale(d("2026-03-02"), ASSET_A, u("1"), m("11.00")))
        assertIguales(destino, Bank.restore(store))
    }

    @Test
    fun datosGuardadosDanadosNuncaSeDescartanEnSilencio() {
        val store = InMemoryBankStore()
        val cfg = AccountConfig(AccountId("rota"), "Rota", d("2026-01-01"), listOf(IncomeConfig(YearMonth.of(2026, 1), m("0.00"), 1)), emptyList())
        store.createAccounts(
            listOf(
                cfg to listOf(
                    LedgerRecord(1, OpeningBalance(d("2026-01-01"), m("10.00"))),
                    LedgerRecord(2, Purchase(d("2026-01-02"), ASSET_A, m("500.00"), u("1"))),
                ),
            ),
        )
        val e = assertFailsWith<CorruptStoreException> { Bank.restore(store) }
        assertEquals(AccountId("rota"), e.accountId)
    }

    @Test
    fun elAlmacenExigeSecuenciasSinHuecos() {
        val store = InMemoryBankStore()
        val cfg = AccountConfig(AccountId("a"), "A", d("2026-01-01"), listOf(IncomeConfig(YearMonth.of(2026, 1), m("0.00"), 1)), emptyList())
        store.createAccounts(listOf(cfg to listOf(LedgerRecord(1, OpeningBalance(d("2026-01-01"), m("10.00"))))))
        assertFailsWith<IllegalStateException> {
            store.appendEvent(AccountId("a"), LedgerRecord(3, CashAdjustment(d("2026-01-02"), m("-1.00"), "x")))
        }
    }
}
