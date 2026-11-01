# Soak harness

Runs the soak test of `soak/DESIGN.md` on one Debian 12 VM and checks Otherlode's read API against
`soak/truth.json`. Python 3 standard library and Bash only.

| File | Where it runs | What it does |
|---|---|---|
| `build-bundle.sh` | Mac | Builds the server (UI embedded), the collector, the agent jar and the apps into a bundle directory |
| `push.sh` | Mac | Copies the bundle to the VM and installs it under `/opt/soak` |
| `vm-setup.sh` | VM, as root | Installs Docker, a JDK and python3, creates the user `soak`, writes the systemd units and the sudoers rule |
| `stack.sh` | VM | `up`, `down`, `status`, plus `stop-collector`, `start-collector`, `restart-server`, `session`, `purge` |
| `soak.py` | VM, by systemd | The supervisor: instances, traffic, schedule, sampler, checker |
| `check.py` | VM | The seven checks; writes `/opt/soak/reports/<timestamp>.md` and `.json` |
| `soakctl.sh` | VM | `start`, `stop`, `down`, `status`, `check`, `session`, `reset` |
| `soakcfg.py` | both | The instance table, the traffic rates and the timetable the supervisor and the checker share |
| `log-allowlist.txt` | VM | The WARNING lines check 6 accepts, each tied to a disruption |

## Build and push

Needs the server and collector checkouts beside this repository (`../otherlode-server`,
`../otherlode-collector`; set `OTHERLODE_SERVER_DIR` and `OTHERLODE_COLLECTOR_DIR` otherwise), Node
26 (nvm), Go, and `soak/truth.json`.

```bash
soak/harness/build-bundle.sh            # writes build/soak-bundle; runs the harness tests first
soak/harness/push.sh --setup            # first time, and after a change to vm-setup.sh: also runs it on the VM
soak/harness/push.sh                    # later pushes replace bin, agent, apps, harness, truth.json
```

If the apps build names its jars differently, set `SOAK_SHOP_JAR`, `SOAK_EVENTS_1_JAR` and
`SOAK_EVENTS_2_JAR`. `push.sh` uses `SOAK_VM`, `SOAK_PROJECT` and `SOAK_ZONE` (default `soak-1`, the
project and zone of the design). Secrets, state, logs and reports on the VM are never replaced.

Over ssh (`gcloud compute ssh soak-1 --project=project-1d40b09b-5fc7-4240-bed --zone=europe-west2-a`),
`vm-setup.sh` can be run again at any time. It installs Temurin 21, or Debian's OpenJDK 17 when Temurin
is unreachable; both are the JDK, since the sampler needs `jcmd`.

## Smoke run

A smoke run divides every time by 60: the 168 hours take 2 hours 48 minutes, a 12 hour restart
comes every 12 minutes, and the agents flush every 10 seconds.

```bash
soakctl start --scale 60 --flush 10
soakctl status
journalctl -u soak -f
```

`soakctl start` writes `/opt/soak/state/run.env` and starts `soak-stack.service` (Postgres, server,
refresh loop, collector) and `soak.service` (the supervisor). T0 is when the supervisor first starts.
The checker runs 5 minutes (scaled) after every 6 design hours. A smoke run cannot assert a stale hit
at `days=1`, since a day must pass; that entry is "not due". The server's snapshots refresh on real
time (every 15 minutes at most), so a smoke run waits `--lag` (30 minutes) after a planted time before
it asserts it.

## The real run

```bash
soakctl reset --yes        # only if a smoke run is on the VM
soakctl start              # --scale 1 --flush 60
```

The supervisor survives `systemctl restart soak` and a VM reboot: it reads `state.json`, takes up the
schedule where it is, and starts the instances again as new generations (`shop-1-g007`). A restart
of the supervisor ends the instances with SIGTERM; their runs read as "supervisor-stop" and check 4
does not assert them. Steps that fell due while the supervisor was down run late, except shop-1's
restarts (6 hours late), checks (3 hours), test runs (12 hours) and the collector stop (30 minutes), which are
skipped.

## Read a report

