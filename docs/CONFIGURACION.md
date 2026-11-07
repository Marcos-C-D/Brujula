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
bash subir-brujula.sh ~/Downloads/Brujula-fase0-v0.0.1.zip
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

## 3. Primera compilación

En GitHub: **Actions → Compilar Brújula**. Al terminar, el APK está en **Artifacts**
(el artefacto se descarga como un ZIP que contiene el `.apk`).

> Sin secretos de firma, se genera un APK **debug**. Un APK debug y uno firmado con tu
> clave propia **no se pueden instalar uno encima del otro** (Android obliga a desinstalar).
> Para no perder datos más adelante, **configura la firma (apartado 4) antes de instalar
> la app por primera vez** en tu móvil.

## 4. Firma propia del APK (keystore)

### 4.1 Generar el keystore (en tu PC)

```bash
keytool -genkeypair -v -keystore brujula-release.jks -alias brujula \
        -keyalg RSA -keysize 4096 -validity 10000
```

Te pedirá una contraseña y datos del certificado (pueden ser genéricos). Con el formato
PKCS12 actual, **la contraseña de la clave es la misma que la del keystore**.

Guarda `brujula-release.jks` y la contraseña en un lugar seguro **fuera del repositorio**
(gestor de contraseñas y una copia de seguridad del archivo). **Si pierdes el keystore no podrás
actualizar la app instalada**: habría que desinstalarla y se perderían sus datos locales.

### 4.2 Guardar los secretos en GitHub (sin escribirlos en ningún archivo)

```bash
base64 -w0 brujula-release.jks | gh secret set KEYSTORE_BASE64 --repo Marcos-C-D/Brujula
gh secret set KEYSTORE_PASSWORD --repo Marcos-C-D/Brujula   # te pide el valor (oculto)
gh secret set KEY_PASSWORD      --repo Marcos-C-D/Brujula   # la misma contraseña
gh secret set KEY_ALIAS         --repo Marcos-C-D/Brujula   # valor: brujula
```

En la siguiente ejecución, el workflow compilará `assembleRelease` firmado.

## 5. Termux (más adelante)

`tools/subir-brujula.sh` es compatible con Termux: necesita `git` y `unzip`. Su configuración de
credenciales se revisará con el script de diagnóstico antes de usarlo allí.
