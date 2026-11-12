#!/usr/bin/env python3
"""The soak supervisor.

It starts and supervises every instance of DESIGN.md, drives the traffic, applies the schedule
(disruptions and one-off requests), samples memory and runs the checker. It keeps T0 and what
it has done in a state file, so a restart of the supervisor, or of the VM, resumes the run.

    soak.py [--scale N] [--flush SECONDS]      run (systemd runs this)
    soak.py --scale 3600 --dry-run            print the timetable and start nothing

--scale N divides every time of DESIGN.md by N: 1 for the real run, 60 for a smoke run.
Standard library only.
"""

import argparse
import concurrent.futures
import datetime
import http.client
import json
import math
import os
import random
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import soakcfg as cfg  # noqa: E402

H = 3600.0


def iso(t=None):
    t = time.time() if t is None else t
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def timetable(scale, flush, check_delay):
    """Return the lines the dry run prints."""
    out = []
    out.append("scale %g: every time below is the design time divided by %g" % (scale, scale))
    out.append("agent flush interval %ds, sampler every %.1fs" % (flush, max(600 / scale, 1)))
    out.append("")
    out.append("%-12s %-14s %s" % ("design", "from T0", "action"))
    out.append("%-12s %-14s %s" % ("T+00:00:00", "0.00s", "start the stack and every instance but shop-test"))
    rows = []
    for it in cfg.build_schedule(scale):
        delay = check_delay if it.kind == "check" else 0.0
        rows.append((it.at / scale + delay, it.at, it.text + (" (+%.1fs)" % delay if delay else "")))
    for scaled, at, text in sorted(rows, key=lambda r: r[0]):
        out.append("%-12s %-14s %s" % ("T+" + cfg.fmt_hms(at), "%.2fs" % scaled, text))
    out.append("")
    out.append("traffic (period between requests; a continuous rate does not scale):")
    out.append("%-16s %-24s %-12s %-10s %s" % ("instance", "route", "period", "rate/s", "note"))
    for inst in cfg.INSTANCES:
        for r in cfg.ROUTES.get(inst.name, []):
            p = r.effective_period(scale)
            note = "only on %s" % r.only_version if r.only_version else ""
            if not r.scaled:
                note = (note + " continuous").strip()
            out.append("%-16s %-24s %-12s %-10.3f %s" % (inst.name, r.key, "%.3fs" % p, 1 / p, note))
    for o in cfg.ONE_OFFS:
        out.append("%-16s %-24s once at T+%s (%.2fs from T0)" % (o.instance, "%s %s" % (o.verb, o.template),
                                                              cfg.fmt_hms(o.at), o.at / scale))
    return out


