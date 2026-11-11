# Fiscalidad española (fase 4)

Simulación del IRPF de la **base del ahorro** de una cuenta. Es educativa: no sustituye a un asesor fiscal ni a la declaración real.

## Fuentes
- Ley 35/2006 del IRPF (LIRPF): art. 33.5 (pérdidas no computables), art. 37.2 (FIFO), art. 49 (integración y compensación),
  art. 66 (escala del ahorro), art. 94 (traspasos entre fondos), art. 80 (deducción por doble imposición internacional).
- Ley 7/2024: el último tramo de la escala del ahorro pasa al 30 % (desde 300.000 €).
- Agencia Tributaria: calendario de la campaña de Renta 2025 (8-abr a 30-jun-2026; cargo de la domiciliación el 30-jun;
  segundo plazo del pago fraccionado el 5-nov).

## Qué tributa y cuándo
| Concepto | Tratamiento |
|---|---|
| Venta de ETF, reembolso de fondo, venta de Bitcoin | Ganancia o pérdida patrimonial en el año de la venta (valor de transmisión neto − coste FIFO, con comisiones) |
| Traspaso entre fondos | No tributa; se conservan el coste y la fecha de adquisición de cada lote (FIFO fiscal) |
| ETF y Bitcoin | **No** tienen diferimiento por traspaso |
| Dividendos (ETF de distribución) | Rendimiento del capital mobiliario; retención del 19 % |
| ETF y fondos de acumulación | No tributan hasta vender |
| Reembolso de fondo con ganancia | Retención del 19 % sobre la ganancia, practicada al liquidarse; se descuenta en la declaración |

## Escala del ahorro (2025 en adelante)
19 % hasta 6.000 € · 21 % hasta 50.000 € · 23 % hasta 200.000 € · 27 % hasta 300.000 € · 30 % desde 300.000 €.
Está en datos con vigencia por ejercicio; los años sin datos oficiales usan la última escala.

## Cálculo anual
1. **FIFO**: se venden primero las participaciones más antiguas.
2. **Regla de recompra (art. 33.5)**: si se recompran valores homogéneos dentro de 2 meses antes o después (cotizados: ETF) o 1 año
   (no cotizados: fondos) y siguen en cartera al cerrarse la ventana, la parte proporcional de la pérdida no computa; se integra al
   vender lo recomprado. Con Bitcoin no se aplica por defecto (opción configurable).
3. **Integración y compensación (art. 49)**: las pérdidas patrimoniales netas compensan dividendos hasta el 25 % de estos; el resto se
   arrastra 4 años y compensa antes ganancias y después dividendos (con el mismo límite).
4. **Cuota íntegra** por la escala sobre la base del ahorro (más las «otras rentas del ahorro» que indiques, que ocupan tramos).
5. **Deducción por doble imposición**: el menor de lo pagado fuera y la cuota atribuible a esa renta.
6. **Resultado** = cuota − deducción − retenciones. Positivo: se paga. Negativo: se devuelve.

## Calendario simulado
- El impuesto del ejercicio N se paga en la campaña de N+1: pago único el **30 de junio** (o 60 % el 30-jun y 40 % el 5-nov).
- La devolución se abona 6 meses después del fin de campaña (plazo máximo legal; la fecha más desfavorable).
- Si el efectivo no alcanza, **pasa a negativo**: no se bloquea el pago ni se inventa dinero.
- Fechas oficiales cargadas: campaña 2026 (Renta 2025). Para los demás años se estima (30-jun o siguiente día hábil; 5-nov).

## Ejemplo numérico
Compras 1.000 € de un ETF (100 u.) y lo vendes por 1.500 €: ganancia 500 €; cuota 19 % = **95 €**. Si en cambio perdieras 2.000 € y
cobraras 1.000 € de dividendos: se compensan 250 € (25 %), base 750 € → cuota 142,50 €; quedan 1.750 € por compensar 4 años.

## Limitaciones conocidas
- Se asume que el mínimo personal y familiar se aplica a la base general; no reduce la base del ahorro.
- Los dividendos se cobran el día ex-dividendo y solo se admiten activos cotizados en EUR; la retención se practica como si el
  intermediario fuera español. El impuesto en origen por activo es configurable (0 por defecto).
- Los traspasos entrantes no cuentan como recompra.
- No se simulan declaraciones complementarias: el resultado de un ejercicio usa solo lo conocido al final de su campaña.
- Los modelos 720 y 721 solo se avisan (umbral de 50.000 €); no se presentan ni se sabe dónde está la custodia.
- Las opciones fiscales (otras rentas, pago fraccionado, etc.) existen en el motor; la pantalla para editarlas llega en la fase 5.
