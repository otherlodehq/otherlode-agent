#!/usr/bin/env bash
# The soak stack on the VM: Postgres in Docker, the Otherlode server with its snapshot refresh
# loop, and the collector. Every command is safe to run again.
#
#   stack.sh up                start what is not running, then print where things are
#   stack.sh down              stop the collector, the refresh loop, the server and Postgres
#   stack.sh status            print what runs; exit 1 when something does not
#   stack.sh stop-collector    stop only the collector (the supervisor's outage)
#   stack.sh start-collector   start only the collector
#   stack.sh collector-limit low|normal
#                              restart the collector with a low manifest element limit (the build of
#                              bin/otherlode-collector-lowmanifest) or the normal one; the collector
#                              keeps the mode across later stops and starts
#   stack.sh restart-server    stop and start only the server
#   stack.sh session           mint a browser session for soak@example.com into secrets/session-token
#   stack.sh run-server|run-collector|refresh-loop
#                              run one process in the foreground; the systemd units soak-server,
#                              soak-collector and soak-refresh call these
#   stack.sh purge --yes       delete the database volume and every secret
#
# The server, the collector and the refresh loop each run in a systemd unit of their own, so a
# restart or a stop of the supervisor's unit never kills them. vm-setup.sh writes the units and a
# sudoers rule that lets the user soak start, stop and restart those three units and nothing else.
#
# State lives under $SOAK_HOME (default /opt/soak). No command prints a key, a token or a password.
set -euo pipefail

home=${SOAK_HOME:-/opt/soak}
bin=$home/bin
secrets=$home/secrets
logs=$home/logs
pg_name=soak-pg
pg_volume=soak-pgdata
pg_port=5433
tenant=soak
user=soak@example.com
# The image compose.yaml pins.
pg_image='postgres:17@sha256:31ba9c94a03ada9988f2bb493a22a75eef3a185884533e5cdbcd498ea835b08f'

log() { printf 'stack: %s\n' "$*"; }
listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
wait_for() {
  for _ in $(seq 1 90); do listening "$1" && return 0; sleep 1; done
  log "nothing listens on :$1; see $logs"
  exit 1
}

# Writes a random secret once, mode 600. $1 is the file name, $2 the number of random bytes,
# $3 an optional prefix.
new_secret() {
  if [ -s "$secrets/$1" ]; then return 0; fi
  (umask 077 && printf '%s%s' "${3:-}" "$(openssl rand -hex "$2")" >"$secrets/$1")
}

ensure_secrets() {
  mkdir -p "$secrets" "$logs"
  chmod 700 "$secrets"
  new_secret ingest.key 24 otl_
  new_secret read.key 24 otl_
  new_secret agent.token 24
  new_secret redact.secret 32
  new_secret pg.password 24
  if [ ! -s "$secrets/pg.env" ]; then
    (umask 077 && printf 'POSTGRES_USER=otherlode\nPOSTGRES_DB=otherlode\nPOSTGRES_PASSWORD=%s\n' \
      "$(cat "$secrets/pg.password")" >"$secrets/pg.env")
  fi
}

# Runs psql inside the container over its socket, which trusts local users. SQL comes on stdin.
psql_admin() { docker exec -i "$pg_name" psql -qtAX -v ON_ERROR_STOP=1 -U otherlode -d otherlode "$@"; }

start_postgres() {
  if ! docker inspect "$pg_name" >/dev/null 2>&1; then
    log "creating Postgres in container $pg_name, volume $pg_volume"
    docker run -d --name "$pg_name" --restart unless-stopped --env-file "$secrets/pg.env" \
      -p "127.0.0.1:$pg_port:5432" -v "$pg_volume:/var/lib/postgresql/data" "$pg_image" >/dev/null
  elif [ "$(docker inspect -f '{{.State.Running}}' "$pg_name")" != true ]; then
    docker start "$pg_name" >/dev/null
  fi
  # The image first runs a temporary server on its socket only. Wait for the real one on TCP.
  for _ in $(seq 1 90); do
    if docker exec "$pg_name" pg_isready -q -h 127.0.0.1 -U otherlode -d otherlode; then return 0; fi
    sleep 1
  done
  log "Postgres did not come up; see: docker logs $pg_name"
  exit 1
}

