# Cambios

## 0.7.0 · Fase 5b-1 · operaciones, estadísticas y fiscalidad
- Pestaña **Operaciones**: nueva orden (comprar, vender, traspasar; a mercado, limitada y stop-loss; caducidad), órdenes pendientes con
  cancelación, historial de órdenes con su cronología (solicitada → aceptada → ejecutada → liquidada) e historial de movimientos
  con el efectivo tras cada uno, y planes periódicos (crear, pausar, reanudar, eliminar).
- **Estadísticas**: rentabilidad total, ponderada por tiempo y anualizada, resultados acumulados, comparación con el índice elegido,
  evolución del patrimonio y del efectivo, y reparto por activos y categorías.
- **Fiscalidad**: próximas fechas de pago o devolución, resumen por ejercicio y opciones fiscales por cuenta (pago fraccionado, otras
  rentas del ahorro, retenciones, recompra con Bitcoin), guardadas con la cuenta y en las copias de seguridad.
- Núcleo: opciones fiscales por cuenta, modelos de órdenes, movimientos y fiscalidad. 184 pruebas automáticas.
- Pendiente (fase 5b-2): copias de seguridad desde la app, modo de avance de tiempo y notificaciones.

## 0.6.0 · Fase 5a · interfaz (Inicio, Cartera, Mercados, Cuentas)
- Nuevo módulo `core-app` (Kotlin puro, probado): formato es-ES, valoración de cuenta (patrimonio, efectivo disponible, beneficio,
  reparto por activos y categorías), evolución del patrimonio, estadísticas (rentabilidad ponderada por tiempo y anualizada,
  comparación con un índice) y modelos de mercado (rentabilidades, riesgo explicado, antigüedad del precio).
- Interfaz Compose: Inicio, Cartera, Mercados (buscador, categorías, ficha con gráfico y rentabilidades), Cuentas (crear,
  renombrar, cambiar ingreso mensual, archivar, restaurar, eliminar) y Más (diagnóstico).
- Una única instancia de datos compartida por la interfaz y la tarea de las 00:00.
- Si falta el precio de un activo no se inventa: se avisa y su valor no entra en el patrimonio.
- Pendiente (fase 5b): operaciones y órdenes, estadísticas en pantalla, copias de seguridad, ajustes fiscales y avance de tiempo.

## 0.5.0 · Fase 4 · fiscalidad española
- Nuevo módulo `core-tax`: escala del ahorro con vigencia por ejercicio (30 % desde 300.000 €), FIFO fiscal, regla de recompra
  (2 meses ETF, 1 año fondos), integración y compensación (25 %, arrastre 4 años), deducción por doble imposición y resultado
  anual (pagar o devolver).
- Nuevos eventos del libro: dividendo cobrado, retención practicada y liquidación del IRPF.
- Calendario de la renta: campaña 2026 oficial y estimaciones para otros años; pago único o fraccionado 60/40; devolución a los 6 meses;
  el efectivo puede quedar negativo al pagar.
- Retención del 19 % en dividendos y en reembolsos de fondos con ganancia; traspasos de fondos sin tributar.
- Dividendos reales desde Yahoo (solo activos en EUR); base de datos de mercado migra a la versión 2 sin perder datos.
- Avisos informativos de los modelos 720/721. 163 pruebas automáticas en total.

## 0.4.1 · corrección de compilación
- `TradingService`: eliminados los smart casts sobre propiedades de otro módulo (`units`, `settlesOn`) que Gradle rechazaba.

## 0.4.0 · Fase 3 · órdenes, liquidaciones y traspasos
- Las órdenes son eventos del libro: solicitada → aceptada → ejecutada → liquidada (o cancelada, caducada, rechazada), con
  cronología consultable por orden. Las reservas de efectivo (compras) y de unidades (ventas, traspasos) se derivan de las
  órdenes pendientes y evitan gastar dos veces el mismo dinero.
- Órdenes a mercado, limitadas, stop-loss (venta), con caducidad configurable; compras y ventas periódicas (semanal/mensual).
- Fondos: ejecución al valor liquidativo según la hora de corte, participaciones bloqueadas hasta liquidar, traspasos con
  diferimiento fiscal (conservan coste y fecha de adquisición, sin ganancia ni efectivo). Los ETF y Bitcoin no se traspasan.
