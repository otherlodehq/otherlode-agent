"""The supervisor's timetable, its dry run and its small parsers."""

import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
HARNESS = os.path.dirname(HERE)
sys.path.insert(0, HARNESS)
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
        restarts = sorted(i.at / 3600 for i in self.items.values() if i.kind == "graceful-restart")
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
                         {"shop-1": 8081, "shop-2": 8082, "shop-control": 8089, "events-prod": 8091,
                          "events-staging": 8092, "events-control": 8099, "events-staging-control": 8098})
        self.assertEqual({i.name: i.control for i in cfg.INSTANCES if i.agent},
                         {"shop-1": "shop-control", "shop-2": "shop-control", "events-prod": "events-control",
                          "events-staging": "events-staging-control"})
        self.assertEqual(cfg.ROUTES["events-staging-control"], cfg.ROUTES["events-staging"])
        self.assertNotIn("upgrade-events-staging-control", {i.id for i in cfg.build_schedule()})
        for n in ("shop-control", "events-control", "events-staging-control"):
            i = cfg.BY_NAME[n]
            self.assertEqual((i.agent, i.service, i.env, i.include), (False, None, None, None))
        self.assertEqual(cfg.jar_for(cfg.BY_NAME["events-prod"], "2.0.0"), "soak-events-2.0.0.jar")
        self.assertEqual(cfg.version_of(cfg.BY_NAME["events-prod"], "2.0.0"), "2.0.0")
        self.assertEqual(cfg.version_of(cfg.BY_NAME["events-staging"], None), "1.0.0")


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
            flush = 60

        m = soak.Managed.__new__(soak.Managed)
        m.sup, m.inst = Sup(), cfg.BY_NAME[name]
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
