# Coordinator bindings and calls

The executable coordinator is Flow-owned (`authoring.packages.PackageCoordinator`). Its optional
DCP factory (`engine.dcp.PackageInvocableFactory`) is registered only through the host broker with
accepted contracts, runtime binding and a `packageConfig` ConfigRef. No new unauthenticated HTTP
route is installed. The host exposes accepted capabilities through its selected tool surface.

| Operation | Arguments | Meaning |
| --- | --- | --- |
| `create_dcp_software_package` | requestKey, packageName, planRef, targetRef | Admit once under trusted owner and exact host plan |
| `software.package.status` | runId | Compact current progress and sanitized pending interaction |
| `software.package.advance` | runId, expectedRevision | Observe or advance at most one native stage |
| `software.package.answer` | runId, expectedRevision, requestId, answer | Authenticated exact request decision, once only |
| `software.package.resume` | runId, expectedRevision, optional executionId | Revalidate pins/reconcile an existing execution; never replay |
| `software.package.cancel` | runId, expectedRevision | Stop dispatch and contain child effects |
| `software.package.result` | runId | Final declared archive/manifest and readiness evidence |

The host must supply approved native definitions, fresh narrowed contexts, fenced workspaces,
independent canonical handoff/schema validation, durable audit, exact interaction decision authority,
quotas and child containment. Foundry and development-task operations remain separate admitted
capabilities. The selected graph/UI is project state, not fixed by this facade. Native validators
and actual services decide executable support; do not invent tool names, endpoints or bindings.

Canonical authoring envelopes live beside the assembly suite's existing schemas:
`package-run.schema.json`, `package-interaction.schema.json`, `package-result.schema.json` and
`package-manifest.schema.json`. The existing CLI validates them offline. These records contain
references, never raw provider captures, credentials or transcripts. Runtime journal files stay
private and are not packaging inputs.

Stage results reuse existing assembly-progress, adapter-result and graph-result payloads. The
host verifier applies `suite.py validate KIND FILE --artifacts` and applicable native/project checks,
then returns a typed verified handoff. It must not certify readiness from an agent's success text.
Use the final progress record's declared artifacts and evidence as the distribution allowlist.

Publication and production deployment remain separate from package creation. The presence of this
skill does not establish that the coordinator or required services are activated in a deployment.
