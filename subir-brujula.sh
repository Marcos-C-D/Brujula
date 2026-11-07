#!/usr/bin/env bash
# =============================================================================
# Brújula - subir un ZIP del proyecto al repositorio de GitHub
#
# Qué hace:
#   1. Valida el ZIP (íntegro, sin rutas peligrosas, sin secretos, es Brújula).
#   2. Clona el repositorio si no existe, o lo actualiza si ya existe.
#   3. Sincroniza SOLO los archivos del proyecto: borra únicamente archivos que
#      una subida anterior (manifiesto .brujula-manifest) puso y ya no existen.
#      Cualquier otro archivo del repositorio se respeta.
#   4. Muestra un resumen, pide confirmación, hace commit y push.
#
# Credenciales: usa lo que ya tengas configurado en git (credential helper de
# gh, SSH, etc.). No pide ni guarda tokens y no imprime nunca su valor.
#
# Uso:
#   bash subir-brujula.sh [opciones] ruta/al/proyecto.zip
#
# Opciones:
#   -m "mensaje"   Mensaje de commit (por defecto se genera con el nombre del ZIP)
#   -d DIR         Carpeta local del repositorio (def.: ~/Projects/Brujula)
#   -r URL         URL del repositorio (def.: https://github.com/Marcos-C-D/Brujula.git)
#   -b RAMA        Rama (def.: main)
#   -y             No pedir confirmación
#   --force        Permitir que el ZIP sobrescriba archivos existentes que no
#                  provienen de una subida anterior (primera sincronización)
#   --dry-run      Solo validar y mostrar qué se haría; no toca el repositorio
#   -h, --help     Esta ayuda
#
# Variables equivalentes: BRUJULA_DIR, BRUJULA_REPO_URL, BRUJULA_BRANCH
# =============================================================================
set -euo pipefail

REPO_URL="${BRUJULA_REPO_URL:-https://github.com/Marcos-C-D/Brujula.git}"
REPO_DIR="${BRUJULA_DIR:-$HOME/Projects/Brujula}"
BRANCH="${BRUJULA_BRANCH:-main}"
MANIFEST=".brujula-manifest"
ASSUME_YES=0
FORCE=0
DRY_RUN=0
MSG=""
ZIP=""

# ---------- utilidades ----------------------------------------------------
mask() {
  sed -E \
    -e 's/(ghp_|gho_|ghs_|ghu_|ghr_)[A-Za-z0-9]{6,}/\1****/g' \
    -e 's/github_pat_[A-Za-z0-9_]+/github_pat_****/g' \
    -e 's#(https?://)[^/@[:space:]]+@#\1***@#g'
}
info() { printf '\033[1;34m[·]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[✔]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[✘]\033[0m %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

normalize_url() { printf '%s' "$1" | sed -E 's#^(https?://)[^/@]*@#\1#; s#\.git$##; s#/$##'; }

TMP=""
NEED_ROLLBACK=0

rollback() {
  # Seguro porque se exigió árbol limpio antes de sincronizar.
  cd "$REPO_DIR" 2>/dev/null || return 0
  if git rev-parse --verify HEAD >/dev/null 2>&1; then
    git reset -q --hard HEAD 2>/dev/null || true
    git clean -fdq 2>/dev/null || true
  else
    git rm -rq --cached . >/dev/null 2>&1 || true
    find "$REPO_DIR" -mindepth 1 -maxdepth 1 -not -name .git -exec rm -rf {} + 2>/dev/null || true
  fi
}

cleanup() {
  local code=$?
  if [ "$NEED_ROLLBACK" -eq 1 ]; then
    warn "Operación interrumpida: se deshacen los cambios locales sin commit."
    rollback
  fi
  [ -n "$TMP" ] && rm -rf "$TMP"
  exit "$code"
}
trap cleanup EXIT

# ---------- argumentos ----------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    -m) [ $# -ge 2 ] || die "Falta el valor de -m"; MSG="$2"; shift 2;;
    -d) [ $# -ge 2 ] || die "Falta el valor de -d"; REPO_DIR="$2"; shift 2;;
    -r) [ $# -ge 2 ] || die "Falta el valor de -r"; REPO_URL="$2"; shift 2;;
    -b) [ $# -ge 2 ] || die "Falta el valor de -b"; BRANCH="$2"; shift 2;;
    -y) ASSUME_YES=1; shift;;
    --force) FORCE=1; shift;;
    --dry-run) DRY_RUN=1; shift;;
    -h|--help) usage 0;;
    -*) die "Opción desconocida: $1 (usa -h)";;
    *) [ -z "$ZIP" ] || die "Solo se admite un ZIP"; ZIP="$1"; shift;;
  esac
