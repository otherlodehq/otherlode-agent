"""The supervisor's timetable, its dry run and its small parsers."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HARNESS = os.path.dirname(HERE)
sys.path.insert(0, HARNESS)
import check  # noqa: E402
import soak  # noqa: E402
import soakcfg as cfg  # noqa: E402


class ScheduleTests(unittest.TestCase):
    def setUp(self):
        self.items = {i.id: i for i in cfg.build_schedule()}

    def at_hours(self, id):
        return self.items[id].at / 3600.0

    def test_the_times_of_design_md(self):
        self.assertEqual(self.items["oneoff-broken"].at, 300)
        self.assertEqual(self.items["oneoff-yearly"].at, 600)
        self.assertEqual(self.at_hours("kill-shop-2"), 30)
        self.assertEqual(self.items["start-shop-2"].at - self.items["kill-shop-2"].at, 120)
        self.assertEqual(self.at_hours("collector-stop"), 40)
        self.assertEqual(self.at_hours("collector-start") - self.at_hours("collector-stop"), 1)
        self.assertEqual(self.at_hours("server-restart"), 50)
        self.assertEqual(self.at_hours("upgrade-events-prod"), 72)
        self.assertEqual(self.at_hours("upgrade-events-control"), 72)
        self.assertEqual(self.at_hours("oneoff-once"), 96)
        self.assertEqual(self.at_hours("check-final"), 168)

    def test_restarts_every_twelve_hours_and_checks_every_six(self):
        restarts = sorted(i.at / 3600 for i in self.items.values()
                          if i.kind == "graceful-restart" and i.id != "restart-shop-1-refused")
        self.assertEqual(restarts, [12.0 * k for k in range(1, 14)])
        checks = sorted(i.at / 3600 for i in self.items.values() if i.kind == "check")
        self.assertEqual(checks, [6.0 * k for k in range(1, 29)])

    def test_test_runs_start_at_one_hour_and_repeat_daily(self):
        runs = sorted(i.at / 3600 for i in self.items.values() if i.kind == "testrun")
        self.assertEqual(runs, [1, 25, 49, 73, 97, 121, 145])

    def test_once_goes_to_shop_2_and_the_broken_route_to_shop_1(self):
        self.assertEqual(self.items["oneoff-once"].args["instance"], "shop-2")
        self.assertEqual(self.items["oneoff-broken"].args["instance"], "shop-1")
        self.assertEqual(self.items["oneoff-yearly"].args["instance"], "shop-1")


class RouteTests(unittest.TestCase):
    def test_rates(self):
        r = {x.key: x for x in cfg.ROUTES["shop-1"]}
        self.assertAlmostEqual(1 / r["GET /orders/{id}"].effective_period(60), 5)
        self.assertAlmostEqual(r["POST /orders"].effective_period(1), 10)
        self.assertAlmostEqual(r["GET /reports/monthly"].effective_period(60), 360)
        self.assertAlmostEqual(r["GET /health-legacy"].effective_period(1), 60)
        e = {x.key: x for x in cfg.ROUTES["events-prod"]}
        self.assertAlmostEqual(1 / e["GET /events"].effective_period(1), 2)
        self.assertAlmostEqual(e["POST /events"].effective_period(1), 1800)
        self.assertEqual(e["GET /events/stats"].only_version, "2.0.0")
        self.assertNotIn("GET /events/archive", e)
        s = {x.key: x for x in cfg.ROUTES["events-staging"]}
        self.assertAlmostEqual(s["GET /events/archive"].effective_period(1), 600)

    def test_the_events_control_mirrors_events_prod(self):
        self.assertEqual(cfg.ROUTES["events-control"], cfg.ROUTES["events-prod"])
        s = {x.key: x for x in cfg.ROUTES["events-control"]}
        self.assertEqual(s["GET /events/stats"].only_version, "2.0.0")

    def test_the_control_upgrade_item_and_its_jar(self):
        it = {i.id: i for i in cfg.build_schedule()}["upgrade-events-control"]
        self.assertEqual((it.kind, it.args), ("upgrade", {"instance": "events-control", "to": "2.0.0"}))
        c = cfg.BY_NAME["events-control"]
        self.assertEqual(cfg.jar_for(c), "soak-events-1.0.0.jar")
        self.assertEqual(cfg.jar_for(c, "2.0.0"), "soak-events-2.0.0.jar")
        self.assertEqual(cfg.running_version(c, "2.0.0"), "2.0.0")
        self.assertIsNone(cfg.version_of(c, "2.0.0"))
        self.assertIsNone(cfg.version_of(c))

    def test_instances(self):
        self.assertEqual({i.name: i.port for i in cfg.INSTANCES},
                         {"shop-1": 8081, "shop-2": 8082, "shop-3": 8083, "shop-control": 8089, "events-prod": 8091,
                          "events-staging": 8092, "events-crash": 8093, "events-control": 8099,
                          "events-staging-control": 8098})
        self.assertEqual({i.name: i.control for i in cfg.INSTANCES if i.agent},
                         {"shop-1": "shop-control", "shop-2": "shop-control", "shop-3": "shop-control",
                          "events-prod": "events-control", "events-staging": "events-staging-control",
                          "events-crash": "events-control"})
        self.assertEqual(cfg.ROUTES["events-staging-control"], cfg.ROUTES["events-staging"])
        self.assertNotIn("upgrade-events-staging-control", {i.id for i in cfg.build_schedule()})
        for n in ("shop-control", "events-control", "events-staging-control"):
            i = cfg.BY_NAME[n]
            self.assertEqual((i.agent, i.service, i.env, i.include), (False, None, None, None))
        self.assertEqual(cfg.jar_for(cfg.BY_NAME["events-prod"], "2.0.0"), "soak-events-2.0.0.jar")
        self.assertEqual(cfg.version_of(cfg.BY_NAME["events-prod"], "2.0.0"), "2.0.0")
        self.assertEqual(cfg.version_of(cfg.BY_NAME["events-staging"], None), "1.0.0")


class NewScheduleTests(unittest.TestCase):
    def items(self, scale):
        return {i.id: i for i in cfg.build_schedule(scale)}

    def real_s(self, it, scale):
        return it.at / scale

    def test_the_crash_cycles_are_four_real_minutes_apart_at_every_scale(self):
        for scale in (1, 60):
            items = self.items(scale)
            cycles = [items["crash-events-1"], items["crash-events-2"], items["crash-events-3"],
                      items["crash-events-nobaseline"]]
            self.assertEqual([i.kind for i in cycles], ["crash-cycle"] * 4)
            self.assertEqual([i.args["baseline"] for i in cycles], [True, True, True, False])
            self.assertEqual({i.args["instance"] for i in cycles}, {"events-crash"})
            real = [self.real_s(i, scale) for i in cycles]
            self.assertAlmostEqual(real[0], 20 * 3600 / scale)
            for a, b in zip(real, real[1:]):
                self.assertAlmostEqual(b - a, 240.0)

    def test_the_crash_cycles_start_at_twenty_design_hours_at_scale_1(self):
        items = self.items(1)
        self.assertEqual(items["crash-events-1"].at, 20 * 3600)
        self.assertEqual(items["crash-events-nobaseline"].at, 20 * 3600 + 720)
        self.assertEqual(cfg.CRASH_LIVE_REAL, 5.0)

    def test_the_refusal_window_is_ten_real_minutes_at_scale_1_and_two_at_scale_60(self):
        for scale, window in ((1, 600.0), (60, 120.0)):
            items = self.items(scale)
            low, restart, normal = (items["collector-low-limit"], items["restart-shop-1-refused"],
                                    items["collector-normal-limit"])
            self.assertEqual((low.kind, low.args), ("collector-limit", {"mode": "low"}))
            self.assertEqual((normal.kind, normal.args), ("collector-limit", {"mode": "normal"}))
            self.assertEqual((restart.kind, restart.args), ("graceful-restart", {"instance": "shop-1"}))
            self.assertEqual(low.at, 61 * 3600)
            self.assertAlmostEqual((normal.at - low.at) / scale, window)
            inside = (restart.at - low.at) / scale
            self.assertGreater(inside, 0)
            self.assertLess(inside, window / 2)

    def test_no_regular_restart_falls_inside_the_refusal(self):
        items = self.items(1)
        low, normal = items["collector-low-limit"].at, items["collector-normal-limit"].at
        regular = [i for i in items.values() if i.id.startswith("restart-shop-1-") and i.id != "restart-shop-1-refused"]
        self.assertFalse([i.id for i in regular if low - 3600 < i.at <= normal], "a regular restart near the refusal")

    def test_the_collector_limit_never_skips_its_restore(self):
        self.assertIn("collector-limit", soak.Supervisor.LATE)
        self.assertIn("crash-cycle", soak.Supervisor.LATE)

    def test_the_dry_run_lists_the_new_items(self):
        out = "\n".join(soak.timetable(60, 10, 5.0))
        self.assertIn("start events-crash, kill -9 it 5s after it is ready", out)
        self.assertIn("with staticBaselineEnabled=false", out)
        self.assertIn("restart the collector with a manifest element limit of 20", out)
        self.assertIn("graceful restart of shop-1 while the collector refuses manifests", out)
        self.assertIn("shop-3", out)


class NewInstanceTests(unittest.TestCase):
    def test_shop_3_runs_the_shop_jar_without_a_baseline_at_a_lower_rate(self):
        i = cfg.BY_NAME["shop-3"]
        self.assertEqual((i.app, i.service, i.env, i.static_baseline, i.control),
                         ("shop", "soak-shop", "nobaseline", False, "shop-control"))
        self.assertEqual(i.exclude, "dev.soaktest.shop.testdriver")
        self.assertEqual(cfg.jar_for(i), "soak-shop.jar")
        shop, low = {r.key: r for r in cfg.ROUTES["shop-1"]}, {r.key: r for r in cfg.ROUTES["shop-3"]}
        self.assertEqual(set(shop), set(low))
        for key in ("GET /orders/{id}", "POST /orders"):
            self.assertGreater(low[key].effective_period(1), shop[key].effective_period(1))
        self.assertEqual(low["GET /reports/monthly"].period, shop["GET /reports/monthly"].period)
        self.assertEqual(len({i.port for i in cfg.INSTANCES}), len(cfg.INSTANCES))

    def test_events_crash_is_the_events_1_jar_with_its_own_flush_and_no_traffic(self):
        i = cfg.BY_NAME["events-crash"]
        self.assertEqual((i.service, i.env, i.version, i.flush, i.always_on, i.asserts_runs),
                         ("soak-events", "prod", "1.0.0", 600, False, False))
        self.assertEqual(cfg.jar_for(i), "soak-events-1.0.0.jar")
        self.assertEqual(cfg.ROUTES["events-crash"], [])
        self.assertTrue(all(j.always_on and j.asserts_runs for j in cfg.INSTANCES if j.name != "events-crash"))

    def managed(self, name, flush=10):
        class State:
            d = {"variants": {}, "gen": {}}

        class Sup:
            state = State()
            paths = {"apps": "/apps", "agent": "/agent.jar", "logs": "/nonexistent"}
            java = "java"
            scale = 1

        Sup.flush = flush
        m = soak.Managed.__new__(soak.Managed)
        m.sup, m.inst = Sup(), cfg.BY_NAME[name]
        m.baseline = m.inst.static_baseline
        return m

    def test_an_exit_during_an_asked_stop_is_not_a_crash(self):
        class Exited:
            returncode = 0

            def poll(self):
                return 0

        m = self.managed("shop-1")
        m.popen, m.run = Exited(), {"ended": None}
        m.expect = "graceful"
        self.assertFalse(m.check_exit())
        self.assertIsNone(m.run["ended"])

    def agent_arg(self, m):
        return next(c for c in m.command("x-g001") if c.startswith("-javaagent"))

    def test_shop_3_turns_the_static_baseline_off(self):
        arg = self.agent_arg(self.managed("shop-3"))
        self.assertIn("staticBaselineEnabled=false", arg)
        self.assertIn("serviceName=soak-shop", arg)
        self.assertIn("environment=nobaseline", arg)
        self.assertIn("excludePackages=dev.soaktest.shop.testdriver", arg)
        self.assertIn("staticBaselineEnabled=true", self.agent_arg(self.managed("shop-1")))

    def test_events_crash_flushes_every_600_seconds_at_every_scale(self):
        for flush in (10, 60):
            arg = self.agent_arg(self.managed("events-crash", flush))
            self.assertIn("flushIntervalSeconds=600", arg)
            self.assertIn("staticBaselineEnabled=true", arg)
            self.assertIn("serviceName=soak-events", arg)
        self.assertIn("flushIntervalSeconds=10", self.agent_arg(self.managed("events-prod", 10)))

    def test_a_crash_cycle_can_turn_the_baseline_off_for_one_start(self):
        m = self.managed("events-crash")
        m.baseline = False
        self.assertIn("staticBaselineEnabled=false", self.agent_arg(m))


class WindowTrackerTests(unittest.TestCase):
    def test_nothing_is_due_before_an_event(self):
        self.assertEqual(soak.WindowTracker().due(1000.0), [])

    def test_a_live_read_every_5_seconds_for_150_and_a_snapshot_read_every_minute_for_20_minutes(self):
        tr = soak.WindowTracker()
        tr.open("event", 1000.0)
        live, snap = [], []
        for t in range(1000, 1000 + 1300):
            for kind in tr.due(float(t)):
                tr.started(kind, float(t))
                (live if kind == "fast" else snap).append(t)
        self.assertEqual(live, list(range(1000, 1150, 5)))
        self.assertEqual(snap, list(range(1000, 2200, 60)))

    def test_the_times_are_real_seconds_at_every_scale(self):
        self.assertEqual((cfg.WINDOW_FAST_PERIOD, cfg.WINDOW_FAST_SPAN), (5.0, 150.0))
        self.assertEqual((cfg.WINDOW_SLOW_PERIOD, cfg.WINDOW_SLOW_SPAN), (60.0, 1200.0))

    def test_a_new_event_extends_the_window(self):
        tr = soak.WindowTracker()
        tr.open("first", 1000.0)
        tr.started("fast", 1140.0)
        tr.open("second", 1145.0)
        self.assertEqual(tr.due(1146.0), ["fast", "slow"])
        self.assertEqual(tr.label("fast", 1146.0), "second")
        self.assertEqual(tr.label("fast", 1100.0), "first")
        self.assertEqual(tr.due(1300.0), ["slow"])

    def test_a_late_read_skips_the_ticks_it_covered(self):
        tr = soak.WindowTracker()
        tr.open("event", 1000.0)
        tr.started("fast", 1000.0)
        tr.started("fast", 1005.0)
        self.assertEqual(tr.skipped["fast"], 0)
        tr.started("fast", 1025.0)
        self.assertEqual(tr.skipped["fast"], 3)

    def test_a_gap_between_two_windows_is_not_a_skip(self):
        tr = soak.WindowTracker()
        tr.open("one", 1000.0)
        tr.started("fast", 1100.0)
        tr.open("two", 5000.0)
        tr.started("fast", 5000.0)
        self.assertEqual(tr.skipped["fast"], 0)

    def test_old_windows_are_dropped(self):
        tr = soak.WindowTracker()
        tr.open("old", 0.0)
        tr.open("new", 5000.0)
        self.assertEqual([w[0] for w in tr.windows], ["new"])


class WindowReaderTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="soak-windows-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        outer = self

        class State:
            lock = threading.RLock()
            d = {"testruns": [], "finished": False, "events": [], "runs": []}

        class Sup:
            paths = {"windows": os.path.join(outer.tmp, "windows.json")}
            state = State()
            stop = threading.Event()
            flush, lag, scale, t0 = 10, 1800.0, 60.0, 1000.0

            def log(self, msg):
                pass

        self.sup = Sup()
        self.r = soak.WindowReader(self.sup)
        self.r.api, self.r.truth = object(), {}
        self.orig = (check.read_window, check.instance_row)
        self.addCleanup(lambda: (setattr(check, "read_window", self.orig[0]), setattr(check, "instance_row", self.orig[1])))

    def saved(self):
        with open(self.sup.paths["windows"]) as f:
            return json.load(f)

    def test_a_violation_is_recorded_with_its_time_and_the_event_that_opened_the_window(self):
        check.read_window = lambda *a, **k: (["soak-shop/prod: truth.json does not name: never-loaded a.X"], [], 0)
        self.r.open("start of events-prod-g002", 5000.0)
        self.r.read("fast", 5004.0)
        self.r.read("fast", 5009.0)
        d = self.saved()
        self.assertEqual(len(d["violations"]), 1)
        v = next(iter(d["violations"].values()))
        self.assertEqual((v["event"], v["kind"], v["first"], v["last"], v["count"]),
                         ("start of events-prod-g002", "fast", 5004.0, 5009.0, 2))
        self.assertIn("never-loaded a.X", v["message"])
        self.assertEqual(d["reads"]["fast"], 2)
        self.assertEqual(d["opened"], 1)

    def test_two_events_give_two_violations_for_the_same_finding(self):
        check.read_window = lambda *a, **k: (["x: not on /never-hit (5 method rows listed)"], [], 0)
        self.r.open("one", 5000.0)
        self.r.read("fast", 5001.0)
        self.r.open("two", 9000.0)
        self.r.read("fast", 9001.0)
        self.assertEqual({v["event"] for v in self.saved()["violations"].values()}, {"one", "two"})

    def test_a_snapshot_read_counts_the_report_and_a_failed_read_is_not_a_violation(self):
        check.read_window = lambda *a, **k: ([], ["could not read the report of soak-shop: HTTP 500"], 0)
        self.r.open("e", 5000.0)
        self.r.read("slow", 5001.0)
        d = self.saved()
        self.assertEqual((d["reads"]["slow"], d["reads"]["errors"], d["violations"]), (1, 1, {}))
        self.assertIn("HTTP 500", d["last_error"])
        check.read_window = lambda *a, **k: ([], [], 1)
        self.r.read("slow", 5061.0)
        self.assertEqual(self.saved()["reads"]["report"], 1)

    def test_the_file_survives_a_new_reader(self):
        check.read_window = lambda *a, **k: (["x"], [], 0)
        self.r.open("e", 5000.0)
        self.r.read("fast", 5001.0)
        again = soak.WindowReader(self.sup)
        self.assertEqual(again.data["reads"]["fast"], 1)
        self.assertEqual(len(again.data["violations"]), 1)

    def test_shop_1_is_read_only_while_the_low_limit_holds_and_after_two_flushes(self):
        rows = []
        check.instance_row = lambda api, svc, env, iid: rows.append(iid) or {"deltas_received": False,
                                                                           "counts_behind_since": None}
        st = self.sup.state.d
        st["events"].append({"kind": "collector-limit", "instance": None, "t0": 5000.0, "t1": None})
        st["runs"].append({"instance": "shop-1", "instance_id": "shop-1-g006", "started": 5030.0, "ready": 5050.0,
                           "ended": None})
        self.r.observe_refusal(5060.0)
        self.assertEqual(rows, [])
        self.r.observe_refusal(5071.0)
        self.assertEqual(rows, ["shop-1-g006"])
        ref = self.r.data["refused"]
        self.assertEqual((ref["reads"], ref["behind"], ref["first_behind"]), (1, 1, 5071.0))
        self.assertTrue(ref["last"][-1]["behind"])
        check.instance_row = lambda *a: {"deltas_received": True, "counts_behind_since": None}
        self.r.observe_refusal(5080.0)
        self.assertEqual((ref["reads"], ref["behind"]), (2, 1))
        st["events"][0]["t1"] = 5120.0
        self.r.observe_refusal(5130.0)
        self.assertEqual(ref["reads"], 2)

    def test_a_run_from_before_the_limit_is_not_read(self):
        check.instance_row = lambda *a: self.fail("read")
        st = self.sup.state.d
        st["events"].append({"kind": "collector-limit", "instance": None, "t0": 5000.0, "t1": None})
        st["runs"].append({"instance": "shop-1", "instance_id": "shop-1-g005", "started": 4000.0, "ready": 4020.0,
                           "ended": None})
        self.r.observe_refusal(5100.0)
        self.assertEqual(self.r.data["refused"]["reads"], 0)


class RunItemTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="soak-run-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.sup = soak.Supervisor(soak.parse_args(["--scale", "60", "--home", self.tmp, "--no-stack"]))
        self.calls = []
        self.items = {i.id: i for i in self.sup.sched}
        orig_sleep = soak.time.sleep
        soak.time.sleep = lambda s: self.calls.append(("sleep", s))
        self.addCleanup(setattr, soak.time, "sleep", orig_sleep)

    def stub_crash(self):
        m = self.sup.mgrs["events-crash"]
        m.start = lambda: (self.calls.append(("start", m.baseline)), setattr(m, "accepting", True))
        m.kill9 = lambda: self.calls.append(("kill9",))
        return m

    def test_a_crash_cycle_starts_waits_five_seconds_and_kills(self):
        m = self.stub_crash()
        self.assertTrue(self.sup.run_item(self.items["crash-events-1"]))
        self.assertEqual(self.calls, [("start", True), ("sleep", 5.0), ("kill9",)])
        ev = [e for e in self.sup.state.d["events"] if e["kind"] == "crash-cycle"]
        self.assertEqual((len(ev), ev[0]["instance"], ev[0]["baseline"]), (1, "events-crash", True))
        self.assertTrue(m.baseline)
        self.assertEqual(self.sup.windows.data["opened"], 0)

    def test_the_last_crash_cycle_starts_without_a_baseline_and_restores_the_setting(self):
        m = self.stub_crash()
        self.sup.run_item(self.items["crash-events-nobaseline"])
        self.assertEqual(self.calls[0], ("start", False))
        self.assertTrue(m.baseline)

    def test_a_crash_cycle_whose_start_never_gets_ready_still_kills(self):
        m = self.sup.mgrs["events-crash"]
        m.start = lambda: self.calls.append(("start",))
        m.kill9 = lambda: self.calls.append(("kill9",))
        self.sup.run_item(self.items["crash-events-1"])
        self.assertEqual(self.calls, [("start",), ("kill9",)])

    def test_the_events_crash_instance_is_not_started_with_the_others(self):
        started = []
        for m in self.sup.mgrs.values():
            m.start = lambda m=m: started.append(m.inst.name)
        orig = soak.RouteGen.start
        soak.RouteGen.start = lambda self: None
        self.addCleanup(setattr, soak.RouteGen, "start", orig)
        self.sup.start_all()
        self.assertNotIn("events-crash", started)
        self.assertIn("shop-3", started)

    def test_the_collector_limit_records_one_event_and_opens_a_window_at_each_restart(self):
        codes = []
        self.sup.stack = lambda *a: codes.append(a) or 0
        self.sup.run_item(self.items["collector-low-limit"])
        ev = self.sup.state.d["events"]
        self.assertEqual([(e["kind"], e["t1"], e["exit"]) for e in ev if e["kind"] == "collector-limit"],
                         [("collector-limit", None, 0)])
        self.sup.run_item(self.items["collector-normal-limit"])
        e = [e for e in self.sup.state.d["events"] if e["kind"] == "collector-limit"][0]
        self.assertIsNotNone(e["t1"])
        self.assertEqual(e["start_exit"], 0)
        self.assertEqual(codes, [("collector-limit", "low"), ("collector-limit", "normal")])
        self.assertEqual(self.sup.windows.data["opened"], 2)

    def test_the_restore_of_the_normal_limit_is_never_skipped_for_being_late(self):
        sup = self.sup
        sup.t0 = soak.time.time() - 100 * 3600 / sup.scale
        sup.state.d["done"]["collector-low-limit"] = {"t": 0, "note": ""}
        ran = []
        sup.pool = type("Pool", (), {"submit": lambda self, fn, it: ran.append(it.id)})()
        sup.sched = [self.items["collector-normal-limit"], self.items["collector-low-limit"]]
        sup.tick()
        self.assertEqual(ran, ["collector-normal-limit"])

    def test_a_server_restart_and_a_collector_start_open_windows(self):
        self.sup.stack = lambda *a: 0
        self.sup.run_item(self.items["server-restart"])
        self.sup.run_item(self.items["collector-start"])
        self.assertEqual(self.sup.windows.data["opened"], 2)


class FirstSendTests(unittest.TestCase):
    def test_a_long_route_sends_two_minutes_after_t0(self):
        self.assertEqual(soak.first_send(1000.0, 1010.0, 21600.0), 1120.0)

    def test_a_late_start_in_the_first_period_sends_at_once(self):
        self.assertEqual(soak.first_send(1000.0, 1300.0, 21600.0), 1300.0)

    def test_a_restart_after_the_first_period_keeps_the_grid(self):
        self.assertEqual(soak.first_send(1000.0, 1120.0 + 21600.0 + 5, 21600.0), 1120.0 + 2 * 21600.0)


class StackTests(unittest.TestCase):
    def sup(self, stack_path):
        class Log:
            def __init__(self):
                self.lines = []

            def write(self, line):
                self.lines.append(line)

        class Args:
            no_stack = False

        s = soak.Supervisor.__new__(soak.Supervisor)
        s.args, s.slog, s.paths = Args(), Log(), {"stack": stack_path}
        return s

    def test_a_missing_stack_script_returns_an_error_code(self):
        code = self.sup("/nonexistent/stack.sh").stack("stop-collector")
        self.assertTrue(isinstance(code, str) and code.startswith("error:"), code)

    def test_a_hung_stack_script_returns_timeout(self):
        orig = soak.subprocess.run

        def hang(*a, **k):
            raise soak.subprocess.TimeoutExpired(a[0], k.get("timeout"))
        soak.subprocess.run = hang
        try:
            self.assertEqual(self.sup("/bin/true").stack("restart-server"), "timeout")
        finally:
            soak.subprocess.run = orig

    def test_a_late_collector_stop_is_skipped(self):
        self.assertEqual(soak.Supervisor.LATE["collector-stop"], 0.5 * soak.H)


class ManagedTests(unittest.TestCase):
    def managed(self, name, variant):
        class State:
            d = {"variants": {name: variant} if variant else {}}

        class Sup:
            state = State()
            paths = {"apps": "/apps", "agent": "/agent.jar", "logs": "/nonexistent"}
            java = "java"
            scale = 1
            flush = 60

        m = soak.Managed.__new__(soak.Managed)
        m.sup, m.inst = Sup(), cfg.BY_NAME[name]
        m.baseline = m.inst.static_baseline
        return m

    def test_the_control_runs_the_upgraded_jar_without_the_agent(self):
        m = self.managed("events-control", "2.0.0")
        cmd = m.command("events-control-g002")
        self.assertIn("/apps/soak-events-2.0.0.jar", cmd)
        self.assertFalse(any("javaagent" in c for c in cmd))
        self.assertIsNone(m.version())
        self.assertEqual(m.running_version(), "2.0.0")
        self.assertEqual(self.managed("events-control", None).running_version(), "1.0.0")

    def test_events_prod_reports_its_version(self):
        m = self.managed("events-prod", "2.0.0")
        self.assertEqual(m.version(), "2.0.0")
        self.assertTrue(any("javaagent" in c and "serviceVersion=2.0.0" in c for c in m.command("x")))


class DryRunTests(unittest.TestCase):
    def test_dry_run_prints_the_timetable_and_starts_nothing(self):
        r = subprocess.run([sys.executable, os.path.join(HARNESS, "soak.py"), "--scale", "3600", "--dry-run",
                            "--home", "/nonexistent/soak-home"], capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr)
        out = r.stdout
        self.assertIn("kill -9 of shop-2", out)
        self.assertIn("upgrade events-prod to 2.0.0", out)
        self.assertIn("upgrade events-control to 2.0.0", out)
        self.assertIn("the final check and the report", out)
        self.assertFalse(os.path.exists("/nonexistent/soak-home"))

    def test_the_heap_parser(self):
        self.assertEqual(soak.parse_heap_info(
            " garbage-first heap   total reserved 524288K, committed 135168K, used 37748K [0x1, 0x2)\n"
            "  region size 1024K, 12 young (12288K), 1 survivors (1024K)\n"), 37748)
        self.assertEqual(soak.parse_heap_info(
            " def new generation   total 39296K, used 4000K [0x1)\n tenured generation   total 87424K, used 1000K [0x2)\n"), 5000)
        self.assertIsNone(soak.parse_heap_info("nothing here"))


if __name__ == "__main__":
    unittest.main()
