---
status: accepted
---

# A class that failed to load is named on the manifest

Decided on 2026-10-06 in a grilling session with Luke, as the last open bullet under `STATUS.md`'s pre-release checklist item 2. This amends ADR 0028, which withheld such a class and put the bucket off until a real report showed one.

When an unconfirmed class is withheld for good, at the moment the agent logs its WARNING, the agent also names it in `ProbeManifest.failed_classes`, sent once and delivered incrementally like `unreported_classes`. The entry is the class name alone. The agent cannot see why the JVM refused the class: the `LinkageError` or `VerifyError` is thrown after its transformer returns. A collector reads such a class as *failed to load*, a deployment to fix, never as never loaded or any other dead-code claim. A class any in-scope run loaded is loaded, whatever another run says. A class no run loaded that some run names as failed to load reads as failed to load, even where a complete baseline declares it.

ADR 0028 judged the bucket out of proportion because nobody had shown the population. Collector ADR 0005, decided later, changed the cost: a field added after release is stripped by every older collector with redaction on, and the whole run then makes no claim until the collector is upgraded. A field added before `0.1.0` costs nothing on the wire, so the proportion argument no longer holds.

## Consequences

- The rest of ADR 0028 stands: the class's probes stay out of the manifest, a collected loader still publishes rather than withholds, and confirmation still matches on the name alone. The endpoint tier's exposure stays deferred.
- `otherlode-testkit` gains `failedToLoadClasses()`, and `otherlode-server` gains the class state and the report's `classes.failed_to_load` count.
