# Repository Build Spec: `unfurl-dcp` (Legacy Python Spec)

**Document status:** Claude-Code-ready build spec. Downstream of HLD-C (DCP v0.2 model) and HLD-C2 (DCP schemas).
**Audience:** Claude Code. Self-contained brief for building the `unfurl-dcp` library.
**Read order for an agent:** this spec → HLD-C2 (the schemas it implements) → HLD-C (the model behind them) only if a schema decision is unclear.

---

> [!WARNING]
> **Legacy / Deprecated for production:** This document captures the original Python-oriented build plan and is retained for historical/reference use.
>
> **Source of truth for production implementation is now Java:** `Docs/files/REPO-unfurl-dcp-java-build-spec.md`.
>
> Python guidance here should be treated as research-tooling context only.

## What This Repository Is

`unfurl-dcp` is the **library** that defines and operates the Domain Claim Protocol. It is substrate (embedded by products and by Fabric), never a deployed service. It provides:

- The **five schemas** (claim, composition contract, runtime binding, webapp manifest, negotiation question schema) as typed models.
- Validators for each schema and the cross-schema rules.
- The **single shared component-description definition** from which the claim and webapp manifest are both derived (HLD-C §4 / HLD-C2 §D). This is the central design constraint: claim and manifest are NOT independent.
- The **resolver** that binds a consumer need to a provider capability (used by Plane 2 negotiation to build a contract).
- The **question-schema renderer** that emits the negotiation question set as (a) a human interview structure for H2C and (b) a model prompt for C2C — from one definition.
- Contract freezing/loading and provenance/trust recording.

**Field naming:** use the canonical HLD-C2 field names — `capability` (not offer), `need` (not dependency), `accepted_providers` (not satisfied_by), `ownership_position` (not claimant_position), `resolution_guidance` (not negotiation_notes), `answer_grounding` (not grounding_guarantees), `consumer_access` (not consumers). Migration code MAY accept the old names as temporary aliases.

