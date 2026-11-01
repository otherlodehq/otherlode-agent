"""Shared configuration of the soak harness: the instances, the traffic, the schedule.

Both soak.py and check.py import this module, so the supervisor and the checker agree on
the instance table, the route rates and the timetable of DESIGN.md. Standard library only.
"""

import math
import os
import re
from dataclasses import dataclass, field

HOME = os.environ.get("SOAK_HOME", "/opt/soak")

API_BASE = "http://localhost:4320"
COLLECTOR_URL = "http://localhost:4319"

# The design time of the whole run, in seconds.
RUN_SECONDS = 168 * 3600

# The shortest gap between two requests of one route, so a very small --scale cannot flood
# an app.
MIN_PERIOD = 0.02

# Used by the checker when a check names the test run.
TEST_TARGET_METHOD = "applyLegacyDiscount"
TEST_CALLER = "SoakTestDriver"
TEST_INSTANCE_ID = "soak-shop-tests"
TEST_SERVICE = "soak-shop"

# How the shop jar runs its test driver: Spring Boot's launcher with loader.main set. The jar is a
# fat jar, so a plain `-cp` would not see its classes.
TESTDRIVER_LAUNCHER = os.environ.get(
    "SOAK_TESTDRIVER_LAUNCHER", "org.springframework.boot.loader.launch.PropertiesLauncher"
)
TESTDRIVER_MAIN = os.environ.get(
    "SOAK_TESTDRIVER_MAIN", "dev.soaktest.shop.testdriver.SoakTestDriver"
)


def paths(home=None):
    """Return the directories and files of one soak home as a dict."""
    h = home or HOME
    return {
        "home": h,
        "bin": os.path.join(h, "bin"),
        "agent": os.path.join(h, "agent", "otherlode-agent.jar"),
        "apps": os.path.join(h, "apps"),
        "truth": os.path.join(h, "truth.json"),
        "harness": os.path.join(h, "harness"),
        "logs": os.path.join(h, "logs"),
        "state_dir": os.path.join(h, "state"),
        "state": os.path.join(h, "state", "state.json"),
        "traffic": os.path.join(h, "state", "traffic.json"),
        "memory": os.path.join(h, "state", "memory.csv"),
        "reports": os.path.join(h, "reports"),
        "secrets": os.path.join(h, "secrets"),
        "run": os.path.join(h, "run"),
        "read_key": os.path.join(h, "secrets", "read.key"),
        "agent_token": os.path.join(h, "secrets", "agent.token"),
        "stack": os.path.join(h, "harness", "stack.sh"),
        "allowlist": os.path.join(h, "harness", "log-allowlist.txt"),
    }


@dataclass(frozen=True)
class Inst:
    name: str
    app: str  # "shop" or "events"
    port: int
    agent: bool
    service: str | None
    env: str | None
    version: str | None
    include: str | None
    exclude: str | None = None
    control: str | None = None  # the agent-less instance whose memory this one is compared with


INSTANCES = [
    Inst("shop-1", "shop", 8081, True, "soak-shop", "prod", "1.0.0", "dev.soaktest.shop", "dev.soaktest.shop.testdriver",
         control="shop-control"),
    Inst("shop-2", "shop", 8082, True, "soak-shop", "prod", "1.0.0", "dev.soaktest.shop", "dev.soaktest.shop.testdriver",
         control="shop-control"),
    Inst("shop-control", "shop", 8089, False, None, None, None, None),
    Inst("events-prod", "events", 8091, True, "soak-events", "prod", "1.0.0", "dev.soaktest.events",
         control="events-control"),
    Inst("events-staging", "events", 8092, True, "soak-events", "staging", "1.0.0", "dev.soaktest.events",
         control="events-staging-control"),
    Inst("events-control", "events", 8099, False, None, None, "1.0.0", None),
    Inst("events-staging-control", "events", 8098, False, None, None, "1.0.0", None),
]
BY_NAME = {i.name: i for i in INSTANCES}


def jar_for(inst, variant=None):
    """The jar an instance runs. Events has one jar for each version."""
    if inst.app == "shop":
        return "soak-shop.jar"
    return "soak-events-%s.jar" % (variant or inst.version)


def running_version(inst, variant=None):
    """The version of the jar an instance runs. Only events-prod and events-control change it."""
    if inst.app == "events":
        return variant or inst.version
    return inst.version


def version_of(inst, variant=None):
    """The serviceVersion an instance reports. None for an instance without the agent."""
    if not inst.agent:
        return None
    return running_version(inst, variant)


@dataclass(frozen=True)
class Route:
    verb: str
    template: str
    path: str  # may hold {id}; the traffic fills it in
    period: float  # seconds between requests, at scale 1
    scaled: bool = True  # False for a sustained rate such as 5 a second
    only_version: str | None = None  # the route runs only while the instance runs this version
    query: str = ""
    body: bytes | None = None

    @property
    def key(self):
        return "%s %s" % (self.verb, self.template)

    def effective_period(self, scale):
        p = self.period / scale if self.scaled else self.period
        return max(p, MIN_PERIOD)


H = 3600.0
_SHOP_ROUTES = [
    Route("GET", "/orders/{id}", "/orders/{id}", 0.2, scaled=False),
    Route("POST", "/orders", "/orders", 10.0, query="q=widget"),
    Route("GET", "/reports/monthly", "/reports/monthly", 6 * H),
    Route("GET", "/health-legacy", "/health-legacy", 60.0),
]
# The fields of the events app's `Event`: id, kind and payload. Ktor's default JSON refuses an
# unknown key, so a wrong name answers 400.
_EVENT_BODY = b'{"id":1,"kind":"soak","payload":"x"}'
_EVENTS_ROUTES = [
    Route("GET", "/events", "/events", 0.5, scaled=False),
    Route("POST", "/events", "/events", 1800.0, body=_EVENT_BODY),
]

