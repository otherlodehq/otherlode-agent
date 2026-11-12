#!/usr/bin/env python3
"""The soak checker: the eight checks of DESIGN.md against Otherlode's read API.

    check.py --now                run all eight checks at once and write the report
    check.py --now --only 3,6     run only checks 3 and 6
    check.py --now --final        the final check: a check that cannot run yet fails

It reads truth.json, the supervisor's state, the traffic file, the memory CSV and the logs,
queries the read API with the read key, and writes /opt/soak/reports/<timestamp>.md and .json.
The exit code is 0 when no check fails. Standard library only.
"""

import argparse
import csv
import datetime
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import soakcfg as cfg  # noqa: E402

NAMES = {
    1: "Planted answers",
    2: "No false claim",
    3: "Counts",
    4: "Runs",
    5: "Memory",
    6: "Logs",
    7: "Test run",
    8: "Windows",
}
MAX_EVIDENCE = 40


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__("HTTP %s: %s" % (status, message))
        self.status = status


class Computing(Exception):
    pass


class Api:
    """The read API with a read key as a bearer token."""

    def __init__(self, base, key):
        self.base = base.rstrip("/")
        self.key = key

    def get(self, path, params=None):
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + self.key})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                raw = r.read()
                return r.status, (json.loads(raw) if raw else None), r.headers
        except urllib.error.HTTPError as e:
            raw = e.read()
            try:
                body = json.loads(raw)
            except ValueError:
                body = raw.decode("utf-8", "replace")
            return e.code, body, e.headers
        except (urllib.error.URLError, OSError) as e:
            raise ApiError(0, "cannot reach %s: %s" % (url, e))


class Reader:
    """Reads the pages of a per-service route, waits out a 202, and remembers what it read."""

    def __init__(self, api, wait=900.0, extra=None):
        self.api = api
        self.wait = wait
        self.extra = dict(extra or {})
        self.memo = {}

    def rows(self, svc, env, route, listkey, paged=True, fresh=False, **params):
        params = dict(self.extra, **params)
        key = (svc, env, route, tuple(sorted(params.items())))
        if not fresh and key in self.memo:
            return self.memo[key]
        path = "/api/v1/services/%s/%s" % (urllib.parse.quote(svc, safe=""), route)
        base = dict(params)
        if env is not None:
            base["environment"] = env
        out = []
        for _attempt in range(4):
            out, as_ofs, snap, restart = [], set(), False, False
            offset = 0
            while True:
                q = dict(base)
                if paged:
                    q.update(limit=5000, offset=offset)
                status, body, hdr = self._get_waiting(path, q)
                if status != 200:
                    msg = body.get("error") if isinstance(body, dict) else body
                    raise ApiError(status, "%s: %s" % (path, msg))
                items = body.get(listkey, [])
                out.extend(items)
                if body.get("as_of"):
                    as_ofs.add(body["as_of"])
                snap = snap or bool(body.get("from_snapshot"))
                if len(as_ofs) > 1 and snap:
                    restart = True
                    break
                total = body.get("total", len(out))
                offset += len(items)
                if not paged or not items or offset >= total:
                    break
            if not restart:
                break
        self.memo[key] = out
        return out

    def _get_waiting(self, path, q):
        deadline = time.time() + self.wait
        while True:
            status, body, hdr = self.api.get(path, q)
            if status != 202:
                return status, body, hdr
            if time.time() >= deadline:
                raise Computing("%s still answers 202 computing after %ds" % (path, self.wait))
            time.sleep(min(60.0, max(1.0, deadline - time.time())))


class Ctx:
    def __init__(self, **kw):
        self.clock = time.time
        self.__dict__.update(kw)

    def window(self, entry):
        """Absolute (start, end) in epoch seconds of a truth entry. end is inf with no until."""
        f = cfg.parse_duration(entry.get("from")) or 0.0
        u = cfg.parse_duration(entry.get("until"))
        return self.t0 + f / self.scale, (self.t0 + u / self.scale if u is not None else math.inf)

    def asserted(self, entry, extra_start=0.0):
        """True when the entry must hold now: its time has come and the snapshots have caught up."""
        s, e = self.window(entry)
        return self.now >= s + self.lag + extra_start and self.now < e

    def may_explain(self, entry):
        """True when the entry may explain a finding now, with the lag allowed on both sides."""
        s, e = self.window(entry)
        return s - self.lag <= self.now < e + self.lag

    def truth_lists(self, svc, env):
        return self.truth.get("services", {}).get(svc, {}).get(env, {})

    def pairs(self):
        for svc, envs in self.truth.get("services", {}).items():
            for env in envs:
                yield svc, env


def result(n, status, summary, evidence=None, **extra):
    r = {"id": n, "name": NAMES[n], "status": status, "summary": summary, "evidence": list(evidence or [])}
    r.update(extra)
    return r


def fmt_method(row, prefix=""):
    return "%s%s.%s%s" % (prefix, row.get("class_name", ""), row.get("method_name", ""),
                          row.get("method_descriptor") or "")


def norm_status(s):
    return str(s).replace("_", "-").lower()


def cond_text(row):
    return "".join(p.get("text", "") for p in row.get("condition") or [])


def site_text(row):
    """A branch site's condition, then the case labels of its outcomes in the finding. An enum or
    string `when` keeps its subject in the condition and each case name in its outcome's label."""
    labels = ["".join(p.get("text", "") for p in o.get("case_label") or [])
              for o in row.get("outcomes") or [] if o.get("in_finding")]
    return " ".join([cond_text(row)] + [l for l in labels if l])


class Finding:
    def __init__(self, kind, key, cls=None, method=None, param=None, verb=None, template=None,
                 artifacts=(), cond=None, status=None):
        self.kind, self.key, self.cls, self.method, self.param = kind, key, cls, method, param
        self.verb, self.template, self.artifacts, self.cond, self.status = verb, template, tuple(artifacts), cond, status


def _artifacts(row):
    out = []
    for ident in row.get("identities") or []:
        out.append(ident.get("artifact_id"))
        out.append("%s:%s" % (ident.get("group_id"), ident.get("artifact_id")))
    out.append(row.get("identity_key", ""))
    return [a for a in out if a]


def collect_findings(rd, svc, env, extra=None, stale_days=30):
    """Every finding row of one service and environment, as Finding objects."""
    extra = extra or {}
    out = []
    for r in rd.rows(svc, env, "never-hit", "rows", **extra):
        if r.get("kind") == "branch":
            out.append(Finding("never-hit branch", "never-hit branch %s:%s [%s]" % (fmt_method(r), r.get("line"), cond_text(r)),
                               r.get("class_name"), r.get("method_name"), cond=site_text(r)))
        else:
            out.append(Finding("never-hit method", "never-hit method " + fmt_method(r), r.get("class_name"), r.get("method_name")))
    for r in rd.rows(svc, env, "stale-hit", "rows", days=stale_days, **extra):
        kind = "stale-hit branch" if r.get("kind") == "branch" else "stale-hit method"
        out.append(Finding(kind, "%s %s" % (kind, fmt_method(r)), r.get("class_name"), r.get("method_name"), cond=site_text(r)))
    for route in ("never-loaded", "never-initialized", "never-instantiated", "failed-to-initialize"):
        for r in rd.rows(svc, env, route, "classes", **extra):
            out.append(Finding(route, "%s %s" % (route, r.get("class_name")), r.get("class_name")))
    for r in rd.rows(svc, env, "unreached-clusters", "clusters", **extra):
        root = r.get("root") or {}
        out.append(Finding("cluster", "cluster %s.%s" % (root.get("class_name"), root.get("method_name")),
                           root.get("class_name"), root.get("method_name") or None, status=root.get("root_kind")))
    for r in rd.rows(svc, env, "endpoints", "endpoints", outside="exclude", days=stale_days, **extra):
        st = norm_status(r.get("status"))
        if r.get("handler_included") is False or (r.get("verb"), r.get("route_template")) in SPRING_OUTSIDE:
            continue
        if st in ("never-called", "stale"):
            out.append(Finding("endpoint " + st, "endpoint %s %s %s" % (st, r.get("verb"), r.get("route_template")),
                               r.get("handler_class"), r.get("handler_method"), verb=r.get("verb"),
                               template=r.get("route_template"), status=st))
    for st in ("never-supplied", "always-supplied"):
        for r in rd.rows(svc, env, "optional-parameters", "optional_parameters", status=st, **extra):
            out.append(Finding(st, "%s %s.%s(%s)" % (st, r.get("class_name"), r.get("method_name"), r.get("parameter_name")),
                               r.get("class_name"), r.get("method_name"), param=r.get("parameter_name")))
    for r in rd.rows(svc, env, "dependencies", "dependencies", status="unused", **extra):
        out.append(Finding("dependency", "dependency %s is %s" % (r.get("identity_key"), norm_status(r.get("status"))),
                           artifacts=_artifacts(r), status=norm_status(r.get("status"))))
    return out


