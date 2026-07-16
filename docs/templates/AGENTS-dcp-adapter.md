# AGENTS.md Template: DCP Adapter And Substrate Component Authors

This file is the canonical DCP adapter authoring guidance. Copy it into a new adapter or
substrate-component repository as `AGENTS.md`, then add only repository-specific build and test
commands below the rules here.

The source of truth for this template lives in:

```text
unfurl-dcp/docs/templates/AGENTS-dcp-adapter.md
```

## Codex Skill

When Codex is being used to create, review, or repair a DCP adapter or substrate component, invoke:

```text
@add-dcp-capability
```

The skill carries the step-by-step runbook, current DCP artifact templates, and verification
checklist. This template remains the repository-level rule set that should be copied into adapter
repos; the skill is the preferred Codex workflow for applying these rules.

To make the skill available from an adapter repository, copy both this template and the skill folder
into the adapter repo root:

```powershell
$adapterRepo = "C:\path\to\adapter-repo"
$unfurlRoot = "C:\path\to\unfurl"
New-Item -ItemType Directory -Force "$adapterRepo\.agents\skills" | Out-Null
Copy-Item -Recurse -Force "$unfurlRoot\unfurl-dcp\.agents\skills\add-dcp-capability" "$adapterRepo\.agents\skills\"
Copy-Item -Force "$unfurlRoot\unfurl-dcp\docs\templates\AGENTS-dcp-adapter.md" "$adapterRepo\AGENTS.md"
```

```bash
adapter_repo=/path/to/adapter-repo
unfurl_root=/path/to/unfurl
mkdir -p "$adapter_repo/.agents/skills"
cp -R "$unfurl_root/unfurl-dcp/.agents/skills/add-dcp-capability" "$adapter_repo/.agents/skills/"
cp "$unfurl_root/unfurl-dcp/docs/templates/AGENTS-dcp-adapter.md" "$adapter_repo/AGENTS.md"
```

After copying, start a fresh Codex session in the adapter repo and invoke `@add-dcp-capability`.

## 1. DCP Is The Contract Boundary

Every adapter or substrate component must describe its integration through DCP constructs before
wiring concrete code.

Required artifacts:

- DCP claim: ownership, refusals, dependencies, offers, faults, and negotiation surface when present.
- Composition contract support: the capability can be bound through a frozen DCP contract.
- Runtime binding support: endpoints, config refs, secret refs, runtime policy, and deployment controls.
- Catalog metadata: `META-INF/unfurl-catalog.yaml` or the repository's equivalent catalog envelope.

Do not hide real integration behavior in private config, code comments, or product-specific sidecars
when it belongs in claim, contract, runtime binding, or catalog metadata.

## 2. Universal Runtime Surface

A DCP capability exposes runtime behavior through the neutral substrate composition API:

```java
ContractInvocable
ContractInvocableFactory
```

The DCP broker registers accepted capabilities through:

```java
CapabilityRegistrar
```

Do not require a host-specific execution interface such as Flow `NodeExecutor`, Foundry
`AgentRuntime`, Spring beans, HTTP controllers, message listeners, or cloud functions as the DCP
capability contract. Those are host adapters.

Correct layering:

```text
DCP capability -> ContractInvocable
Host runtime -> adapts ContractInvocable to its native execution surface
```

Examples:

```text
Flow:    ContractInvocable -> NodeExecutor
HTTP:    ContractInvocable -> POST handler
Queue:   ContractInvocable -> message consumer
Lambda:  ContractInvocable -> function handler
```

## 3. Host Adapters Must Be Explicit

If a host runtime wants to execute accepted DCP capabilities through its own runtime model, it must
provide an explicit adapter.

For Flow-style DAG execution, the expected bridge is:

```text
NodeExecutionRequest
  -> ContractInvocation
  -> ContractInvocable.invoke(...)
  -> ContractInvocationResult
  -> NodeExecutionResult
```

The adapter must preserve:

- contract id
- contract version
- operation/capability name
- consumer and provider component ids
- correlation id
- trace context
- invocation metadata
- DCP result metadata
- structured errors

The adapter must not bypass the frozen contract, re-negotiate at runtime, or call product internals
directly when a DCP contract binding exists.

