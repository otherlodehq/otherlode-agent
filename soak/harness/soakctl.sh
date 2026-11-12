#!/usr/bin/env bash
# Run it over ssh; it uses sudo.
set -euo pipefail

home=${SOAK_HOME:-/opt/soak}
as_soak() { sudo -u soak env SOAK_HOME="$home" "$@"; }

state_json() {
  as_soak python3 - "$home/state/state.json" <<'PY'
import json, sys, time
try:
    d = json.load(open(sys.argv[1]))
except OSError:
    print("no run has started")
    raise SystemExit
el = time.time() - d["t0"]
print("T0 %s, scale %g, flush %ss" % (time.strftime("%Y-%m-%d %H:%M:%SZ", time.gmtime(d["t0"])), d["scale"], d["flush"]))
print("elapsed %.2f of 168 design hours%s" % (el * d["scale"] / 3600, ", FINISHED" if d.get("finished") else ""))
print("steps done: %d; runs recorded: %d; test runs: %d; checks: %d" % (
    len(d["done"]), len(d["runs"]), len(d["testruns"]), len(d["checks"])))
for c in d["checks"][-1:]:
    print("last check: exit %s, report %s" % (c["exit"], c["report"]))
PY
}

case "${1:-}" in
  start)
    shift
    scale=1
    flush=
    while [ $# -gt 0 ]; do
      case "$1" in
        --scale) scale=$2; shift 2 ;;
        --flush) flush=$2; shift 2 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
      esac
    done
    if [ -z "$flush" ]; then
      if [ "$scale" = 1 ]; then flush=60; else flush=10; fi
    fi
    if sudo test -s "$home/state/state.json" && sudo test -s "$home/state/run.env"; then
      echo "soakctl: a run exists; resuming it with the arguments it started with" >&2
      echo "soakctl: to start another, run: soakctl reset --yes" >&2
    else
      printf 'SOAK_ARGS="--scale %s --flush %s"\n' "$scale" "$flush" | as_soak tee "$home/state/run.env" >/dev/null
    fi
    sudo systemctl start soak-stack.service soak.service
    echo "soakctl: started; follow it with: journalctl -u soak -f"
    ;;
  stop) sudo systemctl stop soak.service ;;
  down) sudo systemctl stop soak.service soak-stack.service ;;
  status)
    printf 'soak.service: %s\n' "$(systemctl is-active soak.service || true)"
    as_soak "$home/harness/stack.sh" status || true
    state_json
    ;;
  check)
    shift
    as_soak python3 "$home/harness/check.py" --now "$@"
    ;;
  session) as_soak "$home/harness/stack.sh" session ;;
  reset)
    if [ "${2:-}" != --yes ]; then
      echo "usage: soakctl reset --yes" >&2
      exit 2
    fi
    sudo systemctl stop soak.service soak-stack.service || true
    as_soak "$home/harness/stack.sh" purge --yes
    as_soak find "$home/state" "$home/logs" "$home/reports" -mindepth 1 -delete
    echo "soakctl: the run is deleted"
    ;;
  *)
    echo "usage: soakctl start [--scale N] [--flush S] | stop | down | status | check | session | reset --yes" >&2
    exit 2
    ;;
esac