def collect_removed(rd, svc, env):
    out = []
    for r in rd.rows(svc, env, "removed", "rows"):
        k = r.get("kind")
        if k == "class":
            out.append(Finding("removed class", "removed class %s" % r.get("class_name"), r.get("class_name")))
        elif k == "method":
            out.append(Finding("removed method", "removed method " + fmt_method(r), r.get("class_name"), r.get("method_name")))
        elif k == "endpoint":
            out.append(Finding("removed endpoint", "removed endpoint %s %s" % (r.get("verb"), r.get("route_template")),
                               verb=r.get("verb"), template=r.get("route_template")))
        elif k == "dependency":
            out.append(Finding("removed dependency", "removed dependency %s" % r.get("identity_key"), artifacts=_artifacts(r)))
    return out


def fingerprint(api, truth_path, wait=60.0):
    """The identity of every finding of every service and environment in truth.json, read live.

    A live read (active_within_days) never comes from a snapshot. Counts and dates are left out,
    so the fingerprint changes only when a finding appears or goes away."""
    with open(truth_path) as f:
        truth = json.load(f)
    rd = Reader(api, wait)
    out = {}
    for svc, envs in truth.get("services", {}).items():
        for env, lists in envs.items():
            tested = test_roots(lists)
            fs = collect_findings(rd, svc, env, {"active_within_days": 36500})
            out["%s/%s" % (svc, env)] = sorted({fingerprint_key(f, tested) for f in fs if f.kind != "endpoint stale"})
    return out


def test_roots(lists):
    """The (class, method) of each cluster root that truth.json expects a test run to mark
    called_only_by_tests in one service and environment."""
    roots = {(e.get("class"), e.get("method")) for e in lists.get("unreached_cluster_roots", [])
             if isinstance(e, dict) and e.get("called_only_by_tests")}
    if not roots:
        roots = {(e.get("class"), e.get("method")) for e in lists.get("never_hit_methods", [])
                 if isinstance(e, dict) and e.get("method") == cfg.TEST_TARGET_METHOD}
    return roots


def fingerprint_key(f, tested):
    """A finding's key, with a cluster's root kind. A test run is planned to turn the roots in tested
    from uncalled to called_only_by_tests, so for them the two kinds read the same."""
    if f.kind != "cluster":
        return f.key
    kind = f.status
    if kind == "called_only_by_tests" and (f.cls, f.method) in tested:
        kind = "uncalled"
    return "%s [%s]" % (f.key, kind)


def fingerprint_diff(a, b):
    lines = []
    for scope in sorted(set(a) | set(b)):
        sa, sb = set(a.get(scope, [])), set(b.get(scope, []))
        for k in sorted(sb - sa):
            lines.append("%s: appeared: %s" % (scope, k))
        for k in sorted(sa - sb):
            lines.append("%s: went away: %s" % (scope, k))
    return lines


def _split_member(text):
    """A cluster member in truth.json: 'pkg.Class', 'pkg.Class#method' or 'pkg.Class.method'."""
    if "#" in text:
        c, m = text.split("#", 1)
        return [(c, m)], [c]
    c, _, m = text.rpartition(".")
    return ([(c, m)] if c else []), [text]


CLASS_KEYS = ("never_loaded", "never_initialized", "never_instantiated", "failed_to_initialize")
SPRING_OUTSIDE = {("*", "/*"), ("*", "/webjars/*"), ("*", "/error")}


def cls_entries(tl, key):
    """The entries of a class list as (entry dict, class). An entry is a class name or {class, from, until}."""
    out = []
    for x in tl.get(key, []):
        if isinstance(x, str):
            out.append(({}, x))
        else:
            out.append((x, x["class"]))
    return out


def named(tl):
    """What a service environment's truth lists name: methods, classes, endpoints, artifacts."""
    methods, classes, endpoints, artifacts = set(), set(), set(), set()
    for e in tl.get("never_hit_methods", []) + tl.get("never_hit_outcomes", []) + tl.get("stale_hit", []):
        methods.add((e.get("class"), e.get("method")))
    for e in tl.get("never_supplied", []) + tl.get("always_supplied", []):
        methods.add((e.get("class"), e.get("method")))
    for e in tl.get("unreached_cluster_roots", []):
        methods.add((e.get("class"), e.get("method")))
        for m in e.get("members_include", []):
            ms, cs = _split_member(m)
            methods.update(ms)
            classes.update(cs)
    for key in CLASS_KEYS:
        classes.update(c for _, c in cls_entries(tl, key))
    for e in tl.get("never_called_endpoints", []) + tl.get("live_endpoints", []):
        endpoints.add((e.get("verb"), e.get("template")))
    for e in tl.get("dependencies", []):
        artifacts.add(e.get("artifact"))
    for e in tl.get("removed", []):
        if e.get("verb"):
            endpoints.add((e.get("verb"), e.get("template") or e.get("route_template") or e.get("path")))
        elif e.get("artifact"):
            artifacts.add(e["artifact"])
        elif e.get("method"):
            methods.add((e.get("class") or e.get("class_name"), e["method"]))
        else:
            classes.add(e.get("class") or e.get("class_name"))
    return methods, classes, endpoints, artifacts


def class_lists(tl):
    return {k: {c for _, c in cls_entries(tl, k)} for k in CLASS_KEYS}


def explained(f, tl, ctx):
    """True when truth.json names this finding row, in an entry whose time window holds now. A row
    is explained only by an entry of its own kind: a class in a class list never explains a method
    row, since the server keeps methods a class row covers out of the method findings."""
    def live(entries):
        return [e for e in entries if not isinstance(e, dict) or ctx.may_explain(e)]

    def method_entry(e):
        return e.get("class") == f.cls and (not e.get("method") or e.get("method") == f.method)

    k = f.kind
    if getattr(ctx, "live_read", False) and not k.startswith("removed"):
        gone = [e for e in tl.get("removed", []) if ctx.now >= ctx.window(e)[0]]
        if removed_by_truth(f, gone):
            start = min(ctx.window(e)[0] for e in gone)
            before = Ctx(**dict(ctx.__dict__, now=start - 1.0, live_read=False, lag=0.0))
            return explained(f, tl, before)
    if k in cl_names():
        return any(c == f.cls for e, c in cls_entries(tl, cl_names()[k]) if ctx.may_explain(e))
    if k == "never-hit method":
        if any(method_entry(e) and e.get("method") for e in live(tl.get("never_hit_methods", []))):
            return True
        for root in live(tl.get("unreached_cluster_roots", [])):
            ms, _ = _split_member_list(root.get("members_include", []))
            if (f.cls, f.method) in ms:
                return True
        return False
    if k == "never-hit branch":
        return any(method_entry(e) and e.get("condition_contains", "") in (f.cond or "")
                   for e in live(tl.get("never_hit_outcomes", [])))
    if k in ("stale-hit method", "stale-hit branch"):
        return any(method_entry(e) and e.get("method") for e in live(tl.get("stale_hit", [])))
    if k == "cluster":
        roots = live(tl.get("unreached_cluster_roots", []))
        if f.status == "class_finding":
            return any(e.get("class") == f.cls and not e.get("method") for e in roots)
        return any(method_entry(e) and e.get("method") for e in roots)
    if k.startswith("endpoint"):
        return f.status == "never-called" and any(
            e.get("verb") == f.verb and e.get("template") == f.template
            for e in live(tl.get("never_called_endpoints", [])))
    if k in ("never-supplied", "always-supplied"):
        key = "never_supplied" if k == "never-supplied" else "always_supplied"
        return any(e.get("class") == f.cls and e.get("method") == f.method and e.get("parameter") == f.param
                   for e in live(tl.get(key, [])))
    if k == "dependency":
        strict = getattr(ctx, "strict_status", False)
        return any(e.get("artifact") in f.artifacts and (not strict or norm_status(e.get("status")) == f.status)
                   for e in live(tl.get("dependencies", [])))
    if k.startswith("removed"):
        for e in live(tl.get("removed", [])):
            if k == "removed endpoint" and e.get("verb") == f.verb and \
                    (e.get("template") or e.get("route_template") or e.get("path")) == f.template:
                return True
            if k in ("removed class", "removed method") and (e.get("class") or e.get("class_name")) == f.cls:
                return True
            if k == "removed dependency" and e.get("artifact") in f.artifacts:
                return True
        return False
    return False


