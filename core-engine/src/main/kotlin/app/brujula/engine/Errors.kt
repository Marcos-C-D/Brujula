package app.brujula.engine

import app.brujula.model.AssetId
import app.brujula.model.Money
import app.brujula.model.Units
import java.time.LocalDate
import java.time.YearMonth

/** Error de negocio del libro de movimientos. Nunca deja el estado a medias. */
sealed class LedgerException(message: String) : RuntimeException(message)

class InvalidEntryException(message: String) : LedgerException(message)

class InsufficientCashException(val available: Money, val required: Money) :
    LedgerException("Efectivo insuficiente: disponible $available €, necesario $required €")

class InsufficientUnitsException(val assetId: AssetId, val held: Units, val requested: Units) :
    LedgerException("Unidades insuficientes de $assetId: tienes $held y se piden $requested")

class OutOfOrderEntryException(val entryDate: LocalDate, val lastDate: LocalDate) :
    LedgerException("Movimiento con fecha $entryDate anterior al último registrado ($lastDate)")

class DuplicateIncomePeriodException(val period: YearMonth) :
    LedgerException("El ingreso mensual de $period ya está abonado")

class OpeningBalanceException(message: String) : LedgerException(message)

class MissingPriceException(val assetId: AssetId) :
    LedgerException("No hay precio disponible para $assetId; no se inventa ninguno")

/** Errores de gestión de cuentas. */
sealed class BankException(message: String) : RuntimeException(message)

class InvalidAccountException(message: String) : BankException(message)
class NoSuchAccountException(id: String) : BankException("No existe la cuenta $id")
class AccountArchivedException(id: String) : BankException("La cuenta $id está archivada")
