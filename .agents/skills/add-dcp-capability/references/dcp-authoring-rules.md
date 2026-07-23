# DCP authoring rules

## Source model

`unfurl-dcp` is the runtime protocol library. It validates claims, loads frozen contracts, verifies signatures offline, and registers accepted capabilities through the composition broker. It is not a workflow engine, transport, persistence layer, key server, or design-time negotiator.

## Required integration artifacts

Every third-party adapter should provide:

1. A provider claim describing identity, domain, refusals, dependencies, offers, conflict resolution, integration ports, faults, and metadata.
2. A consumer need that can structurally resolve to the provider offer.
3. A composition contract that freezes the consumer need, provider capability, mapping, transport, provenance, trust, and invalidation policy.
4. A runtime binding for endpoint, configuration, secret references, operational policy, deployment controls, and child bindings when aggregate.
5. Catalog metadata, normally `META-INF/unfurl-catalog.yaml` or the target repository's equivalent catalog envelope.
6. A neutral `ContractInvocable` implementation and, when needed, a `ContractInvocableFactory`.
7. A host/client adapter only when a native runtime surface must invoke the accepted DCP capability.
8. Action-scoped authoring metadata for add/remove/replace/connect/disconnect/configure-runtime flows when the adapter
   requires operator choices.

## Claims

- Use canonical DCP section names from the active HLD/LLD: `identity`, `domain`, `refusals`, `dependencies`,
  `offers`, `conflict_resolution`, `negotiation_surface`, `integration_ports`, `faults`, and `metadata`.
- Use snake_case wire field names. When validating against the current Java implementation, use the enum spelling
  emitted by the repository's configured mapper unless the mapper explicitly accepts aliases.
- Use a stable component URI and SemVer version.
- Name capabilities as domain actions such as `billing.charge`, `search.query`, or `document.render`.
- State actual state and decision ownership, not marketing-level product scope.
- Refuse adjacent concerns owned by the host, another component, or an operator.
- Distinguish required dependencies from recommended dependencies.
- Declare auth, authorization, telemetry, monitoring, policy, and secret integration ports when applicable.
- Make faults explicit, including an empty emitted-fault list when the schema requires it.

## Consumer needs and contracts

- Capture the consumer need name and version range before writing provider code.
- Resolve the need to a provider claim offer structurally; do not rely on natural-language similarity once the contract is frozen.
- Generate a composition contract whose `binding.provider_capability` exists in the provider claim at a satisfying version.
- Freeze and sign the contract when the target repository owns contract publication.
- Store frozen contracts by provider claim URI, provider claim version, and provider capability; provider identity alone is not enough for multi-offer claims.

## Faults

For each cross-boundary fault state:

- stable code
- category and severity
- affected needs, offers, or constraints
- evidence signals
- propagation policy
- allowed remediation actions

Runtime adapters may emit declared signals. They must not renegotiate contracts or perform undeclared remediation.

## Runtime binding firewall

- Use `SecretRef` and `ConfigRef`; reject inline credentials.
- Allow endpoints and environment-specific deployment controls.
- Do not change ownership, dependency satisfaction, conflict resolution, trust tier, or invalidation rules frozen into the contract.
- Link to the exact contract id/version and pinned provider/consumer component versions.
- Use DCP containment metadata for aggregate runtime bindings; do not invent product-specific runtime closure fields.

## Action-scoped authoring

Use DCP `action_context` only on the design-time authoring path. It may identify the selected operation, component,
catalog entry, replacement, ports, capabilities, needs, substrate needs, session, correlation id, policy refs, and
target environment.

Adapters should expose enough metadata for the authoring agent to ask:

- add/replace/connect/configure: provider, endpoint, config, secret reference, runtime target, telemetry, audit, policy,
  and binding questions
- remove/disconnect: dependent contract, child binding, replacement-before-removal, state cleanup, fault, and revocation
  impact questions

Captured answers must feed normal DCP artifacts or explicit product intents. Do not keep required answers only in UI
state, prompts, logs, implementation DTOs, or private sidecars. Do not serialize `action_context` into runtime invocation.

## Governed documentation

- Generate OpenAPI, Swagger UI, AsyncAPI, MCP tool documentation, or equivalent runtime-facing docs from accepted
  contracts, runtime bindings, host-registered capabilities, and explicit schemas.
- Do not treat classpath discovery, plugin jars, vendor registries, tool registries, provider catalogs, model catalogs,
  RAG/vector stores, or raw component registries as public API documentation sources.
- A public capability needs an explicit request schema, response schema, declared fault surface, and visibility policy.
- If a capability is accepted and intended to be public but no schema exists, record a documentation gap and stop rather
  than inferring the public contract from logs, prompts, model output, retrieved chunks, or implementation DTOs.

## Runtime broker

`present(claim, providerCapability, context)` validates the claim, locates the matching frozen contract for the requested capability, and verifies its signature. Use `present(claim, context)` only when the claim has exactly one offer. `accept` re-fetches and re-verifies the contract, materializes the invocable, and registers the exact binding. `revoke` and `invalidate` remove the registered capability without renegotiation.

The host supplies:

- `ContractStore`
- `CapabilityRegistrar`
- `ContractInvocableFactory`
- `BrokerEventSink` (a no-op is acceptable only where audit is unnecessary)

## Layering

- DCP-facing code depends on neutral substrate composition APIs only.
- Vendor dependencies remain in vendor adapter modules.
- Host runtime dependencies remain in host adapter modules.
- The broker is the only component that drives mutable capability registration through `CapabilityRegistrar`.
- DCP annotations may assist discovery but cannot replace canonical protocol artifacts.
- Product docs must name any unsupported DCP concern explicitly rather than hiding the gap in adapter code.