def removed_by_truth(f, entries):
    """True when a finding is about code a removed entry names. A read with active_within_days
    applies no removal, so that code is still in the findings it read, and truth as it stood just
    before the removal judges the row."""
    for e in entries:
        cls = e.get("class") or e.get("class_name")
        if cls and f.cls == cls:
            return True
        if e.get("verb") and f.verb == e.get("verb") and \
                (e.get("template") or e.get("route_template") or e.get("path")) == f.template:
            return True
        if e.get("artifact") and e.get("artifact") in f.artifacts:
            return True
    return False


# Kinds a window read judges before T0 plus the lag: a class that loaded, and a jar's references,
# are known from the start.
EARLY_WINDOW_KINDS = ("never-loaded", "dependency")


def _split_member_list(members):
    ms, cs = set(), set()
    for m in members:
        a, b = _split_member(m)
        ms.update(a)
        cs.update(b)
    return ms, cs


def cl_names():
    return {"never-loaded": "never_loaded", "never-initialized": "never_initialized",
            "never-instantiated": "never_instantiated", "failed-to-initialize": "failed_to_initialize"}


def _cap(lines):
    if len(lines) > MAX_EVIDENCE:
        return lines[:MAX_EVIDENCE] + ["... and %d more" % (len(lines) - MAX_EVIDENCE)]
    return lines


def cluster_test_problem(cluster, tests):
    """Why a cluster does not read as called only by tests that name the driver, or None."""
    root = cluster.get("root") or {}
    callers = [(tc.get("caller") or {}).get("class_name", "") for tc in cluster.get("test_callers") or []]
    if root.get("root_kind") != "called_only_by_tests":
        return "reads %s, not called_only_by_tests" % root.get("root_kind")
    wanted = tests or [cfg.TEST_CALLER]
    if not any(w in c or c in w for w in wanted for c in callers if c):
        return "reads called_only_by_tests but no test caller is one of %s (callers: %s)" % (
            ", ".join(wanted), ", ".join(callers) or "none")
    return None


def crash_hold(ctx, svc, env):
    """True while a crash-cycle run of this scope can still hold claims back: from its start until
    three of its flush intervals after its kill, plus the lag a snapshot may trail by. A crash run
    is killed while it is still starting, so it holds claims whether or not it delivered (server
    ADR 0087, agent ADR 0086)."""
    for e in ctx.state.get("events", []):
        if e.get("kind") != "crash-cycle":
            continue
        inst = cfg.BY_NAME.get(e.get("instance"))
        if not inst or (inst.service, inst.env) != (svc, env):
            continue
        end = (e.get("t1") or ctx.now) + 3 * (cfg.fixed_flush(inst, ctx.scale) or ctx.flush) + ctx.lag
        if e["t0"] <= ctx.now <= end:
            return True
    return False


# Flush intervals after which a run stops starting at the latest. The cap is checked at a flush,
# which can come up to one interval later.
STARTING_CAP_INTERVALS = 10

# Seconds a JVM may take from its start to the agent's.
JVM_BOOT_ALLOWANCE = 60.0


def start_hold(ctx, svc, env, at):
    """True when a run of this scope could have been starting at `at`: from its start until the
    starting cap, or until three flush intervals after it stopped if that came first. A starting run that is live
    holds its scope's claims back (server ADR 0092). `at` is the time the read's data is from: a
    snapshot's as_of, or now for a live read."""
    for r in ctx.state.get("runs", []):
        inst = cfg.BY_NAME.get(r.get("instance"))
        if not inst or (inst.service, inst.env) != (svc, env):
            continue
        flush = cfg.fixed_flush(inst, ctx.scale) or ctx.flush
        end = r["started"] + JVM_BOOT_ALLOWANCE + (STARTING_CAP_INTERVALS + 1) * flush
        if r.get("ended"):
            # A graceful stop whose closing batch never arrived stays live as long as a kill does.
            end = min(end, r["ended"] + 3 * flush)
        if r["started"] <= at <= end:
            return True
    return False


def snapshot_as_of(ctx, svc, env):
    """When the data the scope's reads come from was read: the report's as_of, which a snapshot
    shares with every route it serves, or now when the report cannot be read."""
    try:
        status, body, _ = ctx.reader.api.get("/api/v1/services/%s/report" % urllib.parse.quote(svc, safe=""),
                                             {"environment": env})
        if status == 200 and isinstance(body, dict) and body.get("as_of"):
            return datetime.datetime.fromisoformat(body["as_of"].replace("Z", "+00:00")).timestamp()
    except (ApiError, ValueError):
        pass
    return ctx.now