class State:
    """The persistent state: T0, what is done, the runs, the events. Saved after every change."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.RLock()
        self.d = {
            "version": 1, "t0": None, "scale": None, "flush": None, "settle": None,
            "done": {}, "gen": {}, "variants": {}, "runs": [], "events": [],
            "testruns": [], "checks": [], "finished": False,
        }
        if os.path.exists(path):
            with open(path) as f:
                self.d.update(json.load(f))

    def save(self):
        with self.lock:
            tmp = self.path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.d, f, indent=1)
            os.replace(tmp, self.path)

    def update(self, fn):
        with self.lock:
            r = fn(self.d)
            self.save()
            return r


class Counters:
    """Per instance and route, the 2xx and non-2xx responses and the failed requests."""

    def __init__(self, path, scale, flush):
        self.path = path
        self.lock = threading.Lock()
        self.d = {"scale": scale, "flush": flush, "instances": {}}
        if os.path.exists(path):
            try:
                with open(path) as f:
                    old = json.load(f)
                self.d["instances"] = old.get("instances", {})
            except (OSError, ValueError):
                pass

    def declare(self, inst, key, rate):
        with self.lock:
            e = self.d["instances"].setdefault(inst, {}).setdefault(key, {"ok": 0, "non2xx": 0, "errors": 0})
            e["rate_per_s"] = rate

    def add(self, inst, key, kind):
        with self.lock:
            e = self.d["instances"].setdefault(inst, {}).setdefault(key, {"ok": 0, "non2xx": 0, "errors": 0})
            e[kind] += 1

    def save(self):
        with self.lock:
            self.d["updated"] = iso()
            tmp = self.path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(self.d, f)
            os.replace(tmp, self.path)


def http_request(port, verb, path, query="", body=None, conn=None, timeout=10):
    """One request. Returns (status or None, connection). A failure closes the connection."""
    target = path + ("?" + query if query else "")
    headers = {"Connection": "keep-alive"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    try:
        if conn is None:
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
        conn.request(verb, target, body=body, headers=headers)
        resp = conn.getresponse()
        resp.read()
        return resp.status, conn
    except (OSError, http.client.HTTPException):
        try:
            if conn is not None:
                conn.close()
        except OSError:
            pass
        return None, None


def first_send(t0, now, period):
    """When a route sent every `period` seconds next sends, for a generator started at `now`. The
    first request goes out at T0 plus 2 minutes, or at once if the generator starts later in the
    first period. After that, requests keep to the grid of that first time."""
    first = t0 + min(120.0, period)
    if now < first + period:
        return max(first, now)
    return first + math.ceil((now - first) / period) * period


class RouteGen(threading.Thread):
    def __init__(self, sup, inst, route):
        super().__init__(daemon=True, name="gen-%s-%s" % (inst.name, route.key))
        self.sup, self.inst, self.route = sup, inst, route

    def run(self):
        sup, inst, r = self.sup, self.inst, self.route
        period = r.effective_period(sup.scale)
        sup.counters.declare(inst.name, r.key, 1.0 / period)
        now = time.time()
        if period >= 30:
            nxt = first_send(sup.t0, now, period)
        else:
            nxt = now + random.random() * min(period, 1.0)
        conn = None
        while not sup.stop.is_set() and not sup.state.d["finished"]:
            wait = nxt - time.time()
            if wait > 0:
                sup.stop.wait(min(wait, 1.0))
                continue
            nxt += period
            if nxt < time.time() - 5 * period:
                nxt = time.time() + period
            if r.only_version and sup.mgrs[inst.name].running_version() != r.only_version:
                continue
            if not sup.mgrs[inst.name].accepting:
                if conn is not None:
                    conn.close()
                    conn = None
                continue
            path = r.path.replace("{id}", str(random.randint(1, 50)))
            status, conn = http_request(inst.port, r.verb, path, r.query, r.body, conn)
            if period >= 30 and conn is not None:
                conn.close()
                conn = None
            if status is None:
                sup.counters.add(inst.name, r.key, "errors")
            elif 200 <= status < 300:
                sup.counters.add(inst.name, r.key, "ok")
            else:
                sup.counters.add(inst.name, r.key, "non2xx")


class WindowTracker:
    """Which window reads are due. After an event, a live read is due every `fast_period` seconds for
    `fast_span` seconds, and a snapshot read every `slow_period` seconds for `slow_span`. The times are
    real seconds at every scale. A read that runs late makes the ticks it covered skipped."""

    def __init__(self, fast_period=cfg.WINDOW_FAST_PERIOD, fast_span=cfg.WINDOW_FAST_SPAN,
                 slow_period=cfg.WINDOW_SLOW_PERIOD, slow_span=cfg.WINDOW_SLOW_SPAN):
        self.period = {"fast": fast_period, "slow": slow_period}
        self.span = {"fast": fast_span, "slow": slow_span}
        self.windows = []
        self.last = {"fast": None, "slow": None}
        self.skipped = {"fast": 0, "slow": 0}
        self.lock = threading.Lock()

    def open(self, label, t):
        with self.lock:
            self.windows = [w for w in self.windows if t - w[1] < self.span["slow"]]
            self.windows.append((label, t))

    def covering(self, kind, now):
        return [w for w in self.windows if 0 <= now - w[1] < self.span[kind]]

    def due(self, now):
        with self.lock:
            out = []
            for kind in ("fast", "slow"):
                last = self.last[kind]
                if self.covering(kind, now) and (last is None or now - last >= self.period[kind]):
                    out.append(kind)
            return out

    def label(self, kind, now):
        with self.lock:
            cover = self.covering(kind, now)
            return cover[-1][0] if cover else None

    def started(self, kind, now):
        with self.lock:
            last, p = self.last[kind], self.period[kind]
            if last is not None and now - last > 1.5 * p and any(
                    0 <= last - w[1] < self.span[kind] and 0 <= now - w[1] < self.span[kind] for w in self.windows):
                self.skipped[kind] += int(round((now - last) / p)) - 1
            self.last[kind] = now


class WindowReader(threading.Thread):
    """One thread that reads every finding in the windows after an event, judges each read, and
    keeps the violations, the counts and the reads of shop-1 while the collector refuses manifests in
    `state/windows.json`, which check 8 reads. A read that is still running when the next is due
    makes that tick skipped."""

    def __init__(self, sup):
        super().__init__(daemon=True, name="windows")
        self.sup = sup
        self.tracker = WindowTracker()
        self.path = sup.paths["windows"]
        self.data = {"opened": 0, "events": [], "reads": {"fast": 0, "slow": 0, "report": 0, "errors": 0},
                     "skipped": {"fast": 0, "slow": 0}, "violations": {},
                     "refused": {"reads": 0, "behind": 0, "first_behind": None, "last": []}, "last_error": None}
        try:
            with open(self.path) as f:
                self.data.update(json.load(f))
        except (OSError, ValueError):
            pass
        self.api = None
        self.truth = None
        self.last_refusal_read = 0.0

    def connect(self):
        import check
        if self.api is None:
            with open(self.sup.paths["read_key"]) as f:
                self.api = check.Api(cfg.API_BASE, f.read().strip())
            with open(self.sup.paths["truth"]) as f:
                self.truth = json.load(f)

    def open(self, label, t=None):
        t = time.time() if t is None else t
        self.tracker.open(label, t)
        self.data["opened"] += 1
        self.data["events"] = (self.data["events"] + [{"label": label, "t": t}])[-50:]

    def save(self):
        self.data["skipped"] = dict(self.tracker.skipped)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.data, f)
        os.replace(tmp, self.path)

    def run(self):
        sup = self.sup
        while not sup.stop.is_set() and not sup.state.d["finished"]:
            now = time.time()
            for kind in self.tracker.due(now):
                try:
                    self.read(kind, now)
                except Exception as e:
                    self.data["reads"]["errors"] += 1
                    self.data["last_error"] = "%s read: %r" % (kind, e)
                    sup.log("window read failed: %r" % e)
            if now - self.last_refusal_read >= 5.0:
                self.last_refusal_read = now
                try:
                    if self.observe_refusal(now):
                        self.save()
                except Exception as e:
                    sup.log("refusal read failed: %r" % e)
            sup.stop.wait(1.0)

    def read(self, kind, now):
        import check
        sup = self.sup
        self.connect()
        label = self.tracker.label(kind, now)
        self.tracker.started(kind, now)
        with sup.state.lock:
            state = {"testruns": list(sup.state.d["testruns"])}
        violations, errors, reports = check.read_window(self.api, self.truth, state, now, sup.t0, sup.scale, sup.flush,
                                                        sup.lag, kind, wait=5.0 if kind == "slow" else 30.0)
        r = self.data["reads"]
        r[kind] += 1
        r["report"] += reports
        if errors:
            r["errors"] += 1
            self.data["last_error"] = errors[0]
        for line in violations:
            key = "%s|%s|%s" % (label, kind, check.violation_key(line))
            v = self.data["violations"].get(key)
            if v:
                v["last"], v["count"] = now, v["count"] + 1
            elif len(self.data["violations"]) < check.WINDOW_CAP:
                self.data["violations"][key] = {"event": label, "kind": kind, "first": now, "last": now, "count": 1,
                                                "message": line}
        self.save()

    def observe_refusal(self, now):
        """While the collector holds the low manifest limit, reads shop-1's row on /instances after
        its restart inside the window has had two flushes to send its manifest. Runs every 5 seconds
        on its own, not only inside a read window. Returns whether it read."""
        import check
        sup = self.sup
        with sup.state.lock:
            events = [dict(e) for e in sup.state.d["events"] if e["kind"] == "collector-limit" and e["t1"] is None]
            runs = [dict(r) for r in sup.state.d["runs"] if r["instance"] == check.REFUSED_INSTANCE]
        if not events or not runs:
            return False
        run = runs[-1]
        if run["started"] < events[-1]["t0"] or run.get("ended") or now < (run.get("ready") or now + 1) + 2 * sup.flush:
            return False
        self.connect()
        row = check.instance_row(self.api, *check.REFUSED_SERVICE, run["instance_id"])
        behind = check.run_is_behind(row)
        ref = self.data["refused"]
        ref["reads"] += 1
        if behind:
            ref["behind"] += 1
            ref["first_behind"] = ref["first_behind"] or now
        obs = {"t": now, "instance_id": run["instance_id"], "listed": row is not None, "behind": behind,
               "deltas_received": (row or {}).get("deltas_received"),
               "counts_behind_since": (row or {}).get("counts_behind_since")}
        ref["last"] = (ref["last"] + [obs])[-20:]
        return True


def read_vmrss_kb(pid):
    try:
        with open("/proc/%d/status" % pid) as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1])
    except (OSError, ValueError):
        pass
    return None


def pid_alive(pid):
    try:
        with open("/proc/%d/stat" % pid) as f:
            return f.read().rsplit(")", 1)[1].split()[0] != "Z"
    except (OSError, IndexError):
        return False


def tcp_open(port, timeout=1.0):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


class LogFile:
    """An append-only log whose every line gets an ISO timestamp."""

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()

    def write(self, line):
        with self.lock:
            with open(self.path, "a") as f:
                f.write("%s %s\n" % (iso(), line.rstrip("\n")))


class Managed:
    """One instance: its process, its generation and the run record in the state."""

    def __init__(self, sup, inst):
        self.sup, self.inst = sup, inst
        self.popen = None
        self.run = None
        self.accepting = False
        self.hold = False
        self.expect = None
        self.baseline = inst.static_baseline
        self.log = LogFile(os.path.join(sup.paths["logs"], inst.name + ".log"))
        self.lock = threading.Lock()

    def variant(self):
        return self.sup.state.d["variants"].get(self.inst.name)

    def version(self):
        return cfg.version_of(self.inst, self.variant())

    def running_version(self):
        return cfg.running_version(self.inst, self.variant())

    def agent_opts(self, instance_id, version, extra=""):
        i = self.inst
        o = [
            "serviceName=%s" % i.service, "environment=%s" % i.env, "serviceVersion=%s" % version,
            "serviceInstanceId=%s" % instance_id, "includePackages=%s" % i.include,
            "staticBaselineEnabled=%s" % ("true" if self.baseline else "false"), "classListingEnabled=true",
            "flushIntervalSeconds=%d" % (cfg.fixed_flush(i, self.sup.scale) or self.sup.flush), "exportUrl=%s" % cfg.COLLECTOR_URL,
        ]
        if i.exclude:
            o.append("excludePackages=%s" % i.exclude)
        return ",".join(o)

    def command(self, instance_id):
        i = self.inst
        jar = os.path.join(self.sup.paths["apps"], cfg.jar_for(i, self.variant()))
        cmd = [self.sup.java, "-Xms128m", "-Xmx512m"]
        if i.agent:
            cmd.append("-javaagent:%s=%s" % (self.sup.paths["agent"], self.agent_opts(instance_id, self.version())))
        cmd += ["-Dserver.port=%d" % i.port, "-Dport=%d" % i.port, "-jar", jar]
        return cmd

    def start(self):
        sup, i = self.sup, self.inst
        with self.lock:
            if self.popen is not None and self.popen.poll() is None:
                return
            gen = sup.state.update(lambda d: d["gen"].__setitem__(i.name, d["gen"].get(i.name, 0) + 1) or d["gen"][i.name])
            instance_id = "%s-g%03d" % (i.name, gen)
            env = dict(os.environ)
            env.update(PORT=str(i.port), SERVER_PORT=str(i.port))
            if i.agent:
                env["OTHERLODE_AUTH_TOKEN"] = sup.agent_token()
            cmd = self.command(instance_id)
            self.log.write("start %s: %s" % (instance_id, " ".join(cmd)))
            self.popen = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                                          start_new_session=True, cwd=sup.paths["home"], text=True, bufsize=1)
            self.expect = None
            sup.open_window("start of %s" % instance_id)
            run = {"instance": i.name, "instance_id": instance_id, "gen": gen, "pid": self.popen.pid,
                   "version": self.version(), "service": i.service, "env": i.env,
                   "started": time.time(), "ended": None, "how": None, "exit": None, "ready": None}
            self.run = run
            sup.state.update(lambda d: d["runs"].append(run))
            threading.Thread(target=self._pump, args=(self.popen,), daemon=True).start()
        deadline = time.time() + 240
        while time.time() < deadline and not sup.stop.is_set():
            if self.popen.poll() is not None:
                break
            if tcp_open(i.port):
                self.accepting = True
                sup.state.update(lambda d: run.__setitem__("ready", time.time()))
                sup.log("%s ready (pid %d, %s)" % (i.name, self.popen.pid, instance_id))
                return
            time.sleep(1)
        sup.log("%s did not become ready" % i.name)

    def _pump(self, p):
        for line in p.stdout:
            self.log.write(line)

    def _finish(self, how):
        run, p = self.run, self.popen
        if run is None or run["ended"] is not None:
            return
        self.sup.state.update(lambda d: run.update(ended=time.time(), how=how, exit=p.returncode))
        self.accepting = False

    def stop_graceful(self, timeout=90):
        p = self.popen
        self.accepting = False
        if p is None or p.poll() is not None:
            self._finish(self.expect or "gone")
            return "gone"
        self.expect = "graceful"
        p.send_signal(signal.SIGTERM)
        try:
            code = p.wait(timeout)
            how = "graceful" if code in (0, 143, -15) else "crash"
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
            how = "graceful-timeout"
        self._finish(how)
        return how

    def kill9(self):
        p = self.popen
        self.accepting = False
        if p is None or p.poll() is not None:
            return
        self.expect = "kill9"
        p.kill()
        p.wait()
        self._finish("kill9")

    def check_exit(self):
        """Called every tick. Notes a process that ended and nobody asked it to."""
        p = self.popen
        if p is None or self.run is None or self.run["ended"] is not None:
            return False
        if p.poll() is None:
            return False
        if self.expect:
            return False
        self._finish("crash")
        self.sup.event("crash", self.inst.name, time.time(), time.time())
        self.sup.log("%s exited with %s and nobody stopped it" % (self.inst.name, p.returncode))
        return True

    def pid(self):
        if self.popen is not None and self.popen.poll() is None:
            return self.popen.pid
        return None


class Supervisor:
    def __init__(self, args):
        self.args = args
        self.paths = cfg.paths(args.home)
        for k in ("logs", "state_dir", "reports", "run"):
            os.makedirs(self.paths[k], exist_ok=True)
        self.scale = float(args.scale)
        self.flush = int(args.flush)
        self.settle = float(args.settle)
        self.lag = float(args.lag)
        self.check_delay = args.check_delay if args.check_delay is not None else max(300.0 / self.scale, 1.0)
        self.sample_interval = args.sample_interval if args.sample_interval else max(600.0 / self.scale, 5.0)
        self.java = args.java or os.environ.get("SOAK_JAVA") or shutil.which("java") or "java"
        self.jcmd = shutil.which("jcmd")
        self.stop = threading.Event()
        self.state = State(self.paths["state"])
        d = self.state.d
        if d["t0"] is None:
            d["t0"] = time.time()
            d["scale"], d["flush"], d["settle"] = self.scale, self.flush, self.settle
            self.state.save()
        elif d["scale"] != self.scale:
            raise SystemExit("the state file holds a run with --scale %g; this start says --scale %g. "
                             "Use the same scale, or reset the run." % (d["scale"], self.scale))
        else:
            d["flush"], d["settle"] = self.flush, self.settle
            self.state.save()
        self.t0 = d["t0"]
        self.slog = LogFile(os.path.join(self.paths["logs"], "soak.log"))
        self.counters = Counters(self.paths["traffic"], self.scale, self.flush)
        self.mgrs = {i.name: Managed(self, i) for i in cfg.INSTANCES}
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=8)
        self.inflight = set()
        self.last_try = {}
        self.sched = cfg.build_schedule(self.scale)
        self.sample_lock = threading.Lock()
        self.sampling = False
        self.windows = WindowReader(self)

    def log(self, msg):
        print("%s %s" % (iso(), msg), flush=True)
        self.slog.write(msg)

    def open_window(self, label):
        self.windows.open(label)

    def agent_token(self):
        with open(self.paths["agent_token"]) as f:
            return f.read().strip()

    def event(self, kind, instance, t0, t1=None, **extra):
        e = dict(kind=kind, instance=instance, t0=t0, t1=t1, **extra)
        self.state.update(lambda d: d["events"].append(e))
        return e

    def stack(self, *args):
        if self.args.no_stack:
            self.log("stack.sh %s skipped (--no-stack)" % " ".join(args))
            return 0
        try:
            r = subprocess.run([self.paths["stack"], *args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, timeout=300)
        except (subprocess.TimeoutExpired, OSError) as e:
            self.slog.write("stack.sh %s -> %r" % (" ".join(args), e))
            return "timeout" if isinstance(e, subprocess.TimeoutExpired) else "error: %s" % e
        self.slog.write("stack.sh %s -> %d: %s" % (" ".join(args), r.returncode, r.stdout.strip()[-400:]))
        return r.returncode

    def due_time(self, it):
        extra = self.check_delay if it.kind == "check" else 0.0
        return self.t0 + it.at / self.scale + extra

    def adopt(self):
        """A run record with no end belongs to a process of an earlier supervisor."""
        def fix(d):
            for run in d["runs"]:
                if run["ended"] is None:
                    pid = run["pid"]
                    if pid_alive(pid) and "java" in _cmdline(pid):
                        try:
                            os.kill(pid, signal.SIGTERM)
                        except OSError:
                            pass
                        for _ in range(60):
                            if not pid_alive(pid):
                                break
                            time.sleep(1)
                        if pid_alive(pid):
                            os.kill(pid, signal.SIGKILL)
                        run.update(ended=time.time(), how="orphan-stopped")
                    else:
                        run.update(ended=time.time(), how="lost")
        self.state.update(fix)

    def start_all(self):
        self.event("supervisor-start", None, time.time(), time.time())
        threads = []
        for m in self.mgrs.values():
            if not m.inst.always_on:
                continue
            t = threading.Thread(target=m.start)
            t.start()
            threads.append(t)
            time.sleep(3)
        for t in threads:
            t.join()
        for i in cfg.INSTANCES:
            for r in cfg.ROUTES.get(i.name, []):
                RouteGen(self, i, r).start()

    def run_item(self, it):
        """Do one schedule item. Return True when done, False to try again."""
        k, a = it.kind, it.args
        m = self.mgrs.get(a.get("instance", ""))
        if k == "graceful-restart":
            t0 = time.time()
            m.hold = True
            how = m.stop_graceful()
            m.hold = False
            m.start()
            self.event("graceful-restart", m.inst.name, t0, time.time(), how=how)
        elif k == "kill9":
            t0 = time.time()
            m.hold = True
            m.kill9()
            self.event("kill9", m.inst.name, t0, None)
        elif k == "start":
            m.hold = False
            m.start()
            def close(d):
                for e in reversed(d["events"]):
                    if e["kind"] == "kill9" and e["instance"] == m.inst.name and e["t1"] is None:
                        e["t1"] = time.time()
                        break
            self.state.update(close)
        elif k == "collector-stop":
            code = self.stack("stop-collector")
            self.event("collector-outage", None, time.time(), None, exit=code)
        elif k == "collector-start":
            code = self.stack("start-collector")
            self.open_window("collector start")
            def close(d):
                for e in reversed(d["events"]):
                    if e["kind"] == "collector-outage" and e["t1"] is None:
                        e["t1"] = time.time()
                        e["start_exit"] = code
                        break
            self.state.update(close)
        elif k == "server-restart":
            t0 = time.time()
            code = self.stack("restart-server")
            self.event("server-restart", None, t0, time.time(), exit=code)
            self.open_window("server restart")
        elif k == "crash-cycle":
            t0 = time.time()
            m.hold = True
            m.baseline = a["baseline"]
            m.start()
            if m.accepting:
                time.sleep(cfg.CRASH_LIVE_REAL)
            m.kill9()
            m.baseline = m.inst.static_baseline
            self.event("crash-cycle", m.inst.name, t0, time.time(), baseline=a["baseline"])
        elif k == "collector-limit":
            t0 = time.time()
            code = self.stack("collector-limit", a["mode"])
            self.open_window("collector restart with the %s manifest limit" % a["mode"])
            if a["mode"] == "low":
                self.event("collector-limit", None, t0, None, exit=code)
            else:
                def close(d):
                    for e in reversed(d["events"]):
                        if e["kind"] == "collector-limit" and e["t1"] is None:
                            e["t1"] = time.time()
                            e["start_exit"] = code
                            break
                self.state.update(close)
        elif k == "upgrade":
            t0 = time.time()
            m.hold = True
            how = m.stop_graceful()
            self.state.update(lambda d: d["variants"].__setitem__(m.inst.name, a["to"]))
            m.hold = False
            m.start()
            self.event("upgrade", m.inst.name, t0, time.time(), how=how, to=a["to"])
        elif k == "oneoff":
            if not m.accepting:
                return False
            key = "%s %s" % (a["verb"], a["path"])
            self.counters.declare(m.inst.name, key, 0.0)
            status, conn = http_request(m.inst.port, a["verb"], a["path"])
            if conn is not None:
                conn.close()
            if status is None:
                self.counters.add(m.inst.name, key, "errors")
                return False
            self.counters.add(m.inst.name, key, "ok" if 200 <= status < 300 else "non2xx")
            self.log("%s %s on %s -> %d" % (a["verb"], a["path"], m.inst.name, status))
        elif k == "testrun":
            self.testrun(a["k"])
        elif k == "check":
            self.run_checker(a["final"])
        return True

    def testrun(self, k):
        i = cfg.BY_NAME["shop-1"]
        before = None
        before_t = time.time()
        for attempt in range(3):
            try:
                import check
                before = check.fingerprint(check.Api(cfg.API_BASE, open(self.paths["read_key"]).read().strip()),
                                           self.paths["truth"], wait=300)
                break
            except Exception as e:  # the fingerprint is evidence, not a reason to skip the run
                self.log("fingerprint before test run %d failed (attempt %d): %s" % (k, attempt + 1, e))
                time.sleep(30)
        iid = cfg.TEST_INSTANCE_ID
        opts = ",".join([
            "serviceName=%s" % i.service, "serviceVersion=%s" % i.version, "serviceInstanceId=%s" % iid,
            "includePackages=%s" % i.include, "staticBaselineEnabled=true", "classListingEnabled=true",
            "flushIntervalSeconds=%d" % self.flush, "exportUrl=%s" % cfg.COLLECTOR_URL, "testRun=true",
        ])
        jar = os.path.join(self.paths["apps"], cfg.jar_for(i))
        cmd = [self.java, "-Xmx256m", "-javaagent:%s=%s" % (self.paths["agent"], opts),
               "-Dloader.main=%s" % cfg.TESTDRIVER_MAIN, "-cp", jar, cfg.TESTDRIVER_LAUNCHER]
        env = dict(os.environ, OTHERLODE_AUTH_TOKEN=self.agent_token())
        rec = {"k": k, "before_t": before_t, "started": time.time(), "ended": None, "exit": None, "before": before}
        self.state.update(lambda d: d["testruns"].append(rec))
        log = LogFile(os.path.join(self.paths["logs"], "shop-test.log"))
        log.write("start test run %d" % k)
        try:
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, text=True,
                                 cwd=self.paths["home"], start_new_session=True)
            t = threading.Thread(target=lambda: [log.write(l) for l in p.stdout], daemon=True)
            t.start()
            try:
                code = p.wait(300)
            except subprocess.TimeoutExpired:
                p.kill()
                code = "timeout"
            t.join(5)
        except OSError as e:
            code = "error: %s" % e
        self.state.update(lambda d: rec.update(ended=time.time(), exit=code))
        self.event("test-run", "shop-test", rec["started"], rec["ended"], exit=code)
        self.log("test run %d exited with %s" % (k, code))

    def run_checker(self, final):
        cmd = [sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "check.py"), "--now",
               "--home", self.paths["home"], "--lag", str(self.lag)]
        if final:
            cmd.append("--final")
        log = LogFile(os.path.join(self.paths["logs"], "check.log"))
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=7200)
        for line in r.stdout.splitlines():
            log.write(line)
        report = next((l.split(": ", 1)[1] for l in r.stdout.splitlines() if l.startswith("report: ")), None)
        self.state.update(lambda d: d["checks"].append({"t": time.time(), "final": final, "exit": r.returncode,
                                                          "report": report}))
        self.log("checker %sexited %d%s" % ("(final) " if final else "", r.returncode,
                                           ", report " + report if report else ""))

    def sample(self):
        rows = []
        ts = iso()
        for name, m in self.mgrs.items():
            pid = m.pid()
            if pid is None:
                continue
            rss = read_vmrss_kb(pid)
            heap = self.heap_used_kb(pid)
            rows.append("%s,%s,%d,%d,%s,%s" % (ts, name, pid, m.run["gen"] if m.run else 0,
                                               rss if rss is not None else "", heap if heap is not None else ""))
        if rows:
            new = not os.path.exists(self.paths["memory"])
            with open(self.paths["memory"], "a") as f:
                if new:
                    f.write("ts,instance,pid,generation,rss_kb,heap_used_kb\n")
                f.write("\n".join(rows) + "\n")

    def heap_used_kb(self, pid):
        if not self.jcmd:
            return None
        try:
            r = subprocess.run([self.jcmd, str(pid), "GC.heap_info"], stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            return None
        return parse_heap_info(r.stdout)

    def sample_async(self):
        with self.sample_lock:
            if self.sampling:
                return
            self.sampling = True

        def go():
            try:
                self.sample()
            except Exception as e:
                self.log("sampler error: %s" % e)
            finally:
                self.sampling = False
        threading.Thread(target=go, daemon=True).start()

    LATE = {"graceful-restart": 6 * H, "check": 3 * H, "testrun": 12 * H, "collector-stop": 0.5 * H,
            "collector-limit": 0.5 * H, "crash-cycle": 6 * H}

    def tick(self):
        now = time.time()
        d = self.state.d
        for m in self.mgrs.values():
            if m.check_exit() and not m.hold and m.inst.always_on:
                self.pool.submit(self._restart_after_crash, m)
        if d["finished"]:
            return
        for it in self.sched:
            if it.id in d["done"] or it.id in self.inflight:
                continue
            due = self.due_time(it)
            if now < due:
                continue
            if (it.args.get("mode") == "normal" or it.id == "restart-shop-1-refused") \
                    and "collector-low-limit" not in d["done"]:
                continue
            if it.args.get("mode") == "low":
                normal = next((x for x in self.sched if x.args.get("mode") == "normal"), None)
                if normal is not None and now >= self.due_time(normal):
                    self.state.update(lambda dd: dd["done"].__setitem__(it.id, {"t": now, "note": "skipped, late"}))
                    self.log("%s skipped: its window had passed" % it.id)
                    continue
            late = self.LATE.get(it.kind)
            if it.args.get("mode") == "normal":
                late = None
            final = it.args.get("final")
            if late and not final and now - due > late / self.scale:
                self.state.update(lambda dd: dd["done"].__setitem__(it.id, {"t": now, "note": "skipped, late"}))
                self.log("%s skipped: %.0fs late" % (it.id, now - due))
                continue
            if now - self.last_try.get(it.id, 0) < 5:
                continue
            self.last_try[it.id] = now
            self.inflight.add(it.id)
            self.pool.submit(self._do, it)
        for tr in d["testruns"]:
            key = "after-%d" % tr["k"]
            if tr["ended"] and "after" not in tr and key not in self.inflight and now >= tr["ended"] + self.settle:
                self.inflight.add(key)
                self.pool.submit(self._after, tr, key)

    def _restart_after_crash(self, m):
        time.sleep(5)
        if not self.stop.is_set() and not m.hold:
            m.start()

    def _do(self, it):
        try:
            ok = self.run_item(it)
            if ok:
                self.state.update(lambda d: d["done"].__setitem__(it.id, {"t": time.time(), "note": ""}))
                self.log("done: %s" % it.text)
                if it.id == "check-final":
                    self.state.update(lambda d: d.__setitem__("finished", True))
                    self.log("the run is finished; traffic stops, the instances stay up")
        except Exception as e:
            self.log("%s failed: %r" % (it.id, e))
        finally:
            self.inflight.discard(it.id)

    def _after(self, tr, key):
        try:
            import check
            fp = check.fingerprint(check.Api(cfg.API_BASE, open(self.paths["read_key"]).read().strip()),
                                   self.paths["truth"], wait=300)
            self.state.update(lambda d: tr.update(after=fp, after_t=time.time()))
        except Exception as e:
            self.log("fingerprint after test run %d failed: %s" % (tr["k"], e))
            self.state.update(lambda d: tr.update(after=None, after_error=str(e), after_t=time.time()))
        finally:
            self.inflight.discard(key)

    def main(self):
        signal.signal(signal.SIGTERM, lambda *_: self.stop.set())
        signal.signal(signal.SIGINT, lambda *_: self.stop.set())
        self.log("supervisor start: T0 %s, scale %g, flush %ds" % (iso(self.t0), self.scale, self.flush))
        if not self.args.no_stack and not (tcp_open(4320) and tcp_open(4319)):
            code = self.stack("up")
            if code != 0:
                self.log("stack.sh up failed (%s); the supervisor exits" % code)
                sys.exit(1)
        self.adopt()
        self.windows.start()
        self.start_all()
        last_save = last_sample = 0.0
        while not self.stop.is_set():
            try:
                self.tick()
                now = time.time()
                if now - last_save >= 5:
                    self.counters.save()
                    last_save = now
                if not self.state.d["finished"] and now - last_sample >= self.sample_interval:
                    self.sample_async()
                    last_sample = now
            except Exception as e:
                self.log("tick error: %r" % e)
            self.stop.wait(1.0)
        self.shutdown()

    def shutdown(self):
        self.log("supervisor stopping")
        self.counters.save()
        threads = []
        for m in self.mgrs.values():
            m.hold = True

            def go(m=m):
                m.accepting = False
                if m.pid() is not None:
                    m.expect = "supervisor-stop"
                    m.popen.send_signal(signal.SIGTERM)
                    try:
                        m.popen.wait(90)
                    except subprocess.TimeoutExpired:
                        m.popen.kill()
                        m.popen.wait()
                    m._finish("supervisor-stop")
            t = threading.Thread(target=go)
            t.start()
            threads.append(t)
        for t in threads:
            t.join()
        self.event("supervisor-stop", None, time.time(), time.time())
        self.counters.save()
        self.pool.shutdown(wait=False, cancel_futures=True)


def _cmdline(pid):
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return f.read().replace(b"\0", b" ").decode("utf-8", "replace")
    except OSError:
        return ""


def parse_heap_info(text):
    """Heap used, in kB, from `jcmd GC.heap_info`. G1 prints one summed figure on its first line."""
    if "garbage-first" in text:
        m = re.search(r"used\s+(\d+)([KMG])", text)
        return _kb(m) if m else None
    tot, found = 0, False
    for m in re.finditer(r"total\s+\d+[KMG],\s*used\s+(\d+)([KMG])", text):
        tot += _kb(m)
        found = True
    if found:
        return tot
    m = re.search(r"[Uu]sed:?\s+(\d+)([KMG])", text)
    return _kb(m) if m else None


def _kb(m):
    n, u = int(m.group(1)), m.group(2)
    return n * {"K": 1, "M": 1024, "G": 1024 * 1024}[u]


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scale", type=float, default=1.0, help="divide every time by N (1 real, 60 smoke)")
    p.add_argument("--flush", type=int, default=None, help="agent flush interval in seconds (60 real, 10 smoke)")
    p.add_argument("--home", default=cfg.HOME)
    p.add_argument("--dry-run", action="store_true", help="print the timetable and start nothing")
    p.add_argument("--no-stack", action="store_true", help="do not call stack.sh")
    p.add_argument("--settle", type=float, default=1500.0, help="seconds after a test run before its findings are read")
    p.add_argument("--lag", type=float, default=1800.0, help="seconds the checker waits after a planted time")
    p.add_argument("--check-delay", type=float, default=None, help="seconds after a nominal check time (scaled)")
    p.add_argument("--sample-interval", type=float, default=None)
    p.add_argument("--java", default=None)
    a = p.parse_args(argv)
    if a.scale <= 0:
        p.error("--scale must be positive")
    if a.flush is None:
        a.flush = 60 if a.scale <= 1 else 10
    return a


def main(argv=None):
    a = parse_args(argv)
    if a.dry_run:
        delay = a.check_delay if a.check_delay is not None else max(300.0 / a.scale, 1.0)
        print("\n".join(timetable(a.scale, a.flush, delay)))
        return 0
    Supervisor(a).main()
    return 0


if __name__ == "__main__":
    sys.exit(main())
