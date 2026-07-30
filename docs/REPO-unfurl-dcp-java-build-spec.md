# Repository Build Spec (Source of Truth): `unfurl-dcp` Java

**Document status:** Active production build spec (Java substrate/library path).
**Audience:** Implementation teams building `unfurl-dcp` as a Java library.
**Supersedes for production:** `REPO-unfurl-dcp-build-spec.md` (legacy Python spec).

---

## What This Repository Is

`unfurl-dcp` is a **library** that defines and operates the Domain Claim Protocol. It is an embedded substrate component, not a deployed service. It provides:

- The protocol schemas as Java models:
  - claim
  - fault vocabulary and runtime fault signal
  - composition contract
  - runtime binding
  - webapp manifest
  - capability documentation projection
  - negotiation question schema
- Per-schema validators plus cross-schema validation rules.
- A single shared component-description model that projects into both claim and manifest.
- A structural resolver for `need -> capability` binding.
- Question schema rendering for both human interview and model prompt output.
- Contract freezing/loading and provenance/trust handling.
- A deterministic runtime composition broker that turns accepted frozen contracts into host capability registrations through DCP-owned SPI.

Protocol responsibilities are unchanged from prior specs (claim/manifest/contract/runtime binding/resolver/questions), but all implementation targets in this document are Java deliverables.

---

## Java Package Layout

```text
src/main/java/com/unfurl/dcp/
  description/      // shared component description model + projection logic
  claim/            // claim schema models + validator
  fault/            // fault declarations, runtime fault signals, deterministic propagation gate
  manifest/         // webapp manifest models + validator
  documentation/    // capability documentation projection models + validator
  contract/         // composition contract, freeze/load, provenance, trust, verification
  runtimebinding/   // runtime binding schema + validator
  questions/        // question schema + action context + renderers + captured-answer model
  resolver/         // need->capability structural matching
  validation/       // cross-schema validation
  versioning/       // semver compatibility helpers
  broker/           // deterministic runtime composition broker
  spi/              // ContractStore, ContractInvocableFactory, CapabilityRegistrar, BrokerEventSink
  testing/          // lightweight fixtures for downstream repos (test scope)
```

Recommended layering:
- `model` records/POJOs + Jackson annotations.
- `validator` classes using Jakarta Bean Validation + custom constraint validators.
- `service` classes for projection/freeze/resolve/render operations.

---

## Adapter Authoring Source Of Truth

The canonical `AGENTS.md` template for DCP adapter and substrate-component authors lives at:

```text
unfurl-dcp/docs/templates/AGENTS-dcp-adapter.md
```

External OSS adapters and new substrate-component repos should copy that file as their local
`AGENTS.md` before adding repository-specific build commands. The template encodes the DCP boundary
rules that every adapter must follow:

- DCP capabilities expose `ContractInvocable` / `ContractInvocableFactory`.
- The broker registers accepted capabilities through `CapabilityRegistrar`.
- Host runtimes adapt accepted `ContractInvocable` instances to native execution surfaces such as
  Flow `NodeExecutor`, HTTP handlers, queue consumers, or function handlers.
- Java annotations (`@DcpCapability`, `@DcpExecutionAdapter`) may aid discovery and consistency
  validation, but DCP claim, contract, runtime binding, catalog metadata, and Java interfaces
  remain authoritative.
- Product/vendor implementations stay behind adapters and ports; DCP core remains host-neutral.

---

## Dependency Guidance (Java)

**Internal:**
- `unfurl-substrate` (shared reference and invocable contract abstractions only).

**External (Java):**
- **Jackson** (`jackson-databind`, `jackson-dataformat-yaml`, optionally `jackson-module-parameter-names`) for JSON/YAML model serialization.
- **Jakarta Validation + Hibernate Validator** for schema and cross-schema validation.
- **Semver library** (e.g., `semver4j` or equivalent) for version range compatibility in resolver/versioning.

Do not use Python-era dependencies (`pydantic`, `pyyaml`, `packaging`) in production-path guidance.

---

## Schema Model Strategy

- Use immutable Java records where practical (or immutable POJOs with builders).
- Keep wire names canonical to DCP (`capability`, `need`, `accepted_providers`, `ownership_position`, `resolution_guidance`, `answer_grounding`).
- Centralize polymorphic sections with explicit type discriminators when needed.
- Maintain one canonical `ComponentDescription` model as the source for:
  - `toClaim(ComponentDescription)`
  - `toManifest(ComponentDescription)`
