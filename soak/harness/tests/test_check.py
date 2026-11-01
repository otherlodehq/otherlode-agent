"""Each of the seven checks, passing and failing once, against a fake read API and a toy truth file.

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

    def logs(self, extra_server="", extra_shop=""):
        info = json.dumps({"time": iso(T0 + 100), "level": "INFO", "msg": "listening"})
        write(os.path.join(self.p["logs"], "server.log"), info + "\n" + extra_server)
        write(os.path.join(self.p["logs"], "collector.log"), info + "\n")
        shop = "%s Spring started\n%s INFO: otherlode: installing endpoint modules\n" % (iso(T0 + 50), iso(T0 + 51)) + extra_shop
        write(os.path.join(self.p["logs"], "shop-1.log"), shop)


class CheckTests(unittest.TestCase):
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

    # ---- 1
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

    # ---- 2
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

    # ---- 3
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

    # ---- 4
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

    # ---- 5
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

    # ---- 6
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

    def test_6_app_errors_are_not_judged(self):
        self.w.logs(extra_shop="%s 2026 ERROR 1 --- [nio] o.a.c.c.C.[.[.[/] : Servlet.service() threw\n" % iso(T0 + 400))
        res = self.run_check(6)
        self.assertEqual(res["status"], "pass", res)

    # ---- 7
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

    # ---- the whole run
    def test_all_seven_and_the_report_files(self):
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
