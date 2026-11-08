#!/usr/bin/env bash
# =============================================================================
# Brújula - crear la firma propia del APK y guardarla como secretos de GitHub
#
# Se ejecuta UNA vez. Hace esto:
#   1. Genera un keystore con contraseña aleatoria en ~/.brujula-firma/ (permisos privados).
#   2. Sube a GitHub los 4 secretos que usa el workflow (sin mostrar ningún valor):
#        KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
#
# Si el keystore ya existe NO lo regenera (cambiar la firma impediría actualizar la app);
# solo vuelve a subir los secretos si faltan o los has borrado.
#
# Uso:
#   bash crear-firma.sh [-r usuario/repositorio] [-d carpeta]
# Requisitos: keytool (JDK), openssl, base64, gh (con sesión iniciada).
# =============================================================================
set -euo pipefail
umask 077

REPO="Marcos-C-D/Brujula"
DIR="$HOME/.brujula-firma"
ALIAS_NAME="brujula"

info() { printf '\033[1;34m[·]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[✔]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[✘]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    -r) [ $# -ge 2 ] || die "Falta el valor de -r"; REPO="$2"; shift 2;;
    -d) [ $# -ge 2 ] || die "Falta el valor de -d"; DIR="$2"; shift 2;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) die "Opción desconocida: $1";;
  esac
done

for t in keytool openssl base64 gh; do
  command -v "$t" >/dev/null 2>&1 || die "Falta '$t'. En Arch: sudo pacman -S jdk-openjdk openssl github-cli coreutils"
done
gh auth status >/dev/null 2>&1 || die "gh no tiene sesión iniciada. Ejecuta: gh auth login"
gh repo view "$REPO" >/dev/null 2>&1 || die "No puedo acceder al repositorio $REPO con gh."

KS="$DIR/brujula-release.jks"
PWF="$DIR/contrasena.txt"
mkdir -p "$DIR"
chmod 700 "$DIR"

if [ -f "$KS" ] && [ -f "$PWF" ]; then
  info "El keystore ya existe en $DIR: NO se regenera."
else
  [ ! -e "$KS" ] || die "Existe $KS pero falta $PWF. No toco nada para no perder la firma; revisa $DIR."
  info "Generando keystore nuevo…"
  openssl rand -base64 33 | tr -d '/+=\n' | cut -c1-28 > "$PWF"
  chmod 600 "$PWF"
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 \
          -alias "$ALIAS_NAME" -keyalg RSA -keysize 4096 -validity 10000 \
          -dname "CN=Brujula, O=Personal, C=ES" \
          -storepass:file "$PWF" >/dev/null 2>&1 \
    || { rm -f "$KS" "$PWF"; die "keytool falló al generar el keystore."; }
  chmod 600 "$KS"
  ok "Keystore creado."
fi

info "Subiendo secretos a $REPO (los valores no se muestran)…"
base64 -w0 "$KS" | gh secret set KEYSTORE_BASE64 --repo "$REPO" >/dev/null
tr -d '\n' < "$PWF" | gh secret set KEYSTORE_PASSWORD --repo "$REPO" >/dev/null
tr -d '\n' < "$PWF" | gh secret set KEY_PASSWORD      --repo "$REPO" >/dev/null
printf '%s' "$ALIAS_NAME" | gh secret set KEY_ALIAS   --repo "$REPO" >/dev/null
ok "Secretos guardados: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_PASSWORD, KEY_ALIAS"

HUELLA="$(keytool -list -v -keystore "$KS" -storepass:file "$PWF" 2>/dev/null | grep -E 'SHA-?256:' | head -n1 | sed 's/^[[:space:]]*//')"
echo
echo "Huella (SHA-256) de tu firma — todos tus APK deben llevar esta misma:"
echo "    ${HUELLA:-(no disponible)}"
echo
warn "HAZ COPIA DE SEGURIDAD de la carpeta $DIR (pendrive o gestor de contraseñas)."
warn "Contiene el keystore y su contraseña. Si los pierdes no podrás actualizar la app instalada."
warn "No los subas a ningún repositorio ni los compartas."
