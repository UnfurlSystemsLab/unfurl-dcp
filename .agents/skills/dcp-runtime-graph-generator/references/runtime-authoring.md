# Runtime source map and acceptance

Author against declared capability needs first. Keep unresolved dependencies in the handoff;
only admitted runtime execution requires accepted contracts and verified connections. Missing
live login is not a reason to stop native definition authoring. If unresolved references prevent
native validation, return the real diagnostic with draft status, not fabricated success.

Resolve sibling checkouts from the target workspace; do not require original workstation paths.

## Flow

Read `unfurl-flow/docs/REPO-unfurl-flow-build-spec.md`, its distributed-engine LLD and
`unfurl-foundry/deployment/prompts/flow-dag-authoring.md`. Inspect current substrate
WorkflowDefinition and Flow's validation/authoring implementations. Existing tool surfaces
include `flow.workflow-draft-questions`, `flow.workflow-validate` and suggestion/registration
tools; discover actual availability rather than assuming a running server.

Use canonical references such as `$.workflow.input.field` and `$.nodes.nodeId.output.field`.
Dynamic input is not static config. Explicit dependencies order reads of upstream output.
Do not replace the model with friendly `steps/action/arguments` fields. Only use constructs
supported by the selected runtime profile. Registration is distinct from local validation.

## Foundry

Read `unfurl-foundry/docs/REPO-unfurl-foundry-build-spec.md` and relevant substrate HLD/LLD.
Inspect `AgentDefinition`, `AgentDefinitionValidator`, supported harness definitions,
directory loaders, pinned agent references and tool/provider registration. Author the canonical
graph and use actual schema/validator outputs; an illustrative YAML diagram is not executable.
Bind model/provider choices, secret references, permissions and budgets through existing ports.

## Combined

Use the current governed `agent.run` contract and pinned `agentRef` when supported by the
selected runtime versions. Flow handles the coarse invocation and terminal result, not internal
agent turns. Record shared schemas, correlation, cancellation, timeout, approval and unknown
outcome behavior. If the installed versions lack the bridge, return a precise blocker rather
than changing the API or inventing an executor.

## Required evidence

Validate graph syntax and semantics, tool/capability resolution, input/output mapping, ordering,
governance and denial paths. Test Flow-only, Foundry-only and combined boundaries according to
the project selection. An unavailable model can permit structural validation but not a claim
of successful agent execution. Preserve exact command/tool results in sanitized evidence files.
