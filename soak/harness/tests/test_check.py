"""Each of the eight checks, passing and failing once, against a fake read API and a toy truth file.

Run from soak/harness: python3 -m unittest
"""

import copy
import http.server
import io
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import check  # noqa: E402
import soakcfg as cfg  # noqa: E402

KEY = "otl_test-read-key"
T0 = 1_800_000_000.0
NOW = T0 + 30 * 3600  # 30 hours in, so every entry of the toy truth is due
FLUSH = 60
AFTER_LIMIT = T0 + 61 * 3600

TRUTH = {
    "services": {
        "soak-shop": {
            "prod": {
                "never_hit_methods": [
                    {"class": "a.Pricing", "method": "applyLegacyDiscount"},
                    {"class": "a.AdminController", "method": "rebuild"},
                ],
                "never_hit_outcomes": [{"class": "a.OrderService", "method": "find", "condition_contains": "id < 0"}],
                "never_called_endpoints": [{"verb": "DELETE", "template": "/orders/{id}", "until": None}],
                "live_endpoints": [{"verb": "GET", "template": "/orders/{id}", "from": "PT0S"}],
                "never_loaded": ["a.Legacy", {"class": "a.Late", "from": "PT100H"}],
                "never_initialized": ["a.TaxTables"],
                "never_instantiated": ["a.AuditRecord"],
                "failed_to_initialize": ["a.Broken"],
                "never_supplied": [{"class": "a.OrderSearch", "method": "search", "parameter": "sort"}],
                "always_supplied": [{"class": "a.OrderSearch", "method": "search", "parameter": "limit"}],
                "dependencies": [{"artifact": "commons-csv", "status": "unreached"},
                                 {"artifact": "annotations", "status": "unloaded", "framework": True}],
                "stale_hit": [{"class": "a.Report", "method": "yearly", "days": 1, "from": "PT26H"}],
                "unreached_cluster_roots": [
                    {"class": "a.AdminController", "method": "rebuild", "members_include": ["a.Legacy"]},
                    {"class": "a.Pricing", "method": "applyLegacyDiscount", "members_include": [],
                     "called_only_by_tests": {"from": "PT2H", "tests": ["a.testdriver.SoakTestDriver"]}},
                ],
                "removed": [{"kind": "class", "class": "a.Gone", "from": "PT100H"}],
                "never_in_findings": [{"class_pattern": "a.OrderView", "methods": ["copy", "component1"]}],
            }
        }
    }
}


def method_row(cls, name):
    return {"kind": "method", "class_name": cls, "method_name": name, "method_descriptor": "()V"}


GOOD = {
    "never-hit": [
        method_row("a.Pricing", "applyLegacyDiscount"),
        method_row("a.AdminController", "rebuild"),
        {"kind": "branch", "class_name": "a.OrderService", "method_name": "find", "method_descriptor": "(J)V", "line": 9,
         "condition": [{"kind": "code", "text": "id < 0"}], "outcomes": [{"in_finding": True}]},
    ],
    "stale-hit": [dict(method_row("a.Report", "yearly"), _max_days=1)],
    "never-loaded": [{"class_name": "a.Legacy"}],
    "never-initialized": [{"class_name": "a.TaxTables"}],
    "never-instantiated": [{"class_name": "a.AuditRecord"}],
    "failed-to-initialize": [{"class_name": "a.Broken"}],
    "endpoints": [
        {"verb": "DELETE", "route_template": "/orders/{id}", "status": "never-called", "calls_total": 0},
        {"verb": "GET", "route_template": "/orders/{id}", "status": "live", "calls_total": 2000},
        {"verb": "*", "route_template": "/*", "status": "never-called", "calls_total": 0, "handler_included": False},
        {"verb": "*", "route_template": "/error", "status": "never-called", "calls_total": 0},
    ],
    "optional-parameters": [
        {"class_name": "a.OrderSearch", "method_name": "search", "parameter_name": "sort", "_status": "never-supplied"},
        {"class_name": "a.OrderSearch", "method_name": "search", "parameter_name": "limit", "_status": "always-supplied"},
    ],
    "dependencies": [
        {"identity_key": "org.apache.commons:commons-csv", "status": "unreached",
         "identities": [{"group_id": "org.apache.commons", "artifact_id": "commons-csv"}]},
        {"identity_key": "org.jetbrains:annotations", "status": "unloaded",
         "identities": [{"group_id": "org.jetbrains", "artifact_id": "annotations"}]},
        {"identity_key": "com.google.guava:guava", "status": "used",
         "identities": [{"group_id": "com.google.guava", "artifact_id": "guava"}]},
    ],
    "unreached-clusters": [
        {"root": {"class_name": "a.AdminController", "method_name": "rebuild", "root_kind": "uncalled"},
         "members": [], "whole_classes": [{"class_name": "a.Legacy"}], "test_callers": []},
        {"root": {"class_name": "a.Pricing", "method_name": "applyLegacyDiscount", "root_kind": "called_only_by_tests"},
         "members": [], "whole_classes": [], "test_callers": [{"caller": {"class_name": "a.testdriver.SoakTestDriver"}}]},
    ],
    "removed": [],
    "instances": [
        {"instance_id": "shop-1-g001", "ended_cleanly": True, "runs": 1},
        {"instance_id": "shop-1-g002", "ended_cleanly": False, "runs": 1},
        {"instance_id": "shop-2-g001", "ended_cleanly": False, "runs": 1},
        {"instance_id": "soak-shop-tests", "ended_cleanly": True, "runs": 1},
    ],
}

LISTKEY = {
    "never-hit": "rows", "stale-hit": "rows", "removed": "rows", "never-loaded": "classes",
    "never-initialized": "classes", "never-instantiated": "classes", "failed-to-initialize": "classes",
    "endpoints": "endpoints", "optional-parameters": "optional_parameters", "dependencies": "dependencies",
    "unreached-clusters": "clusters", "instances": "instances",
}