def check1(ctx):
    rd = ctx.reader
    ok, bad, notdue = 0, [], 0
    for svc, env in ctx.pairs():
        tl = ctx.truth_lists(svc, env)
        tag = "%s/%s" % (svc, env)
        held = crash_hold(ctx, svc, env) or start_hold(ctx, svc, env, snapshot_as_of(ctx, svc, env))

        def run(entry, label, test, extra_start=0.0, live=False):
            nonlocal ok, notdue
            # /endpoints, /optional-parameters and /dependencies are always read live, so a run
            # starting now holds what they show, whatever the snapshot's as_of.
            if held or (live and start_hold(ctx, svc, env, ctx.clock())) or not ctx.asserted(entry, extra_start):
                notdue += 1
                return
            try:
                msg = test()
            except (ApiError, Computing) as e:
                msg = "could not read: %s" % e
            if msg is None:
                ok += 1
            else:
                bad.append("%s: %s: %s" % (tag, label, msg))

        def find(rows, pred):
            return [r for r in rows if pred(r)]

        for e in tl.get("never_hit_methods", []):
            def t(e=e):
                rows = rd.rows(svc, env, "never-hit", "rows")
                hit = find(rows, lambda r: r.get("kind") == "method" and r.get("class_name") == e["class"]
                           and r.get("method_name") == e["method"]
                           and (not e.get("descriptor") or r.get("method_descriptor") == e["descriptor"]))
                return None if hit else "not on /never-hit (%d method rows listed)" % sum(1 for r in rows if r.get("kind") == "method")
            run(e, "never-hit method %s.%s" % (e["class"], e["method"]), t)
        for e in tl.get("never_hit_outcomes", []):
            def t(e=e):
                rows = rd.rows(svc, env, "never-hit", "rows")
                hit = find(rows, lambda r: r.get("kind") == "branch" and r.get("class_name") == e["class"]
                           and (not e.get("method") or r.get("method_name") == e["method"])
                           and e.get("condition_contains", "") in site_text(r)
                           and any(o.get("in_finding") for o in r.get("outcomes") or []))
                return None if hit else "no never-hit branch site with condition containing %r (%d branch rows listed)" % (
                    e.get("condition_contains"), sum(1 for r in rows if r.get("kind") == "branch"))
            run(e, "never-hit outcome %s.%s [%s]" % (e["class"], e.get("method"), e.get("condition_contains")), t)
        for e in tl.get("never_called_endpoints", []):
            def t(e=e):
                rows = rd.rows(svc, env, "endpoints", "endpoints", days=30)
                row = find(rows, lambda r: r.get("verb") == e["verb"] and r.get("route_template") == e["template"])
                if not row:
                    return "endpoint not listed"
                return None if norm_status(row[0].get("status")) == "never-called" else \
                    "status is %s, calls_total %s" % (row[0].get("status"), row[0].get("calls_total"))
            run(e, "never-called endpoint %s %s" % (e["verb"], e["template"]), t, live=True)
        for e in tl.get("live_endpoints", []):
            def t(e=e):
                rows = rd.rows(svc, env, "endpoints", "endpoints", days=30)
                row = find(rows, lambda r: r.get("verb") == e["verb"] and r.get("route_template") == e["template"])
                if not row:
                    return "endpoint not listed"
                return None if norm_status(row[0].get("status")) == "live" else \
                    "status is %s, calls_total %s" % (row[0].get("status"), row[0].get("calls_total"))
            run(e, "live endpoint %s %s" % (e["verb"], e["template"]), t, live=True)
        for key, route in (("never_loaded", "never-loaded"), ("never_initialized", "never-initialized"),
                           ("never_instantiated", "never-instantiated"), ("failed_to_initialize", "failed-to-initialize")):
            for entry, cls in cls_entries(tl, key):
                def t(cls=cls, route=route):
                    rows = rd.rows(svc, env, route, "classes")
                    return None if any(r.get("class_name") == cls for r in rows) else \
                        "not on /%s (%d classes listed)" % (route, len(rows))
                run(entry, "%s %s" % (key.replace("_", " "), cls), t)
        for key, status in (("never_supplied", "never-supplied"), ("always_supplied", "always-supplied")):
            for e in tl.get(key, []):
                def t(e=e, status=status):
                    rows = rd.rows(svc, env, "optional-parameters", "optional_parameters", status=status)
                    return None if find(rows, lambda r: r.get("class_name") == e["class"] and r.get("method_name") == e["method"]
                                        and r.get("parameter_name") == e["parameter"]) else \
                        "not on /optional-parameters?status=%s (%d rows)" % (status, len(rows))
                run(e, "%s %s.%s(%s)" % (key.replace("_", " "), e["class"], e["method"], e["parameter"]), t, live=True)
        for e in tl.get("dependencies", []):
            def t(e=e):
                rows = rd.rows(svc, env, "dependencies", "dependencies")
                row = find(rows, lambda r: e["artifact"] in _artifacts(r))
                if not row:
                    return "dependency not listed"
                if norm_status(row[0].get("status")) != norm_status(e["status"]):
                    return "status is %s, expected %s" % (row[0].get("status"), e["status"])
                if e.get("site_class") and not any(s_.get("class_name") == e["site_class"] for s_ in row[0].get("sites") or []):
                    return "no site in %s (sites: %s)" % (
                        e["site_class"], ", ".join(s_.get("class_name", "") for s_ in row[0].get("sites") or []) or "none")
                return None
            run(e, "dependency %s" % e["artifact"], t, live=True)
        for e in tl.get("stale_hit", []):
            days = int(e.get("days", 30))
            extra = max(0.0, days * 86400 + 600.0 / ctx.scale - (cfg.parse_duration(e.get("from")) or 0.0) / ctx.scale)

            def t(e=e, days=days):
                rows = rd.rows(svc, env, "stale-hit", "rows", days=days, kind="method")
                return None if find(rows, lambda r: r.get("class_name") == e["class"] and r.get("method_name") == e["method"]) \
                    else "not on /stale-hit?days=%d (%d rows)" % (days, len(rows))
            run(e, "stale hit %s.%s at days=%d" % (e["class"], e["method"], days), t, extra_start=extra)
        for e in tl.get("live_methods", []):
            def t(e=e):
                rows = rd.rows(svc, env, "never-hit", "rows")
                hit = find(rows, lambda r: r.get("kind") == "method" and r.get("class_name") == e["class"]
                           and r.get("method_name") == e["method"])
                return "on /never-hit, but it ran" if hit else None
            run(e, "live method %s.%s" % (e["class"], e["method"]), t)
        for e in tl.get("unreached_cluster_roots", []):
            def t(e=e):
                rows = rd.rows(svc, env, "unreached-clusters", "clusters")
                match = find(rows, lambda r: (r.get("root") or {}).get("class_name") == e["class"]
                             and (r.get("root") or {}).get("method_name") == e["method"])
                if not match:
                    return "no cluster rooted there (%d clusters listed)" % len(rows)
                c = match[0]
                have = {(m.get("class_name"), m.get("method_name")) for m in c.get("members") or []}
                have_cls = {m.get("class_name") for m in c.get("whole_classes") or []} | {c["root"].get("class_name")}
                have_cls |= {cls for cls, _ in have}
                missing = []
                for m in e.get("members_include", []):
                    ms, cs = _split_member(m)
                    if not (any(x in have for x in ms) or any(x in have_cls for x in cs)):
                        missing.append(m)
                if missing:
                    return "members missing from the cluster: %s" % ", ".join(missing)
                cot = e.get("called_only_by_tests")
                if cot and ctx.asserted(cot if isinstance(cot, dict) else {}) and any(
                        t_.get("ended") for t_ in ctx.state.get("testruns", [])):
                    return cluster_test_problem(c, cot.get("tests") if isinstance(cot, dict) else None)
                return None
            run(e, "cluster root %s.%s" % (e["class"], e["method"]), t)
        for e in tl.get("removed", []):
            def t(e=e):
                rows = rd.rows(svc, env, "removed", "rows")
                if e.get("verb"):
                    tpl = e.get("template") or e.get("route_template") or e.get("path")
                    ok_ = find(rows, lambda r: r.get("kind") == "endpoint" and r.get("verb") == e["verb"] and r.get("route_template") == tpl)
                elif e.get("artifact"):
                    ok_ = find(rows, lambda r: r.get("kind") == "dependency" and e["artifact"] in _artifacts(r))
                elif e.get("method"):
                    cls = e.get("class") or e.get("class_name")
                    ok_ = find(rows, lambda r: r.get("kind") == "method" and r.get("class_name") == cls and r.get("method_name") == e["method"])
                else:
                    cls = e.get("class") or e.get("class_name")
                    ok_ = find(rows, lambda r: r.get("kind") == "class" and r.get("class_name") == cls)
                return None if ok_ else "not on /removed (%d rows)" % len(rows)
            run(e, "removed %s" % json.dumps({k: v for k, v in e.items() if k not in ("from", "until")}), t)
    asserted = ok + len(bad)
    if not asserted:
        return result(1, "skip", "no truth entry is due yet (%d waiting)" % notdue)
    status = "pass" if not bad else "fail"
    return result(1, status, "%d entries hold, %d fail, %d not due yet" % (ok, len(bad), notdue), _cap(bad))


def pattern_matches(pattern, cls):
    """A class_pattern is a class name, a regular expression that matches the whole name, or a name suffix."""
    if cls == pattern or cls.endswith(pattern):
        return True
    try:
        return re.fullmatch(pattern, cls) is not None
    except re.error:
        return False


def check2(ctx):
    rd = ctx.reader
    if ctx.now < ctx.t0 + ctx.lag and not getattr(ctx, "windowed", False):
        return result(2, "skip", "too early: findings are read %ds after T0" % ctx.lag)
    evidence, total, unexplained = [], 0, 0
    for svc, env in ctx.pairs():
        tl = ctx.truth_lists(svc, env)
        tag = "%s/%s" % (svc, env)
        try:
            fs = collect_findings(rd, svc, env)
            removed = collect_removed(rd, svc, env)
        except (ApiError, Computing) as e:
            evidence.append("%s: could not read the findings: %s" % (tag, e))
            unexplained += 1
            continue
        if getattr(ctx, "windowed", False) and ctx.now < ctx.t0 + ctx.lag:
            fs = [f for f in fs if f.kind in EARLY_WINDOW_KINDS]
            removed = []
        total += len(fs) + len(removed)
        for f in fs + removed:
            if not explained(f, tl, ctx):
                unexplained += 1
                evidence.append("%s: truth.json does not name: %s" % (tag, f.key))
        for e in tl.get("never_in_findings", []):
            methods = set(e.get("methods") or [])
            for f in fs + removed:
                if f.cls and pattern_matches(e["class_pattern"], f.cls) and (not methods or f.method in methods):
                    unexplained += 1
                    evidence.append("%s: must be in no finding, but is: %s" % (tag, f.key))
        removed_classes = {f.cls for f in removed if f.kind == "removed class"}
        for f in fs:
            if f.cls in removed_classes:
                unexplained += 1
                evidence.append("%s: a removed class is also in a finding: %s" % (tag, f.key))
    if unexplained:
        return result(2, "fail", "%d of %d finding rows are not explained by truth.json" % (unexplained, total), _cap(evidence))
    return result(2, "pass", "all %d finding rows are named in truth.json" % total, evidence)


def read_json(path, default=None):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def traffic_sum(ctx, svc, env, verb, template):
    key = "%s %s" % (verb, template)
    t = read_json(ctx.paths["traffic"], {"instances": {}})
    ok = non = err = 0
    tol = 0
    flush = ctx.flush
    for inst in cfg.INSTANCES:
        if inst.service != svc or inst.env != env:
            continue
        e = t.get("instances", {}).get(inst.name, {}).get(key)
        if not e:
            continue
        ok += e["ok"]
        non += e["non2xx"]
        err += e["errors"]
        rate = e.get("rate_per_s", 0.0)
        per_flush = int(math.ceil(rate * flush)) + 1
        starts = sum(1 for r in ctx.state.get("runs", []) if r["instance"] == inst.name) or 1
        kills = sum(1 for ev in ctx.state.get("events", []) if ev["kind"] == "kill9" and ev["instance"] == inst.name)
        limits = sum(1 for ev in ctx.state.get("events", []) if ev["kind"] == "collector-limit")
        tol += per_flush + 2 * starts + (kills + limits) * per_flush + int(math.ceil(rate * 10))
    return ok, non, err, tol


