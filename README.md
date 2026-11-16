# Brújula

Simulador de inversión para Android (fondos indexados, ETF y Bitcoin) con fiscalidad española.
Es una **simulación educativa**: no ejecuta operaciones reales ni constituye asesoramiento financiero.

- Kotlin + Jetpack Compose, mínimo Android 8 (API 26).
- Todo se guarda en local; Internet solo se usa para actualizar precios.
- Se compila en GitHub Actions (no hace falta Android Studio).

## Documentación
`docs/DOCUMENTACION_TECNICA.md` (cómo está hecho y cómo modificarlo), `docs/ARQUITECTURA.md`, `docs/NUCLEO_FINANCIERO.md`, `docs/DATOS_DE_MERCADO.md`,
`docs/CONTRATACION.md`, `docs/FISCALIDAD.md` y `docs/CONFIGURACION.md`. Dentro de la app: **Más › Cómo funciona todo (guía)**.

## Estado

**Fase 6 · funcionalidades completas.** Fondos, ETF y Bitcoin con datos reales con retraso, órdenes, liquidaciones, traspasos, fiscalidad española, estadísticas, copias de seguridad, avance de tiempo, notificaciones y guía educativa.

## Estructura

| Carpeta | Contenido |
|---|---|
| `app/` | Aplicación Android (Compose) |
| `core-model/` | Tipos financieros puros en Kotlin (sin Android), con pruebas |
| `tools/` | Script de subida de ZIP (`subir-brujula.sh`) |
| `docs/` | Arquitectura y guía de configuración |
| `.github/workflows/` | Compilación, pruebas y APK |

## Flujo de trabajo

1. Se genera un ZIP versionado (`Brujula-faseN-vX.Y.Z.zip`).
2. `bash subir-brujula.sh ruta/al.zip` valida, sincroniza, hace commit y push.
3. GitHub Actions ejecuta las pruebas y compila el APK.
4. El APK firmado (siempre con la misma clave) se descarga desde **Actions → ejecución → Artifacts**.

Primera configuración paso a paso: [`docs/CONFIGURACION.md`](docs/CONFIGURACION.md).
