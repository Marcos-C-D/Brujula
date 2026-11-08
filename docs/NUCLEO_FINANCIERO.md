# Núcleo financiero (fase 1)

## Modelo
- **Dinero** (`Money`): `BigDecimal` exacto en euros; nunca `Double`. La igualdad ignora la escala (1,0 = 1,00).
  Cualquier importe que entra en el libro debe estar en **céntimos exactos**; el redondeo es siempre explícito.
- **Unidades** (`Units`): participaciones, ETF o fracciones de Bitcoin, exactas.
- **Libro** (`Ledger`): lista de eventos solo-añadir. El estado (`AccountState`) se deriva de reproducirlos.
  Un movimiento inválido no cambia nada (atómico) y el libro es seguro entre hilos.

## Eventos
| Evento | Efecto |
|---|---|
| `OpeningBalance` | Saldo inicial (debe ser el primero y único) |
| `MonthlyIncome` | Ingreso de un periodo (año-mes); un periodo no se abona dos veces |
| `Purchase` | Efectivo − (invertido + comisiones); crea un lote cuyo coste incluye las comisiones |
| `Sale` | Efectivo + (bruto − comisiones); consume lotes FIFO; calcula la ganancia realizada |
| `CashAdjustment` | Ajuste con signo (p. ej. impuestos). Único que puede dejar el efectivo en negativo |

Reglas: fechas no decrecientes; no se compra sin efectivo suficiente ni se vende lo que no se tiene.

## FIFO
Se vende siempre el lote más antiguo primero. En ventas parciales el coste se reparte redondeando a céntimos
y, al agotar un lote, se toma todo su coste restante: la suma de los costes consumidos de un lote es exactamente
su coste original (no se pierde ni se crea ningún céntimo).

## Ingresos mensuales
- Vencen el día configurado (en meses cortos, el último día del mes).
- Solo se abonan los de fecha **posterior** a la creación de la cuenta y no futuros.
- Al abrir la app se recuperan todos los atrasados, una sola vez cada uno (idempotente).
- Cada periodo usa la configuración vigente entonces; un cambio nunca reescribe periodos ya abonados.
- Una cuenta archivada no recibe ingresos mientras lo está.

## Cuentas
Cada cuenta tiene su propio libro, efectivo, cartera, historial y configuración. No existen transferencias entre
cuentas. Archivar conserva y bloquea; eliminar definitivamente solo se permite sobre cuentas archivadas.

## Auditoría
`LedgerAuditor` recalcula con sumas simples y verifica, entre otras, la identidad
**efectivo + coste en cartera = aportaciones + beneficio realizado + ajustes**, que debe cumplirse siempre al céntimo.

## Patrimonio
`netWorth = efectivo + valor de las inversiones` usando precios reales. Si falta el precio de un activo en cartera
se lanza `MissingPriceException`: nunca se inventa un precio.