def check3(ctx):
    lines, ok, bad, failed = [], 0, [], []
    for svc, env in ctx.pairs():
        for e in ctx.truth_lists(svc, env).get("live_endpoints", []):
            if not ctx.asserted(e):
                continue
            label = "%s/%s %s %s" % (svc, env, e["verb"], e["template"])
            ok_b, _, _, _ = traffic_sum(ctx, svc, env, e["verb"], e["template"])
            try:
                rows = ctx.reader.rows(svc, env, "endpoints", "endpoints", fresh=True, days=30)
            except (ApiError, Computing) as ex:
                bad.append("%s: could not read: %s" % (label, ex))
                continue
            ok_a, non, err, tol = traffic_sum(ctx, svc, env, e["verb"], e["template"])
            row = [r for r in rows if r.get("verb") == e["verb"] and r.get("route_template") == e["template"]]
            if not row:
                bad.append("%s: the endpoint is not listed; the generator recorded %d 2xx" % (label, ok_a))
                continue
            calls = row[0].get("calls_total", 0)
            lo, hi = ok_b - tol, ok_a + non + err + tol
            line = "%s: calls_total %d, 2xx recorded %d..%d (+%d non-2xx, +%d failed), tolerance %d" % (
                label, calls, ok_b, ok_a, non, err, tol)
            if lo <= calls <= hi:
                ok += 1
                lines.append(line)
            else:
                bad.append(line + " -> outside %d..%d" % (lo, hi))
    for e in ctx.state.get("events", []):
        for field, cmd in (("exit", {"collector-outage": "stop-collector", "server-restart": "restart-server",
                                     "collector-limit": "collector-limit low"}),
                           ("start_exit", {"collector-outage": "start-collector",
                                           "collector-limit": "collector-limit normal"})):
            if e.get(field) and e["kind"] in cmd:
                failed.append("the planned %s did not run as planned: stack.sh %s exited %s"
                              % (e["kind"], cmd[e["kind"]], e[field]))
    for item in ("collector-stop", "server-restart", "collector-low-limit"):
        note = (ctx.state.get("done", {}).get(item) or {}).get("note", "")
        if note.startswith("skipped"):
            failed.append("the planned %s did not run: %s" % (item, note))
    if not ok and not bad and not failed:
        return result(3, "skip", "no live endpoint is due yet")
    if failed:
        return result(3, "fail", "%d planned disruptions did not run as planned" % len(failed),
                      _cap(failed + bad + lines))
    if bad:
        return result(3, "fail", "%d endpoint counts differ, %d agree" % (len(bad), ok), _cap(bad + lines))
    return result(3, "pass", "%d endpoint counts agree with the traffic" % ok, _cap(lines))


def check4(ctx):
    runs = ctx.state.get("runs", [])
    if not runs:
        return result(4, "skip", "the supervisor has recorded no run")
    ev = ctx.state.get("events", [])
    outages = [(e["t0"], e["t1"] or ctx.now) for e in ev if e["kind"] in ("collector-outage", "collector-limit")]
    unasserted = {i.name for i in cfg.INSTANCES if not i.asserts_runs}
    bad, good, notes = [], 0, []
    by_scope = {}
    for r in runs:
        if r.get("service") and r.get("env"):
            by_scope.setdefault((r["service"], r["env"]), []).append(r)
    for (svc, env), rs in by_scope.items():
        try:
            inst = {i["instance_id"]: i for i in ctx.reader.rows(svc, env, "instances", "instances", paged=False, fresh=True)}
        except (ApiError, Computing) as e:
            bad.append("%s/%s: could not read /instances: %s" % (svc, env, e))
            continue
        for r in rs:
            iid, how = r["instance_id"], r.get("how")
            if r.get("ended") is None:
                continue
            if r.get("instance") in unasserted:
                if how == "crash":
                    bad.append("%s: the process exited on its own (exit %s)" % (iid, r.get("exit")))
                else:
                    notes.append("%s: ended as %s before its first flush; not asserted" % (iid, how))
                continue
            if how == "crash":
                bad.append("%s: the process exited on its own (exit %s)" % (iid, r.get("exit")))
                continue
            if how in ("graceful-timeout",):
                bad.append("%s: SIGTERM did not stop it in time; it was killed" % iid)
                continue
            if how not in ("graceful", "kill9"):
                notes.append("%s: ended as %s; not asserted" % (iid, how))
                continue
            if how == "graceful" and any(a - 5 <= r["ended"] <= b + 2 * ctx.flush for a, b in outages):
                notes.append("%s: stopped during or just after the collector outage; not asserted" % iid)
                continue
            if ctx.now - r["ended"] < 2 * ctx.flush + 30:
                notes.append("%s: stopped too recently to judge" % iid)
                continue
            row = inst.get(iid)
            if row is None:
                if (r["ended"] - r["started"]) >= 2 * ctx.flush:
                    bad.append("%s (%s): the server does not list this instance" % (iid, how))
                else:
                    notes.append("%s: lived under two flush intervals, not listed; not asserted" % iid)
                continue
            clean = bool(row.get("ended_cleanly"))
            if how == "graceful" and not clean:
                bad.append("%s: stopped with SIGTERM but ended_cleanly is false" % iid)
            elif how == "kill9" and clean:
                bad.append("%s: killed with -9 but ended_cleanly is true" % iid)
            else:
                good += 1
                notes.append("%s: %s, ended_cleanly=%s" % (iid, how, clean))
    # A test run counts toward no instance figure, so /instances never lists it.
    if not good and not bad:
        return result(4, "skip", "no stopped run is old enough to judge", notes)
    if bad:
        return result(4, "fail", "%d runs read wrong, %d right" % (len(bad), good), _cap(bad + notes))
    return result(4, "pass", "%d stopped runs read as expected" % good, _cap(notes))