## 4. Optional Java Annotations

Java annotations may mirror DCP metadata to improve discovery and validation, but they are not the
protocol. The YAML/JSON DCP artifacts and Java interfaces remain authoritative.

Recommended annotation shape:

```java
@DcpCapability(
    name = "agent.run",
    version = "1.0.0",
    surfaces = {"contract.invocable"}
)
public final class AgentRunInvocable implements ContractInvocable {
}
```

Host adapters may declare their local surface:

```java
@DcpExecutionAdapter(
    capability = "agent.run",
    surface = "substrate.node-executor"
)
public final class ContractInvocableNodeExecutor implements NodeExecutor {
}
```

Annotation processors or scanners may validate consistency, but they must not replace DCP claim,
contract, runtime-binding, and catalog validation.

## 5. Product And Vendor Boundaries

Core DCP/substrate code must stay product-neutral and dependency-light.

Do not import product runtimes into a DCP adapter core:

- `unfurl-flow`
- `unfurl-foundry`
- `unfurl-fabric`
- application-specific services

Do not put concrete vendor dependencies in the core contract surface:

- cloud SDKs
- model SDKs
- database drivers
- auth provider clients
- observability SDKs
- queue/broker clients
- web framework APIs

Concrete integrations belong in adapter modules behind ports. The DCP-facing module exposes claims,
contracts, runtime binding metadata, and `ContractInvocable`/`ContractInvocableFactory`.

## 6. Claims Must State Boundaries

Each claim must include:

- concrete concerns owned by the component
- state and decisions owned under each concern
- explicit refusals for out-of-bound concerns
- required and recommended dependencies
- offered capabilities with versions
- fault declarations for runtime failure surfaces
- integration ports for auth, authorization, telemetry, monitoring, policy, and secrets when needed

If an adapter wraps an OSS product, do not claim ownership broader than the product truly owns.
Refuse concerns that belong to the host platform or another component.

## 7. Runtime Bindings Must Use References

Runtime bindings are environment-specific. They may provide endpoints, configuration refs, secret
refs, runtime policy, and deployment controls.

Never inline secrets. Use:

```text
SecretRef
ConfigRef
```

Runtime bindings may disable a binding or tune operational policy, but they must not alter claim
ownership, dependency satisfaction, conflict decisions, trust tier, or invalidation rules captured in
the frozen contract.

Aggregate deployments must use DCP containment:

```text
metadata.extensions.contains
metadata.extensions.children
metadata.extensions.containsClaimUris
metadata.extensions.childClaimUris
```

Do not invent product-specific closure fields as a replacement for child DCP contracts or child DCP
runtime bindings.

## 8. Faults Are First-Class

Runtime failures that matter across component boundaries must be declared as DCP faults.

Each fault must state:

- code
- category
- severity
- affected needs/offers/constraints
- evidence signals
- propagation policy
- allowed remediation actions

Adapters may emit runtime fault signals, but they must not re-negotiate contracts or self-heal beyond
declared allowed actions in the runtime path.

## 9. Tests Required For Every Adapter

Every DCP adapter/substrate component must test:

- claim validation
- catalog metadata round-trip
- composition contract validation
- runtime binding validation
- no inline secrets
- `ContractInvocable` execution success and failure
- `ContractInvocableFactory` materialization from contract binding
- host adapter mapping, when a host-specific surface is supplied
- fault declarations and runtime fault signal behavior
- forbidden import boundaries
- no model/cloud/database/auth/telemetry SDKs in protocol/core modules

For Flow adapters, additionally test:

- `ContractInvocable -> NodeExecutor` mapping
- DCP metadata propagation into node outputs/events
- `uses: <capability>` dispatch against the accepted DCP capability

## 10. Documentation Required

Each adapter repo must document:

- what OSS product or runtime is being adapted
- which DCP concerns it owns
- which concerns it refuses
- which DCP capabilities it offers
- which dependencies and runtime bindings it requires
- what host adapters are provided
- which deployment shapes and binding modes are supported
- how to build, test, and package the catalog artifact

The docs must point back to this template as the inherited source of truth.