done
[ -n "$ZIP" ] || { usage 1; }
[ -f "$ZIP" ] || die "No existe el archivo: $ZIP"
ZIP="$(cd "$(dirname "$ZIP")" && pwd)/$(basename "$ZIP")"

for t in git unzip tar sort comm find sed grep; do
  command -v "$t" >/dev/null 2>&1 || die "Falta la herramienta '$t' (instálala y vuelve a intentarlo)."
done
git config --get user.name  >/dev/null || die "git no tiene user.name configurado."
git config --get user.email >/dev/null || die "git no tiene user.email configurado."

# ---------- 1. validar el ZIP ---------------------------------------------
info "Validando $(basename "$ZIP")…"
unzip -tq "$ZIP" >/dev/null 2>&1 || die "El ZIP está corrupto o no es un ZIP válido."

# Enlaces simbólicos: no se admiten.
if unzip -Z "$ZIP" 2>/dev/null | grep -qE '^l'; then
  die "El ZIP contiene enlaces simbólicos; no se admiten."
fi

ENTRIES="$(unzip -Z1 "$ZIP")"
[ -n "$ENTRIES" ] || die "El ZIP está vacío."

# Rutas peligrosas.
if printf '%s\n' "$ENTRIES" | grep -qE '(^/|(^|/)\.\.(/|$)|\\)'; then
  die "El ZIP contiene rutas absolutas, con '..' o con barras invertidas: rechazado."
fi
# Carpeta .git dentro del ZIP.
if printf '%s\n' "$ENTRIES" | grep -qE '(^|/)\.git(/|$)'; then
  die "El ZIP contiene una carpeta .git: rechazado."
fi
# Ficheros que suelen contener secretos.
BAD="$(printf '%s\n' "$ENTRIES" | grep -E '(\.(jks|keystore|p12|pfx|pem|key)$|(^|/)\.env(\.|$)|(^|/)local\.properties$|(^|/)\.git-credentials$|(^|/)\.netrc$|(^|/)id_(rsa|ed25519|ecdsa)(\.pub)?$)' || true)"
if [ -n "$BAD" ]; then
  printf '%s\n' "$BAD" | sed 's/^/    /' >&2
  die "El ZIP incluye archivos que parecen credenciales o claves: rechazado."
fi
ok "ZIP íntegro y sin rutas peligrosas ni archivos de credenciales."

# ---------- 2. extraer a carpeta temporal ---------------------------------
TMP="$(mktemp -d)"
mkdir -p "$TMP/x"
unzip -q -o "$ZIP" -d "$TMP/x"
rm -rf "$TMP/x/__MACOSX"
find "$TMP/x" -name '.DS_Store' -delete

ROOT="$TMP/x"
if [ ! -f "$ROOT/settings.gradle.kts" ]; then
  mapfile -t subs < <(find "$ROOT" -mindepth 1 -maxdepth 1 -type d)
  if [ "${#subs[@]}" -eq 1 ] && [ -f "${subs[0]}/settings.gradle.kts" ]; then
    ROOT="${subs[0]}"
  fi
fi
[ -f "$ROOT/settings.gradle.kts" ] || die "No encuentro settings.gradle.kts: este ZIP no parece el proyecto Brújula."
grep -qE 'rootProject\.name *= *"Brujula"' "$ROOT/settings.gradle.kts" \
  || die "settings.gradle.kts no declara rootProject.name = \"Brujula\": ZIP de otro proyecto."

