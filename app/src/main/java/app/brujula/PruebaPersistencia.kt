package app.brujula

import android.content.Context
import app.brujula.data.SqliteBankStore
import app.brujula.engine.Bank
import app.brujula.engine.LedgerAuditor
import app.brujula.engine.Purchase
import app.brujula.engine.Sale
import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.LocalDate

/**
 * Autoprueba de persistencia sobre una base de datos TEMPORAL (nunca toca tus datos reales):
 * crea una cuenta con operaciones, la guarda en SQLite, cierra, reabre, la recupera y compara todo.
 */
object PruebaPersistencia {

    fun ejecutar(context: Context, dbName: String): String {
        context.deleteDatabase(dbName)
        try {
            val hoy = LocalDate.now()

            val primero = SqliteBankStore(context, dbName)
            val original = try {
                val bank = Bank(store = primero)
                val cuenta = bank.createAccount("Prueba", Money.of("1000.00"), Money.of("100.00"), 1, hoy)
                bank.post(cuenta.id, Purchase(hoy, AssetId("prueba"), Money.of("25.50"), Units.of("0.255"), Money.of("0.50")))
                bank.post(cuenta.id, Sale(hoy, AssetId("prueba"), Units.of("0.1"), Money.of("11.00"), Money.of("0.50")))
                bank.rename(cuenta.id, "Prueba renombrada")
                bank.changeIncome(cuenta.id, hoy, Money.of("150.00"), 7)
                bank.archive(cuenta.id, hoy)
                bank
            } finally {
                primero.close()
            }

            val segundo = SqliteBankStore(context, dbName)
            val restaurado = try {
                Bank.restore(segundo)
            } finally {
                segundo.close()
            }

            val a = original.all().single()
            val b = restaurado.all().single()
            check(a.config == b.config) { "La configuración recuperada no coincide" }
            check(a.state == b.state) { "El estado recuperado no coincide" }
            check(a.ledger.records == b.ledger.records) { "Los movimientos recuperados no coinciden" }
            check(b.isArchived) { "El archivado no se conservó" }
            val problemas = LedgerAuditor.audit(b.ledger.records)
            check(problemas.isEmpty()) { "La auditoría no cuadra: $problemas" }

            return "✔ Prueba superada: ${b.ledger.records.size} movimientos y la configuración se guardaron y " +
                "se recuperaron idénticos (efectivo ${b.state.cash} €)."
        } finally {
            context.deleteDatabase(dbName)
        }
    }
}
