# Documentación técnica de Brújula

Guía para entender el código y modificarlo. Complementa `ARQUITECTURA.md` (decisiones), `NUCLEO_FINANCIERO.md`, `DATOS_DE_MERCADO.md`,
`CONTRATACION.md`, `FISCALIDAD.md` y `CONFIGURACION.md` (puesta en marcha, firma y scripts).

## 1. Visión general
Aplicación Android nativa (Kotlin, Jetpack Compose, minSdk 26). Todo el dominio es **Kotlin puro y determinista** (sin Android), probado en JVM.
La capa Android (`app`, `data-db`) solo dibuja pantallas, guarda en SQLite, descarga datos y programa tareas.

```
core-model ──► core-engine ──► core-market ──► core-trading ──► core-tax ──► core-app ──► app
 (dinero)       (libro, FIFO,    (precios,         (órdenes,         (IRPF,      (modelos de    (Compose,
                 cuentas)         calendarios,      liquidación)      calendario)  pantalla)     WorkManager)
                                  proveedores)                                                   ▲
                                                                          data-db (SQLite, HTTP) ┘
```
Cada flecha es una dependencia de compilación: un módulo solo conoce a los anteriores.

## 2. Módulos
| Módulo | Qué contiene | Claves |
|---|---|---|
| `core-model` | `Money`, `Units`, `AccountId`, `AssetId` | `BigDecimal` exacto; igualdad que ignora la escala |
| `core-engine` | Libro de eventos, `AccountState`, FIFO, `Bank`, `BackupService`, `EventCodec`, JSON propio | El estado **se deriva** de reproducir los eventos |
| `core-market` | `PriceSeries`, rentabilidades, riesgo, calendarios, divisas, proveedores, `MarketUpdater`, catálogo | Nunca se inventan datos |
| `core-trading` | `TradingService`, liquidaciones, comisiones, planes periódicos | Reservas, ejecución por sesiones y cotización inmediata |
| `core-tax` | `TaxCalculator`, `TaxService`, calendario de la renta | Pura función del libro; reglas en datos con vigencia |
| `core-app` | Valoración, historial, estadísticas, modelos de mercado/órdenes/fiscalidad, `TimeMachine`, notificaciones, educación, formato es-ES | Todo lo que no es dibujar |
| `data-db` | `SqliteBankStore`, `SqliteMarketStore`, `HttpUrlFetcher` | Escritura transaccional |
| `app` | `AppContainer`, `Presenter`, `UiController`, pantallas Compose, WorkManager, avisos | Una sola instancia de `Bank` |

## 3. Ideas clave
- **Event sourcing**: cada cambio es un `LedgerEvent` inmutable (`OpeningBalance`, `MonthlyIncome`, `Purchase`, `Sale`, `FundTransfer`, `OrderPlaced`, `OrderClosed`,
  `OrderSettled`, `DividendReceived`, `WithholdingPaid`, `TaxSettlement`, `CashAdjustment`…). `AccountState.apply` valida y devuelve un estado nuevo o lanza una
  excepción sin dejar nada a medias. `LedgerAuditor` comprueba la identidad *efectivo + coste en cartera = aportaciones + beneficio realizado + ajustes + dividendos − retenciones*.
- **Persistencia antes que memoria**: `Bank` guarda en el `BankStore` antes de confirmar en memoria; si el disco falla, la operación falla.
- **Dinero**: importes en céntimos exactos; el redondeo es siempre explícito (`roundedToCents`). Nunca `Double` para dinero.
- **Reproducibilidad**: la fiscalidad y las estadísticas son funciones puras del libro y de los precios.
- **Datos reales o nada**: si falta un precio se avisa; no se rellena.

## 4. Flujo de datos
1. **00:00 (Madrid)** `UpdateWorker` → `MarketUpdater.updateAll` (precios, divisas, dividendos) → `TradingModule.processAll`: para cada cuenta, día a día:
   ingresos mensuales → planes periódicos → ejecuciones de órdenes → liquidaciones y caducidades → hechos fiscales (`TaxService`).
2. **Al abrir la app**: se recupera lo pendiente (`catchUpIfNeeded`) y se abonan los ingresos atrasados.
3. **Al enviar una orden**: `TradingService.placeBuy/placeSell`; los ETF y Bitcoin a mercado se ejecutan al instante con la última cotización
   (`TradingModule.latestQuote` refresca ese activo).
4. **Pantallas**: `Presenter.load` calcula todo fuera del hilo principal y publica `HomeData`; cada cambio de datos incrementa `AppContainer.version`.

## 5. Cómo modificar
- **Añadir un activo**: una línea en `core-market/.../Catalog.kt` (`AssetDefinition`) con sus símbolos de proxy; actualizar y mirar el diagnóstico.
- **Cambiar un proveedor de precios**: implementar `HistoryProvider` y añadirlo al mapa de `MarketModule.newUpdater`; nada más cambia.
- **Cambiar un plazo de liquidación o una comisión**: `SettlementRules` y `FeeSchedule` (`TradingConfig`), con vigencia por fecha.
- **Actualizar la fiscalidad**: `TaxRules.DEFAULT_SCALES` (nuevas escalas por ejercicio) y `TaxCalendar` (fechas de campaña oficiales). Actualiza `docs/FISCALIDAD.md` y `Education.kt`.
- **Nuevo evento del libro**: clase en `Events.kt`, rama en `AccountState.apply`, `LedgerAuditor`, `EventCodec` (JSON), `CsvExporter` y un test de ida y vuelta.
- **Cambiar el esquema SQLite**: sube `DB_VERSION` y añade la migración en `onUpgrade` (nunca borres datos); hay una `v1→v2` de ejemplo en `SqliteMarketStore`.
- **Nueva pantalla**: composable en `app/.../ui/`, estado en `UiController`, y los cálculos en `core-app` (para poder probarlos).

## 5b. Trampas conocidas
- **Smart cast entre módulos**: Gradle no permite hacer smart cast sobre una propiedad pública de *otro* módulo; usa una variable local (`val x = obj.prop`).
- El reloj de las órdenes nunca puede ir por detrás del último movimiento del libro (el libro exige fechas no decrecientes).

## 6. Pruebas
- Núcleo: `gradle test` (JUnit 5 con `kotlin.test`). Cubren dinero, FIFO, libro, persistencia, copias, mercado, órdenes, fiscalidad, modelos de pantalla, avance de tiempo y los
  ejemplos numéricos de la guía educativa (`EducationTest`).
- La interfaz Compose se verifica compilando en GitHub Actions y probando en el móvil.
- Estructura de tests típica: construir un `Ledger`/`Bank` con eventos, ejecutar y comprobar estado y auditoría.

## 7. Compilación y entrega
- GitHub Actions (`.github/workflows/build.yml`): instala Gradle, ejecuta pruebas, compila `app-release.apk` firmado y lo publica como artefacto.
- Firma: keystore propio generado con `tools/crear-firma.sh`, guardado en secretos de GitHub; **haz copia de `~/.brujula-firma/`**.
- Subida: `tools/subir-brujula.sh` (valida el ZIP, sincroniza, commit y push) y, en Termux, `brujula-termux.sh` (sube, espera la compilación y descarga el APK).

## 8. Limitaciones conocidas
Ver «Limitaciones de la simulación» en la guía de la app y las secciones de límites de cada documento. En resumen: datos gratuitos con retraso y proxies, fondos aproximados con ETF,
dividendos solo en EUR, fechas fiscales oficiales solo para 2026, sin declaraciones complementarias.
