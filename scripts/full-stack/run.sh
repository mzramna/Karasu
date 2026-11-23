#!/usr/bin/env bash
# Full-stack battery, OUTSIDE the pipelines: real server + real WebUI (in a real browser) + the app's sync code,
# all talking to each other, on H2 or PostgreSQL. Each scenario changes something on one side (the app, or the
# WebUI by clicking in Chromium) and checks the other.
#
#   SERVER_JAR=/path/Suwayomi-Server-vX.jar WEBUI_DIR=/path/Suwayomi-WebUI/build \
#     scripts/full-stack/run.sh h2
#   ... PGHOST=localhost PGUSER=postgres PGPASSWORD=x scripts/full-stack/run.sh postgres
#
# Needs: java 21 (server), the app's JDK + Android SDK (Gradle), python3, node with the `playwright` module
# (WEBUI_REPO=/path/Suwayomi-WebUI makes its node_modules visible) and a Chromium (CHROMIUM=/path, optional).
# ONLY_STACK=1 starts the stack and runs no tests (to develop against it). KEEP_UP=1 leaves the stack running after the tests so you can poke at it; the URL is printed.
set -euo pipefail

DB="${1:-h2}"
: "${SERVER_JAR:?SERVER_JAR must point at the Suwayomi-Server jar of the fork}"
: "${WEBUI_DIR:?WEBUI_DIR must point at the WebUI build directory (the one with index.html)}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(mktemp -d)"
PORT="${FULLSTACK_PORT:-4592}"
USER_NAME=karasu
USER_PASS=s3cret
SERVER_PID=""
P=suwayomi.tachidesk.config.server
# run a copy under another name: other scripts clean up with `pkill -f Suwayomi-Server` and must not hit this one
APP_JAR="$ROOT/app.jar"
cp "$SERVER_JAR" "$APP_JAR"

[[ -f "$WEBUI_DIR/index.html" ]] || { echo "WEBUI_DIR has no index.html: $WEBUI_DIR" >&2; exit 2; }

DB_ARGS=()
case "$DB" in
  h2) ;;
  postgres)
    export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
    DBNAME="karasu_fullstack_$$"
    psql -d postgres -qc "DROP DATABASE IF EXISTS $DBNAME" -c "CREATE DATABASE $DBNAME"
    DB_ARGS=(-D$P.databaseType=POSTGRESQL "-D$P.databaseUrl=postgresql://$PGHOST:$PGPORT/$DBNAME"
             "-D$P.databaseUsername=$PGUSER" "-D$P.databasePassword=${PGPASSWORD:-}")
    ;;
  *) echo "usage: $0 h2|postgres" >&2; exit 2 ;;
esac

stop_server() {
  [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null && wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
}
cleanup() { stop_server; [[ "$DB" == postgres ]] && psql -d postgres -qc "DROP DATABASE IF EXISTS $DBNAME" || true; rm -rf "$ROOT"; }
trap cleanup EXIT

start_server() {
  java "-D$P.rootDir=$ROOT" "-D$P.port=$PORT" "-D$P.ip=127.0.0.1" \
       "-D$P.webUIEnabled=true" "-D$P.webUIFlavor=CUSTOM" \
       "-D$P.initialOpenInBrowserEnabled=false" "-D$P.systemTrayEnabled=false" "-D$P.downloadAsCbz=true" \
       "-D$P.authMode=BASIC_AUTH" "-D$P.authUsername=$USER_NAME" "-D$P.authPassword=$USER_PASS" \
       "${DB_ARGS[@]}" -Xmx1g -jar "$APP_JAR" > "$ROOT/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 120); do
    curl -fsS -u "$USER_NAME:$USER_PASS" "http://127.0.0.1:$PORT/api/v1/karasu/health" >/dev/null 2>&1 && return 0
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "server died:"; tail -30 "$ROOT/server.log"; exit 1; }
    sleep 2
  done
  echo "server did not come up:"; tail -30 "$ROOT/server.log"; exit 1
}

echo "== [$DB] first start: creating the schema"
start_server
stop_server

echo "== [$DB] seeding works, CBZs and the WebUI"
python3 "$HERE/../live/seed.py" "$ROOT" "$ROOT/seed.sql" "${LIVE_WORKS:-40}"
if [[ "$DB" == postgres ]]; then
  PGOPTIONS='-c search_path=suwayomi' psql -d "$DBNAME" -q -v ON_ERROR_STOP=1 -f "$ROOT/seed.sql"
else
  java -cp "$SERVER_JAR" org.h2.tools.RunScript -url "jdbc:h2:$ROOT/database" -script "$ROOT/seed.sql"
fi
# the server serves a custom WebUI from <root>/webUI and reads its version from a `revision` file, like the release zip has
mkdir -p "$ROOT/webUI" && cp -r "$WEBUI_DIR"/. "$ROOT/webUI/"
[[ -f "$ROOT/webUI/revision" ]] || echo "rfullstack" > "$ROOT/webUI/revision"

echo "== [$DB] starting the full stack"
start_server
echo "server + WebUI: http://127.0.0.1:$PORT  (user $USER_NAME)"

if [[ "${ONLY_STACK:-0}" == 1 ]]; then
  echo "ONLY_STACK=1: stack up, no tests (Ctrl-C to stop)"; wait "$SERVER_PID" || true; exit 0
fi

cd "$HERE/../.."
export KARASU_LIVE_URL="http://127.0.0.1:$PORT" KARASU_LIVE_USER="$USER_NAME" KARASU_LIVE_PASS="$USER_PASS"
export KARASU_FULLSTACK_DRIVER="$HERE/web-driver.mjs"
export KARASU_FULLSTACK_NODE_PATH="${WEBUI_REPO:+$WEBUI_REPO/node_modules}"
set +e
./gradlew :app:testStandardDebugUnitTest --tests '*FullStackTest' ${GRADLE_ARGS:-} --console=plain
STATUS=$?
set -e
if [[ "${KEEP_UP:-0}" == 1 ]]; then echo "KEEP_UP=1: stack left running on http://127.0.0.1:$PORT (Ctrl-C to stop)"; wait "$SERVER_PID" || true; fi
exit $STATUS