class FakeApi:
    """A read API that serves canned rows for the service soak-shop."""

    def __init__(self):
        self.data = copy.deepcopy(GOOD)
        self.requests = []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                u = urllib.parse.urlparse(self.path)
                q = dict(urllib.parse.parse_qsl(u.query))
                outer.requests.append((u.path, q))
                if self.headers.get("Authorization") != "Bearer " + KEY:
                    return self.reply(401, {"error": "unauthorized"})
                m = re.match(r"^/api/v1/(?:namespaces/[^/]+/)?services/([^/]+)/([a-z-]+)$", u.path)
                if not m:
                    return self.reply(404, {"error": "no route"})
                svc, route = urllib.parse.unquote(m.group(1)), m.group(2)
                if svc not in ("soak-shop", "soak-shop-tests-only"):
                    return self.reply(404, {"error": "service not found"})
                if route == "report":
                    return self.reply(200, {"instances": {"total": 3}})
                rows = list(outer.data.get(route, []))
                if route == "stale-hit":
                    rows = [r for r in rows if int(q.get("days", 30)) <= r.get("_max_days", 0)]
                if route == "optional-parameters" and q.get("status") in ("never-supplied", "always-supplied"):
                    rows = [r for r in rows if r.get("_status") == q["status"]]
                if route == "dependencies" and q.get("status") == "unused":
                    rows = [r for r in rows if r["status"] in ("unloaded", "unreferenced", "unreached", "no-live-reference")]
                body = {LISTKEY[route]: rows}
                if route != "instances":
                    body.update(total=len(rows), as_of="2026-01-01T00:00:00Z", from_snapshot=False)
                self.reply(200, body)

            def reply(self, code, body):
                raw = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.server.server_address[1]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def iso(t):
    import datetime
    return datetime.datetime.fromtimestamp(t, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def windows_file(**over):
    """The file the supervisor's window reader keeps, after 40 live reads and 3 snapshot reads."""
    d = {"opened": 12, "events": [], "reads": {"fast": 40, "slow": 3, "report": 3, "errors": 0},
         "skipped": {"fast": 0, "slow": 0}, "violations": {},
         "refused": {"reads": 0, "behind": 0, "first_behind": None, "last": []}, "last_error": None}
    d.update(over)
    return d


def read(path):
    with open(path) as f:
        return f.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


class World:
    """A soak home with state, traffic, memory, logs and truth for the toy run."""

    def __init__(self, root):
        self.home = root
        self.p = cfg.paths(root)
        self.state = {
            "t0": T0, "scale": 1, "flush": FLUSH,
            "runs": [
                {"instance": "shop-1", "instance_id": "shop-1-g001", "service": "soak-shop", "env": "prod",
                 "started": T0, "ended": T0 + 12 * 3600, "how": "graceful", "exit": 0},
                {"instance": "shop-1", "instance_id": "shop-1-g002", "service": "soak-shop", "env": "prod",
                 "started": T0 + 12 * 3600, "ended": None, "how": None, "exit": None},
                {"instance": "shop-2", "instance_id": "shop-2-g001", "service": "soak-shop", "env": "prod",
                 "started": T0, "ended": T0 + 10 * 3600, "how": "kill9", "exit": -9},
            ],
            "events": [
                {"kind": "graceful-restart", "instance": "shop-1", "t0": T0 + 12 * 3600, "t1": T0 + 12 * 3600 + 60},
                {"kind": "kill9", "instance": "shop-2", "t0": T0 + 10 * 3600, "t1": T0 + 10 * 3600 + 120},
                {"kind": "collector-outage", "instance": None, "t0": T0 + 20 * 3600, "t1": T0 + 21 * 3600},
            ],
            "testruns": [
                {"k": 0, "started": T0 + 3600, "ended": T0 + 3700, "exit": 0, "after_t": T0 + 5200,
                 "before": {"soak-shop/prod": ["a", "b"]}, "after": {"soak-shop/prod": ["a", "b"]}},
            ],
        }
        self.traffic = {"instances": {
            "shop-1": {"GET /orders/{id}": {"ok": 1000, "non2xx": 0, "errors": 0, "rate_per_s": 5.0}},
            "shop-2": {"GET /orders/{id}": {"ok": 1000, "non2xx": 0, "errors": 0, "rate_per_s": 5.0}},
        }}
        self.save()
        write(self.p["windows"], json.dumps(windows_file()))
        write(self.p["truth"], json.dumps(TRUTH))
        write(self.p["read_key"], KEY)
        write(self.p["allowlist"], "collector-outage otherlode: delta export failed, will retry next flush\n")
        self.memory(grow_shop1=0.0)
        self.logs()

    def save(self):
        write(self.p["state"], json.dumps(self.state))
        write(self.p["traffic"], json.dumps(self.traffic))

    def memory(self, grow_shop1):
        rows = ["ts,instance,pid,generation,rss_kb,heap_used_kb"]
        for i in range(12):
            t = T0 + 86400 + i * 600
            rows.append("%s,shop-control,10,1,%d,50000" % (iso(t), 400 * 1024 + i))
            rows.append("%s,shop-1,11,2,%d,50000" % (iso(t), int((400 + grow_shop1 * i / 6.0) * 1024)))
        write(self.p["memory"], "\n".join(rows) + "\n")

    def logs(self, extra_server="", extra_shop="", extra_collector=""):
        info = json.dumps({"time": iso(T0 + 100), "level": "INFO", "msg": "listening"})
        write(os.path.join(self.p["logs"], "server.log"), info + "\n" + extra_server)
        write(os.path.join(self.p["logs"], "collector.log"), info + "\n" + extra_collector)
        shop = "%s Spring started\n%s INFO: otherlode: installing endpoint modules\n" % (iso(T0 + 50), iso(T0 + 51)) + extra_shop
        write(os.path.join(self.p["logs"], "shop-1.log"), shop)


class CheckCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.api = FakeApi()

    @classmethod
    def tearDownClass(cls):
        cls.api.close()

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="soak-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.api.data = copy.deepcopy(GOOD)
        self.api.requests.clear()
        self.w = World(self.tmp)

    def run_check(self, n, final=False, now=NOW):
        out = io.StringIO()
        with redirect_stdout(out):
            code = check.main(["--now", "--only", str(n), "--home", self.tmp, "--api", self.api.url, "--lag", "1800",
                               "--wait", "0", "--at", str(now), "--out", os.path.join(self.tmp, "reports")]
                              + (["--final"] if final else []))
        reports = sorted(f for f in os.listdir(os.path.join(self.tmp, "reports")) if f.endswith(".json"))
        with open(os.path.join(self.tmp, "reports", reports[-1])) as f:
            rep = json.load(f)
        self.assertTrue(os.path.exists(os.path.join(self.tmp, "reports", reports[-1][:-5] + ".md")))
        res = rep["checks"][0]
        self.assertEqual(res["id"], n)
        self.assertEqual(code, 0 if res["status"] != "fail" else 1)
        return res

    def evidence(self, res):
        return "\n".join(res["evidence"])


class CheckTests(CheckCase):
    def test_1_planted_answers_pass(self):
        res = self.run_check(1)
        self.assertEqual(res["status"], "pass", res)

    def test_1_planted_answers_fail(self):
        self.api.data["endpoints"][0]["status"] = "live"
        self.api.data["never-loaded"] = []
        res = self.run_check(1)
        self.assertEqual(res["status"], "fail")
        ev = self.evidence(res)
        self.assertIn("DELETE /orders/{id}", ev)
        self.assertIn("a.Legacy", ev)

    def test_1_entry_not_due_is_not_asserted(self):
        res = self.run_check(1, now=T0 + 600)
        self.assertEqual(res["status"], "skip")

    def test_2_no_false_claim_pass(self):
        res = self.run_check(2)
        self.assertEqual(res["status"], "pass", res)

    def test_2_no_false_claim_fail(self):
        self.api.data["never-hit"].append(method_row("a.Surprise", "unexpected"))
        self.api.data["dependencies"].append(
            {"identity_key": "x:y", "status": "unloaded", "identities": [{"group_id": "x", "artifact_id": "y"}]})
        self.api.data["never-instantiated"].append({"class_name": "a.OrderView"})
        res = self.run_check(2)
        self.assertEqual(res["status"], "fail")
        ev = self.evidence(res)
        self.assertIn("a.Surprise.unexpected", ev)
        self.assertIn("x:y", ev)
        self.assertIn("a.OrderView", ev)

    def test_2_never_in_findings_by_method(self):
        self.api.data["never-hit"].append(method_row("a.OrderView", "copy"))
        res = self.run_check(2)
        self.assertEqual(res["status"], "fail")
        self.assertIn("must be in no finding", self.evidence(res))

    def test_1_a_time_limited_class_entry_waits_for_its_time(self):
        res = self.run_check(1)
        self.assertEqual(res["status"], "pass")
        self.assertIn("not due", res["summary"])
        res = self.run_check(1, now=T0 + 101 * 3600)
        self.assertEqual(res["status"], "fail")
        ev = self.evidence(res)
        self.assertIn("a.Late", ev)
        self.assertIn("removed", ev)

    def test_1_a_framework_dependency_is_asserted_like_any_other(self):
        self.api.data["dependencies"][1]["status"] = "used"
        res = self.run_check(1)
        self.assertEqual(res["status"], "fail")
        self.assertIn("annotations", self.evidence(res))

    def test_2_spring_outside_routes_are_not_false_claims(self):
        res = self.run_check(2)
        self.assertEqual(res["status"], "pass", res)

    def test_3_counts_pass(self):
        res = self.run_check(3)
        self.assertEqual(res["status"], "pass", res)

    def test_3_counts_fail(self):
        self.api.data["endpoints"][1]["calls_total"] = 5000
        res = self.run_check(3)
        self.assertEqual(res["status"], "fail")
        self.assertIn("calls_total 5000", self.evidence(res))

    def test_3_kill_widens_the_tolerance_by_one_flush(self):
        self.api.data["endpoints"][1]["calls_total"] = 2000 - 300
        res = self.run_check(3)
        self.assertEqual(res["status"], "pass", res)

    def test_4_runs_pass(self):
        res = self.run_check(4)
        self.assertEqual(res["status"], "pass", res)

    def test_4_runs_fail(self):
        self.api.data["instances"][2]["ended_cleanly"] = True  # shop-2-g001, killed with -9
        res = self.run_check(4)
        self.assertEqual(res["status"], "fail")
        self.assertIn("shop-2-g001", self.evidence(res))

    def test_4_a_graceful_stop_that_reads_unclean_fails(self):
        self.api.data["instances"][0]["ended_cleanly"] = False
        res = self.run_check(4)
        self.assertEqual(res["status"], "fail")
        self.assertIn("shop-1-g001", self.evidence(res))

    def test_4_a_crash_fails(self):
        self.w.state["runs"][1].update(ended=NOW - 3600, how="crash", exit=1)
        self.w.save()
        res = self.run_check(4)
        self.assertEqual(res["status"], "fail")
        self.assertIn("exited on its own", self.evidence(res))

    def test_5_memory_pass(self):
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)

    def test_5_memory_fail(self):
        self.w.memory(grow_shop1=60.0)
        res = self.run_check(5)
        self.assertEqual(res["status"], "fail")
        self.assertIn("shop-1 (pid 11)", self.evidence(res))

    def mem(self, *series):
        rows = ["ts,instance,pid,generation,rss_kb,heap_used_kb"]
        for name, pid, start, grow in series:
            for i in range(12):
                rows.append("%s,%s,%d,1,%d,50000" % (iso(start + i * 600), name, pid, int((400 + grow * i / 6.0) * 1024)))
        write(self.w.p["memory"], "\n".join(rows) + "\n")

    def test_5_an_events_process_is_judged_against_events_control(self):
        b = T0 + 86400
        self.mem(("shop-control", 10, b, 0), ("shop-1", 11, b, 0), ("events-control", 30, b, 60), ("events-prod", 21, b, 60))
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("events-prod (pid 21)", self.evidence(res))
        self.assertIn("events-control (pid 30)", self.evidence(res))
        self.assertNotIn("Ktor", self.evidence(res))

    def test_5_an_events_process_that_outgrows_its_control_fails(self):
        b = T0 + 86400
        self.mem(("shop-control", 10, b, 60), ("shop-1", 11, b, 60), ("events-control", 30, b, 0), ("events-prod", 21, b, 60))
        res = self.run_check(5)
        self.assertEqual(res["status"], "fail")
        self.assertIn("events-prod (pid 21)", self.evidence(res))
        self.assertIn("events-control (pid 30)", self.evidence(res))
        self.assertNotIn("shop-1 (pid 11)", "\n".join(e for e in res["evidence"] if "above the allowance" in e))

    def test_5_a_shop_process_is_judged_against_shop_control(self):
        b = T0 + 86400
        self.mem(("shop-control", 10, b, 0), ("shop-1", 11, b, 60), ("events-control", 30, b, 60), ("events-prod", 21, b, 60))
        res = self.run_check(5)
        self.assertEqual(res["status"], "fail")
        bad = [e for e in res["evidence"] if "above the allowance" in e]
        self.assertEqual(len(bad), 1, bad)
        self.assertIn("shop-1 (pid 11)", bad[0])
        self.assertIn("shop-control (pid 10)", bad[0])

    def test_5_the_control_process_started_closest_is_used(self):
        b = T0 + 86400
        self.mem(("shop-control", 10, b, 0), ("shop-1", 11, b, 0), ("events-prod", 21, b, 60),
                 ("events-control", 30, b + 600, 60), ("events-control", 31, b + 30000, 0))
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("events-control (pid 30)", self.evidence(res))
        self.assertNotIn("events-control (pid 31)", self.evidence(res))
        self.mem(("shop-control", 10, b, 0), ("shop-1", 11, b, 0), ("events-prod", 21, b, 60),
                 ("events-control", 30, b + 600, 0), ("events-control", 31, b + 30000, 60))
        res = self.run_check(5)
        self.assertEqual(res["status"], "fail")
        self.assertIn("events-control (pid 30)", self.evidence(res))

    def test_5_a_missing_events_control_leaves_shop_judged(self):
        b = T0 + 86400
        self.mem(("shop-control", 10, b, 0), ("shop-1", 11, b, 0), ("events-prod", 21, b, 60))
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)
        ev = self.evidence(res)
        self.assertIn("events-prod (pid 21)", ev)
        self.assertIn("events-control has no process", ev)
        self.assertIn("too few to judge", ev)
        self.assertIn("shop-control (pid 10)", ev)

    def test_5_compares_only_the_ages_the_control_covers(self):
        b = T0 + 86400
        rows = ["ts,instance,pid,generation,rss_kb,heap_used_kb"]
        for i in range(12):
            rows.append("%s,events-staging-control,30,1,%d,50000" % (iso(b + i * 600), 400 * 1024))
        for i in range(30):
            rss = 400 if i < 12 else 400 + 20 * (i - 11)
            rows.append("%s,events-staging,22,1,%d,50000" % (iso(b + i * 600), rss * 1024))
        write(self.w.p["memory"], "\n".join(rows) + "\n")
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)
        ev = self.evidence(res)
        self.assertIn("events-staging (pid 22): ages 0.5h to 1.8h", ev)
        self.assertIn("events-staging (pid 22): ages 2.0h to 4.8h not compared, since events-staging-control (pid 30) "
                      "covers only ages 0.5h to 1.8h", ev)
        self.assertIn("1 age spans not compared", res["summary"])

    def test_5_a_one_sample_tail_is_not_listed(self):
        b = T0 + 86400
        rows = ["ts,instance,pid,generation,rss_kb,heap_used_kb"]
        for i in range(12):
            rows.append("%s,events-staging-control,30,1,%d,50000" % (iso(b + 5 + i * 600), 400 * 1024))
            rows.append("%s,events-staging,22,1,%d,50000" % (iso(b + i * 600 + (600 if i == 11 else 0)), 400 * 1024))
        write(self.w.p["memory"], "\n".join(rows) + "\n")
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)
        self.assertNotIn("not compared", self.evidence(res))

    def test_5_events_staging_is_judged_against_its_own_control(self):
        b = T0 + 86400
        self.mem(("events-control", 30, b, 0), ("events-staging-control", 32, b, 60), ("events-staging", 22, b, 60))
        res = self.run_check(5)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("events-staging-control (pid 32)", self.evidence(res))

    def test_3_a_failed_disruption_fails(self):
        self.w.state["events"][2]["exit"] = 1
        self.w.state["events"].append({"kind": "server-restart", "instance": None, "t0": T0 + 30 * 3600,
                                       "t1": T0 + 30 * 3600 + 60, "exit": 0})
        self.w.save()
        res = self.run_check(3)
        self.assertEqual(res["status"], "fail")
        self.assertIn("collector-outage did not run as planned: stack.sh stop-collector exited 1", self.evidence(res))
        self.assertNotIn("server-restart did not run", self.evidence(res))
        self.assertIn("1 planned disruptions did not run as planned", res["summary"])

    def test_3_a_skipped_collector_stop_fails(self):
        self.w.state.setdefault("done", {})["collector-stop"] = {"t": T0 + 42 * 3600, "note": "skipped, late"}
        self.w.save()
        res = self.run_check(3)
        self.assertEqual(res["status"], "fail")
        self.assertIn("the planned collector-stop did not run: skipped, late", self.evidence(res))

    def test_3_a_stack_timeout_fails(self):
        self.w.state["events"][2]["start_exit"] = "timeout"
        self.w.save()
        res = self.run_check(3)
        self.assertEqual(res["status"], "fail")
        self.assertIn("stack.sh start-collector exited timeout", self.evidence(res))

    def test_5_age_counts_from_the_recorded_start(self):
        b = T0 + 86400
        self.mem(("shop-control", 10, b, 0), ("shop-1", 11, b, 0))
        for name, pid in (("shop-1", 11), ("shop-control", 10)):
            self.w.state["runs"].append({"instance": name, "instance_id": "%s-g009" % name, "pid": pid, "service": None,
                                         "env": None, "started": b - 600, "ended": None, "how": None, "exit": None})
        self.w.save()
        res = self.run_check(5)
        self.assertIn("shop-1 (pid 11): ages 0.5h to 2.0h", self.evidence(res))

    def test_5_skips_only_when_no_process_is_judged(self):
        b = T0 + 86400
        self.mem(("events-prod", 21, b, 60))
        self.assertEqual(self.run_check(5)["status"], "skip")

    def test_6_logs_pass(self):
        warn = "%s WARNING: otherlode: delta export failed, will retry next flush\n" % iso(T0 + 20 * 3600 + 30)
        self.w.logs(extra_shop=warn)
        res = self.run_check(6)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("inside an allowlisted disruption window: 1", self.evidence(res))

    def test_6_an_error_line_fails(self):
        err = json.dumps({"time": iso(T0 + 500), "level": "ERROR", "msg": "boom"}) + "\n"
        self.w.logs(extra_server=err)
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("boom", self.evidence(res))

    def test_6_a_warning_outside_its_window_fails(self):
        warn = "%s WARNING: otherlode: delta export failed, will retry next flush\n" % iso(T0 + 5 * 3600)
        self.w.logs(extra_shop=warn)
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("not inside a collector-outage window", self.evidence(res))

    def test_6_a_warning_with_no_entry_fails(self):
        self.w.logs(extra_shop="%s SEVERE: otherlode: something new\n" % iso(T0 + 60))
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.w.logs(extra_shop="%s WARNING: otherlode: something new\n" % iso(T0 + 60))
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("no allowlist entry", self.evidence(res))

    SHIPPED = os.path.join(os.path.dirname(HERE), "log-allowlist.txt")

    def sink_rejected(self, t, reason="transport", kind="delta batch"):
        return json.dumps({"time": iso(t), "level": "ERROR", "msg": "sink rejected " + kind,
                           "error": "backend returned 503 Service Unavailable for service \"demo\" instance \"i1\"",
                           "reason": reason, "unlogged_sink_failures": 0}, separators=(",", ":")) + "\n"

    def server_restart(self, t0=50 * 3600):
        self.w.state["events"].append({"kind": "server-restart", "instance": None, "t0": T0 + t0, "t1": T0 + t0 + 20})
        self.w.save()
        write(self.w.p["allowlist"], read(self.SHIPPED))

    def test_6_a_sink_rejected_error_inside_the_server_restart_window_passes(self):
        self.server_restart()
        lines = "".join(self.sink_rejected(T0 + 50 * 3600 + 5, r, k)
                        for r in ("transport", "timeout")
                        for k in ("delta batch", "manifest", "static baseline", "class listing"))
        self.w.logs(extra_collector=lines)
        res = self.run_check(6)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("ERROR lines inside an allowlisted disruption window: 8", self.evidence(res))

    def test_6_a_sink_rejected_error_after_the_server_is_back_fails(self):
        self.server_restart()
        self.w.logs(extra_collector=self.sink_rejected(T0 + 50 * 3600 + 20 + 60, "timeout"))
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("not inside a server-restart window", self.evidence(res))

    def test_6_a_sink_rejected_server_error_fails_inside_the_restart(self):
        self.server_restart()
        self.w.logs(extra_collector=self.sink_rejected(T0 + 50 * 3600 + 5, "server_error"))
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_a_panic_while_serving_is_an_error(self):
        self.server_restart()
        panic = "%s http: panic serving 127.0.0.1:51234: runtime error: invalid memory address\n" % iso(T0 + 50 * 3600 + 5)
        self.w.logs(extra_server=panic)
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("panic serving", self.evidence(res))

    def test_6_a_sink_rejected_error_outside_the_server_restart_window_fails(self):
        self.server_restart()
        self.w.logs(extra_collector=self.sink_rejected(T0 + 40 * 3600))
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("not inside a server-restart window", self.evidence(res))

    def test_6_a_sink_rejected_error_with_another_reason_fails(self):
        self.server_restart()
        self.w.logs(extra_collector=self.sink_rejected(T0 + 50 * 3600 + 5, "shutting_down"))
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_a_sink_rejected_error_needs_the_server_restart_window_not_another(self):
        self.w.state["events"].append({"kind": "collector-outage", "instance": None, "t0": T0 + 50 * 3600,
                                       "t1": T0 + 50 * 3600 + 20})
        self.w.save()
        write(self.w.p["allowlist"], read(self.SHIPPED))
        self.w.logs(extra_collector=self.sink_rejected(T0 + 50 * 3600 + 5))
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_an_error_with_no_error_entry_fails_inside_a_window(self):
        self.server_restart()
        err = json.dumps({"time": iso(T0 + 50 * 3600 + 5), "level": "ERROR", "msg": "boom"}) + "\n"
        self.w.logs(extra_server=err)
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("boom", self.evidence(res))

    def test_6_a_warn_entry_does_not_allow_an_error_line(self):
        self.server_restart()
        write(self.w.p["allowlist"], "server-restart \"msg\":\"sink rejected\n")
        self.w.logs(extra_collector=self.sink_rejected(T0 + 50 * 3600 + 5))
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_an_error_entry_does_not_allow_a_warn_line(self):
        self.server_restart()
        write(self.w.p["allowlist"], 'server-restart [ERROR] "msg":"sink refused\n')
        warn = json.dumps({"time": iso(T0 + 50 * 3600 + 5), "level": "WARN", "msg": "sink refused delta batch"}) + "\n"
        self.w.logs(extra_collector=warn)
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_the_shipped_allowlist_has_no_dropping_payload_entry_and_one_error_disruption(self):
        rules = check.load_allowlist(self.SHIPPED)
        self.assertFalse(any(rx.search('"msg":"dropping payload') for _, rx, _, _ in rules))
        self.assertEqual({k for k, _, _, lv in rules if lv == "ERROR"}, {"server-restart"})

    def test_6_a_shutdown_warning_stderr_line_inside_its_window_passes(self):
        t = T0 + 20 * 3600 + 30
        self.w.logs(extra_shop="%s Nov 02, 2026 2:12:28 AM dev.otherlode.shaded.export.ExportScheduler sendDeltaBatch\n"
                    "%s WARNING: otherlode: delta export failed, will retry next flush\n" % (iso(t), iso(t)))
        self.assertEqual(self.run_check(6)["status"], "pass")

    def test_6_a_shutdown_budget_warning_needs_a_disruption_window(self):
        write(self.w.p["allowlist"], read(self.SHIPPED))
        line = "%s WARNING: otherlode: a send of the early shutdown flush ran out of time\n"
        self.w.logs(extra_shop=line % iso(T0 + 5 * 3600))
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_the_hooks_still_running_warning_is_never_allowed(self):
        self.server_restart()
        write(self.w.p["allowlist"], read(self.SHIPPED))
        self.w.logs(extra_shop="%s WARNING: otherlode: the application's shutdown hooks were still running 18s into shutdown, "
                    "so the final flush runs now and code they run later will not be counted\n" % iso(T0 + 50 * 3600 + 5))
        self.assertEqual(self.run_check(6)["status"], "fail")

    def test_6_app_errors_are_not_judged(self):
        self.w.logs(extra_shop="%s 2026 ERROR 1 --- [nio] o.a.c.c.C.[.[.[/] : Servlet.service() threw\n" % iso(T0 + 400))
        res = self.run_check(6)
        self.assertEqual(res["status"], "pass", res)

    def test_7_test_run_pass(self):
        res = self.run_check(7)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("called_only_by_tests", self.evidence(res))

    def test_7_a_changed_finding_fails(self):
        self.w.state["testruns"][0]["after"] = {"soak-shop/prod": ["a", "b", "c"]}
        self.w.save()
        res = self.run_check(7)
        self.assertEqual(res["status"], "fail")
        self.assertIn("appeared: c", self.evidence(res))

    def test_7_a_missing_before_fingerprint_fails(self):
        self.w.state["testruns"][0]["before"] = None
        self.w.save()
        res = self.run_check(7)
        self.assertEqual(res["status"], "fail")
        self.assertIn("could not be read before the run", self.evidence(res))

    def test_7_a_window_with_a_planned_change_is_not_compared(self):
        run = self.w.state["testruns"][0]
        run["after_t"] = T0 + 8000
        run["after"] = {"soak-shop/prod": ["a", "b", "c"]}
        self.w.save()
        res = self.run_check(7)
        self.assertNotIn("appeared: c", self.evidence(res))
        self.assertIn("not compared", self.evidence(res))

    def test_7_a_cluster_not_called_by_tests_fails(self):
        self.api.data["unreached-clusters"][1]["root"]["root_kind"] = "uncalled"
        res = self.run_check(7)
        self.assertEqual(res["status"], "fail")
        self.assertIn("not called_only_by_tests", self.evidence(res))

    def test_7_the_driver_must_be_named(self):
        self.api.data["unreached-clusters"][1]["test_callers"] = [{"caller": {"class_name": "a.SomethingElse"}}]
        res = self.run_check(7)
        self.assertEqual(res["status"], "fail")
        self.assertIn("SoakTestDriver", self.evidence(res))

    def hold_the_target(self, started):
        self.w.state["runs"][1]["started"] = started
        self.w.save()
        self.api.data["never-hit"] = [r for r in self.api.data["never-hit"] if r.get("method_name") != "applyLegacyDiscount"]
        self.api.data["unreached-clusters"] = self.api.data["unreached-clusters"][:1]

    def test_7_a_starting_run_holds_the_cluster_and_the_never_hit_method_back(self):
        self.hold_the_target(NOW - 300)
        res = self.run_check(7)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("could be starting", self.evidence(res))

    def test_7_a_missing_cluster_fails_with_no_run_starting(self):
        self.hold_the_target(T0)
        res = self.run_check(7)
        self.assertEqual(res["status"], "fail")
        self.assertIn("no cluster rooted at a.Pricing.applyLegacyDiscount", self.evidence(res))

    def test_all_eight_and_the_report_files(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = check.main(["--now", "--home", self.tmp, "--api", self.api.url, "--lag", "1800", "--wait", "0",
                               "--at", str(NOW), "--out", os.path.join(self.tmp, "reports"), "--final"])
        self.assertEqual(code, 0, out.getvalue())
        self.assertTrue(os.path.exists(os.path.join(self.tmp, "reports", "final.md")))
        with open(os.path.join(self.tmp, "reports", "final.md")) as f:
            md = f.read()
        for n, name in check.NAMES.items():
            self.assertIn("## %d. %s: PASS" % (n, name), md)

    def test_final_turns_a_skip_into_a_fail(self):
        res = self.run_check(1, final=True, now=T0 + 600)
        self.assertEqual(res["status"], "fail")

    def test_the_read_key_never_reaches_a_report(self):
        self.run_check(1)
        for name in os.listdir(os.path.join(self.tmp, "reports")):
            with open(os.path.join(self.tmp, "reports", name)) as f:
                self.assertNotIn(KEY, f.read())


class WindowCheckTests(CheckCase):
    """Check 8, the window read, and what the other checks do with the new instances and disruptions."""

    def violation(self, **over):
        v = {"event": "start of events-prod-g002", "kind": "fast", "first": T0 + 7200, "last": T0 + 7210, "count": 3,
             "message": "soak-shop/prod: truth.json does not name: never-loaded a.Pricing"}
        v.update(over)
        return v

    def limit_event(self, t1=T0 + 60 * 3600 + 600):
        self.w.state["events"].append({"kind": "collector-limit", "instance": None, "t0": T0 + 60 * 3600, "t1": t1,
                                       "exit": 0, "start_exit": 0})
        self.w.save()

    def edit_truth(self, fn):
        t = copy.deepcopy(TRUTH)
        fn(t)
        write(self.w.p["truth"], json.dumps(t))

    def set_windows(self, **over):
        write(self.w.p["windows"], json.dumps(windows_file(**over)))

    def test_8_passes_with_the_counts_of_reads(self):
        res = self.run_check(8)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("40 live reads, 3 snapshot reads, 3 report reads, 12 windows opened", res["summary"])

    def test_8_fails_on_a_recorded_violation_and_lists_it(self):
        self.set_windows(violations={"k": self.violation()})
        res = self.run_check(8)
        self.assertEqual(res["status"], "fail")
        ev = self.evidence(res)
        self.assertIn("start of events-prod-g002", ev)
        self.assertIn("never-loaded a.Pricing", ev)
        self.assertIn("3 times", ev)
        self.assertIn("fast read", ev)

    def test_8_skips_before_any_read_and_fails_at_the_end(self):
        os.remove(self.w.p["windows"])
        self.assertEqual(self.run_check(8)["status"], "skip")
        self.assertEqual(self.run_check(8, final=True)["status"], "fail")

    def test_8_a_refused_manifest_must_read_as_counts_behind(self):
        self.limit_event()
        self.set_windows(refused={"reads": 20, "behind": 0, "first_behind": None, "last": []})
        res = self.run_check(8, now=AFTER_LIMIT)
        self.assertEqual(res["status"], "fail")
        self.assertIn("never read as counts behind", self.evidence(res))
        self.set_windows(refused={"reads": 20, "behind": 12, "first_behind": T0, "last": []})
        res = self.run_check(8, now=AFTER_LIMIT)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("counts behind in 12 of 20 reads", self.evidence(res))

    def test_8_no_read_of_shop_1_while_refused_fails(self):
        self.limit_event()
        res = self.run_check(8, now=AFTER_LIMIT)
        self.assertEqual(res["status"], "fail")
        self.assertIn("no read of shop-1", self.evidence(res))

    def test_8_a_refusal_under_way_is_not_judged(self):
        self.limit_event(t1=None)
        res = self.run_check(8)
        self.assertEqual(res["status"], "pass", res)

    def test_run_is_behind(self):
        self.assertTrue(check.run_is_behind({"deltas_received": False, "counts_behind_since": None}))
        self.assertTrue(check.run_is_behind({"deltas_received": True, "counts_behind_since": "2026-01-01T00:00:00Z"}))
        self.assertFalse(check.run_is_behind({"deltas_received": True, "counts_behind_since": None}))
        self.assertFalse(check.run_is_behind(None))

    def read(self, kind, now=NOW, truth=None):
        truth = json.loads(json.dumps(truth or TRUTH))
        return check.read_window(check.Api(self.api.url, KEY), truth, {"testruns": []}, now, T0, 1, FLUSH, 1800, kind)

    def test_a_live_read_passes_judges_and_asks_for_the_live_view(self):
        violations, errors, reports = self.read("fast")
        self.assertEqual((violations, errors, reports), ([], [], 0))
        self.assertTrue(self.api.requests)
        self.assertTrue(all(q.get("active_within_days") == "36500" for path, q in self.api.requests if "/report" not in path))

    def test_a_snapshot_read_reads_the_report_and_not_the_live_view(self):
        violations, errors, reports = self.read("slow")
        self.assertEqual((violations, errors, reports), ([], [], 1))
        self.assertTrue(any(path.endswith("/report") for path, _ in self.api.requests))
        self.assertTrue(all("active_within_days" not in q for _, q in self.api.requests))

    def test_a_window_read_catches_a_false_never_loaded_claim_from_t0(self):
        self.api.data["never-loaded"].append({"class_name": "a.Pricing"})
        violations, _, _ = self.read("fast", now=T0 + 90)
        self.assertEqual(len(violations), 1, violations)
        self.assertIn("truth.json does not name: never-loaded a.Pricing", violations[0])

    def test_a_live_window_read_judges_removed_code_as_truth_stood_before_the_removal(self):
        truth = copy.deepcopy(TRUTH)
        shop = truth["services"]["soak-shop"]["prod"]
        shop["removed"] = [{"kind": "class", "class": "a.Legacy", "from": "PT20H"}, {"kind": "class", "class": "a.Gone", "from": "PT20H"}]
        self.api.data["never-loaded"].append({"class_name": "a.Gone", "methods": ["m"]})
        violations, _, _ = self.read("fast", truth=truth)
        self.assertFalse(any("a.Legacy" in v for v in violations), "truth named it never loaded before the removal")
        self.assertTrue(any("a.Gone" in v for v in violations), "truth never named it, before or after")

    def test_a_never_loaded_row_for_a_class_that_fails_later_is_explained_only_until_the_lag_after_it(self):
        truth = json.loads(json.dumps(TRUTH))
        lists = truth["services"]["soak-shop"]["prod"]
        lists["never_loaded"].append({"class": "a.Cfg", "until": "PT5M", "note": "n"})
        lists["failed_to_initialize"].append({"class": "a.Cfg", "from": "PT5M"})
        self.api.data["never-loaded"].append({"class_name": "a.Cfg"})
        violations, _, _ = self.read("slow", now=T0 + 302, truth=truth)
        self.assertFalse(any("a.Cfg" in v for v in violations), violations)
        violations, _, _ = self.read("slow", now=T0 + 300 + 1800 + 60, truth=truth)
        self.assertTrue(any("a.Cfg" in v for v in violations), violations)

    def test_an_early_window_read_judges_only_never_loaded_and_dependency_rows(self):
        self.api.data["never-hit"].append({"kind": "method", "class_name": "a.BrokenController", "method_name": "broken"})
        self.api.data["never-loaded"].append({"class_name": "a.Stray", "methods": ["m"]})
        violations, _, _ = self.read("fast", now=T0 + 120)
        self.assertFalse(any("BrokenController" in v for v in violations), violations)
        self.assertTrue(any("a.Stray" in v for v in violations), violations)

    def test_8_fails_when_more_than_a_tenth_of_the_reads_failed(self):
        self.set_windows(reads={"fast": 40, "slow": 3, "report": 3, "errors": 5})
        res = self.run_check(8)
        self.assertEqual(res["status"], "fail")
        self.assertIn("5 of 43 window reads failed", self.evidence(res))
        self.set_windows(reads={"fast": 40, "slow": 3, "report": 3, "errors": 4})
        self.assertEqual(self.run_check(8)["status"], "pass")

    def test_a_window_read_leaves_planted_answers_to_the_six_hourly_check(self):
        self.api.data["never-loaded"] = []
        violations, _, _ = self.read("fast")
        self.assertFalse(any("a.Legacy" in v for v in violations), violations)

    def test_a_window_read_holds_a_dependency_to_its_status(self):
        self.api.data["dependencies"][0]["status"] = "unreferenced"
        violations, _, _ = self.read("fast")
        self.assertTrue(any("commons-csv" in v and "unreferenced" in v for v in violations), violations)
        out = io.StringIO()
        with redirect_stdout(out):
            res = self.run_check(2)
        self.assertEqual(res["status"], "pass", "check 2 keeps its rule")

    def test_a_failed_read_is_an_error_and_not_a_violation(self):
        self.api.data.pop("never-loaded")
        violations, errors, _ = check.read_window(check.Api("http://127.0.0.1:1", KEY), json.loads(json.dumps(TRUTH)),
                                                   {"testruns": []}, NOW, T0, 1, FLUSH, 1800, "fast")
        self.assertEqual(violations, [])
        self.assertTrue(errors)

    def test_violation_key_ignores_counts(self):
        self.assertEqual(check.violation_key("x: not on /never-hit (12 method rows listed)"),
                         check.violation_key("x: not on /never-hit (13 method rows listed)"))

    def test_1_a_live_method_must_leave_never_hit(self):
        self.edit_truth(lambda t: t["services"]["soak-shop"]["prod"].update(
            live_methods=[{"class": "a.Pricing", "method": "applyLegacyDiscount", "from": "PT12H"}]))
        res = self.run_check(1, now=T0 + 5 * 3600)
        self.assertEqual(res["status"], "pass", res)
        res = self.run_check(1)
        self.assertEqual(res["status"], "fail")
        self.assertIn("live method a.Pricing.applyLegacyDiscount: on /never-hit", self.evidence(res))

    def test_1_a_dependency_site_is_asserted(self):
        self.edit_truth(lambda t: t["services"]["soak-shop"]["prod"]["dependencies"][0].update(site_class="a.Legacy"))
        res = self.run_check(1)
        self.assertEqual(res["status"], "fail")
        self.assertIn("no site in a.Legacy", self.evidence(res))
        self.api.data["dependencies"][0]["sites"] = [{"class_name": "a.Legacy"}]
        self.assertEqual(self.run_check(1)["status"], "pass")

    def test_4_a_killed_events_crash_run_is_not_asserted(self):
        self.w.state["runs"].append({"instance": "events-crash", "instance_id": "events-crash-g001",
                                     "service": "soak-shop", "env": "prod", "started": T0 + 20 * 3600,
                                     "ended": T0 + 20 * 3600 + 30, "how": "kill9", "exit": -9})
        self.w.save()
        res = self.run_check(4)
        self.assertEqual(res["status"], "pass", res)
        self.assertIn("events-crash-g001: ended as kill9 before its first flush; not asserted", self.evidence(res))

    def test_4_an_events_crash_process_that_exits_alone_fails(self):
        self.w.state["runs"].append({"instance": "events-crash", "instance_id": "events-crash-g001",
                                     "service": "soak-shop", "env": "prod", "started": T0 + 20 * 3600,
                                     "ended": T0 + 20 * 3600 + 30, "how": "crash", "exit": 1})
        self.w.save()
        self.assertEqual(self.run_check(4)["status"], "fail")

    def test_3_a_failed_collector_limit_command_fails(self):
        self.limit_event()
        self.w.state["events"][-1].update(exit=1, start_exit="timeout")
        self.w.save()
        res = self.run_check(3)
        self.assertEqual(res["status"], "fail")
        self.assertIn("stack.sh collector-limit low exited 1", self.evidence(res))
        self.assertIn("stack.sh collector-limit normal exited timeout", self.evidence(res))

    def test_3_a_skipped_low_limit_fails(self):
        self.w.state.setdefault("done", {})["collector-low-limit"] = {"t": T0, "note": "skipped, late"}
        self.w.save()
        self.assertEqual(self.run_check(3)["status"], "fail")

    def test_3_the_tolerance_grows_by_a_flush_for_the_limit(self):
        base = check.traffic_sum(check.Ctx(paths=self.w.p, state=self.w.state, flush=FLUSH), "soak-shop", "prod",
                                 "GET", "/orders/{id}")[3]
        self.limit_event()
        more = check.traffic_sum(check.Ctx(paths=self.w.p, state=self.w.state, flush=FLUSH), "soak-shop", "prod",
                                 "GET", "/orders/{id}")[3]
        self.assertGreater(more, base)

    def test_6_a_warning_of_the_refused_manifest_is_allowed_inside_its_window(self):
        write(self.w.p["allowlist"], "collector-limit otherlode: manifest export failed, will retry next flush\n")
        self.limit_event()
        inside = "%s WARNING: otherlode: manifest export failed, will retry next flush\n" % iso(T0 + 60 * 3600 + 120)
        self.w.logs(extra_shop=inside)
        res = self.run_check(6)
        self.assertEqual(res["status"], "pass", res)
        outside = "%s WARNING: otherlode: manifest export failed, will retry next flush\n" % iso(T0 + 40 * 3600)
        self.w.logs(extra_shop=outside)
        res = self.run_check(6)
        self.assertEqual(res["status"], "fail")
        self.assertIn("not inside a collector-limit window", self.evidence(res))

    def test_6_the_shipped_allowlist_names_the_refused_manifest(self):
        rules = check.load_allowlist(os.path.join(os.path.dirname(HERE), "log-allowlist.txt"))
        self.assertTrue(any(k == "collector-limit" and rx.search('"msg":"refusing manifest with too many elements"')
                            for k, rx, _, _ in rules))
        self.assertTrue(any(k == "collector-limit" and rx.search("otherlode: unexpected status 413 from http://x")
                            for k, rx, _, _ in rules))

    def test_a_crash_cycle_holds_check_1_until_its_live_deadline_plus_the_lag(self):
        kill = T0 + 20 * 3600 + 30
        self.w.state["events"].append({"kind": "crash-cycle", "instance": "events-crash", "t0": T0 + 20 * 3600, "t1": kill})
        inst = cfg.BY_NAME["events-crash"]
        end = kill + 3 * (cfg.fixed_flush(inst, 1) or FLUSH) + 1800
        ctx = check.Ctx(state=self.w.state, now=end - 1, t0=T0, scale=1, flush=FLUSH, lag=1800)
        self.assertTrue(check.crash_hold(ctx, inst.service, inst.env))
        ctx.now = end + 1
        self.assertFalse(check.crash_hold(ctx, inst.service, inst.env))

    def test_a_starting_run_holds_check_1_for_data_read_while_it_could_be_starting(self):
        inst = cfg.BY_NAME["shop-1"]
        flush = cfg.fixed_flush(inst, 1) or FLUSH
        start = T0 + 36 * 3600
        self.w.state["runs"].append({"instance": "shop-1", "started": start, "ended": None})
        end = start + check.JVM_BOOT_ALLOWANCE + (check.STARTING_CAP_INTERVALS + 1) * flush
        ctx = check.Ctx(state=self.w.state, now=end + 3600, t0=T0, scale=1, flush=FLUSH, lag=1800)
        self.assertTrue(check.start_hold(ctx, inst.service, inst.env, end - 1))
        self.assertFalse(check.start_hold(ctx, inst.service, inst.env, end + 1))
        self.assertFalse(check.start_hold(ctx, inst.service, inst.env, start - 1))
        self.assertFalse(check.start_hold(ctx, "soak-events", "prod", end - 1))

    def test_a_run_that_stopped_while_starting_holds_until_three_intervals_after_it_stopped(self):
        inst = cfg.BY_NAME["shop-1"]
        flush = cfg.fixed_flush(inst, 1) or FLUSH
        start = T0 + 36 * 3600
        killed = {"instance": "shop-1", "started": start, "ended": start + 30, "how": "kill9"}
        self.w.state["runs"].append(killed)
        ctx = check.Ctx(state=self.w.state, now=start + 3600, t0=T0, scale=1, flush=FLUSH, lag=1800)
        self.assertTrue(check.start_hold(ctx, inst.service, inst.env, start + 30 + 3 * flush - 1))
        self.assertFalse(check.start_hold(ctx, inst.service, inst.env, start + 30 + 3 * flush + 1))
        killed["how"] = "graceful"
        self.assertTrue(check.start_hold(ctx, inst.service, inst.env, start + 30 + 3 * flush - 1),
                        "a graceful stop whose closing batch never arrived stays live as long as a kill")

    def test_6_a_crash_cycle_opens_a_kill_window(self):
        self.w.state["events"].append({"kind": "crash-cycle", "instance": "events-crash", "t0": T0 + 20 * 3600,
                                       "t1": T0 + 20 * 3600 + 30})
        ctx = check.Ctx(state=self.w.state, now=NOW, t0=T0, scale=1, flush=FLUSH)
        a, b = check.disruption_windows(ctx)["kill-9"][-1]
        self.assertLess(a, T0 + 20 * 3600)
        self.assertGreater(b, T0 + 20 * 3600 + 30)


class TruthTimingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(os.path.dirname(os.path.dirname(HERE)), "truth.json")) as f:
            cls.truth = json.load(f)
        cls.shop = cls.truth["services"]["soak-shop"]["prod"]
        cls.events = cls.truth["services"]["soak-events"]["prod"]

    def entry(self, lists, key, cls, method=None):
        found = [e for e in lists[key] if isinstance(e, dict) and e.get("class") == cls and e.get("method") == method]
        self.assertEqual(len(found), 1, (key, cls, method))
        return found[0]

    def test_the_shutdown_audit_is_never_hit_until_the_first_graceful_restart_and_live_after(self):
        for cls, m in (("dev.soaktest.shop.ShutdownAudit", "record"), ("dev.soaktest.shop.ShutdownHook", "close")):
            never = self.entry(self.shop, "never_hit_methods", cls, m)
            live = self.entry(self.shop, "live_methods", cls, m)
            self.assertEqual(cfg.parse_duration(never["until"]), 12 * 3600)
            self.assertEqual(cfg.parse_duration(live["from"]), 12 * 3600)
            self.assertNotIn("until", live)
        first = min(i.at for i in cfg.build_schedule() if i.kind == "graceful-restart")
        self.assertEqual(first, 12 * 3600)

    def test_the_shutdown_hook_roots_a_cluster_that_holds_record_until_then(self):
        root = self.entry(self.shop, "unreached_cluster_roots", "dev.soaktest.shop.ShutdownHook", "close")
        self.assertEqual(root["members_include"], ["dev.soaktest.shop.ShutdownAudit#record"])
        self.assertEqual(cfg.parse_duration(root["until"]), 12 * 3600)

    def test_the_codec_jar_is_unreached_from_the_upgrade(self):
        dep = [d for d in self.events["dependencies"] if d["artifact"] == "commons-codec"]
        self.assertEqual(len(dep), 1)
        self.assertEqual(dep[0]["status"], "unreached")
        self.assertEqual(dep[0]["site_class"], "dev.soaktest.events.LegacyDigest")
        upgrade = {i.id: i for i in cfg.build_schedule()}["upgrade-events-prod"].at
        self.assertEqual(cfg.parse_duration(dep[0]["from"]), upgrade)
        never = [e for e in self.events["never_loaded"] if isinstance(e, dict) and e["class"].endswith("LegacyDigest")]
        self.assertEqual(cfg.parse_duration(never[0]["from"]), upgrade)
        staging = self.truth["services"]["soak-events"]["staging"]
        self.assertEqual(staging["dependencies"], [])

    def test_the_annotation_jar_is_unreached_until_the_first_graceful_restart_and_used_after(self):
        dep = [d for d in self.shop["dependencies"] if d["artifact"] == "jakarta.annotation-api"]
        self.assertEqual(sorted(d["status"] for d in dep), ["unreached", "used"])
        unreached = next(d for d in dep if d["status"] == "unreached")
        used = next(d for d in dep if d["status"] == "used")
        first = min(i.at for i in cfg.build_schedule() if i.kind == "graceful-restart")
        self.assertEqual(cfg.parse_duration(unreached["until"]), first)
        self.assertEqual(cfg.parse_duration(used["from"]), first)
        self.assertEqual(unreached["site_class"], "dev.soaktest.shop.ShutdownHook")
        ctx = check.Ctx(truth=self.truth, now=T0, t0=T0, scale=1, lag=1800)
        for at in range(0, 24 * 3600, 600):
            ctx.now = T0 + at
            self.assertFalse(ctx.asserted(unreached) and ctx.asserted(used), at)

    def test_broken_config_reads_never_loaded_until_the_broken_request_and_failed_after(self):
        entry = next(e for e in self.shop["never_loaded"]
                     if isinstance(e, dict) and e["class"] == "dev.soaktest.shop.BrokenConfig")
        broken = next(o for o in cfg.ONE_OFFS if o.id == "broken").at
        self.assertEqual(cfg.parse_duration(entry["until"]), broken)
        self.assertTrue(entry["note"])
        failed = next(e for e in self.shop["failed_to_initialize"] if e["class"] == "dev.soaktest.shop.BrokenConfig")
        self.assertEqual(cfg.parse_duration(failed["from"]), broken)
        lag = 1800
        ctx = check.Ctx(truth=self.truth, now=T0 + broken + 2, t0=T0, scale=1, lag=lag)
        self.assertFalse(ctx.asserted(entry))
        self.assertTrue(ctx.may_explain(entry))
        for at in range(0, int(broken + lag), 30):
            ctx.now = T0 + at
            self.assertFalse(ctx.asserted(entry) and ctx.asserted(failed), at)
        ctx.now = T0 + broken + lag + 60
        self.assertFalse(ctx.may_explain(entry))

    def test_the_new_entries_are_ones_the_checker_understands(self):
        ctx = check.Ctx(truth=self.truth, now=T0 + 80 * 3600, t0=T0, scale=1, lag=1800)
        live = self.shop["live_methods"][0]
        self.assertFalse(ctx.asserted(live, 0) is False)
        self.assertTrue(ctx.may_explain(self.entry(self.shop, "never_hit_methods", "dev.soaktest.shop.ShutdownAudit", "record")) is False)
        ctx.now = T0 + 11 * 3600
        self.assertTrue(ctx.may_explain(self.entry(self.shop, "never_hit_methods", "dev.soaktest.shop.ShutdownAudit", "record")))
        self.assertFalse(ctx.asserted(live))


