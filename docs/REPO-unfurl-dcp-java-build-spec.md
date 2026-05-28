# Repository Build Spec (Source of Truth): `unfurl-dcp` Java

**Document status:** Active production build spec (Java substrate/library path).
**Audience:** Implementation teams building `unfurl-dcp` as a Java library.
**Supersedes for production:** `REPO-unfurl-dcp-build-spec.md` (legacy Python spec).

---

## What This Repository Is

`unfurl-dcp` is a **library** that defines and operates the Domain Claim Protocol. It is an embedded substrate component, not a deployed service. It provides:

- The five protocol schemas as Java models:
  - claim
  - composition contract
  - runtime binding
  - webapp manifest
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
  manifest/         // webapp manifest models + validator
  contract/         // composition contract, freeze/load, provenance, trust, verification
  runtimebinding/   // runtime binding schema + validator
  questions/        // question schema + renderers + captured-answer model
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

This ensures claim/manifest drift is structurally prevented.

---

## Validation Strategy

Validation is layered:
1. **Field-level constraints** via Jakarta annotations (`@NotNull`, `@NotEmpty`, etc.).
2. **Object-level schema constraints** via class-level custom validators.
3. **Cross-schema constraints** in `com.unfurl.dcp.validation` services.

Required rule classes include:
- Claim invariants (required sections, refusals/boundary principles, negotiation_surface for intelligent components).
- Manifest invariants (claim identity/version parity, permission derivability, theme mode constraints).
- Contract invariants (party count/version pinning, provenance consistency, trust derivation consistency, runtime violation hard-fail policy).
- Runtime binding invariants (contract linkage/version parity, no inline secrets, runtime-policy firewall).
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

### 4B) Runtime Binding
- Binding links to real contract id/version and matching party versions.
- Inline secrets are rejected; reference-only secret strategy enforced.
- Runtime policy cannot alter ownership/dependency/conflict/trust/invalidation decisions.

### 5) Resolver + Versioning
- Compatible need/capability pair resolves.
- Incompatible versions are rejected.
- Access-control restrictions (e.g., named-components-only) are enforced.

### 6) Cross-Schema Validation
- Manifest without claim fails.
- Contract capability binding to missing capability fails.
- Contract pinning to mismatched claim versions fails.

### 7) Questions
- Canonical question set renders to both human interview and model prompt from one definition.
- Captured answers feed contract building and serialize to training tuple shape.

### 8) Runtime Composition Broker
- `present(claim, context)` validates the claim, performs frozen-contract lookup, verifies the offline signature, and returns `ACCEPT(MATCH_FOUND)` or structured refusal reasons without model calls or network access.
- `accept(disposition, registrar, factory, context)` accepts only `DispositionKind.ACCEPT`, re-fetches the frozen contract by id/version, re-verifies its signature, and registers exactly the contract's binding through `CapabilityRegistrar`.
- `revoke(handle, registrar, context)` unregisters exposed capabilities without renegotiation.
- Broker events carry metadata only by default and propagate correlation ids through the broker path.

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
