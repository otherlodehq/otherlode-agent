# Claude Code project configuration

`commands/` holds the two slash commands this project's status log refers
to: `/deep-review` and `/chunked-build`.

`skills/` vendors the skills those commands and the design notes depend
on, so a fresh checkout has them without a user-level install:

| Skill | Source | Licence |
| --- | --- | --- |
| `grilling`, `domain-modeling`, `grill-me`, `grill-with-docs` | https://github.com/mattpocock/skills | MIT, `LICENSE` beside each |
| `technical-writing` | https://github.com/cursor/plugins/tree/main/pstack/skills/technical-writing (`23e4138`) | MIT, `LICENSE` beside it |
| `unslop` | https://github.com/cursor/plugins/tree/main/pstack/skills/unslop (`70b2dc8`) | MIT, `LICENSE` beside it |
| `nightwork` | Written for these repos. Runs `/chunked-build` unattended from a grilled brief and ends with an `unslop` report | n/a |
| `power-mode` | Written for these repos. Routes a task to a playbook and names the file for each command and skill above, so a mention mid-sentence still finds it | n/a |
| `copy-style` | Written for these repos. Sets the voice for customer-facing copy, one guide each for web UI strings, customer docs and marketing, on top of `unslop` | n/a |

Only the skill prompts and their format references are copied. Upstream
packaging (plugin manifests, validators, Codex metadata) is left out.

`shared-files` lists the paths every otherlode repo carries as an exact copy
of this repo's: these skills and commands, `.githooks/commit-msg`, the
session-start hook in `settings.json` that turns the git hook on, and the
`Claude config` workflow. Edit a shared path here, then copy it to the
other repos. That workflow runs `check-shared.sh` in every repo and fails
when a copy differs from this repo's master. It also fails a push whose
commit messages carry a session id or link.
