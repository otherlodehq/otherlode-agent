#!/usr/bin/env bash
# Builds everything the VM needs, on the Mac, into one directory:
#
#   bin/otherlode-server        linux/amd64, with the web UI embedded
#   bin/otherlode-collector     linux/amd64
#   agent/otherlode-agent.jar   the agent's shadow jar
#   apps/soak-shop.jar, apps/soak-events-1.0.0.jar, apps/soak-events-2.0.0.jar
#   truth.json, harness/, VERSION
#
#   build-bundle.sh [output-dir]
#
# The server and the collector are read from sibling checkouts of this repository. Set
# OTHERLODE_SERVER_DIR and OTHERLODE_COLLECTOR_DIR when they are somewhere else. Set
# SOAK_SHOP_JAR, SOAK_EVENTS_1_JAR and SOAK_EVENTS_2_JAR when the apps build names its jars
# differently from what this script looks for.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
agent_dir=$(cd "$here/../.." && pwd)
server_dir=${OTHERLODE_SERVER_DIR:-$agent_dir/../otherlode-server}
collector_dir=${OTHERLODE_COLLECTOR_DIR:-$agent_dir/../otherlode-collector}
out=${1:-$agent_dir/build/soak-bundle}

log() { printf 'build-bundle: %s\n' "$*"; }
die() { printf 'build-bundle: %s\n' "$*" >&2; exit 1; }

[ -d "$server_dir/web" ] || die "no server checkout at $server_dir (set OTHERLODE_SERVER_DIR)"
[ -d "$collector_dir/cmd" ] || die "no collector checkout at $collector_dir (set OTHERLODE_COLLECTOR_DIR)"
[ -f "$agent_dir/soak/truth.json" ] || die "soak/truth.json is missing; the apps' author writes it"

if [ -d "$HOME/.nvm/versions/node/v26.12.0/bin" ]; then
  export PATH="$HOME/.nvm/versions/node/v26.12.0/bin:$PATH"
fi
command -v npm >/dev/null || die "npm is not on the PATH (Node 26 via nvm)"
command -v go >/dev/null || die "go is not on the PATH"

log "running the harness tests"
(cd "$here" && python3 -m unittest -q)

log "building the web UI"
(
  cd "$server_dir/web"
  if [ ! -d node_modules ]; then npm ci --no-audit --no-fund; fi
  npm run build
)

stage=$(mktemp -d "${TMPDIR:-/tmp}/soak-bundle.XXXXXX")
mkdir -p "$stage/bin" "$stage/agent" "$stage/apps" "$stage/harness"

log "building the server for linux/amd64"
(cd "$server_dir" && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags='-s -w' -o "$stage/bin/otherlode-server" ./cmd/otherlode-server)

log "building the collector for linux/amd64"
(cd "$collector_dir" && GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags='-s -w' -o "$stage/bin/otherlode-collector" ./cmd/otherlode-collector)

log "building the agent"
(cd "$agent_dir" && ./gradlew --console=plain shadowJar)
agent_jar=$agent_dir/build/libs/otherlode-agent-0.1.0-SNAPSHOT.jar
[ -f "$agent_jar" ] || die "the agent build left no $agent_jar"
cp "$agent_jar" "$stage/agent/otherlode-agent.jar"

log "building the apps"
(cd "$agent_dir" && ./gradlew --console=plain -p soak/apps build)

# The newest jar of a directory whose name matches, never a -plain or -sources jar.
find_jar() {
  local dir=$1 pattern=$2
  find "$dir" -maxdepth 2 -name "$pattern" -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' \
    -print 2>/dev/null | head -1
}
shop=${SOAK_SHOP_JAR:-$(find_jar "$agent_dir/soak/apps/shop/build" 'soak-shop*')}
ev1=${SOAK_EVENTS_1_JAR:-$(find_jar "$agent_dir/soak/apps/events/build" '*1.0.0*')}
ev2=${SOAK_EVENTS_2_JAR:-$(find_jar "$agent_dir/soak/apps/events/build" '*2.0.0*')}
for pair in "shop:$shop" "events 1.0.0:$ev1" "events 2.0.0:$ev2"; do
  [ -n "${pair#*:}" ] && [ -f "${pair#*:}" ] || {
    find "$agent_dir/soak/apps" -name '*.jar' -path '*build*' >&2 || true
    die "no jar for ${pair%%:*}; the jars above are what the apps build left (set SOAK_*_JAR)"
  }
done
cp "$shop" "$stage/apps/soak-shop.jar"
cp "$ev1" "$stage/apps/soak-events-1.0.0.jar"
cp "$ev2" "$stage/apps/soak-events-2.0.0.jar"

cp "$agent_dir/soak/truth.json" "$stage/truth.json"
for f in "$here"/*.py "$here"/*.sh "$here"/log-allowlist.txt "$here"/README.md; do
  cp "$f" "$stage/harness/"
done
chmod +x "$stage"/harness/*.sh "$stage"/harness/*.py "$stage"/bin/*

{
  printf 'built %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'agent %s\n' "$(git -C "$agent_dir" rev-parse --short HEAD)"
  printf 'server %s\n' "$(git -C "$server_dir" rev-parse --short HEAD)"
  printf 'collector %s\n' "$(git -C "$collector_dir" rev-parse --short HEAD)"
} >"$stage/VERSION"

mkdir -p "$out"
find "$out" -mindepth 1 -delete
cp -R "$stage"/. "$out"/
find "$stage" -mindepth 1 -delete
rmdir "$stage"

log "bundle in $out"
du -sh "$out" | sed 's/^/build-bundle: size /'