```bash
soakctl status
ls /opt/soak/reports                     # <timestamp>.md and .json; final.md and final.json at the end
less /opt/soak/reports/$(ls /opt/soak/reports | grep '\.md$' | tail -1)
soakctl check                            # run all seven checks now
soakctl check --only 3,6                 # or some of them
```

Each check is PASS, FAIL or SKIP (not yet due). The final check turns a skip into a fail. A failure
lists its evidence: the rows that differ, the counts compared with the tolerance, the memory slopes,
the log lines. A failed check is triaged: a wrong `truth.json` entry is fixed there, a product defect is
a bug. Other files:

- `/opt/soak/logs/<instance>.log`: each instance's output, every line stamped by the supervisor;
  `server.log`, `collector.log`, `refresh.log`, `shop-test.log`, `soak.log`, `check.log`.
- `/opt/soak/state/traffic.json`: per instance and route, `ok` (2xx), `non2xx`, `errors`, `rate_per_s`.
- `/opt/soak/state/memory.csv`: RSS and heap used of every Java process, every 10 minutes (scaled).
- `/opt/soak/state/state.json`: T0, the runs, the events, the test runs, what is done.

How the checks read the design:

1. Entries are asserted from `from` plus the lag (30 minutes) until `until`. A stale hit is asserted
   only when the day has really passed. `never_in_findings` is checked in check 2.
2. Every finding row of every service and environment must be named in `truth.json`. The 30 day view
   is used. Spring's `* /*`, `* /webjars/*` and `* /error` outside routes are skipped.
3. `calls_total` must lie between the 2xx recorded before the read, less the tolerance, and the
   2xx, non-2xx and failed requests recorded after, plus the tolerance. The tolerance is one flush
   interval of requests per route, one more for each `kill -9`, and a few for the requests in flight at a stop.
   A planned collector stop, collector start or server restart whose `stack.sh` command failed or timed
   out fails, and so does a collector stop skipped because it fell due more than 30 minutes late.
4. Each stopped process is looked up by its instance id (`shop-1-g003`). SIGTERM must read as
   `ended_cleanly`, `kill -9` must not, a crash fails.
5. Per process, after its first 30 minutes: the slope of RSS (MB per hour) must not pass the slope of
   its control (floor 0) plus 10% of the control's mean RSS per day. Age counts from the process start
   in `state.json`. Shop instances are compared with `shop-control`, `events-prod` with
   `events-control` and `events-staging` with `events-staging-control`. The control process started
   closest to the agent process is the one used, at the ages both cover; ages it does not cover are
   listed as not compared when they hold two or more samples.
6. An ERROR line from an agent, the collector or the server fails. A WARNING needs an allowlist entry
   whose disruption window holds its time. App output other than the agent's is counted, not judged.
7. Before each test run and 25 minutes after it, the identity of every finding is read live and
   compared, with each cluster's root kind. A root `truth.json` marks `called_only_by_tests` may turn
   from `uncalled` to `called_only_by_tests`. The clusters marked `called_only_by_tests` in `truth.json` must read so, with the test
   driver as a caller.

## Open the UI

```bash
soakctl session                                    # writes /opt/soak/secrets/session-token
gcloud compute ssh soak-1 --project=project-1d40b09b-5fc7-4240-bed --zone=europe-west2-a -- -L 4320:localhost:4320
```

Open <http://localhost:4320>. Set the cookie `otherlode_session` to the file's content (read it
with `sudo cat /opt/soak/secrets/session-token` on the VM; the cookie is for `localhost`, path `/`).
The session is for `soak@example.com`, the admin of tenant `soak`.

## Stop and clean up

```bash
soakctl stop        # the supervisor and the instances stop; the stack and the state stay
soakctl down        # also the stack, the database volume stays
soakctl reset --yes # deletes state, logs, reports, the database and the secrets
```

## Test the harness on the Mac

```bash
cd soak/harness
python3 -m unittest                              # the checker against a fake API, the timetable
python3 soak.py --scale 3600 --dry-run           # prints the timetable and starts nothing
```
