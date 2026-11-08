package app.brujula.engine

import app.brujula.model.AccountId
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CodecAndBackupTest {

    private val todos: List<LedgerEvent> = listOf(
        OpeningBalance(d("2026-01-01"), m("1000.00")),
        MonthlyIncome(d("2026-02-01"), YearMonth.of(2026, 2), m("200.00"), "Ingreso con \"comillas\" y ñ"),
        Purchase(d("2026-02-02"), ASSET_A, m("25.50"), u("0.255000"), m("0.50"), "nota"),
        Sale(d("2026-02-03"), ASSET_A, u("0.1"), m("11.00"), m("0.50")),
        CashAdjustment(d("2026-06-30"), m("-3000.00"), "Pago IRPF"),
    )

    @Test
    fun cadaTipoDeEventoSobreviveAlViajeDeIdaYVuelta() {
        for (e in todos) assertEquals(e, EventCodec.fromJson(EventCodec.toJson(e)))
    }

    @Test
    fun noSePierdeLaEscalaNiLaPrecision() {
        val e = Purchase(d("2026-02-02"), ASSET_A, m("10.00"), u("0.123456789012"), m("0.00"))
        val de = EventCodec.fromJson(EventCodec.toJson(e)) as Purchase
        assertEquals("10.00", de.invested.toString())
        assertEquals("0.123456789012", de.units.toString())
    }

    @Test
    fun eventosInvalidosSeRechazan() {
        assertFailsWith<CodecException> { EventCodec.fromJson("{\"type\":\"RARO\",\"date\":\"2026-01-01\"}") }
        assertFailsWith<CodecException> { EventCodec.fromJson("{\"type\":\"OPENING\",\"date\":\"2026-01-01\"}") }
        assertFailsWith<CodecException> { EventCodec.fromJson("{\"type\":\"OPENING\",\"date\":\"ayer\",\"amount\":\"1.00\"}") }
        assertFailsWith<CodecException> { EventCodec.fromJson("{\"type\":\"OPENING\",\"date\":\"2026-01-01\",\"amount\":\"abc\"}") }
        assertFailsWith<CodecException> { EventCodec.fromJson("{\"type\":\"OPENING\",\"date\":\"2026-01-01\",\"amount\":\"-5.00\"}") }
        assertFailsWith<CodecException> { EventCodec.fromJson("[1,2]") }
    }

    @Test
    fun laConfiguracionDeCuentaViajaIntacta() {
        val config = AccountConfig(
            AccountId("id-1"), "Mi cuenta ñ", d("2026-03-15"),
            listOf(IncomeConfig(YearMonth.of(2026, 3), m("100.00"), 31), IncomeConfig(YearMonth.of(2026, 7), m("250.50"), 5)),
            listOf(ArchiveInterval(d("2026-05-01"), d("2026-06-01")), ArchiveInterval(d("2026-09-01"), null)),
        )
        assertEquals(config, EventCodec.configFromJson(EventCodec.configToJson(config)))
    }

    // ---------- copias de seguridad ----------

    private fun bankConDatos(): Bank {
        val b = Bank()
        val a1 = b.createAccount("Principal", m("1000.00"), m("200.00"), 1, d("2026-01-10"))
        b.post(a1.id, Purchase(d("2026-03-05"), ASSET_A, m("300.00"), u("3"), m("1.00")))
        b.post(a1.id, Sale(d("2026-03-06"), ASSET_A, u("1"), m("120.00")))
        b.post(a1.id, CashAdjustment(d("2026-03-07"), m("-2000.00"), "impuesto"))
        b.changeIncome(a1.id, d("2026-03-08"), m("300.00"), 5)
        val a2 = b.createAccount("Ahorro ñandú", m("50.00"), m("0.00"), 20, d("2026-02-01"))
        b.archive(a2.id, d("2026-02-10"))
        return b
    }

    @Test
    fun copiaYRestauracionDejanTodoIdentico() {
        val original = bankConDatos()
        val json = BackupService.export(original, d("2026-04-01"))
        val restaurado = Bank()
        restaurado.importAccounts(BackupService.parse(json))

        assertEquals(original.all().size, restaurado.all().size)
        for (a in original.all()) {
            val b = restaurado.get(a.id)
            assertEquals(a.config, b.config)
            assertEquals(a.state, b.state)
            assertEquals(a.ledger.records, b.ledger.records)
            assertEquals(a.isArchived, b.isArchived)
        }
        // la copia incluye cuentas archivadas y el efectivo negativo
        assertTrue(restaurado.all().any { it.isArchived })
        assertTrue(restaurado.all().any { it.state.cash.isNegative })
        // exportar dos veces el mismo estado da exactamente el mismo texto
        assertEquals(json, BackupService.export(restaurado, d("2026-04-01")))
    }

    @Test
    fun importarSobreCuentasExistentesDetectaConflictosYNoCambiaNada() {
        val original = bankConDatos()
        val copia = BackupService.parse(BackupService.export(original, d("2026-04-01")))
        val antes = original.all().map { it.state }
        assertFailsWith<ImportConflictException> { original.importAccounts(copia) }
        assertEquals(antes, original.all().map { it.state })
        assertEquals(2, original.all().size)
    }

    @Test
    fun importarEsTodoONadaSiUnaCuentaChocaPorNombre() {
        val destino = Bank()
        destino.createAccount("Ahorro ñandú", m("1.00"), m("0.00"), 1, d("2026-01-01"))
        val copia = BackupService.parse(BackupService.export(bankConDatos(), d("2026-04-01")))
        assertFailsWith<ImportConflictException> { destino.importAccounts(copia) }
        assertEquals(1, destino.all().size) // ni siquiera "Principal" se importó
    }

    @Test
    fun copiasManipuladasOIncoherentesSeRechazanEnteras() {
        // un libro imposible: compra por encima del efectivo
        val cfg = AccountConfig(AccountId("x"), "Rota", d("2026-01-01"), listOf(IncomeConfig(YearMonth.of(2026, 1), m("0.00"), 1)), emptyList())
        val roto = StoredAccount(cfg, listOf(OpeningBalance(d("2026-01-01"), m("10.00")), Purchase(d("2026-01-02"), ASSET_A, m("500.00"), u("1"))))
        val json = BackupService.export(listOf(roto), d("2026-04-01"))
        assertFailsWith<BackupException> { BackupService.parse(json) }
    }

    @Test
    fun copiasDeOtroFormatoOVersionSeRechazan() {
        assertFailsWith<BackupException> { BackupService.parse("esto no es json") }
        assertFailsWith<BackupException> { BackupService.parse("[]") }
        assertFailsWith<BackupException> { BackupService.parse("{\"format\":\"otro\",\"version\":1,\"accounts\":[]}") }
        assertFailsWith<BackupException> { BackupService.parse("{\"format\":\"brujula-backup\",\"version\":99,\"accounts\":[]}") }
        assertFailsWith<BackupException> { BackupService.parse("{\"format\":\"brujula-backup\",\"version\":1}") }
        assertEquals(0, BackupService.parse("{\"format\":\"brujula-backup\",\"version\":1,\"accounts\":[]}").size)
    }

    @Test
    fun identificadoresRepetidosEnLaCopiaSeRechazan() {
        val bank = bankConDatos()
        val una = bank.snapshot().first()
        val json = BackupService.export(listOf(una, una), d("2026-04-01"))
        assertFailsWith<BackupException> { BackupService.parse(json) }
    }

    // ---------- CSV ----------

    @Test
    fun csvConFormatoDeExcelEspanol() {
        val l = Ledger(AccountId("c"))
        l.post(OpeningBalance(d("2026-01-01"), m("1000.00")))
        l.post(Purchase(d("2026-01-02"), ASSET_A, m("25.50"), u("0.255"), m("0.50")))
        l.post(Sale(d("2026-01-03"), ASSET_A, u("0.1"), m("11.00"), m("0.50"), "=cmd|' /C calc'!A0"))
        val csv = CsvExporter.ledger(l.records)
        val lineas = csv.split("\r\n").filter { it.isNotEmpty() }
        assertEquals("n;fecha;tipo;activo;unidades;importe_efectivo;comisiones;saldo_efectivo;nota", lineas[0])
        assertEquals("1;2026-01-01;APERTURA;;;1000,00;;1000,00;Saldo inicial", lineas[1])
        assertEquals("2;2026-01-02;COMPRA;msci-world;0,255;-26,00;0,50;974,00;", lineas[2])
        assertEquals("3;2026-01-03;VENTA;msci-world;0,1;10,50;0,50;984,50;'=cmd|' /C calc'!A0", lineas[3])
    }

    @Test
    fun csvConPuntoDecimalYEscapadoDeCampos() {
        val l = Ledger(AccountId("c"))
        l.post(OpeningBalance(d("2026-01-01"), m("5.00"), "a;b \"c\""))
        val csv = CsvExporter.ledger(l.records, CsvOptions(separator = ';', decimalComma = false))
        assertEquals("1;2026-01-01;APERTURA;;;5.00;;5.00;\"a;b \"\"c\"\"\"", csv.split("\r\n")[1])
    }
}