class FingerprintTests(unittest.TestCase):
    def test_diff(self):
        a = {"s/p": ["x", "y"]}
        b = {"s/p": ["y", "z"]}
        self.assertEqual(check.fingerprint_diff(a, a), [])
        self.assertEqual(check.fingerprint_diff(a, b), ["s/p: appeared: z", "s/p: went away: x"])

    def test_fingerprint_reads_live(self):
        api = FakeApi()
        try:
            d = tempfile.mkdtemp(prefix="soak-test-")
            self.addCleanup(shutil.rmtree, d, True)
            write(os.path.join(d, "truth.json"), json.dumps(TRUTH))
            fp = check.fingerprint(check.Api(api.url, KEY), os.path.join(d, "truth.json"), wait=0)
            self.assertIn("soak-shop/prod", fp)
            self.assertTrue(any("applyLegacyDiscount" in k for k in fp["soak-shop/prod"]))
            self.assertTrue(all(q.get("active_within_days") == "36500" for _, q in api.requests))
        finally:
            api.close()


class FingerprintKeyTests(unittest.TestCase):
    def cluster(self, cls, meth, kind):
        return check.Finding("cluster", "cluster %s.%s" % (cls, meth), cls, meth, status=kind)

    def test_the_planned_root_reads_the_same_before_and_after_a_test_run(self):
        tested = {("dev.soaktest.shop.Pricing", "applyLegacyDiscount")}
        before = check.fingerprint_key(self.cluster("dev.soaktest.shop.Pricing", "applyLegacyDiscount", "uncalled"), tested)
        after = check.fingerprint_key(
            self.cluster("dev.soaktest.shop.Pricing", "applyLegacyDiscount", "called_only_by_tests"), tested)
        self.assertEqual(before, after)

    def test_another_root_changing_kind_is_a_change(self):
        tested = {("dev.soaktest.shop.Pricing", "applyLegacyDiscount")}
        before = check.fingerprint_key(self.cluster("dev.soaktest.shop.AdminController", "rebuild", "uncalled"), tested)
        after = check.fingerprint_key(
            self.cluster("dev.soaktest.shop.AdminController", "rebuild", "called_only_by_tests"), tested)
        self.assertNotEqual(before, after)
        self.assertNotEqual(check.fingerprint_key(self.cluster("dev.soaktest.shop.AdminController", "rebuild",
                                                               "reached_from_hit"), tested), before)

    def test_test_roots_read_truth(self):
        lists = {"unreached_cluster_roots": [
            {"class": "x.Pricing", "method": "applyLegacyDiscount", "called_only_by_tests": {"from": "PT2H"}},
            {"class": "x.AdminController", "method": "rebuild"}]}
        self.assertEqual(check.test_roots(lists), {("x.Pricing", "applyLegacyDiscount")})


