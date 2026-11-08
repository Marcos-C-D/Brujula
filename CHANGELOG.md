# Cambios

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