def parse_ts(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def slope(xs, ys):
    """Least-squares slope of ys against xs."""
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def check5(ctx):
    """Each agent process against the control of its own app at the same age. A process's age counts
    from its start in state.json. Its first WARM_S seconds are JVM warm-up and are left out, so a
    process restarted every 12 hours is compared with the control's own hours 0.5 to 12, not with a
    control that has run for days. A control has one segment for each pid; the one that started
    closest to the agent process is used, and only the ages both cover are compared."""
    try:
        with open(ctx.paths["memory"]) as f:
            rows = list(csv.DictReader(f))
    except OSError:
        return result(5, "skip", "no memory samples yet")
    warm_s = 1800.0
    segs = {}
    for r in rows:
        try:
            t = parse_ts(r["ts"])
            rss = float(r["rss_kb"]) / 1024.0
        except (ValueError, KeyError):
            continue
        heap = float(r["heap_used_kb"]) / 1024.0 if r.get("heap_used_kb") else None
        segs.setdefault((r["instance"], r["pid"]), []).append((t, rss, heap))
    ev = []
    minn = 6

    starts = {(r.get("instance"), str(r.get("pid"))): r.get("started") for r in ctx.state.get("runs", [])}

    def origin(key, samples):
        return starts.get(key) or samples[0][0]

    def aged(key, samples):
        start = origin(key, samples)
        return [(t - start, rss, heap) for t, rss, heap in samples if t - start >= warm_s]

    def stats(samples):
        xs = [s[0] / 3600.0 for s in samples]
        ys = [s[1] for s in samples]
        hs = [s[2] for s in samples if s[2] is not None]
        hx = [x for x, s in zip(xs, samples) if s[2] is not None]
        return slope(xs, ys), sum(ys) / len(ys), (slope(hx, hs) if len(hs) >= minn else None), ys[0], ys[-1]

    controls = {i.control for i in cfg.INSTANCES if i.control}
    ctl = {}
    for (name, pid), v in segs.items():
        if name in controls:
            ctl.setdefault(name, []).append(((name, pid), origin((name, pid), v), aged((name, pid), v)))
    bad, judged, uncompared = [], 0, 0
    for (name, pid), v in sorted(segs.items()):
        inst = cfg.BY_NAME.get(name)
        if not inst or not inst.agent:
            continue
        av = aged((name, pid), v)
        if len(av) < minn:
            ev.append("%s (pid %s): %d samples after warm-up, too few to judge" % (name, pid, len(av)))
            continue
        lo, hi = av[0][0], av[-1][0]
        cname = inst.control
        usable = []
        for ck, cstart, cv in ctl.get(cname, []):
            if not cv:
                continue
            mine = [a for a in av if cv[0][0] <= a[0] <= cv[-1][0]]
            same = [c for c in cv if mine and mine[0][0] <= c[0] <= mine[-1][0]]
            if len(mine) >= minn and len(same) >= minn:
                usable.append((abs(cstart - origin((name, pid), v)), ck, same, mine, cv))
        if not usable:
            ev.append("%s (pid %s): ages %.1fh to %.1fh, %s has no process with %d samples at those ages, too few to judge"
                      % (name, pid, lo / 3600.0, hi / 3600.0, cname or "no control", minn))
            continue
        _, ck, same, mine, cv = min(usable, key=lambda u: u[0])
        before = [a for a in av if a[0] < mine[0][0]]
        after = [a for a in av if a[0] > mine[-1][0]]
        for out in (before, after):
            if len(out) < 2:
                continue
            uncompared += 1
            ev.append("%s (pid %s): ages %.1fh to %.1fh not compared, since %s (pid %s) covers only ages %.1fh to %.1fh"
                      % (name, pid, out[0][0] / 3600.0, out[-1][0] / 3600.0, ck[0], ck[1],
                         cv[0][0] / 3600.0, cv[-1][0] / 3600.0))
        av = mine
        lo, hi = av[0][0], av[-1][0]
        c_slope, c_mean, _, _, _ = stats(same)
        allowed = max(c_slope, 0.0) + 0.10 * c_mean / 24.0
        s, mean, hs, first, last = stats(av)
        judged += 1
        line = ("%s (pid %s): ages %.1fh to %.1fh, RSS %.0f -> %.0f MB, slope %+.3f MB/h%s; %s (pid %s) at the same "
                "ages %+.3f MB/h, allowed %.3f MB/h") % (
            name, pid, lo / 3600.0, hi / 3600.0, first, last, s,
            "" if hs is None else ", heap slope %+.3f MB/h" % hs, ck[0], ck[1], c_slope, allowed)
        if s > allowed:
            bad.append(line + " -> above the allowance")
        else:
            ev.append(line)
    if not judged:
        return result(5, "skip", "no agent process has %d samples after warm-up at ages its control covers" % minn, ev)
    if bad:
        return result(5, "fail", "%d agent processes grow faster than their control plus 10%%" % len(bad), _cap(bad + ev))
    return result(5, "pass", "%d agent processes grow no faster than their control plus 10%%%s" % (
        judged, "; %d age spans not compared" % uncompared if uncompared else ""), _cap(ev))


ISO_PREFIX = re.compile(r"^(\d{4}-\d\d-\d\dT[\d:.]+Z) (.*)$")
AGENT_LINE = re.compile(r"(?<![\w.])otherlode: |dev\.otherlode\.(?!soak)|^(?:OpenJDK|Java HotSpot).*warning:", re.I)
LEVEL_PLAIN = re.compile(r"^(SEVERE|WARNING):|(?:^|[\s\[])(ERROR|WARN)(?:[\s\]])")


def classify(line):
    """Return (time or None, level or None, is_agent, text) for one log line."""
    line = line.rstrip("\n")
    if line.startswith("{") and '"level"' in line:
        try:
            o = json.loads(line)
        except ValueError:
            o = None
        if isinstance(o, dict):
            lv = str(o.get("level", "")).upper()
            lv = {"WARNING": "WARN"}.get(lv, lv)
            try:
                ts = parse_ts(o["time"]) if o.get("time") else None
            except ValueError:
                ts = None
            return ts, (lv if lv in ("WARN", "ERROR") else None), False, line
    ts = None
    m = ISO_PREFIX.match(line)
    rest = line
    if m:
        ts, rest = parse_ts(m.group(1)), m.group(2)
    lm = LEVEL_PLAIN.search(rest)
    level = None
    if lm:
        lv = lm.group(1) or lm.group(2)
        level = {"SEVERE": "ERROR", "WARNING": "WARN"}.get(lv, lv)
    elif re.match(r"^(?:OpenJDK|Java HotSpot).*warning:", rest, re.I):
        level = "WARN"
    return ts, level, bool(AGENT_LINE.search(rest)), rest


ERROR_MARK = "[ERROR]"


def load_allowlist(path):
    """Return (disruption, expression, line number, level) for each entry. The level is WARN, or
    ERROR for an entry that starts with the marker [ERROR]."""
    out = []
    try:
        with open(path) as f:
            for n, raw in enumerate(f, 1):
                s = raw.strip()
                if not s or s.startswith("#"):
                    continue
                kind, _, rx = s.partition(" ")
                rx = rx.strip()
                level = "WARN"
                if rx.startswith(ERROR_MARK + " "):
                    level, rx = "ERROR", rx[len(ERROR_MARK):].strip()
                if not rx:
                    raise ValueError("%s line %d: needs a disruption and a regular expression" % (path, n))
                out.append((kind, re.compile(rx), n, level))
    except OSError:
        pass
    return out


ERROR_TAIL = 30.0


def error_windows(ctx):
    """Windows [a, b] in epoch seconds in which an [ERROR] entry holds, by disruption. They are
    tight: a stop of the server or the collector, or the server's whole restart, plus
    ERROR_TAIL seconds, the collector's 8-second request timeout with room. A fault on a server
    that is back must still fail the check."""
    w = {"collector-outage": [], "collector-limit": [], "server-restart": []}
    for e in ctx.state.get("events", []):
        t0, t1, k = e["t0"], e.get("t1"), e["kind"]
        if k == "server-restart":
            w[k].append((t0 - 5, (t1 or ctx.now) + ERROR_TAIL))
        elif k == "collector-outage":
            w[k].append((t0 - 5, t0 + ERROR_TAIL))
        elif k == "collector-limit":
            w[k].append((t0 - 5, t0 + ERROR_TAIL))
            if t1:
                w[k].append((t1 - 5, t1 + ERROR_TAIL))
    return w


def disruption_windows(ctx):
    """Windows [a, b] in epoch seconds, by the disruption name the allowlist uses."""
    w = {k: [] for k in ("jvm-start", "graceful-restart", "kill-9", "collector-outage", "collector-limit",
                         "server-restart", "upgrade", "stack-start")}
    grace = max(300.0, 3 * ctx.flush)
    for r in ctx.state.get("runs", []):
        w["jvm-start"].append((r["started"] - 5, r["started"] + 180))
    for t in ctx.state.get("testruns", []):
        w["jvm-start"].append((t["started"] - 5, (t.get("ended") or ctx.now) + 60))
    for e in ctx.state.get("events", []):
        t1 = e.get("t1")
        k = e["kind"]
        if k == "graceful-restart":
            w[k].append((e["t0"] - 5, (t1 or ctx.now) + 180))
        elif k == "upgrade":
            w[k].append((e["t0"] - 5, (t1 or ctx.now) + 180))
        elif k in ("kill9", "crash-cycle"):
            w["kill-9"].append((e["t0"] - 5, (t1 or ctx.now) + 180))
        elif k == "collector-limit":
            w[k].append((e["t0"] - 5, (t1 or ctx.now) + grace))
        elif k == "collector-outage":
            w[k].append((e["t0"] - 5, (t1 or ctx.now) + grace))
        elif k == "server-restart":
            w[k].append((e["t0"] - 5, (t1 or ctx.now) + grace))
        elif k in ("supervisor-start",):
            w["stack-start"].append((e["t0"] - 5, e["t0"] + 300))
    w["stack-start"].append((ctx.t0 - 5, ctx.t0 + 300))
    return w


def check6(ctx):
    logs = ctx.paths["logs"]
    allow = load_allowlist(ctx.paths["allowlist"])
    windows = disruption_windows(ctx)
    err_windows = error_windows(ctx)
    files = {}
    try:
        names = sorted(os.listdir(logs))
    except OSError:
        return result(6, "skip", "no log directory yet")
    for n in names:
        if not n.endswith(".log"):
            continue
        base = n[:-4]
        if base in ("soak", "check"):
            continue
        if base in ("server", "refresh", "migrate", "bootstrap"):
            src = "server"
        elif base == "collector":
            src = "collector"
        else:
            src = "app:" + base
        files[os.path.join(logs, n)] = src
    if not any(s == "server" for s in files.values()) or not any(s == "collector" for s in files.values()):
        missing = [x for x in ("server", "collector") if x not in files.values()]
        return result(6, "fail", "no log of the %s" % " or ".join(missing))
    errors, warns, app_info = [], [], {}
    allowed_n = 0
    allowed_err = 0
    counts = {}
    for path, src in files.items():
        try:
            f = open(path, errors="replace")
        except OSError:
            continue
        with f:
            for line in f:
                ts, level, is_agent, text = classify(line)
                if level is None and src in ("server", "collector"):
                    if re.search(r"\b(panic:|fatal error:)|http: panic serving", line):
                        level = "ERROR"
                if level is None:
                    continue
                judged = src in ("server", "collector") or is_agent
                if not judged:
                    app_info[(src, level)] = app_info.get((src, level), 0) + 1
                    continue
                counts[(src if not src.startswith("app:") else "agent (" + src[4:] + ")", level)] = \
                    counts.get((src if not src.startswith("app:") else "agent (" + src[4:] + ")", level), 0) + 1
                where = "%s %s: %s" % (os.path.basename(path), datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ") if ts else "?", text.strip()[:240])
                matched = [(k, rx, n) for k, rx, n, lv in allow if lv == level and rx.search(text)]
                bad = errors if level == "ERROR" else warns
                if not matched:
                    bad.append(where + ("" if level == "ERROR" else "  [no allowlist entry]"))
                    continue
                spans = err_windows if level == "ERROR" else windows
                if ts is not None and any(a <= ts <= b for k, rx, n in matched for a, b in spans.get(k, [])):
                    allowed_n += 1
                    allowed_err += level == "ERROR"
                else:
                    bad.append(where + "  [matches allowlist line %s but not inside a %s window]" % (
                        ",".join(str(n) for _, _, n in matched), "/".join(sorted({k for k, _, _ in matched}))))
    summary = ["%s %s: %d" % (s, lv, n) for (s, lv), n in sorted(counts.items())]
    summary.append("WARN lines inside an allowlisted disruption window: %d" % (allowed_n - allowed_err))
    summary.append("ERROR lines inside an allowlisted disruption window: %d" % allowed_err)
    for (s, lv), n in sorted(app_info.items()):
        summary.append("app output, not judged: %s %s: %d" % (s[4:], lv, n))
    if errors or warns:
        ev = ["ERROR: " + e for e in errors] + ["WARN: " + w for w in warns]
        return result(6, "fail", "%d ERROR lines and %d unexplained WARN lines" % (len(errors), len(warns)), _cap(ev) + summary)
    return result(6, "pass", "no unexplained ERROR or WARN line", summary)


def planned_changes(ctx):
    """Design times, in seconds from T0, at which a finding is planned to change: every truth.json
    from and until, every one-off request, the upgrade, and the first request of each route sent
    less often than every 30 seconds."""
    out = []
    for svc, env in ctx.pairs():
        for key, entries in ctx.truth_lists(svc, env).items():
            if not isinstance(entries, list):
                continue
            for e in entries:
                if not isinstance(e, dict):
                    continue
                for part in (e, e.get("called_only_by_tests")):
                    if not isinstance(part, dict):
                        continue
                    for edge in ("from", "until"):
                        if part.get(edge):
                            out.append((cfg.parse_duration(part[edge]), "%s %s of %s/%s" % (key, edge, svc, env)))
    for o in cfg.ONE_OFFS:
        out.append((o.at, "%s %s on %s" % (o.verb, o.path, o.instance)))
    out.append((72 * cfg.H, "the upgrade of events-prod"))
    for name, routes in cfg.ROUTES.items():
        for r in routes:
            period = r.effective_period(ctx.scale)
            if period >= 30:
                out.append((min(120.0, period) * ctx.scale, "the first %s %s on %s" % (r.verb, r.path, name)))
    return out


def check7(ctx):
    runs = [t for t in ctx.state.get("testruns", []) if t.get("ended")]
    if not runs:
        return result(7, "skip", "no test run has finished yet")
    ev, bad = [], []
    compared = 0
    for t in runs:
        label = "test run %d" % (t["k"] + 1)
        if t.get("exit") != 0:
            bad.append("%s exited with %s" % (label, t.get("exit")))
        if t.get("before") is None:
            bad.append("%s: the findings could not be read before the run, so it cannot be compared" % label)
            continue
        if "after" not in t:
            ev.append("%s: findings not read after the run yet" % label)
            continue
        if t["after"] is None:
            bad.append("%s: could not read the findings after the run: %s" % (label, t.get("after_error")))
            continue
        lo = (t.get("before_t", t["started"]) - ctx.t0) * ctx.scale
        hi = (t.get("after_t", ctx.now) - ctx.t0) * ctx.scale
        inside = sorted({label_ for at, label_ in planned_changes(ctx) if lo <= at <= hi})
        if inside:
            ev.append("%s: not compared, since its window (design %s to %s) holds a planned change: %s" % (
                label, cfg.fmt_hms(lo), cfg.fmt_hms(hi), "; ".join(inside)))
            continue
        compared += 1
        diff = fingerprint_diff(t["before"], t["after"])
        if diff:
            bad.extend("%s changed a finding: %s" % (label, d) for d in diff)
        else:
            ev.append("%s: no finding changed (%d scopes compared)" % (label, len(t["before"])))
    targets = []
    for svc, env in ctx.pairs():
        tl = ctx.truth_lists(svc, env)
        for e in tl.get("unreached_cluster_roots", []):
            cot = e.get("called_only_by_tests")
            if cot:
                targets.append((svc, env, e["class"], e["method"], cot.get("tests") if isinstance(cot, dict) else None,
                                cot if isinstance(cot, dict) else {}))
        if not any(t[0] == svc and t[1] == env for t in targets):
            for e in tl.get("never_hit_methods", []):
                if e.get("method") == cfg.TEST_TARGET_METHOD:
                    targets.append((svc, env, e["class"], e["method"], None, {}))
    settled = [t for t in runs if "after" in t]
    if not targets:
        bad.append("truth.json names no cluster root called only by tests (%s)" % cfg.TEST_TARGET_METHOD)
    elif settled and ctx.now >= settled[0]["ended"] + ctx.lag and all(ctx.asserted(t[5]) for t in targets):
        for svc, env, cls, meth, tests, _ in targets:
            if crash_hold(ctx, svc, env) or start_hold(ctx, svc, env, snapshot_as_of(ctx, svc, env)):
                ev.append("%s.%s: not judged, since a run of %s/%s could be starting and holds its claims back" % (
                    cls, meth, svc, env))
                continue
            try:
                clusters = ctx.reader.rows(svc, env, "unreached-clusters", "clusters", fresh=True)
                hits = ctx.reader.rows(svc, env, "never-hit", "rows", fresh=True)
            except (ApiError, Computing) as ex:
                bad.append("%s/%s: could not read: %s" % (svc, env, ex))
                continue
            if not [r for r in hits if r.get("kind") == "method" and r.get("class_name") == cls and r.get("method_name") == meth]:
                bad.append("%s.%s left /never-hit after a test run" % (cls, meth))
            match = [c for c in clusters if (c.get("root") or {}).get("class_name") == cls
                     and (c.get("root") or {}).get("method_name") == meth]
            if not match:
                bad.append("no cluster rooted at %s.%s" % (cls, meth))
                continue
            problem = cluster_test_problem(match[0], tests)
            if problem:
                bad.append("%s.%s %s" % (cls, meth, problem))
            else:
                ev.append("%s.%s reads called_only_by_tests, with a test caller from %s" % (
                    cls, meth, ", ".join(tests or [cfg.TEST_CALLER])))
    else:
        ev.append("the cluster is read %ds after the first test run's findings; not yet" % ctx.lag)
    if bad:
        return result(7, "fail", "%d problems in %d test runs" % (len(bad), len(runs)), _cap(bad + ev))
    if not compared:
        return result(7, "skip", "no test run could be compared yet (%d finished)" % len(runs), _cap(ev))
    return result(7, "pass", "%d of %d test runs compared, and each left every finding as it was" % (compared, len(runs)), _cap(ev))


WINDOW_CAP = 1000
REFUSED_SERVICE = ("soak-shop", "prod")
REFUSED_INSTANCE = "shop-1"


def violation_key(line):
    """A line of check 1 or 2 without the counts that change between reads, so a finding that stays
    wrong is one violation and not one for every read."""
    return re.sub(r"\(\d+ [^)]*\)", "(N)", line)


def read_window(api, truth, state, now, t0, scale, flush, lag, kind, wait=30.0):
    """One window read of every finding, judged by check 2's rule. Check 1's planted answers are
    not judged here: a run that has not delivered its counts holds those claims back on purpose
    (server ADR 0059, 0087), and the six-hourly checker judges them. A `fast` read is live. A `slow` read is what a page of the UI reads:
    the snapshot-served routes and the report. Returns the violations, the read errors and the
    number of reads of the report."""
    rd = Reader(api, wait, extra={"active_within_days": 36500} if kind == "fast" else None)
    ctx = Ctx(paths={}, truth=truth, state=state, now=now, t0=t0, scale=scale, flush=flush, lag=lag, final=False,
              reader=rd, windowed=True, strict_status=True, live_read=kind == "fast")
    violations, errors, reports = [], [], 0
    global MAX_EVIDENCE
    saved, MAX_EVIDENCE = MAX_EVIDENCE, WINDOW_CAP
    try:
        for fn in (check2,):
            res = fn(ctx)
            if res["status"] != "fail":
                continue
            for line in res["evidence"]:
                if line.startswith("... and ") or re.search(r"still answers 202 computing after \d+s", line):
                    continue
                (errors if "could not read" in line else violations).append(line)
        if kind == "slow":
            for svc in sorted({svc for svc, _ in ctx.pairs()}):
                try:
                    status, body, _ = api.get("/api/v1/services/%s/report" % urllib.parse.quote(svc, safe=""))
                except ApiError as e:
                    errors.append("could not read the report of %s: %s" % (svc, e))
                    continue
                if status == 200 and isinstance(body, dict):
                    reports += 1
                elif status != 202:
                    errors.append("could not read the report of %s: HTTP %s" % (svc, status))
    finally:
        MAX_EVIDENCE = saved
    return violations, errors, reports


def instance_row(api, svc, env, instance_id, wait=30.0):
    """The /instances row of one instance, or None when the server does not list it."""
    rows = Reader(api, wait).rows(svc, env, "instances", "instances", paged=False, fresh=True)
    return next((r for r in rows if r.get("instance_id") == instance_id), None)


def run_is_behind(row):
    """True when a run reads as one whose counts are behind: no delta batch yet, or a payload that
    said hits had not arrived."""
    return row is not None and (not row.get("deltas_received") or row.get("counts_behind_since") is not None)


def check8(ctx):
    w = read_json(ctx.paths["windows"], None)
    reads = (w or {}).get("reads", {})
    made = reads.get("fast", 0) + reads.get("slow", 0)
    if not made and reads.get("errors", 0):
        return result(8, "fail", "every window read failed (%d); the last: %s" % (reads["errors"], w.get("last_error")))
    if not made:
        return result(8, "skip", "no window read has been made yet")
    bad, ev = [], []
    viols = sorted(w.get("violations", {}).values(), key=lambda v: v.get("first", 0))
    for v in viols:
        when = datetime.datetime.fromtimestamp(v["first"], datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        bad.append("%s, %s read, %d times, opened by %s: %s" % (when, v.get("kind"), v.get("count", 1), v.get("event"),
                                                               v.get("message")))
    for e in ctx.state.get("events", []):
        if e["kind"] != "collector-limit":
            continue
        ref = w.get("refused", {})
        n, behind = ref.get("reads", 0), ref.get("behind", 0)
        if e.get("t1") is None or ctx.now < e["t1"] + 60:
            ev.append("the collector refuses manifests now or just stopped; %d reads of %s, %d behind" % (
                n, REFUSED_INSTANCE, behind))
        elif behind:
            ev.append("%s read as counts behind in %d of %d reads while the collector refused its manifest" % (
                REFUSED_INSTANCE, behind, n))
        elif n:
            bad.append("%s never read as counts behind on /instances in %d reads while the collector refused its "
                       "manifest" % (REFUSED_INSTANCE, n))
        else:
            bad.append("no read of %s on /instances was made while the collector refused manifests" % REFUSED_INSTANCE)
    opened = w.get("opened", 0)
    stats = "%d live reads, %d snapshot reads, %d report reads, %d windows opened, %d ticks skipped, %d reads failed" % (
        reads.get("fast", 0), reads.get("slow", 0), reads.get("report", 0), opened,
        sum((w.get("skipped") or {}).values()), reads.get("errors", 0))
    if w.get("last_error"):
        ev.append("the last failed read: %s" % w["last_error"])
    errors = reads.get("errors", 0)
    if errors * 10 > made:
        bad.append("%d of %d window reads failed, so the windows were not read" % (errors, made))
    if bad:
        return result(8, "fail", "%d problems in the windows after a start, a restart, a kill, an upgrade, a collector "
                      "start or a server restart; %s" % (len(bad), stats), _cap(bad + ev))
    return result(8, "pass", "no violation in %s" % stats, _cap(ev))


CHECKS = {1: check1, 2: check2, 3: check3, 4: check4, 5: check5, 6: check6, 7: check7, 8: check8}


def render_md(rep):
    out = ["# Soak check %s" % rep["generated"], ""]
    out.append("- elapsed since T0: %.2f hours of the design's %d (scale %g)" % (
        rep["elapsed_s"] * rep["scale"] / 3600.0, cfg.RUN_SECONDS // 3600, rep["scale"]))
    out.append("- T0: %s" % rep["t0"])
    out.append("- kind: %s" % ("final" if rep["final"] else "scheduled"))
    out.append("- result: **%s**" % rep["result"])
    out.append("")
    out.append("| # | Check | Status | Summary |")
    out.append("|---|---|---|---|")
    for r in rep["checks"]:
        out.append("| %d | %s | %s | %s |" % (r["id"], r["name"], r["status"].upper(), r["summary"].replace("|", "\\|")))
    for r in rep["checks"]:
        out.append("")
        out.append("## %d. %s: %s" % (r["id"], r["name"], r["status"].upper()))
        out.append("")
        out.append(r["summary"])
        if r["evidence"]:
            out.append("")
            for line in r["evidence"]:
                out.append("- " + line.replace("\n", " "))
    out.append("")
    return "\n".join(out)


def run_checks(ctx, only=None):
    results = []
    for n in sorted(CHECKS):
        if only and n not in only:
            continue
        try:
            r = CHECKS[n](ctx)
        except Exception as e:  # a bug in a check is a failure to report, not a crash
            r = result(n, "fail", "the check raised %s: %s" % (type(e).__name__, e))
        if ctx.final and r["status"] == "skip":
            r["status"] = "fail"
            r["summary"] = "at the end of the run, " + r["summary"]
        results.append(r)
    failed = [r for r in results if r["status"] == "fail"]
    gen = datetime.datetime.fromtimestamp(ctx.now, datetime.timezone.utc)
    return {
        "generated": gen.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "t0": datetime.datetime.fromtimestamp(ctx.t0, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "elapsed_s": ctx.now - ctx.t0, "scale": ctx.scale, "final": bool(ctx.final),
        "result": "FAIL (%d of %d)" % (len(failed), len(results)) if failed else "PASS",
        "failed": [r["id"] for r in failed],
        "checks": results,
    }


def write_report(rep, outdir, final=False):
    os.makedirs(outdir, exist_ok=True)
    stamp = rep["generated"].replace("-", "").replace(":", "")
    base = os.path.join(outdir, stamp)
    with open(base + ".json", "w") as f:
        json.dump(rep, f, indent=1)
    with open(base + ".md", "w") as f:
        f.write(render_md(rep))
    if final:
        for ext in (".json", ".md"):
            with open(base + ext) as src, open(os.path.join(outdir, "final" + ext), "w") as dst:
                dst.write(src.read())
    return base + ".md"


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--now", action="store_true", help="run at once and write the report")
    p.add_argument("--only", default="", help="comma-separated check numbers")
    p.add_argument("--final", action="store_true", help="the end of the run: a check that cannot run fails")
    p.add_argument("--home", default=cfg.HOME)
    p.add_argument("--api", default=cfg.API_BASE)
    p.add_argument("--key-file", default=None)
    p.add_argument("--truth", default=None)
    p.add_argument("--allowlist", default=None)
    p.add_argument("--out", default=None, help="report directory")
    p.add_argument("--lag", type=float, default=1800.0, help="seconds to wait after a planted time")
    p.add_argument("--wait", type=float, default=900.0, help="seconds to wait for a snapshot that is computing")
    p.add_argument("--at", type=float, default=None, help="evaluate as of this epoch time (tests)")
    p.add_argument("--t0", type=float, default=None)
    p.add_argument("--scale", type=float, default=None)
    p.add_argument("--flush", type=int, default=None)
    a = p.parse_args(argv)
    paths = cfg.paths(a.home)
    if a.truth:
        paths["truth"] = a.truth
    if a.allowlist:
        paths["allowlist"] = a.allowlist
    elif not os.path.exists(paths["allowlist"]):
        paths["allowlist"] = os.path.join(os.path.dirname(os.path.abspath(__file__)), "log-allowlist.txt")
    if a.key_file:
        paths["read_key"] = a.key_file
    state = read_json(paths["state"], {})
    t0 = a.t0 or state.get("t0")
    if t0 is None:
        print("no T0: start the supervisor first, or pass --t0", file=sys.stderr)
        return 2
    scale = a.scale or state.get("scale") or 1.0
    flush = a.flush or state.get("flush") or 60
    truth = read_json(paths["truth"])
    if truth is None:
        print("cannot read %s" % paths["truth"], file=sys.stderr)
        return 2
    with open(paths["read_key"]) as f:
        key = f.read().strip()
    now = a.at if a.at is not None else time.time()
    ctx = Ctx(paths=paths, truth=truth, state=state, now=now, t0=t0, scale=scale, flush=flush, lag=a.lag,
              clock=(lambda: a.at) if a.at is not None else time.time,
              final=a.final, reader=Reader(Api(a.api, key), a.wait))
    only = {int(x) for x in a.only.split(",") if x.strip()} or None
    rep = run_checks(ctx, only)
    path = write_report(rep, a.out or paths["reports"], final=a.final)
    for r in rep["checks"]:
        print("%d %-16s %-5s %s" % (r["id"], r["name"], r["status"].upper(), r["summary"]))
    print("report: %s" % path)
    return 1 if rep["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
