# Arquitectura (decisiones aprobadas)

## Principios
- Kotlin, Jetpack Compose, Room, WorkManager. minSdk 26, target 35.
- Dinero siempre en `BigDecimal`; nunca `Double`.
- **Libro de movimientos inmutable**: cada cambio de efectivo o participaciones es un asiento;
  patrimonio, cartera y estadísticas se derivan de él (auditable y reproducible).
- **Motor determinista con reloj inyectado**: recibe fecha, precios y reglas, devuelve asientos.
  Permite pruebas rápidas en JVM y el modo de avance de tiempo.
- Modo de avance de tiempo sobre una base de datos sandbox separada de la real.
- Inyección de dependencias manual (contenedor propio) para acortar la compilación en CI.
- Reglas fiscales y de liquidación como **datos con fecha de vigencia** (no fijadas en el código).

## Módulos
| Módulo | Responsabilidad | Estado |
|---|---|---|
| `core-model` | Tipos puros: dinero, unidades, identificadores | Hecho (fase 1) |
| `core-engine` | Libro, cuentas, ingresos, FIFO (fase 1); órdenes y liquidaciones (fase 3) | Fase 1 hecha |
| `core-tax` | Fiscalidad y calendario fiscal | Fase 4 |
| `data-market` | Proveedores de precios intercambiables, divisas, calendarios | Fase 2 |
| `data-db` | Almacén SQLite nativo (en vez de Room: esquema simple de registro de eventos, menos dependencias) | Hecho (fase 1b) |
| `app` y `feature-*` | Interfaz | Fase 5 |

## Reglas aprobadas
1. **ETF**: órdenes de mercado al precio de apertura de la siguiente sesión. Limitadas: compra si el mínimo
   de la sesión baja del límite, venta si el máximo lo supera, ejecución al límite (o a la apertura si ya lo
   mejora). Si solo lo toca exactamente, no se ejecuta.
2. **Fondos**: valor liquidativo = cierre del proxy del día de valoración aplicable según la hora de corte; nunca
   un valor aún no publicado.
3. **Bitcoin**: precio actual de CoinGecko; histórico largo de otra fuente (CoinGecko gratis solo da 365 días).
4. **Regla de recompra**: dos meses para valores cotizados, un año para el resto; se contrastará con la LIRPF.
5. Nombre: Brújula.

## Fases
| Fase | Contenido |
|---|---|
| 0 | Esqueleto, CI, script de subida, documentación |
| 1 | Núcleo financiero con tests: dinero, libro, cuentas, ingresos, FIFO, independencia |
| 2 | Datos de mercado, calendarios, divisas, rentabilidades, volatilidad, diagnóstico |
| 3 | Órdenes, reservas, liquidaciones, traspasos, limitadas, stop-loss, programadas |
| 4 | Fiscalidad y calendario fiscal |
| 5 | Interfaz completa, notificaciones, copias de seguridad, avance de tiempo |
| 6 | Sección educativa, documentación técnica, ZIP final |

## Verificación
Las pruebas se ejecutan en GitHub Actions (el workflow falla si alguna prueba o la compilación falla).
Limitaciones conocidas de los datos y de la fiscalidad se documentarán en la app y en `docs/`.