_EVENTS_PROD_ROUTES = _EVENTS_ROUTES + [
    Route("GET", "/events/stats", "/events/stats", 0.5, scaled=False, only_version="2.0.0")
]
_EVENTS_STAGING_ROUTES = _EVENTS_ROUTES + [Route("GET", "/events/archive", "/events/archive", 600.0)]

ROUTES = {
    "shop-1": list(_SHOP_ROUTES),
    "shop-2": list(_SHOP_ROUTES),
    "shop-control": list(_SHOP_ROUTES),
    "events-prod": list(_EVENTS_PROD_ROUTES),
    "events-staging": list(_EVENTS_STAGING_ROUTES),
    "events-control": list(_EVENTS_PROD_ROUTES),
    "events-staging-control": list(_EVENTS_STAGING_ROUTES),
}


@dataclass(frozen=True)
class OneOff:
    id: str
    at: float  # design seconds from T0
    instance: str
    verb: str
    template: str
    path: str


ONE_OFFS = [
    OneOff("broken", 5 * 60, "shop-1", "GET", "/broken", "/broken"),
    OneOff("yearly", 10 * 60, "shop-1", "GET", "/reports/yearly", "/reports/yearly"),
    OneOff("once", 96 * H, "shop-2", "GET", "/once", "/once"),
    # The control gets the same one-off requests, so its memory is comparable.
    OneOff("broken-control", 5 * 60, "shop-control", "GET", "/broken", "/broken"),
    OneOff("yearly-control", 10 * 60, "shop-control", "GET", "/reports/yearly", "/reports/yearly"),
    OneOff("once-control", 96 * H, "shop-control", "GET", "/once", "/once"),
]


@dataclass
class Item:
    id: str
    at: float  # design seconds from T0
    kind: str
    args: dict = field(default_factory=dict)
    text: str = ""


def build_schedule():
    """The timetable of DESIGN.md, in design seconds. Dynamic items (the test run's
    follow-up) are not here; the supervisor adds them."""
    items = []
    for o in ONE_OFFS:
        items.append(
            Item("oneoff-" + o.id, o.at, "oneoff", {"instance": o.instance, "verb": o.verb, "path": o.path},
                 "%s %s on %s" % (o.verb, o.path, o.instance))
        )
    k = 1
    while 12 * H * k < RUN_SECONDS:
        items.append(Item("restart-shop-1-%02d" % k, 12 * H * k, "graceful-restart", {"instance": "shop-1"},
                          "graceful restart of shop-1 (SIGTERM, wait, start)"))
        k += 1
    items.append(Item("kill-shop-2", 30 * H, "kill9", {"instance": "shop-2"}, "kill -9 of shop-2"))
    items.append(Item("start-shop-2", 30 * H + 120, "start", {"instance": "shop-2"},
                      "start shop-2 again, 2 minutes after the kill"))
    items.append(Item("collector-stop", 40 * H, "collector-stop", {}, "stop the collector"))
    items.append(Item("collector-start", 41 * H, "collector-start", {}, "start the collector, 60 minutes later"))
    items.append(Item("server-restart", 50 * H, "server-restart", {}, "restart the server"))
    items.append(Item("upgrade-events-prod", 72 * H, "upgrade", {"instance": "events-prod", "to": "2.0.0"},
                      "upgrade events-prod to 2.0.0 (SIGTERM, start the 2.0.0 jar)"))
    items.append(Item("upgrade-events-control", 72 * H, "upgrade", {"instance": "events-control", "to": "2.0.0"},
                      "upgrade events-control to 2.0.0 (SIGTERM, start the 2.0.0 jar)"))
    k = 0
    while H + 24 * H * k < RUN_SECONDS:
        items.append(Item("testrun-%d" % k, H + 24 * H * k, "testrun", {"k": k},
                          "shop-test runs SoakTestDriver (run %d)" % (k + 1)))
        k += 1
    k = 1
    while 6 * H * k < RUN_SECONDS:
        items.append(Item("check-%02d" % k, 6 * H * k, "check", {"final": False}, "checker (every 6 hours)"))
        k += 1
    items.append(Item("check-final", RUN_SECONDS, "check", {"final": True}, "the final check and the report"))
    items.sort(key=lambda i: (i.at, i.id))
    return items


_DUR = re.compile(
    r"^P(?:(?P<w>\d+(?:\.\d+)?)W)?(?:(?P<d>\d+(?:\.\d+)?)D)?"
    r"(?:T(?:(?P<h>\d+(?:\.\d+)?)H)?(?:(?P<m>\d+(?:\.\d+)?)M)?(?:(?P<s>\d+(?:\.\d+)?)S)?)?$"
)


def parse_duration(text):
    """Seconds in an ISO 8601 duration such as PT0S, PT26H, P4D or P1DT2H30M. None for None."""
    if text is None:
        return None
    m = _DUR.match(str(text).strip())
    if not m or str(text).strip() in ("P", "PT"):
        raise ValueError("not an ISO 8601 duration: %r" % (text,))
    g = {k: float(v) if v else 0.0 for k, v in m.groupdict().items()}
    return g["w"] * 604800 + g["d"] * 86400 + g["h"] * 3600 + g["m"] * 60 + g["s"]


def fmt_hms(seconds):
    s = int(round(seconds))
    return "%02d:%02d:%02d" % (s // 3600, (s % 3600) // 60, s % 60)


def ceil_div(a, b):
    return int(math.ceil(a / b)) if b else 0
