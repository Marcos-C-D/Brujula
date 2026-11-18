# Contratación simulada (fase 3)

## Ciclo de una orden
Solicitada → aceptada → ejecutada → liquidada. Una orden también puede ser cancelada, caducada o rechazada.
Todo queda en el libro de movimientos (`OrderPlaced`, `Purchase`/`Sale`/`FundTransfer`, `OrderSettled`, `OrderClosed`,
`OrderRejected`), de modo que el estado de cada orden se puede consultar y reproducir.

## Reservas
- Compra: al aceptar la orden se reserva importe + comisión; no se puede gastar en otra cosa.
- Venta y traspaso: se reservan las unidades. Cancelar, caducar o rechazar libera la reserva.
- Efectivo disponible = efectivo − reservado − ventas aún sin liquidar.

## Reglas de ejecución (datos diarios, del lado conservador)
| Caso | Regla |
|---|---|
| ETF a mercado | **Inmediata**: al último precio disponible (cotización con retraso si el mercado está abierto; si no, el último cierre) |
| Bitcoin a mercado | **Inmediata**: al precio actual (cotización con retraso, 24 h) |
| ETF y Bitcoin limitadas / stop-loss | Se evalúan con los precios de cada sesión posterior a la creación |
| Limitada de compra | A la apertura si ya está por debajo del límite; si no, al límite solo si el mínimo baja **estrictamente** del límite |
| Limitada de venta | Simétrica con el máximo |
| Stop-loss (venta) | Se activa si el mínimo llega al precio de activación (rozarlo basta); a la apertura si abre por debajo |
| Fondos | Solo a mercado, al valor liquidativo (cierre del proxy): mismo día hábil si se crea antes de la hora de corte (14:00 Madrid), si no, el siguiente |
| Caducidad | Válida hasta la fecha indicada (inclusive); «hasta cancelar» si no hay fecha |
| Falta de dato | Las órdenes esperan; nunca se ejecutan con precios de otra fecha ni retroactivamente |

Con datos diarios no hay hora de apertura por mercado, por eso las órdenes que se evalúan con sesiones nunca se ejecutan el mismo día en que se crean. Los planes periódicos usan la regla de la siguiente sesión.

## Liquidación
- ETF: EE. UU. T+1 desde el 28-05-2024; Europa (Xetra, Euronext, Londres) T+2 hasta el 10-10-2027 y T+1 desde el 11-10-2027.
- Fondos (indexados, monetarios y mixtos): cada compra, reembolso o traspaso tarda **1 o 2 días hábiles al azar** del mercado abierto (el plazo elegido queda guardado en el movimiento; hay plazos fijos configurables en `SettlementRules`). Las participaciones compradas no se pueden vender
  hasta liquidar; el importe de un reembolso no está disponible hasta su fecha.
- Bitcoin: inmediata. Los días hábiles respetan los calendarios de cada mercado (festivos incluidos).

## Traspasos entre fondos
Solo fondos. Se conservan el coste y la fecha de adquisición de cada lote (FIFO), no se realiza ganancia ni se mueve efectivo.
Las unidades de destino se calculan por el valor de ambos fondos en el día de valoración. Los ETF y Bitcoin **no** tienen este régimen.

## Planes periódicos
Compra de un importe fijo o venta de unidades fijas, semanal o mensual, siempre a mercado. Cada ocurrencia crea una orden con
identificador determinista (no se duplica). Si no hay efectivo o unidades queda registrada como rechazada y no se reintenta.
Al reanudar un plan pausado no se recuperan las ocurrencias perdidas.

## Limitaciones conocidas
- Con datos diarios no se simula la hora exacta de ejecución ni el volumen.
- Los fondos usan el cierre de un ETF como valor liquidativo (proxy).
- Si un activo deja de recibir datos, las órdenes de la cuenta esperan hasta que lleguen o se cancelen.
- La interfaz para crear órdenes llega en la fase 5; hasta entonces el motor se valida con pruebas automáticas.