- Liquidaciones con calendarios y vigencia (EE. UU. T+1; Europa T+2 hasta el 10-10-2027 y T+1 desde el 11-10-2027;
  fondos 2/3/4 días hábiles configurables; Bitcoin inmediata). El efectivo vendido no está disponible hasta liquidar.
- Comisiones configurables (0 por defecto). Procesado día a día e idempotente; si falta un dato de mercado, las órdenes esperan.
- Nuevo módulo `core-trading`. 139 pruebas automáticas en total.
- La actualización diaria de las 00:00 ahora también procesa las órdenes. Aún no hay interfaz para crearlas (fase 5).

## 0.3.0 · Fase 2 · datos de mercado
- Nuevo módulo `core-market`: series de precios, rentabilidades (YTD, 3m, 6m, 1a, 2a, 5a, 10a, desde el inicio) con
  «Sin datos suficientes» cuando no hay histórico, volatilidad anualizada y clase de riesgo 1-7, calendarios de mercado
  (NYSE, Xetra, Euronext, Londres), conversión a EUR con tipos del BCE, y comprobación de antigüedad de precios.
- Proveedores intercambiables: Yahoo Finance (no oficial), CoinGecko y BCE, con símbolos alternativos y respaldo.
  Nunca se inventan datos: si falla todo se conserva el último precio válido y se anota el error.
- Actualización diaria a las 00:00 (Europe/Madrid, con cambio de horario) con WorkManager y recuperación al abrir la app.
- Catálogo de 17 activos sin marcas comerciales; los símbolos de proxy están sin verificar (ver diagnóstico).
- La app incluye un botón de diagnóstico que actualiza y muestra el resultado por activo.
- 119 pruebas automáticas en total.

## 0.2.0 · Fase 1b · persistencia y copias de seguridad
- `Bank` guarda cada cambio antes de confirmarlo (si el guardado falla, no cambia nada) y se recupera con `Bank.restore`.
- Nuevo módulo `data-db`: `SqliteBankStore` (SQLite nativo, transaccional, importes como texto).
- Copia de seguridad JSON completa (`BackupService`) con validación y auditoría al importar (todo o nada).
- Exportación CSV por cuenta (formato Excel en español por defecto).
- Identificadores de cuenta aleatorios (UUID).
- La app muestra un resumen verificado de los datos guardados y una autoprueba de persistencia con base temporal.
- 85 pruebas automáticas en el núcleo.

## 0.1.0 · Fase 1 · núcleo financiero
- `core-model`: `Money` (ahora con igualdad que ignora la escala), `Units`, `AccountId`, `AssetId`.
- Nuevo módulo `core-engine`: libro de movimientos inmutable, lotes con FIFO exacto, estado derivado de
  los eventos, auditor independiente, planificador de ingresos mensuales y gestión de cuentas
  independientes (`Bank`): crear, renombrar, archivar, restaurar y eliminar.
- 59 pruebas automáticas (incluye 3.000 operaciones aleatorias auditadas y concurrencia).
- Pendiente para la siguiente fase: persistencia en Room y copias de seguridad JSON/CSV.

## 0.0.3 · Fase 0 · firma fija obligatoria
- Nuevo `tools/crear-firma.sh`: crea el keystore propio y sube los secretos de firma a GitHub.
- El workflow exige los secretos y compila siempre `app-release.apk` firmado con la misma clave
  (corrige `INSTALL_FAILED_UPDATE_INCOMPATIBLE` al actualizar).
- La variante debug también usa esa firma cuando está disponible.

## 0.0.2 · Fase 0 · corrección del script de subida
- `tools/subir-brujula.sh`: corregido un error de ordenación (`comm`) en sistemas con idioma distinto de C
  (p. ej. es_ES.UTF-8) que abortaba la primera subida. Ahora la comparación fuerza `LC_ALL=C`.

## 0.0.1 · Fase 0 · esqueleto
- Proyecto Gradle multimódulo (`app`, `core-model`), minSdk 26, compileSdk/targetSdk 35.
- `Money`: importe exacto en euros (BigDecimal) con pruebas unitarias.
- Workflow de GitHub Actions: pruebas, APK firmado (si hay secretos) o debug, artefactos.
- Script `tools/subir-brujula.sh` con validación del ZIP, manifiesto y push.
- Documentación inicial.
