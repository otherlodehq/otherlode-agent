---
status: accepted, amended by ADR 0049 and on 2026-10-04
---

# Every option resolves from three sources, in a fixed precedence order

Every agent option can be set from the `-javaagent` args string, a JVM system property, or an environment variable, in that precedence order, falling back to the option's own default if none is set. A blank value at any level counts as unset and falls through to the next one. The property and environment variable names are derived mechanically from the option's camelCase name, split on word boundaries and rejoined (`otherlode.service.name`, `OTHERLODE_SERVICE_NAME`), so a name exists at exactly one place and cannot drift between the two derived forms.

## Considered options

Environment overriding the agent option, so an operator could override a value baked into an image's command line without rebuilding it. Rejected: the most explicitly written, most launch-specific value should win, the same ordering OpenTelemetry's Java agent uses (system properties over environment). The operator's real need, turning the agent off without a rebuild, is already covered by `enabled` itself being settable from the environment when the command line does not set it.

## Consequences

An option's name is a compatibility surface beyond the agent-args key: renaming an option renames its derived property and environment variable names too, with no separate alias to preserve the old ones.

## Amended on 2026-10-04: names reviewed before release

Reviewed as `STATUS.md`'s pre-release checklist item 4, with Luke, since this record makes every name permanent.

- **`endpoint` is `exportUrl`** (`otherlode.export.url`, `OTHERLODE_EXPORT_URL`). `CONTEXT.md` keeps *endpoint* for a verb and route template, which `endpointsEnabled` already uses, and the agent can post to a backend as well as a collector. A query string or a fragment in the URL is rejected with a WARNING and the default, as a malformed URL is: the exporter appends its paths to the whole string, and nothing reads a query parameter.
- **No option begins with `testkit` or `collector`**, so a derived name never lands in the testkit's `otherlode.testkit.*` properties or the collector's `OTHERLODE_COLLECTOR_*` settings.
- **The other names stand**: `serviceName`, `serviceNamespace`, `serviceVersion`, `serviceInstanceId`, `environment`, `authToken`, `flushIntervalSeconds`, `includePackages`, `excludePackages`, `staticBaselineEnabled`, `enabled`, `endpointsEnabled`, `otelBridgeEnabled` and `testRun`. A unit goes in a name (`Seconds`) rather than in a parsed suffix, and a flag that marks what a run is (`testRun`) takes no `Enabled`. Internal field names follow the keys.
