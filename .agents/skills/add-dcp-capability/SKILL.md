---
name: add-dcp-capability
description: Analyze a third-party application or open-source project and add a standards-compliant Unfurl Domain Claim Protocol (DCP) integration. Use when Codex needs to identify capability boundaries, author DCP claims and catalog metadata, implement ContractInvocable or ContractInvocableFactory adapters, create runtime bindings, declare faults, connect a host through CapabilityRegistrar, or validate the present/accept/invoke/revoke lifecycle. Also use for reviewing or repairing an existing DCP adapter.
---

# Add DCP Capability

Turn existing application behavior into a bounded, contract-governed DCP capability. Preserve the third-party application's native architecture behind an explicit adapter.

## Load references selectively

- Read [references/dcp-authoring-rules.md](references/dcp-authoring-rules.md) before designing or reviewing an integration.
- Read [references/adapter-patterns.md](references/adapter-patterns.md) after identifying the application's available integration surfaces.
- Read [references/verification-checklist.md](references/verification-checklist.md) before implementation and again before completion.
- Copy and adapt [assets/claim.yaml](assets/claim.yaml), [assets/composition-contract.yaml](assets/composition-contract.yaml),
  [assets/runtime-binding.yaml](assets/runtime-binding.yaml), and [assets/unfurl-catalog.yaml](assets/unfurl-catalog.yaml);
  never present placeholders as completed artifacts.

## Workflow

### Phase 1: Design And Scope

Read the target repository's governing HLD, LLD, and build spec before changing code. If the target repository has no
DCP adapter design, create or update the appropriate design doc first and then implement against that design.

Inspect the repository, build system, runtime model, public interfaces, configuration, authentication, persistence,
error model, and existing tests. Identify both sides of the DCP edge:

- provider capability: the concrete behavior exposed by this component
- consumer need: the client/host need that will bind to that capability

For each candidate, record:

- capability name and SemVer version
- consumer need name and required version range
- inputs, outputs, and operational side effects
- state and decisions owned by the provider
- concerns explicitly not owned
- required and recommended dependencies
- authentication, authorization, telemetry, policy, monitoring, config, and secret needs
- meaningful cross-component faults

Prefer the smallest end-to-end capability that proves the DCP lifecycle. Ask the user only when competing scopes would materially change the integration.

### Phase 2: Choose The Boundary

Expose DCP runtime behavior through `ContractInvocable`; use `ContractInvocableFactory` when construction depends on the frozen contract or runtime binding.

Keep this direction of dependency:

```text
third-party implementation <- vendor adapter <- ContractInvocable
host-native runtime         <- host adapter   <- accepted ContractInvocable
```

Do not make Spring beans, HTTP controllers, Flow `NodeExecutor`, Foundry runtimes, queue listeners, cloud functions, CLI commands, or vendor SDK types the DCP contract. Treat them as implementation surfaces behind adapters.

### Phase 3: Plan Protocol Artifacts

Produce a short integration plan containing:

1. selected capability and exclusions
2. consumer need and provider capability version range
3. claim identity and version
4. composition contract parties, binding, mapping, transport, provenance, and invalidation policy
5. runtime binding endpoint/config/secret references
6. vendor adapter and transport choice
7. host adapter, if required
8. declared faults and allowed remediation
9. generated files and test plan

Flag assumptions. Do not broaden ownership merely because the wrapped product can technically perform adjacent operations.

### Phase 4: Author Canonical Artifacts

Create or update:

- DCP claim: identity, domain, refusals, dependencies, offers, conflict resolution, integration ports, faults, and metadata
- consumer need: the client requirement that resolves to the provider offer
- composition contract: parties, single binding, data mapping, transport, expectations, provenance, trust, invalidation, and containment metadata when aggregate
- frozen/signed contract artifact when the repository owns contract freeze/sign output
- runtime binding: contract id/version, provider/consumer instances, endpoint/config/secret refs, runtime policy, deployment controls, lifecycle, and containment metadata when aggregate
- catalog entry: normally `META-INF/unfurl-catalog.yaml`
- adapter implementation exposing `ContractInvocable`
- `ContractInvocableFactory` when runtime materialization is required
- explicit host adapter when translating to a native execution surface

Treat YAML/JSON DCP artifacts and Java interfaces as authoritative. Annotations may mirror them for discovery but must not replace them.

Never inline credentials. Never let runtime bindings change frozen ownership, dependency satisfaction, conflict decisions, trust, or invalidation rules.

For aggregate integrations, use DCP containment metadata (`contains`, `children`, `containsClaimUris`, or `childClaimUris`) instead of private closure fields.

### Phase 5: Implement Provider, Client, And Host Adapters

Provider side:

1. translate `ContractInvocation` into the vendor/application call
2. translate vendor output into `ContractInvocationResult`
3. map vendor failures to structured errors or declared DCP faults
4. keep vendor SDKs, transport clients, and framework types behind adapter ports

Client/host side:

1. load the frozen contract into a `ContractStore`
2. construct the host `CapabilityRegistrar`
3. construct the `ContractInvocableFactory`
4. construct a `BrokerEventSink` that satisfies the deployment audit policy
5. adapt accepted `ContractInvocable` instances into the host-native surface only after broker acceptance

### Phase 6: Implement Lifecycle Integration

Use the runtime broker as a deterministic contract gate:

1. load and validate the claim
2. load and verify the frozen contract into `ContractStore`
3. `present(claim, providerCapability, context)`; use the no-capability overload only for single-offer claims
4. continue only for `DispositionKind.ACCEPT`
5. `accept(disposition, registrar, factory, context)`
6. invoke the registered capability through the host registry
7. `revoke` on shutdown or withdrawal
8. `invalidate` when the frozen contract becomes invalid

Do not negotiate, select providers, fetch keys, or self-heal inside the runtime path. Design-time negotiation belongs outside `unfurl-dcp`.

### Phase 7: Preserve Invocation Semantics

Across vendor and host adapters preserve:

- contract id and version
- capability or operation name
- consumer and provider component ids
- correlation id and trace context
- invocation and DCP result metadata
- structured errors

Map vendor errors to declared DCP faults without leaking secrets or unnecessary payloads.

### Phase 8: Verify

Run repository-native build, formatting, static analysis, and tests. Add focused tests for:

- valid claim and catalog loading
- consumer need to provider offer resolution
- contract freeze/load byte stability when contract generation is changed
- refusal and dependency boundaries
- contract and binding compatibility
- no-inline-secret validation
- capability-specific present/accept/invoke/revoke behavior
- malformed claim, missing contract, invalid signature, and stale disposition
- runtime binding contract id/version and party-version compatibility
- fault declaration, emission, propagation, and allowed remediation
- correlation and trace propagation
- architecture boundaries preventing host or vendor dependencies from entering DCP-facing core

Use [references/verification-checklist.md](references/verification-checklist.md) as the completion gate. Report commands run, results, assumptions, and remaining external setup.

## Guardrails

- Keep DCP core product-neutral and dependency-light.
- Put vendor SDKs, database drivers, cloud clients, auth clients, transports, frameworks, and observability SDKs behind adapter ports.
- Register accepted capabilities only through `CapabilityRegistrar`; do not mutate a read-only substrate registry directly.
- Verify signatures offline using caller-supplied keys.
- Do not add transport, persistence, key discovery, or design-time reasoning to `unfurl-dcp` itself.
- Do not claim completion when placeholders, unresolved secrets, unverified schemas, or unrun lifecycle tests remain.

## Completion output

Lead with the implemented capability. Summarize the boundary, generated artifacts, adapter path, tests run, and any external contract/key/runtime-binding setup still required.
