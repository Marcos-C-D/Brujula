# Configuración inicial

Todo lo de esta guía se hace **una sola vez**.

## 1. Comprobar que git puede subir al repositorio

En tu PC ya tienes GitHub CLI configurado como credential helper de git. Comprueba:

```bash
gh auth status
```

Debe aparecer tu cuenta, `Token scopes` con **repo**, y `Git operations protocol: https`.
Si algo falta: `gh auth login` y luego `gh auth setup-git`.

No hace falta generar otro Personal Access Token ni copiarlo a ningún script:
el script usa lo que git ya tiene configurado y nunca imprime ni guarda tokens.

## 2. Subir el primer ZIP

```bash
bash subir-brujula.sh ~/Downloads/Brujula-fase1-v0.1.0.zip
```

- Si `~/Projects/Brujula` no existe, lo clona (el repositorio remoto puede estar vacío).
- Muestra un resumen y pide confirmación (`s`).
- Usa `--dry-run` para validar sin tocar nada, `-y` para no preguntar, `-d` para otra carpeta.

Seguridad del script:
- Rechaza ZIP corruptos, con rutas absolutas o `..`, enlaces simbólicos o carpeta `.git`.
- Rechaza ZIP con claves o credenciales (`.jks`, `.keystore`, `.pem`, `.env`, `local.properties`,
  tokens o claves privadas en el texto).
- Solo borra archivos que él mismo subió antes (lista en `.brujula-manifest`).
- Si el ZIP fuera a sobrescribir archivos ajenos, se detiene (salvo `--force`).
- Si falla algo antes del commit, deshace los cambios locales.

## 3. Firma propia del APK (obligatoria, una sola vez)

Android solo deja instalar una versión **encima de otra** si ambas están firmadas con la misma clave.
Si cada compilación usara una clave distinta (como hacen los APK "debug" que genera GitHub en cada
ejecución), aparecería el error `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

Por eso el workflow **exige una firma fija** y falla si no está configurada. Para crearla:

```bash
bash tools/crear-firma.sh
```

Qué hace:
- Genera `~/.brujula-firma/brujula-release.jks` con una contraseña aleatoria (`contrasena.txt`, solo legible por ti).
- Sube a GitHub los secretos `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` y `KEY_ALIAS` mediante `gh`,
  sin mostrar ningún valor.
- Si el keystore ya existe **no lo regenera**: solo vuelve a subir los secretos.
- Muestra la huella SHA-256 de tu firma.

**Haz copia de seguridad de `~/.brujula-firma/`** (pendrive o gestor de contraseñas) y no la subas a ningún
repositorio. Si la pierdes, tendrás que desinstalar la app (se pierden sus datos locales) para instalar una
firmada con otra clave.

## 4. Compilar e instalar

En GitHub: **Actions → Compilar Brújula**. Al terminar, el APK firmado está en **Artifacts**
(se descarga como un ZIP que contiene `app-release.apk`).

```bash
adb install -r app-release.apk
```

Si ya tienes instalada una versión firmada con **otra** clave (por ejemplo un APK debug anterior),
desinstálala una única vez: `adb uninstall app.brujula`. A partir de entonces, `adb install -r` actualiza
conservando los datos.

## 5. Termux (más adelante)

`tools/subir-brujula.sh` es compatible con Termux: necesita `git` y `unzip`. Su configuración de
credenciales se revisará con el script de diagnóstico antes de usarlo allí.
