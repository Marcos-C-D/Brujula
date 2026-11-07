# Brújula

Simulador de inversión para Android (fondos indexados, ETF y Bitcoin) con fiscalidad española.
Es una **simulación educativa**: no ejecuta operaciones reales ni constituye asesoramiento financiero.

- Kotlin + Jetpack Compose, mínimo Android 8 (API 26).
- Todo se guarda en local; Internet solo se usa para actualizar precios.
- Se compila en GitHub Actions (no hace falta Android Studio).

## Estado

**Fase 0 · esqueleto de compilación.** La app solo muestra una pantalla inicial.
Sirve para validar el ciclo completo: ZIP → script → push → Actions → APK.
Ver [`CHANGELOG.md`](CHANGELOG.md) y [`docs/ARQUITECTURA.md`](docs/ARQUITECTURA.md).

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
4. El APK se descarga desde la pestaña **Actions → ejecución → Artifacts**.

Primera configuración paso a paso: [`docs/CONFIGURACION.md`](docs/CONFIGURACION.md).