# Búsqueda de secretos en el contenido (solo texto).
if grep -rIlE 'ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY' "$ROOT" >/dev/null 2>&1; then
  grep -rIlE 'ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY' "$ROOT" | sed "s#^$ROOT/#    #" >&2
  die "Se han detectado tokens o claves privadas dentro del contenido del ZIP: rechazado."
fi
ok "Contenido revisado: es el proyecto Brújula y no se detectaron secretos."

NEW_LIST="$TMP/new.list"
( cd "$ROOT" && find . -type f | sed 's#^\./##' | grep -vx "$MANIFEST" | LC_ALL=C sort ) > "$NEW_LIST"
NEW_COUNT="$(wc -l < "$NEW_LIST" | tr -d ' ')"
[ "$NEW_COUNT" -gt 0 ] || die "El ZIP no contiene archivos."

# ---------- 3. comprobar acceso y preparar el repositorio ------------------
info "Comprobando acceso al repositorio…"
if ! GIT_TERMINAL_PROMPT=0 git ls-remote "$REPO_URL" >/dev/null 2>"$TMP/lsremote.err"; then
  mask < "$TMP/lsremote.err" | sed 's/^/    /' >&2
  die "No puedo acceder a $(normalize_url "$REPO_URL"). Revisa que exista y que git tenga credenciales (por ejemplo: 'gh auth status' y 'gh auth setup-git')."
fi
ok "Acceso al repositorio correcto."

if [ "$DRY_RUN" -eq 1 ]; then
  info "Modo --dry-run: no se toca el repositorio."
  OLD_COUNT=0
  if [ -d "$REPO_DIR/.git" ] && [ -f "$REPO_DIR/$MANIFEST" ]; then
    LC_ALL=C sort "$REPO_DIR/$MANIFEST" > "$TMP/old.list"
    OLD_COUNT="$(comm -23 "$TMP/old.list" "$NEW_LIST" | wc -l | tr -d ' ')"
  fi
  echo "    Archivos en el ZIP:                $NEW_COUNT"
  echo "    Se eliminarían (de subidas previas): $OLD_COUNT"
  echo "    Carpeta local:  $REPO_DIR"
  echo "    Repositorio:    $(normalize_url "$REPO_URL") (rama $BRANCH)"
  exit 0
fi

if [ ! -d "$REPO_DIR/.git" ]; then
  if [ -e "$REPO_DIR" ] && [ -n "$(ls -A "$REPO_DIR" 2>/dev/null)" ]; then
    die "$REPO_DIR existe, no está vacía y no es un repositorio git. Indica otra carpeta con -d."
  fi
  info "Clonando en $REPO_DIR …"
  mkdir -p "$(dirname "$REPO_DIR")"
  git clone -q "$REPO_URL" "$REPO_DIR" 2> >(mask >&2)
else
  [ "$(normalize_url "$(git -C "$REPO_DIR" remote get-url origin 2>/dev/null || echo '')")" = "$(normalize_url "$REPO_URL")" ] \
    || die "El remoto 'origin' de $REPO_DIR no coincide con $(normalize_url "$REPO_URL"). Usa -d con otra carpeta."
fi
cd "$REPO_DIR"

[ -z "$(git status --porcelain)" ] || { git status --short | head -n 15 | sed 's/^/    /' >&2; die "El repositorio local tiene cambios sin confirmar. Confírmalos o descártalos antes de subir."; }

if git rev-parse --verify HEAD >/dev/null 2>&1 || [ -n "$(git ls-remote --heads origin 2>/dev/null)" ]; then
  GIT_TERMINAL_PROMPT=0 git fetch -q origin 2> >(mask >&2) || die "Falló 'git fetch'."
  if git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
    git checkout -q "$BRANCH" 2>/dev/null || git checkout -q -b "$BRANCH" "origin/$BRANCH"
    git merge -q --ff-only "origin/$BRANCH" || die "Tu rama local '$BRANCH' ha divergido de origin. Resuélvelo manualmente."
  else
    die "El remoto tiene ramas pero no existe '$BRANCH'. Usa -b con la rama correcta."
  fi