class ParseTests(unittest.TestCase):
    def test_durations(self):
        self.assertEqual(cfg.parse_duration("PT0S"), 0)
        self.assertEqual(cfg.parse_duration("PT26H"), 26 * 3600)
        self.assertEqual(cfg.parse_duration("P4D"), 4 * 86400)
        self.assertEqual(cfg.parse_duration("P1DT2H30M"), 86400 + 7200 + 1800)
        self.assertIsNone(cfg.parse_duration(None))
        with self.assertRaises(ValueError):
            cfg.parse_duration("26h")

    def test_classify_the_shutdown_stderr_format(self):
        head = "2026-11-02T02:12:28.411Z Nov 02, 2026 2:12:28 AM dev.otherlode.shaded.export.ExportScheduler sendDeltaBatch"
        ts, lv, _, _ = check.classify(head)
        self.assertIsNone(lv)
        self.assertIsNotNone(ts)
        ts, lv, agent, text = check.classify(
            "2026-11-02T02:12:28.412Z WARNING: otherlode: delta export failed, will retry next flush")
        self.assertEqual((lv, agent), ("WARN", True))
        self.assertEqual(ts, check.parse_ts("2026-11-02T02:12:28.412Z"))
        self.assertTrue(text.startswith("WARNING: otherlode:"))
        ts, lv, agent, _ = check.classify(
            "2026-11-02T02:12:28.413Z SEVERE: otherlode: failed to send the closing batch\n")
        self.assertEqual((lv, agent), ("ERROR", True))

    def test_classify_a_collector_sink_rejected_line(self):
        ts, lv, agent, _ = check.classify(
            '{"time":"2026-11-02T02:00:00Z","level":"ERROR","msg":"sink rejected delta batch","reason":"timeout"}')
        self.assertEqual((lv, agent), ("ERROR", False))
        self.assertIsNotNone(ts)

    def test_classify(self):
        ts, lv, agent, _ = check.classify('2026-11-01T10:00:00.000Z WARNING: otherlode: ignoring unknown agent option')
        self.assertEqual((lv, agent), ("WARN", True))
        self.assertIsNotNone(ts)
        _, lv, agent, _ = check.classify('2026-11-01T10:00:00.000Z SEVERE: otherlode: exportUrl must be absolute')
        self.assertEqual((lv, agent), ("ERROR", True))
        _, lv, agent, _ = check.classify('2026-11-01T10:00:00.000Z 12:00 ERROR dev.otherlode.soak.shop.X - boom')
        self.assertEqual((lv, agent), ("ERROR", False))
        _, lv, agent, _ = check.classify('{"time":"2026-11-01T10:00:00Z","level":"WARN","msg":"x"}')
        self.assertEqual(lv, "WARN")
        _, lv, _, _ = check.classify("2026-11-01T10:00:00.000Z OpenJDK 64-Bit Server VM warning: Sharing is only supported")
        self.assertEqual(lv, "WARN")
        _, lv, _, _ = check.classify("2026-11-01T10:00:00.000Z Nov 01, 2026 6:09:02 PM dev.otherlode.config.AgentConfig parse")
        self.assertIsNone(lv)


if __name__ == "__main__":
    unittest.main()