It does NOT contain: the negotiation reasoning itself (that is Fabric's model — this library only provides the questions and the contract structure), any execution engine, any product logic, any network transport implementation, any UI.

## Package

```
unfurl_dcp/
  description/      # the SHARED component-description definition (claim + manifest derive from this)
  claim/            # claim schema models + validator (projection of description)
  manifest/         # webapp manifest models + validator (projection of description)
  contract/         # composition contract models, freeze/load, provenance, trust
  runtime_binding/  # runtime binding schema + validator (environment-specific wiring of a contract)
  questions/        # negotiation question schema + renderer (human + model projections)
  resolver/         # need->capability binding resolver
  validation/       # cross-schema validation
  versioning/       # semver matching for capability versions
  testing/          # fakes + fixtures for downstream repos
```

## Dependencies

**Internal:**
- `unfurl-substrate` (for shared types only — e.g. the `$.x.y` reference type reused in contract data_mapping). Depend on it minimally; if a needed type can live in dcp instead, prefer that.

**Legacy external (Python, deprecated for production):**
- `pydantic` → **Java equivalent:** Jakarta Validation + Hibernate Validator
- `pyyaml` → **Java equivalent:** Jackson (`jackson-dataformat-yaml`)
- `packaging` → **Java equivalent:** semver library (e.g., semver4j)

For production substrate/library work, use the Java dependency set in `REPO-unfurl-dcp-java-build-spec.md`.

## Forbidden Imports

`unfurl_dcp` MUST NOT import, directly or transitively:
- Any model/AI SDK (openai, anthropic, transformers, etc.) — **this is critical**: the negotiation *reasoning* lives in Fabric, not here. This library provides the question structure and the contract shape; it never calls a model.
- Any web framework, HTTP client, queue, cache, database driver, cloud SDK, auth client, observability SDK.
- Any other `unfurl-*` repo except `unfurl-substrate`.
- Any product repo (`unfurl-flow`, `unfurl-foundry`, etc.) or `unfurl-fabric`.

CI enforces this with import-linter. The AI-SDK prohibition is the one most likely to be violated under pressure ("just call a model to validate the contract") and must be guarded hardest. If a feature seems to need a model, it belongs in Fabric, not here.

## Out of Scope

- Negotiation reasoning (Fabric).
- Contract execution / invocation transport (the products + the substrate's in-process call mechanism).
- The federated flywheel mechanics (deferred per HLD-C2 §F).
- Trust *policy* — this library records `trust.tier`; what a deployment does with it is policy elsewhere.
- Wire-protocol serialization beyond YAML/JSON equivalence at the model level.

---

## Phased Build Plan

### Phase 1 — The shared component-description definition

Build `unfurl_dcp/description/` FIRST. This is the single source from which claim and manifest are projections. Getting this first prevents the drift the whole design forbids.

- Define a `ComponentDescription` model that holds the canonical facts: identity, concerns (with nested state/decisions per settled #1), refusals, dependencies, offers, conflict positions, negotiation surface, and the human-surface facts the manifest needs (routes, navigation, permissions basis, theme contribution).
- Define the two **projection functions**:
  - `to_claim(description) -> Claim`
  - `to_manifest(description) -> WebappManifest`
- These functions are the mechanism that guarantees claim and manifest cannot drift.

**Acceptance criteria:**
- A `ComponentDescription` produces both a claim and a manifest.
- The manifest's `permissions` are derived (not re-specified) from the description's access boundaries.
- The manifest's `component_uri`/`version` always equal the claim's.
- Round-trip: description → claim → (no manifest fields lost that the manifest needs).

### Phase 2 — Claim schema and validator

Implement `unfurl_dcp/claim/` per HLD-C2 §A.

- All sections as typed pydantic models (identity, domain with nested concern state/decisions, refusals, dependencies, offers, conflict_resolution, negotiation_surface, metadata).
- Loader from YAML/JSON.
- Validator enforcing HLD-C2 §E claim rules:
  - required sections present; `refusals` and `boundary_principles` non-empty;
  - `kind == intelligent_component` ⇒ `negotiation_surface` present;
  - concern identifiers unique;
  - `dcp_version >= 0.2.0`; `claim_version == identity.version`;
  - refusal-specificity warning (vague concern / short rationale).

**Acceptance criteria:**
- The `keycloak-domain-claim-example.md` claim, updated to the v0.2 folded shape, loads and validates clean.
- A claim with empty refusals fails.
- A claim with no boundary_principles fails.
- An intelligent_component claim with no negotiation_surface fails.

### Phase 3 — Webapp manifest schema and validator

Implement `unfurl_dcp/manifest/` per HLD-C2 §C.

- Typed models for the manifest (routePrefix, routes, navigation, permissions, themeContribution, bootstrap).
- Validator enforcing: `component_uri`/`version` match the claim; `permissions` derivable from the claim; `themeContribution.mode == suggestive`.
- The manifest is normally produced via `to_manifest` (Phase 1). The standalone validator exists to check hand-edited or third-party manifests.

**Acceptance criteria:**
- A manifest generated from a description validates against its claim.
- A manifest whose permissions have no basis in the claim fails.
- A manifest with `themeContribution.mode` other than `suggestive` fails.
- A manifest referencing a component with no claim fails the cross-schema check (Phase 6).

### Phase 4 — Composition contract: models, freeze, provenance, trust

Implement `unfurl_dcp/contract/` per HLD-C2 §B.

- Typed contract model: parties (pinned claim versions), binding, data_mapping, transport, expectations, provenance, trust, invalidation.
- `freeze(contract) -> FrozenContract`: produces the immutable canonical artifact. Once frozen, mutation raises. **`FrozenContract` MUST implement the `ContractInvocable` interface defined in `unfurl-substrate` (substrate Phase 9)** — this is how the substrate's in-process composition mechanism executes a contract without importing dcp (dependency inversion: substrate defines the interface, dcp satisfies it).
- `load(serialized) -> FrozenContract`.
- `verify(frozen_contract, fabric_public_key) -> ok | fail`: offline verification of the contract's `proof` (HLD-E §1) — checks the signature against the configured Fabric public key AND recomputes `integrity_hash` against the governed definition. Pure crypto; NO network call; NO model. This is the runtime's tamper-evidence check (verification of a design-time decision), not authorization.
- Provenance recording: `authored_by`, `mode`, `model_id?`, `fabric_version?`, `human_in_loop`, `authored_at`.
- Trust derivation: `trust.tier = self if authored_by == embedded_self else neutral`.
- Validator enforcing HLD-C2 §E contract rules:
  - exactly two parties, versions pinned;
  - `in_process` transport only when a co-packaged flag is set;
  - provenance consistency (c2c⇒model_id, h2c⇒human_in_loop);
  - trust tier consistent with authored_by;
  - `on_runtime_violation == hard_fail` (the only allowed value).

**Acceptance criteria:**
- A contract can be built, frozen, serialized, and reloaded byte-stably.
- A frozen contract rejects mutation.
- A c2c contract with no model_id fails.
- A contract setting `on_runtime_violation` to anything but `hard_fail` fails.
- `trust.tier` is auto-derived and cannot contradict provenance.
- A contract with an invalid or missing `proof` fails verification; a tampered definition (integrity-hash mismatch) fails; verification makes no network call.

### Phase 4B — Runtime binding

Implement `unfurl_dcp/runtime_binding/` per HLD-C2 §C. The runtime binding is the environment-specific, mutable wiring of an (immutable) contract: the contract says *what is allowed*; the binding says *where and how it runs*.

- Typed model: binding_id, contract_id, contract_version, target_environment (environment/tenant/region/namespace), provider_instance (deployment_kind, `base_url_ref`, `credentials_ref`), consumer_instance, runtime_policy (enabled, timeouts, retry/circuit-breaker/rate-limit refs, telemetry_namespace, audit_enabled), configuration (values + `config_refs`), deployment_controls, lifecycle.
- Validator enforcing HLD-C2 §C / §G runtime-binding rules:
  - `contract_id` references an existing contract; `contract_version` matches; party versions match the contract;
  - **secrets are references only** — inline secret values are rejected (residency/safety critical);
  - `base_url` and `base_url_ref` are mutually exclusive; prefer `base_url_ref` outside local dev;
  - **runtime policy can disable a binding but CANNOT change ownership, dependency, conflict, trust, or invalidation decisions** — this is the design-time/runtime firewall enforced at the schema level.

This is the schema Fabric's binding compiler produces and bakes into a deployable. One frozen contract may have many runtime bindings (dev/staging/prod, per-tenant, per-region).

**Acceptance criteria:**
- A binding referencing a real contract validates; one with a mismatched contract version fails.
- An inline secret value (not a reference) fails validation.
- A binding attempting to alter an ownership/trust/invalidation decision fails.
- The same contract supports multiple bindings with different environments/secrets/scaling.

### Phase 5 — Need→capability resolver and versioning

Implement `unfurl_dcp/resolver/` and `unfurl_dcp/versioning/`.

- `versioning`: semver range matching using `packaging` (settled #6). Given a capability at `semver` and a consumer constraint `semver_range`, decide compatibility.
- `resolver`: given a consumer's required need and a provider's claim, find the capability/capabilities that satisfy it, respecting version constraints and `consumer_access` (any vs named_components_only). Produce the `binding` portion of a contract.
- The resolver does NOT reason — it matches structurally. (Reasoning about *whether* two components *should* compose is Fabric's model; the resolver only finds candidate capabilities that *can* satisfy a need.)

**Acceptance criteria:**
- A consumer need is matched to a provider offer at a compatible version.
- An offer at an incompatible version is rejected.
- A `named_components_only` offer is not matched for an unnamed consumer.
- The resolver reuses the substrate's `$.x.y` reference type for data_mapping references.

### Phase 6 — Cross-schema validation

Implement `unfurl_dcp/validation/` for the rules that span schemas (HLD-C2 §E cross-schema):

- A manifest's component MUST have a corresponding claim.
- A capability referenced by a contract `binding.provider_capability` MUST exist in the provider's claim at a satisfying version.
- A contract's pinned claim versions MUST match real claims.

**Acceptance criteria:**
- A contract binding to a non-existent capability fails.
- A contract binding to a capability at an unsatisfiable version fails.
- A manifest with no backing claim fails.

### Phase 7 — Question-schema renderer (human + model projections)

Implement `unfurl_dcp/questions/` per HLD-C2 §D.

- The negotiation question schema as typed models (id, applies_when, prompt, answer_type, feeds).
- The canonical v0.2 question set (owns-concern, refused-owner, exclusive-conflict, conflict-scope, dependency-satisfied, data-shape) as data, not code.
- Two renderers from the one definition:
  - `render_human_interview(questions, context) -> InterviewForm` — for Fabric's H2C UI.
  - `render_model_prompt(questions, context) -> Prompt` — for C2C (the prompt text only; this library does NOT call a model).
- An answer-capture model: a structured answer to the question set that (a) feeds contract construction and (b) is the training-data tuple for the LoRA experiment. The same answer object serves both.

**Acceptance criteria:**
- The human interview and the model prompt are generated from the same question definition.
- A captured answer set can both build a contract binding AND serialize to the LoRA training tuple `(claim, request, disposition, redirection, rationale)`.
- Adding a question to the canonical set appears in both renderings without separate edits.

### Phase 8 — Testing fixtures

Implement `unfurl_dcp/testing/`:

- A fake `ComponentDescription` and its derived claim+manifest.
- A fake provider claim with capabilities and a fake consumer claim with needs (for resolver tests).
- A reference frozen contract.
- A reference runtime binding for that contract (with reference-only secrets).
- A captured-answer fixture that doubles as a LoRA training tuple.

These are reused by `unfurl-flow`, `unfurl-foundry`, and `unfurl-fabric` test suites.

**Acceptance criteria:**
- Downstream repos can import fixtures without pulling heavy deps.
- Fixtures cover claim, manifest, contract, runtime binding, resolver, and question-answer paths.

---

## Build Order Summary

1. Phase 1 — shared component-description definition (FIRST; prevents drift)
2. Phase 2 — claim schema + validator
3. Phase 3 — manifest schema + validator
4. Phase 4 — contract models + freeze + provenance + trust
5. Phase 4B — runtime binding schema + validator
6. Phase 5 — resolver + versioning
7. Phase 6 — cross-schema validation
8. Phase 7 — question-schema renderer (human + model)
9. Phase 8 — testing fixtures

## Definition of Done

- The five schemas are implemented and validated.
- Claim and manifest are both generated from one `ComponentDescription`; they cannot drift.
- Contracts freeze, serialize byte-stably, and record provenance + trust.
- Runtime bindings wire a contract to an environment with reference-only secrets, and cannot alter ownership/trust/invalidation decisions.
- The resolver matches needs to capabilities by version and consumer-access rules, without reasoning.
- The question schema renders to both a human interview and a model prompt from one definition, and a captured answer doubles as a LoRA training tuple.
- No forbidden imports — especially no AI SDK.
- The updated keycloak v0.2 claim validates clean.
- Coverage > 90% on validators and the resolver.

## Testing Strategy

- Pure unit tests; no external services; no model calls.
- Property-based tests (hypothesis) for the resolver and version matcher.
- The keycloak claim (v0.2 folded form) is the primary realistic fixture.
- A round-trip test proving description → claim → manifest consistency.
- A test proving a captured negotiation answer serializes to the exact LoRA training tuple shape from the experiment plan.

## What Claude Code Must Not Do

- Do NOT call or import any model/AI SDK. The reasoning is Fabric's; this library only defines questions and contract shapes.
- Do NOT author the manifest schema independently of the claim — both derive from `ComponentDescription`.
- Do NOT implement negotiation reasoning, contract execution, or transport.
- Do NOT add `state_owned`/`decisions_owned` as top-level claim lists — they are nested under each concern (settled #1).
- Do NOT allow `on_runtime_violation` any value but `hard_fail` (settled #2).
- Do NOT make `trust.tier` settable independently of provenance — it is derived.
- Do NOT import any product repo or Fabric.

---

## Note on the keycloak example

The existing `keycloak-domain-claim-example.md` is a v0.1 claim. Part of Phase 2's acceptance is producing a **v0.2 folded version** of it (state and decisions nested under their concerns) as the primary realistic test fixture. This doubles as the migration reference for how v0.1 claims become v0.2 claims.


## Migration Note (Normative)

- **Python-only going forward:** research tooling, experiments, and analysis scripts.
- **Java-required going forward:** production `unfurl-dcp` substrate/library implementation (models, validators, resolver, contract/runtime binding behavior).
- Protocol responsibilities remain identical; acceptance criteria should be implemented as Java deliverables per `REPO-unfurl-dcp-java-build-spec.md`.