- Model `ActionContext` as a design-time input in `questions/`, not as a runtime artifact.
- Model capability documentation projection in `documentation/`; validate it from accepted contracts, active runtime
  bindings, host-registered capabilities, explicit schemas, and declared faults.

This ensures claim/manifest drift is structurally prevented.

---

## Validation Strategy

Validation is layered:
1. **Field-level constraints** via Jakarta annotations (`@NotNull`, `@NotEmpty`, etc.).
2. **Object-level schema constraints** via class-level custom validators.
3. **Cross-schema constraints** in `com.unfurl.dcp.validation` services.

Required rule classes include:
- Claim invariants (required sections, refusals/boundary principles, negotiation_surface for intelligent components).
- Fault invariants (declared faults affect at least one need/offer/constraint; parent-impact propagation rules include a gate condition).
- Manifest invariants (claim identity/version parity, permission derivability, theme mode constraints).
- Contract invariants (party count/version pinning, provenance consistency, trust derivation consistency, runtime violation hard-fail policy).
- Runtime binding invariants (contract linkage/version parity, no inline secrets, runtime-policy firewall).
- Capability documentation invariants (accepted contract source, active runtime binding source, registered capability
  source, explicit request/response schemas, declared fault references, visibility policy).
- Cross-schema references (manifest->claim, contract capability->claim capability/version).

---

## Versioning Approach

- DCP schema version and component/claim versions are SemVer.
- Use a Java semver library to evaluate compatibility constraints.
- Resolver matches are strictly structural:
  - capability exists
  - version satisfies range
  - consumer access policy allows the consumer
- Resolver performs no model/AI reasoning.

---

## Java-Mapped Acceptance Criteria by Protocol Responsibility

### 1) Shared Description + Projections (Claim/Manifest)
- `ComponentDescription` can produce valid `Claim` and `WebappManifest` Java objects.
- Manifest permissions are derived from description boundaries (not duplicated free text).
- Claim/manifest `componentUri` and `version` remain equal.

### 2) Claim
- YAML/JSON claim documents deserialize via Jackson and validate successfully.
- Invalid claims (empty refusals, missing boundary principles, missing negotiation surface for intelligent component) fail validation with deterministic errors.
- `faults` is always explicit in Java producers and claim YAML. Producers with no declared operational faults use `faults: { emitted: [] }`; constructors must not silently add it.

### 2B) Fault Vocabulary And Propagation
- Claims carry a first-class `faults` section declaring emitted fault codes, affected needs/offers/constraints, propagation policy, evidence, and allowed remediation actions.
- `FaultPropagationGate` deterministically evaluates a runtime `FaultSignal` against the source claim: undeclared faults do not propagate, `NONE` impact suppresses, and `DEGRADED`/`BLOCKED` impact propagates without runtime renegotiation.
- Fault models serialize through the same snake_case JSON/YAML path as other public DCP records.

### 3) Manifest
- Manifest generated from description validates against linked claim.
- Permission entries lacking claim basis fail.
- Non-`suggestive` theme mode fails validation.

### 4) Contract
- Contract freezes into immutable artifact and reloads byte-stably.
- Frozen contract implements substrate contract-invocation interface.
- Provenance fields are validated by mode (`c2c` requires model metadata; `h2c` requires human-in-loop marker).
- Trust tier is derivable and non-contradictory.
- Invalid or tampered proof fails offline verification.
- Aggregate contracts reuse the recursive DCP containment bridge in metadata extensions (`contains`, `children`, `containsClaimUris`, `childClaimUris`) to reference child contract ids. Tree validation fails on missing child refs or cycles. Product/private planner metadata is not a valid substitute for child DCP contracts.

### 4B) Runtime Binding
- Binding links to real contract id/version and matching party versions.
- Inline secrets are rejected; reference-only secret strategy enforced.
- Runtime policy cannot alter ownership/dependency/conflict/trust/invalidation decisions.
- Aggregate runtime bindings reuse the recursive DCP containment bridge in metadata extensions (`contains`, `children`, `containsClaimUris`, `childClaimUris`) to reference child runtime binding ids. Tree validation fails on missing child refs, cycles, or inline secrets in any descendant. Product-specific runtime closure sidecars are not valid substitutes for child DCP runtime bindings.

### 5) Resolver + Versioning
- Compatible need/capability pair resolves.
- Incompatible versions are rejected.
- Access-control restrictions (e.g., named-components-only) are enforced.
- Required offer details resolve structurally and deterministically. Scalar details match by equality, list details by containment, and map details recursively by subset. Capability execution modes such as `agent.run` `execution_modes: [simple, harness]` must be selectable through this path.

