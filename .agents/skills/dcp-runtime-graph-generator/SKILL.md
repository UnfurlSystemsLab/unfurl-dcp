---
name: dcp-runtime-graph-generator
description: Author and validate native Unfurl Flow workflows, Foundry agent graphs, or their integration during DCP assembly design. Make unresolved capabilities and connections explicit; require accepted contracts and verified bindings before execution.
---

# DCP Runtime Graph Generator

Generate native graph artifacts incrementally, not a new orchestration DSL. Read
[runtime-authoring.md](references/runtime-authoring.md) and the assembly suite's
[handoff contracts](../dcp-software-assembly-generator/references/handoffs.md).

Use the implemented `runtime_bundle.py` projector and
[API/hook execution guide](../dcp-software-assembly-generator/references/api-execution.md).
It emits native authoring preflight/call/post-gate workflows, Foundry reasoning agents/skills,
policy fragments and a draft Kotak capability graph. Validate with the canonical repo's
`tools/skill-runtime-validation` module; do not equate native syntax with resolved live bindings.
Adapt the generated drafts to the project rather than treating undelivered capabilities as tools.

1. Read progress, requirements, pinned assembly artifacts, capability inventory, governance and
   existing graphs. Verify artifact hashes. Ask Flow, Foundry, both or neither only if not decided.
2. Read target runtime AGENTS/HLD/LLD/build specs and inspect real definitions, registries,
   loaders and validators. Reuse existing authoring tools and patterns. A listed tool must
   actually be available and authorized; absence is a blocker, not an invented tool response.
3. Map each required operation to an explicit capability need and schema, with accepted bindings
   where available. Record unresolved needs separately from native definitions. Record inputs,
   outputs, ordering, decisions, approval gates, failure/unknown-outcome handling and cancellation.
   Do not infer acceptance from a registry entry or bypass a binding with direct vendor calls.
   Missing connections do not prevent authoring. Never register unresolved calls as fake executors;
   if native validation cannot resolve them, return draft plus the diagnostic rather than claiming
   runtime validation or abandoning the rest of the graph.
4. Generate only selected runtimes. Flow owns deterministic steps; Foundry owns model/agent
   decisions and its canonical inspectable graph. In both mode, pin the supported agent reference
   and cross-runtime boundary instead of embedding the agent's reasoning scheduler in Flow.
5. Reuse host governance ports, preserve contract/scope/correlation and bound retries/budgets.
   Missing executors, bindings, model providers or native interfaces are explicit limitations.
   Separate strategy component calls from broker wrappers. Resolve connections through a host-bound
   provider; do not embed passwords, token refresh or provider login algorithms in graph nodes.
6. Validate definitions using real native validators and run authorized local smoke tests.
   Do not register/deploy graphs or execute external writes beyond the user's authorization.
7. Return `graph-result.json` with project identity, runtime selection, pinned native definitions
   and bindings, check evidence and limitations. Return to the assembly workflow; do not call
   the assembly skill recursively. Neither mode emits no graph result and returns that decision.
   Use `authored` only with successful structural evidence; keep missing bindings/needs visible.
   `validated`/`runnable` require resolved dependencies and actual runtime binding evidence.