# The roles compose.yaml's init script creates, plus the refresh login. They share one password.
ensure_roles() {
  local pw
  pw=$(cat "$secrets/pg.password")
  psql_admin >/dev/null <<SQL
DO \$do\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otherlode_app') THEN
    CREATE ROLE otherlode_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otherlode_admin') THEN
    CREATE ROLE otherlode_admin NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otherlode_cli') THEN
    CREATE ROLE otherlode_cli LOGIN PASSWORD '$pw' IN ROLE otherlode_admin;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otherlode_server') THEN
    CREATE ROLE otherlode_server LOGIN PASSWORD '$pw' IN ROLE otherlode_app;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'otherlode_refresher') THEN
    CREATE ROLE otherlode_refresher LOGIN PASSWORD '$pw';
  END IF;
END
\$do\$;
SQL
}

# Sets the database login in the calling subshell's environment. $1 is the role.
db_env() {
  export OTHERLODE_SERVER_DB_HOST=127.0.0.1 OTHERLODE_SERVER_DB_PORT=$pg_port \
    OTHERLODE_SERVER_DB_NAME=otherlode OTHERLODE_SERVER_DB_SSLMODE=disable
  export OTHERLODE_SERVER_DB_USER=$1
  OTHERLODE_SERVER_DB_PASSWORD=$(cat "$secrets/pg.password")
  export OTHERLODE_SERVER_DB_PASSWORD
}

unit() { sudo -n /usr/bin/systemctl "$1" "soak-$2.service"; }

running() { systemctl is-active --quiet "soak-$1.service"; }

migrate_and_bootstrap() {
  log "migrating"
  (
    db_env otherlode
    export OTHERLODE_SERVER_MIGRATE_REFRESH_LOGIN=otherlode_refresher
    "$bin/otherlode-server" migrate
  ) >>"$logs/migrate.log" 2>&1
  log "bootstrapping tenant $tenant"
  (
    db_env otherlode_cli
    export OTHERLODE_SERVER_BOOTSTRAP_TENANT=$tenant OTHERLODE_SERVER_BOOTSTRAP_USER=$user
    OTHERLODE_SERVER_BOOTSTRAP_API_KEY=$(cat "$secrets/ingest.key")
    OTHERLODE_SERVER_BOOTSTRAP_READ_API_KEY=$(cat "$secrets/read.key")
    export OTHERLODE_SERVER_BOOTSTRAP_API_KEY OTHERLODE_SERVER_BOOTSTRAP_READ_API_KEY
    "$bin/otherlode-server" bootstrap
    # The free plan tracks fewer services than the soak reports; the cap is an override.
    "$bin/otherlode-server" set-tenant-service-cap "$tenant" 100
  ) >>"$logs/bootstrap.log" 2>&1
}

start_server() {
  if running server; then return 0; fi
  if listening 4320; then
    log "something else holds :4320"
    exit 1
  fi
  log "starting the server"
  unit start server
  wait_for 4320
}

run_server() {
  exec >>"$logs/server.log" 2>&1
  db_env otherlode_server
  export OTHERLODE_SERVER_ADDR=127.0.0.1:4320
  exec "$bin/otherlode-server"
}

start_refresh() {
  if running refresh; then return 0; fi
  log "starting the snapshot refresh loop"
  unit start refresh
}

# The refresh job runs every 5 minutes, as Cloud Scheduler runs it. A snapshot is replaced at
# most once in 15 minutes.
refresh_loop() {
  trap 'exit 0' TERM INT
  while :; do
    (
      db_env otherlode_refresher
      export OTHERLODE_SERVER_SNAPSHOT_REFRESH_INTERVAL=15m OTHERLODE_SERVER_SNAPSHOT_REFRESH_INTERVAL_FREE=15m
      "$bin/otherlode-server" refresh-snapshots
    ) || true
    sleep 300 &
    wait $! || true
  done
}

start_collector() {
  if running collector; then return 0; fi
  if listening 4319; then
    log "something else holds :4319"
    exit 1
  fi
  log "starting the collector"
  unit start collector
  wait_for 4319
}