### 6) Cross-Schema Validation
- Manifest without claim fails.
- Contract capability binding to missing capability fails.
- Contract pinning to mismatched claim versions fails.

### 7) Questions
- Canonical question set renders to both human interview and model prompt from one definition.
- Captured answers feed contract building and serialize to training tuple shape.
- Action-scoped authoring context selects targeted add/remove/replace/connect/disconnect/runtime-configuration
  questions and is not serialized into Plane 3 runtime invocation or frozen contract semantics.

### 7B) Capability Documentation Projection
- Generated OpenAPI, Swagger UI, AsyncAPI, MCP tool documentation, or equivalent runtime-facing docs are projected
  from accepted composition contracts, active runtime bindings, host-registered capabilities, explicit schemas, and
  declared faults.
- Classpath scans, plugin jars, tool registries, provider registries, model catalogs, RAG/vector stores, prompts,
  logs, model outputs, and implementation DTOs are rejected as documentation proof.
- Missing explicit request/response schemas fail with a documentation gap instead of inferred public schemas.

### 8) Runtime Composition Broker
- `present(claim, context)` validates the claim, performs frozen-contract lookup, verifies the offline signature, and returns `ACCEPT(MATCH_FOUND)` or structured refusal reasons without model calls or network access.
- `accept(disposition, registrar, factory, context)` accepts only `DispositionKind.ACCEPT`, re-fetches the frozen contract by id/version, re-verifies its signature, and registers exactly the contract's binding through `CapabilityRegistrar`.
- `revoke(handle, registrar, context)` unregisters exposed capabilities without renegotiation.
- `invalidate(handle, registrar, context)` emits `CONTRACT_INVALIDATED` and revokes exposed capabilities without attempting runtime self-heal or design-time renegotiation.
- Broker events carry metadata only by default and propagate correlation ids through the broker path.

### 9) SPI
- `ContractStore` provides deterministic frozen-contract lookup by provider claim identity plus provider capability, and by contract id/version. Provider identity alone is not a valid runtime lookup key because a single claim can publish multiple offers such as `agent.run`, `rag.search`, and `tool.call`.
- `ContractInvocableFactory` materializes the host executor for the contract's single binding without making DCP depend on host products.
- `CapabilityRegistrar` is the only mutable registration port used by the broker; the broker must not import or mutate substrate's read-only `CapabilityRegistry`.
- `BrokerEventSink` defaults to `NoopBrokerEventSink`, which performs no I/O.

### 10) Architecture And Enterprise Guardrails
- ArchUnit enforces package boundaries for the single-artifact layout: design-time packages do not depend on broker/SPI, `trust` remains a leaf, production code does not depend on test fixtures, and the broker does not import question/manifest/documentation/description packages.
- No host product packages, web frameworks, HTTP clients, DB clients, cloud SDKs, auth SDKs, AI SDKs, or observability SDKs are production dependencies.
- Offline verification uses caller-supplied key sets only; no remote key fetches or callbacks are permitted.
- Property tests cover claim-validator determinism, resolver stability under candidate shuffling, renderer identity, freeze/load byte stability, and SemVer helper behavior.

---

## Migration Note: Python-Only vs Java-Required

### Python-only (legacy / research tooling)
- Experimental research workflows and training-data experimentation utilities.
- Ad hoc analysis scripts and non-production validation experiments.

### Java-required (production substrate/library path)
- All protocol models, validators, resolver logic, contract freeze/load/verify, and runtime binding enforcement.
- Production dependency guidance and CI guardrails.
- Source-of-truth schema behavior for downstream product embedding.

In short: Python remains a research support lane; Java is the required production implementation lane for `unfurl-dcp`.

---

## GitHub Packages

This repository participates in the `UnfurlSystemsLab` private Maven package chain. The Lab source repository is
`UnfurlSystemsLab/dcp`, while the Maven artifact coordinates remain `com.unfurl.dcp:unfurl-dcp`.

- Publish: GitHub Actions deploys this repository's Maven artifact to `https://maven.pkg.github.com/UnfurlSystemsLab/dcp` using Maven server id `github`.
- Consume: this repository resolves internal `com.unfurl...` artifacts through `https://maven.pkg.github.com/UnfurlSystemsLab/*`.
- Credentials: local and CI Maven settings must provide server id `github`; use `GITHUB_TOKEN` for same-repository publish and `CI_REPO_TOKEN` or a PAT with `read:packages` for cross-repository private dependency reads.
- Bootstrap order: publish `unfurl-substrate` first, then publish `unfurl-dcp`, then publish `unfurl-substrate-api` and higher-level repositories.
