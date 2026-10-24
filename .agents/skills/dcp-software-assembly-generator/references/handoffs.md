# Handoff files and offline tools

Schemas under [assets](../assets) define `assembly-progress`, `adapter-result` and `graph-result`.
These are authoring records, not alternative DCP contract formats. Place files in the generated
project; their artifact paths resolve relative to the containing JSON file's directory. Package
referenced files under that root, without secrets or raw captures. Use the same projectId across
handoffs and unique requestId for each adapter request. The coordinator checks both before merging.

Save decisions before switching skills. Record required operations exactly; registry query matches
names only and Fabric resolves compatibility. Progress tracks completed stages and unresolved
needs, selected adapters with versions/hashes, dependency edges, governance, runtime/UI and checks.
Merge child results only after schema, file hash, identity, scope and requested-operation checks.
Preserve unanswered needs and external prerequisites. Revalidate derived artifacts after drift.

## Setup and commands

Use Python 3.11+ and install development dependencies from
`scripts/requirements.txt` into an isolated environment when needed. No Java dependency changes.
Commands below use `suite.py` in this skill's scripts directory:

```text
python suite.py query <registry.json> --domain finance --capability gtt.instructions.list --verify
python suite.py validate registry <registry.json> --artifacts
python suite.py pin <project-root> <relative-artifact-path> <kind>
python suite.py validate assembly-progress <project>/assembly-progress.json --artifacts
python suite.py validate adapter-result <project>/adapter-result.json --artifacts
python suite.py validate graph-result <project>/graph-result.json --artifacts
python suite.py handoff <project>/assembly-progress.json --adapter-result <project>/adapter-result.json
python suite.py handoff <project>/assembly-progress.json --graph-result <project>/graph-result.json
python suite.py upsert <registry.json> <entry.json>
python suite.py install --destination <codex-home>/skills
```

`install` refuses divergent files by default; use `--replace-local` only when intentionally
synchronizing known local suite changes. It installs all five sibling skills and records canonical
source/registry locations in `suite-source.json`. Keep that checkout available for registry access.
It never installs unrelated skills or executes recorded evidence commands.

Registry entries carry domains, source path/repository, pinned artifacts, capabilities/effects and
evidence scope. Local verification needs the source checkout. An unavailable source or stale digest
blocks reuse. Query without verification is useful discovery only; it never authorizes execution.

Completion status is intentionally strict: validated needs passing local and protocol evidence;
runnable additionally needs passing startup evidence. No unresolved needs, failed checks, missing
required governance, operation gaps, absent runtime/UI artifacts or dependency cycles may remain.
CLI validation does not prove those reports are truthful or replace actual native validators.

During interview, progress can use `undecided` runtime/UI. During graph authoring use `draft` or
`authored`, empty bindings and explicit unresolvedNeeds/connection requirements as appropriate.
`authored` needs passing structural evidence and actual native graph files; it is not an execution
gate. Native validation blocked on unknown capabilities must remain a recorded gap/draft.
Optional `strategyComponents` record business logic with its own DCP claim. Optional `connections`
record host-owned provider-token/OAuth requirements using developer/client SecretRefs only.
Read [connections.md](connections.md) for identity/grant and credential lifecycle ownership.

The read-only `handoff` command checks child project/request identity, requested coverage and
runtime selection against saved progress. Keep `adapterRequests` with unique request IDs in
progress before calling the adapter skill. Merge only after this check; the coordinator then
reruns full assembly validation. Write results require approvalPolicy and reconciliationPolicy.
Registry verification recognizes explicit claim effect details `READ_ONLY`, `WRITE` or `MUTATING`;
other upstream classifications require a documented explicit normalization, never a guessed effect.