run_collector() {
  exec >>"$logs/collector.log" 2>&1
  export OTHERLODE_COLLECTOR_ADDR=127.0.0.1:4319 \
    OTHERLODE_COLLECTOR_AUTH_TOKEN_FILE="$secrets/agent.token" \
    OTHERLODE_COLLECTOR_FORWARD_URL=http://127.0.0.1:4320 \
    OTHERLODE_COLLECTOR_FORWARD_AUTH_TOKEN_FILE="$secrets/ingest.key" \
    OTHERLODE_COLLECTOR_REDACT_ALL_LITERALS=true \
    OTHERLODE_COLLECTOR_REDACT_SECRET_FILE="$secrets/redact.secret" \
    OTHERLODE_COLLECTOR_RATE_LIMIT_RPS=50 OTHERLODE_COLLECTOR_RATE_LIMIT_BURST=200
  if [ "$(cat "$home/run/collector-limit" 2>/dev/null || true)" = low ] && [ -x "$bin/otherlode-collector-lowmanifest" ]; then
    exec "$bin/otherlode-collector-lowmanifest"
  fi
  exec "$bin/otherlode-collector"
}

# Restarts the collector in a limit mode. The collector has no variable for its element limits, so the
# low mode runs a build of it with a lower manifest limit.
set_collector_limit() {
  case "$1" in
    low) [ -x "$bin/otherlode-collector-lowmanifest" ] || { log "no $bin/otherlode-collector-lowmanifest"; exit 1; } ;;
    normal) ;;
    *) echo "usage: $0 collector-limit low|normal" >&2; exit 2 ;;
  esac
  mkdir -p "$home/run"
  printf '%s' "$1" >"$home/run/collector-limit"
  log "restarting the collector with the $1 manifest limit"
  unit stop collector
  start_collector
}

# Writes a session for the bootstrap user. The database keeps only the token's SHA-256, as the
# server does. The plaintext goes to a file only this user can read.
mint_session() {
  local token hash n
  token=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')
  hash=$(printf %s "$token" | sha256sum | cut -d' ' -f1)
  n=$(psql_admin <<SQL
WITH s AS (
  INSERT INTO sessions (tenant_id, user_id, token_hash, via_sso)
  SELECT tenant_id, id, decode('$hash', 'hex'), false FROM users WHERE email = '$user'
  RETURNING 1)
SELECT count(*) FROM s;
SQL
)
  if [ "$n" != 1 ]; then
    log "no user $user in the database; run: stack.sh up"
    exit 1
  fi
  (umask 077 && printf %s "$token" >"$secrets/session-token")
  log "session token for $user: $secrets/session-token (cookie otherlode_session)"
}

status() {
  local bad=0
  show() {
    if [ "$2" = up ]; then
      printf '%-12s up\n' "$1"
    else
      printf '%-12s DOWN\n' "$1"
      bad=1
    fi
  }
  if docker exec "$pg_name" pg_isready -q -h 127.0.0.1 -U otherlode -d otherlode 2>/dev/null; then show postgres up; else show postgres down; fi
  if running server && listening 4320; then show server up; else show server down; fi
  if running refresh; then show refresh up; else show refresh down; fi
  if running collector && listening 4319; then show collector up; else show collector down; fi
  return $bad
}

purge() {
  if [ "${1:-}" != --yes ]; then
    echo "usage: $0 purge --yes" >&2
    exit 2
  fi
  "$0" down
  docker rm -f "$pg_name" >/dev/null 2>&1 || true
  docker volume rm "$pg_volume" >/dev/null 2>&1 || true
  find "$secrets" -mindepth 1 -delete 2>/dev/null || true
  rm -f "$home/run/collector-limit"
}

case "${1:-}" in
  up)
    ensure_secrets
    start_postgres
    ensure_roles
    migrate_and_bootstrap
    start_server
    start_refresh
    start_collector
    log "server :4320, collector :4319, Postgres :$pg_port (all on 127.0.0.1); logs in $logs"
    ;;
  down)
    unit stop refresh
    unit stop collector
    unit stop server
    docker stop "$pg_name" >/dev/null 2>&1 || true
    ;;
  status) status ;;
  stop-collector) unit stop collector ;;
  start-collector)
    ensure_secrets
    start_collector
    ;;
  collector-limit)
    ensure_secrets
    set_collector_limit "${2:-}"
    ;;
  restart-server)
    unit stop server
    start_server
    ;;
  run-server) run_server ;;
  run-collector)
    ensure_secrets
    run_collector
    ;;
  refresh-loop)
    exec >>"$logs/refresh.log" 2>&1
    refresh_loop
    ;;
  session) mint_session ;;
  purge) purge "${2:-}" ;;
  *)
    echo "usage: $0 up|down|status|stop-collector|start-collector|collector-limit low|normal|restart-server|session|purge --yes" >&2
    exit 2
    ;;
esac
