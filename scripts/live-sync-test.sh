#!/usr/bin/env bash
# Runs the app <-> server sync tests against a real Suwayomi-Server (the mzramna fork) started here,
# on H2 or PostgreSQL:
#
#   SERVER_JAR=/path/Suwayomi-Server-vX.jar scripts/live-sync-test.sh h2
#   SERVER_JAR=... PGHOST=localhost PGUSER=postgres PGPASSWORD=x scripts/live-sync-test.sh postgres
#
# The server is started once to create its schema, stopped, seeded (scripts/live/seed.py), started again
# with Basic Auth (work /live/m30 is reserved for ServerSyncApiLiveTest, m01..m40 for the bidirectional tests), and the tests in SyncLiveBidirectionalTest / ServerSyncApiLiveTest run against it.
set -euo pipefail

DB="${1:-h2}"
: "${SERVER_JAR:?SERVER_JAR must point at the Suwayomi-Server jar of the fork}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(mktemp -d)"
PORT="${LIVE_PORT:-4591}"
USER_NAME=karasu
USER_PASS=s3cret
SERVER_PID=""
P=suwayomi.tachidesk.config.server
# run a copy under another name: other scripts clean up with `pkill -f Suwayomi-Server` and must not hit this one
APP_JAR="$ROOT/app.jar"
cp "$SERVER_JAR" "$APP_JAR"

DB_ARGS=()
case "$DB" in
  h2) ;;
  postgres)
    export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
    DBNAME="karasu_live_$$"
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
  java "-D$P.rootDir=$ROOT" "-D$P.port=$PORT" "-D$P.ip=127.0.0.1" "-D$P.webUIEnabled=false" \
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

echo "== [$DB] seeding"
python3 "$HERE/live/seed.py" "$ROOT" "$ROOT/seed.sql" "${LIVE_WORKS:-40}"
if [[ "$DB" == postgres ]]; then
  PGOPTIONS='-c search_path=suwayomi' psql -d "$DBNAME" -q -v ON_ERROR_STOP=1 -f "$ROOT/seed.sql"
else
  java -cp "$SERVER_JAR" org.h2.tools.RunScript -url "jdbc:h2:$ROOT/database" -script "$ROOT/seed.sql"
fi

echo "== [$DB] starting the server for the tests"
start_server
curl -fsS -u "$USER_NAME:$USER_PASS" "http://127.0.0.1:$PORT/api/v1/karasu/health"; echo

cd "$HERE/.."
KARASU_LIVE_URL="http://127.0.0.1:$PORT" KARASU_LIVE_USER="$USER_NAME" KARASU_LIVE_PASS="$USER_PASS" \
KARASU_LIVE_MANGA_URL=/live/m30 KARASU_LIVE_DOWNLOADED=/live/m30/c2 KARASU_LIVE_MISSING=/live/m30/c3 \
  ./gradlew :app:testStandardDebugUnitTest --tests '*SyncLiveBidirectionalTest' --tests '*ServerSyncApiLiveTest' \
  ${GRADLE_ARGS:-} --console=plain