else
  info "El repositorio remoto está vacío: se creará la rama '$BRANCH'."
  git checkout -q -B "$BRANCH"
fi

# ---------- 4. comprobar colisiones con archivos ajenos --------------------
HAS_HEAD=0
git rev-parse --verify HEAD >/dev/null 2>&1 && HAS_HEAD=1
OLD_LIST="$TMP/old.list"
: > "$OLD_LIST"
if [ -f "$MANIFEST" ]; then
  LC_ALL=C sort "$MANIFEST" > "$OLD_LIST"
elif [ "$HAS_HEAD" -eq 1 ] && [ "$FORCE" -eq 0 ]; then
  : > "$TMP/collide.list"
  while IFS= read -r f; do [ -e "$f" ] && printf '%s\n' "$f" >> "$TMP/collide.list"; done < "$NEW_LIST"
  if [ -s "$TMP/collide.list" ]; then
    head -n 20 "$TMP/collide.list" | sed 's/^/    /' >&2
    die "El ZIP sobrescribiría archivos que ya existen en el repositorio y no vienen de una subida anterior. Revísalos o usa --force."
  fi
fi

# ---------- 5. sincronizar -------------------------------------------------
NEED_ROLLBACK=1
REMOVED_LIST="$TMP/removed.list"
comm -23 "$OLD_LIST" "$NEW_LIST" > "$REMOVED_LIST"
REMOVED_COUNT="$(wc -l < "$REMOVED_LIST" | tr -d ' ')"
while IFS= read -r f; do
  [ -n "$f" ] || continue
  rm -f -- "$f"
done < "$REMOVED_LIST"

( cd "$ROOT" && tar cf - . ) | tar xf - -C "$REPO_DIR"
cp "$NEW_LIST" "$REPO_DIR/$MANIFEST"
# Quitar carpetas que hayan quedado vacías.
find "$REPO_DIR" -mindepth 1 -type d -empty -not -path "$REPO_DIR/.git/*" -delete 2>/dev/null || true

git add -A
if git diff --cached --quiet; then
  ok "No hay cambios respecto a lo que ya está en el repositorio. Nada que subir."
  NEED_ROLLBACK=0
  exit 0
fi

echo
info "Resumen de cambios:"
git diff --cached --stat | tail -n 25 | sed 's/^/    /'
echo "    Archivos eliminados por no estar ya en el proyecto: $REMOVED_COUNT"
echo

# ---------- 6. confirmar, commit y push -----------------------------------
if [ "$ASSUME_YES" -eq 0 ]; then
  read -r -p "¿Crear commit y subir a '$BRANCH'? [s/N] " resp
  case "$resp" in
    s|S|si|SI|sí|Sí) ;;
    *) warn "Cancelado por el usuario."; exit 1;;
  esac
fi

[ -n "$MSG" ] || MSG="Brújula: $(basename "$ZIP" .zip) ($(date '+%Y-%m-%d %H:%M'))"
git commit -q -m "$MSG" -m "Subida automática desde ZIP: $(basename "$ZIP"). Archivos en el proyecto: $NEW_COUNT; eliminados: $REMOVED_COUNT."
NEED_ROLLBACK=0
COMMIT="$(git rev-parse --short HEAD)"
ok "Commit creado: $COMMIT"

info "Subiendo a origin/$BRANCH …"
set +e
git push -u origin "$BRANCH" 2>&1 | mask
PUSH_CODE="${PIPESTATUS[0]}"
set -e
if [ "$PUSH_CODE" -ne 0 ]; then
  warn "El commit está guardado en local ($COMMIT), pero el push falló."
  die "Revisa los mensajes anteriores. Si es de credenciales: 'gh auth status'. Puedes reintentar con 'git -C \"$REPO_DIR\" push'."
fi

ACTIONS_URL="$(normalize_url "$REPO_URL")/actions"
echo
ok "Subida completada (commit $COMMIT)."
echo "    GitHub Actions compilará el APK: $ACTIONS_URL"
