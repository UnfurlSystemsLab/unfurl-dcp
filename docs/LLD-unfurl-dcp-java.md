# Low-Level Design: `unfurl-dcp` Java

**Document status:** Implementation LLD for the Java DCP library.
**Audience:** Engineers implementing `unfurl-dcp`.
**Primary sources:**

- `HLD-C-dcp-v0.2-internal.md` (the three-plane model)
- `HLD-C2-dcp-schema-spec-updated.md` (the five schemas, field-by-field)
- `REPO-unfurl-dcp-java-build-spec.md` (the Java repository build spec, authoritative)
- `LLD-unfurl-substrate-java.md`, `LLD-unfurl-foundry-substrate-java.md` (sibling LLDs whose voice and posture this mirrors)
- `RECONCILIATION.md` in `unfurl-foundry-substrate/docs/` (assigns the runtime composition broker to this repo)

---

## Purpose And Sources

This document translates the DCP HLDs and the Java build spec into implementation-level design for the `unfurl-dcp` library. The library is the **keystone** of the Intelligent Components portfolio: it defines and operates the Domain Claim Protocol — claims, composition contracts, runtime bindings, the webapp manifest projection, and the negotiation question schema — and provides the **runtime composition broker** that hosts (flow, foundry) use to register dynamically composed components.

`unfurl-dcp` is a JDK 21 Maven library under `com.unfurl.dcp`. It is not a deployable service. It depends only on `unfurl-substrate`'s reference and invocable contract abstractions plus standard schema/validation/test libraries (Jackson, Jakarta Validation/Hibernate Validator, semver4j, JUnit, AssertJ, jqwik, ArchUnit); it never depends on any host product, model SDK, network transport, or AI runtime.

`HLD-C` is the model source of truth. `HLD-C2` is the schema source of truth — every field shape, validation rule, and settled decision in this LLD comes from there. The Java build spec is the layering source of truth. Where this document diverges from any of them, the divergence is the runtime composition broker added by the foundry-substrate RECONCILIATION, and the broker is built on top of — not in conflict with — the contract schema.

The design preserves the enterprise posture inherited from `unfurl-substrate` and `unfurl-foundry-substrate`:

- No runtime callbacks to Unfurl.
- No Unfurl design-time AI/model reasoning in the perimeter.
- No product-to-product shortcuts when co-packaged.
- The composition broker is **deterministic**: claim → frozen-contract lookup → disposition. No reasoning, no models, no phone-home.
- Audit and telemetry are shaped through ports; concrete bindings live in products/adapters.
- Trust, signature verification, and offline licensing flow through DCP types but are decided by host policy.

---

## Non-Goals And Enterprise Guardrails

`unfurl-dcp` must not implement:

- Design-time negotiation **intelligence** (accept/reject/refusal reasoning). That is `unfurl-fabric`; this layer consumes its output, the frozen composition contract.
- Concrete model, embedding, vector, or LLM SDKs; HTTP clients; sockets; or any network transport. The protocol is transport-agnostic by design (HLD-C §6A).
- A negotiation server or contract registry service. DCP is a library, not a service.
- Per-tenant credential storage, encryption, rotation, or secret management. Runtime bindings reference secrets; they do not hold them.
- Durable execution, work queues, retries, or any orchestration runtime.
- Concrete capability executors (HTTP, function, approval, storage, model, agent, RAG). Those live above DCP in adapters and products.
- Fabric H2C/H2H interview UI or interview-session capture. The question schema is rendered to a structured shape; the actual interview lives in Fabric.
- Customer-system bindings (auth providers, OTLP collectors, CloudEvents sinks, JWKS). DCP defines the integration ports; adapters bind them.

`unfurl-dcp` must provide:

- Java models for all DCP schemas: claim, fault vocabulary/signals, composition contract, runtime binding, webapp manifest, negotiation question schema.
- A single shared `ComponentDescription` that projects to both `Claim` and `WebappManifest` so the two never drift.
- Layered validators: field-level (Jakarta), object-level (custom), cross-schema (services).
- A structural resolver for `need → capability` binding using SemVer range matching.
- Question schema rendering into two neutral shapes: human-interview view and model-prompt view, from one canonical definition.
- Contract freeze/load with provenance and trust handling; offline signature verification of frozen contracts.
- A **runtime composition broker** that, given a presented claim, deterministically looks up the matching frozen contract, returns a disposition, and on accept registers the guest's offers into a host's `CapabilityRegistry` as `ContractInvocable`-backed executors.
- No-op/default implementations that perform no I/O and never phone home.
- A `correlationId` path through every broker decision, contract invocation, and emitted event so downstream audit can stitch the trace.

---

## Package And Module Design

`unfurl-dcp` is **one Maven artifact** with cohesive subpackages (per the Java build spec). All public types live under `com.unfurl.dcp`:

```text
group: com.unfurl.dcp
java: 21
artifact: unfurl-dcp
root package: com.unfurl.dcp

src/main/java/com/unfurl/dcp/
  description/      // shared ComponentDescription + projections to Claim and Manifest
  claim/            // Claim schema records + ClaimValidator
  fault/            // fault declarations, runtime fault signals, deterministic propagation gate
  manifest/         // WebappManifest schema records + WebappManifestValidator
  contract/         // CompositionContract records, freezing, loading, provenance, trust, signature verification
  runtimebinding/   // RuntimeBinding records + RuntimeBindingValidator (no-inline-secrets, policy firewall)
  questions/        // NegotiationQuestion(Schema) + InterviewRenderer + ModelPromptRenderer + CapturedAnswer
  resolver/         // CapabilityResolver: need -> capability structural matching with SemVer
  validation/       // CrossSchemaValidator: claim<->manifest, contract<->claim, binding<->contract checks
  versioning/       // SemverRange helpers, claim/offer/contract version axes
  broker/           // CompositionBroker (the runtime composition broker)
  trust/            // ContractSigner interface, OfflineContractVerifier, TrustTier + derivation (single home)
  spi/              // service-provider points: ContractStore, ContractInvocableFactory, CapabilityRegistrar, BrokerEventSink (no concrete impls)

src/test/java/com/unfurl/dcp/
  testing/          // lightweight fixtures: InMemoryContractStore, EchoContractInvocable, RecordingBrokerEventSink
```

The split is load-bearing:

- Embedders that only consume frozen contracts depend on `contract/`, `trust/`, and `spi/` — never on `questions/` or `description/` projection logic.
- Adapters that implement a custom `ContractStore` depend on `spi/` only.
- The broker depends on `claim/`, `contract/`, `resolver/`, `trust/`, and `spi/`. It does not depend on `questions/`, `description/`, or `manifest/` — interview rendering and manifest projection are design-time concerns.
- ArchUnit and Maven enforcer rules enforce architecture as structure: no AI/HTTP SDK, no host imports, the broker never imports interview/manifest code, runtime-binding never inlines secrets.

Package contents and dependency direction (within the artifact):

```text
description
  contains:
    ComponentDescription, Identity, DomainAssertion, Concern, StateOwned, DecisionOwned,
    BoundaryPrinciple, Refusal, Dependency, Offer, ConflictPosition, NegotiationSurface,
    IntegrationPorts, FaultPolicy, ComponentMetadata
    Projections: ClaimProjector.toClaim(ComponentDescription), ManifestProjector.toManifest(ComponentDescription)
  may depend on:
    Jackson annotations, Jakarta Validation API

claim
  contains:
    Claim record + nested records mirroring HLD-C2 §A, ClaimValidator
  may depend on:
    description, fault, versioning, Jakarta Validation API, Hibernate Validator

fault
  contains:
    FaultPolicy, FaultDeclaration, FaultAffects, FaultEvidence, FaultPropagation,
    FaultRemediation, FaultSignal, FaultPropagationGate, FaultPropagationDecision,
    FaultCategory, FaultSeverity, ParentImpact
  may depend on:
    claim, validation

manifest
  contains:
    WebappManifest record + nested records mirroring HLD-C2 §D, WebappManifestValidator
  may depend on:
    description, claim (for cross-projection invariants), versioning, Jakarta Validation API

contract
  contains:
    CompositionContract record + nested records mirroring HLD-C2 §B,
    ContractFreezer, ContractLoader, Provenance,
    ContractInvocableAdapter (substrate ContractInvocable wrapper),
    InvalidationTrigger handling
    (delegates TrustTier derivation to trust/; does not redeclare it)
  may depend on:
    claim, versioning, trust, unfurl-substrate (substrate-composition-api)

runtimebinding
  contains:
    RuntimeBinding record + nested records mirroring HLD-C2 §C, RuntimeBindingValidator
  may depend on:
    contract, versioning, Jakarta Validation API

questions
  contains:
    NegotiationQuestion, NegotiationQuestionSchema, AnswerType, FeedsTarget,
    InterviewRenderer (renders to a neutral InterviewView shape),
    ModelPromptRenderer (renders to a neutral PromptView shape),
    CapturedAnswer, AnswerCorpus
  may depend on:
    claim, description, Jakarta Validation API

resolver
  contains:
    CapabilityResolver, ResolutionRequest, ResolutionResult, AccessPolicy
  may depend on:
    claim, versioning

validation
  contains:
    CrossSchemaValidator, SchemaValidationReport
  may depend on:
    claim, manifest, contract, runtimebinding, fault

versioning
  contains:
    SemverRange, SemverHelpers, VersionAxis (CLAIM, OFFER, CONTRACT)
  may depend on:
    a SemVer library (e.g. semver4j)

trust
  contains:
    TrustTier (single home), TrustCreatedBy, TrustTierDeriver (TrustCreatedBy -> TrustTier),
    ContractSigner (interface), OfflineContractVerifier,
    SignedContract (envelope), VerificationResult
  may depend on:
    nothing in unfurl-dcp; deliberately a leaf to avoid a cycle with contract/
    (see rule below)

broker
  contains:
    CompositionBroker (interface + DefaultCompositionBroker), Disposition,
    DispositionKind, DispositionReason, RegistrationHandle, BrokerEvent, BrokerEventType
  may depend on:
    claim, contract, resolver, trust, spi, unfurl-substrate (substrate-composition-api)
    (NOT substrate-ports.CapabilityRegistry directly — the broker registers through
    spi.CapabilityRegistrar so the substrate side stays read-only)

spi
  contains:
    ContractStore (interface; provider claim + capability lookup), ContractInvocableFactory (interface),
    CapabilityRegistrar (interface) — DCP's mutable registration port that hosts
        implement on top of their mutable capability manager/registry implementation,
    BrokerEventSink (interface), NoopBrokerEventSink (default)
  may depend on:
    contract, claim, unfurl-substrate (substrate-composition-api, substrate-ports)
```

Dependency direction is acyclic. Critical rules:

- `trust/` is a **leaf**: it owns `TrustTier`, `TrustCreatedBy`, `TrustTierDeriver`, and `SignedContract` envelopes (canonical bytes + signature). It does NOT import `contract.CompositionContract` or `contract.Provenance`. `contract/` adapts `Provenance.createdBy` into the trust-owned `TrustCreatedBy` input and calls `TrustTierDeriver.derive(createdBy)` to populate `Trust.tier` — the derivation has exactly one home.
- `description/`, `claim/`, `manifest/`, `runtimebinding/`, `questions/`, `resolver/`, `versioning/`, `validation/` must not depend on `broker/` or `spi/`.
- `fault/` is a schema/runtime-decision package. It may read claims to evaluate declared fault policy, but it must not depend on broker/SPI or any concrete monitoring adapter.
- `broker/` must not depend on `questions/`, `manifest/`, or `description/`. Interview rendering and manifest projection are design-time-only; the runtime broker is invocation-only.
- `broker/` must not import `substrate-ports.CapabilityRegistry` directly. It registers/revokes through `spi.CapabilityRegistrar`, which the host implements over its mutable capability manager/registry implementation. This keeps the substrate `CapabilityRegistry` interface read-only and lets DCP own the mutability contract.
- `unfurl-dcp` may depend on `unfurl-substrate` (substrate-composition-api, substrate-ports). It must not depend on `unfurl-flow`, `unfurl-foundry`, `unfurl-foundry-substrate`, or `unfurl-fabric`.
- **Single artifact, package-level enforcement.** Because `unfurl-dcp` ships as one Maven artifact (per the Java build spec), the rules above are not enforced by module boundaries but by ArchUnit assertions on package boundaries. See §"Testing And Architecture Enforcement".

Implementation rules:

- Prefer Java records for immutable data. Use immutable classes with builders only when Jackson/Jakarta Validation ergonomics require them.
- Use defensive copies for collections and map-like payloads.
- Use Jackson (`jackson-databind`, `jackson-dataformat-yaml`) with `snake_case` property naming strategy. Wire field names match HLD-C2 §H preferred names exactly (`ownership_position`, `accepted_providers`, `resolution_guidance`, `answer_grounding`, `requires_human_escalation`, `need`, `consumer_access`, `created_by`, `created_at`).
- Use Jakarta Validation API (Hibernate Validator at test/runtime) for field- and class-level constraints.
- Use a SemVer library (`semver4j` or equivalent) for version range matching.
- Keep service classes (validators, projectors, resolver, broker) stateless unless they are explicitly registry/store implementations.
- Production modules must not depend on `testing/` fixtures.
- The parent Maven POM owns dependency/plugin versions and publishes the artifact with a coordinated version.

---

## Core Types And Interfaces

### Shared Component Description (HLD-C §4)

A component has **one self-description with two projections**. The shared model lives in `com.unfurl.dcp.description`:

```java
public record ComponentDescription(
        Identity identity,
        DomainAssertion domain,
        List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,   // required iff identity.kind == INTELLIGENT_COMPONENT
        IntegrationPorts integrationPorts,
        FaultPolicy faults,
        ComponentMetadata metadata
) { /* defensive copies, validation */ }
```

Projections are pure, deterministic functions:

```java
public final class ClaimProjector {
    public Claim toClaim(ComponentDescription description) { ... }
}

public final class ManifestProjector {
    public WebappManifest toManifest(ComponentDescription description) { ... }
}
```

The projection discipline (HLD-C §4): the manifest's `component_uri` and `component_version` are always read from the description's identity; manifest `permissions` are derived from concerns' `owns_decisions[].authority` and offers' `consumer_access`. Building a manifest independently of a description is forbidden; the cross-schema validator (see §5.8) refuses any manifest that cannot be matched to a claim it projects from.

### Claim (HLD-C2 §A)

Core records in `com.unfurl.dcp.claim`, snake_case on the wire:

```java
public record Claim(
        Identity identity,
        DomainAssertion domain,
        @NotEmpty List<Refusal> refusals,
        Dependencies dependencies,
        List<Offer> offers,
        ConflictResolution conflictResolution,
        NegotiationSurface negotiationSurface,
        IntegrationPorts integrationPorts,
        FaultPolicy faults,
        ClaimMetadata metadata
) { ... }

public record Identity(
        URI uri, @NotBlank String name, ComponentKind kind,
        @NotBlank String version, @NotBlank String publisher, URI publisherUri
) { ... }

public enum ComponentKind { INTELLIGENT_COMPONENT, COMPONENT, INFRASTRUCTURE }

public record DomainAssertion(
        @NotBlank String summary,
        @NotEmpty List<Concern> concerns,
        @NotEmpty List<String> boundaryPrinciples
) { ... }

public record Concern(
        String concern, String description, String scopeNotes,
        List<StateOwned> ownsState, List<DecisionOwned> ownsDecisions
) { ... }

public record StateOwned(String resource, String description, Sensitivity sensitivity) { ... }
public enum Sensitivity { PUBLIC, INTERNAL, CONFIDENTIAL, SECRET }

public record DecisionOwned(String decision, String description, Authority authority) { ... }
public enum Authority { EXCLUSIVE, CONSULTED, ADVISORY }

public record Refusal(@NotBlank String concern, @NotBlank String rationale, String ownedBy) { ... }

public record Offer(
        @NotBlank String capability, @NotBlank String description,
        ConsumerAccess consumerAccess, OfferInterface offerInterface,
        Stability stability, @NotBlank String version,
        boolean metered, String costImplications
) { ... }
public enum ConsumerAccess { ANY, NAMED_COMPONENTS_ONLY }
public enum Stability { EXPERIMENTAL, EVOLVING, STABLE, DEPRECATED }
public enum InterfaceKind { HTTP_API, EVENT_STREAM, NEGOTIATION, IN_PROCESS }

public record ConflictResolution(
        List<OverlappingConcern> overlappingConcerns,
        List<String> precedenceRules,
        boolean requiresHumanEscalation
) { ... }

public record OverlappingConcern(
        String concern, OwnershipPosition ownershipPosition, String resolutionGuidance
) { ... }
public enum OwnershipPosition { EXCLUSIVE, NEGOTIABLE, DEFERRING, CONSULTED }

public record NegotiationSurface(
        @NotBlank String endpoint, List<String> protocolsSupported,
        @NotEmpty List<SupportedIntent> supportedIntents,
        @NotEmpty List<String> answerGrounding,
        @NotEmpty List<String> limitations
) { ... }
```

`faults` is a required top-level claim section even when it is empty. The Java record does not provide a
backward-compatible constructor that omits it; all claim producers must pass either a declared `FaultPolicy`
or `FaultPolicy.empty()` explicitly so missing fault vocabulary is caught during integration.

`ClaimValidator` is a Jakarta-based service that enforces HLD-C2 §F's claim rules:

- All required sections present; `refusals` and `boundaryPrinciples` non-empty.
- `kind == INTELLIGENT_COMPONENT` ⇒ `negotiationSurface` present.
- Concern identifiers unique within the claim.
- `faults` present; each declared fault has code/category/severity/description and affects at least one need, offer, or constraint.
- A fault whose `parentImpact` is not `NONE` has a non-blank `propagatesWhen` gate condition.
- `metadata.dcpVersion >= 0.2.0`; `metadata.claimVersion == identity.version`.
- Refusal specificity: emit a warning (not a hard fail) when a refusal's `concern` is "everything-else" or its `rationale` is below a minimum length.
- Each offer's `version` valid SemVer; `metered` defaults to `false` when omitted; `costImplications` required when `metered == true` or `interfaceKind == NEGOTIATION`.

Validation results are structured records (see §8), never exceptions for expected validation failures.

### Fault Vocabulary And Propagation Gate (HLD-C2 A.10)

Records in `com.unfurl.dcp.fault`:

```java
public record FaultPolicy(List<FaultDeclaration> emitted) { ... }

public record FaultDeclaration(
        String code,
        FaultCategory category,
        FaultSeverity severity,
        String description,
        FaultAffects affects,
        FaultEvidence evidence,
        FaultPropagation propagation,
        FaultRemediation remediation
) { ... }

public record FaultSignal(
        String faultId,
        URI sourceClaimUri,
        String sourceInstance,
        URI contractId,
        URI bindingId,
        String capability,
        String code,
        FaultCategory category,
        FaultSeverity severity,
        Instant observedAt,
        List<String> affectedNeeds,
        List<String> affectedOffers,
        List<String> affectedConstraints,
        List<String> evidenceRefs,
        String correlationId
) { ... }

public final class FaultPropagationGate {
    public FaultPropagationDecision evaluate(Claim sourceClaim, FaultSignal signal) { ... }
}
```

The gate is a deterministic Strategy over declared claim policy:

1. Look up `signal.code` in `sourceClaim.faults().emitted()`.
2. Reject undeclared fault codes with a structured non-propagating decision.
3. Suppress faults whose declaration has `parentImpact == NONE`.
4. Propagate declared faults with `DEGRADED` or `BLOCKED` impact, carrying affected needs/offers/constraints from the signal where present and otherwise from the declaration.

The gate never calls a model, monitoring backend, network service, or Fabric. It only interprets a runtime signal against the frozen claim vocabulary so parent DCP graphs can explain blast radius without runtime renegotiation.

### Webapp Manifest (HLD-C2 §D)

Records in `com.unfurl.dcp.manifest`:

```java
public record WebappManifest(
        URI componentUri, String componentVersion,
        String routePrefix, @NotEmpty List<Route> routes,
        Navigation navigation, @NotEmpty List<String> permissions,
        ThemeContribution themeContribution, Bootstrap bootstrap
) { ... }

public record ThemeContribution(ThemeMode mode, Map<String, String> tokens) { ... }
public enum ThemeMode { SUGGESTIVE }       // suggestive-only; protocol enforces single value

public record Bootstrap(boolean standaloneApp, boolean hostedInShell) { ... }
```

`WebappManifestValidator` enforces:

- `componentUri` and `componentVersion` match the linked claim.
- Every entry in `permissions` is derivable from the claim's concerns/offers; a permission with no claim basis fails validation.
- `themeContribution.mode` is `SUGGESTIVE` (the schema permits no other value; reject any other input).

### Composition Contract (HLD-C2 §B)

Records in `com.unfurl.dcp.contract`:

```java
public record CompositionContract(
        URI contractId, String contractVersion,
        Parties parties, Binding binding,
        DataMapping dataMapping, Transport transport,
        Expectations expectations, Provenance provenance,
        Trust trust, Invalidation invalidation,
        CompositionContractMetadata metadata
) { ... }

public record Parties(Party consumer, Party provider) { ... }
public record Party(URI claimUri, String claimVersion) { ... }

public record Binding(String consumerNeed, String providerCapability, String providerCapabilityVersion) { ... }

public record DataMapping(Map<String, String> inbound, Map<String, String> outbound) { ... }

public record Transport(TransportKind kind, Map<String, Object> details) { ... }
public enum TransportKind { IN_PROCESS, HTTP_JSON, GRPC }

public record Expectations(
        Integer timeoutMs, boolean idempotent, boolean async, boolean correlationIdRequired
) { ... }

public record Provenance(
        CreatedBy createdBy, NegotiationMode mode,
        String modelId, String fabricVersion, boolean humanInLoop, Instant createdAt
) { ... }
public enum CreatedBy { FABRIC, EMBEDDED_SELF }
public enum NegotiationMode { C2C, H2C, H2H }

public record Trust(TrustTier tier) { ... }
public enum TrustTier { NEUTRAL, SELF }
public record CompositionContractMetadata(Map<String, Object> extensions) { ... }

public record Invalidation(
        List<InvalidationTrigger> triggers, RuntimeViolationPolicy onRuntimeViolation
) { ... }
public enum InvalidationTrigger { CLAIM_VERSION_CHANGED, PATTERN_UNSUPPORTED, RUNTIME_ASSUMPTION_VIOLATED }
public enum RuntimeViolationPolicy { HARD_FAIL }   // settled; HLD-C2 §F
```

Freeze and load:

```java
public final class ContractFreezer {
    public FrozenContract freeze(CompositionContract contract, ContractSigner signer);
}

public final class ContractLoader {
    public LoadResult load(byte[] frozenBytes, OfflineContractVerifier verifier);
}
```

`FrozenContract` is the immutable canonical artifact (HLD-C §5.2 "Frozen means: once produced, immutable and the canonical execution artifact"). It exposes:

- The canonical byte representation (stable JSON, sorted keys; this is what the signature is computed over).
- The decoded `CompositionContract`.
- The signature envelope (delegated to `trust/`).

A `FrozenContract` exposes a substrate-shaped invocable via `ContractInvocableAdapter`. The adapter wraps a host-supplied `ContractInvocable` (the actual implementor of the provider's offer) and tags every invocation so audit can stitch the call back to its frozen contract.

The substrate `ContractInvocation` has a typed `contractId` field but no typed `contractVersion` field. DCP therefore maps `FrozenContract.id` to `ContractInvocation.contractId` and carries the remaining DCP attributes in invocation metadata using stable keys:

- `dcp.contractVersion`
- `dcp.trustTier`
- `dcp.registrationHandle`

The adapter also preserves these keys on result metadata. A delegate `ContractInvocable` may add its own metadata, but it must not override the reserved `dcp.*` keys above; an override attempt is a structured adapter failure.

Validation rules (HLD-C2 §F) enforced by `ContractValidator`:

- Exactly two parties; both `claimVersion` pinned.
- `transport.kind == IN_PROCESS` only when the host indicates co-packaging (the validator alone cannot know this; surfaces a warning that must be confirmed at packaging time by Fabric).
- Provenance consistency: `mode == C2C` requires `modelId`; `mode == H2C` requires `humanInLoop == true`.
- Trust derivation: `trust.tier == SELF` iff `provenance.createdBy == EMBEDDED_SELF`; otherwise `NEUTRAL`.
- `invalidation.onRuntimeViolation == HARD_FAIL` (no other value accepted).
- Cross-schema: the contract's `binding.providerCapability` exists in the provider's claim at a version satisfying `binding.providerCapabilityVersion`.
- Aggregate containment: contract metadata uses the same recursive DCP bridge keys as claims (`contains`, `children`, `containsClaimUris`, `childClaimUris`). Child values may be URI strings or maps with `contractId`, `claimUri`, `uri`, or `ref`. `ContractValidator.validateTree(root, contractsById)` walks the contract tree, validates every child, and rejects missing child refs and cycles.

Fabric compilers that produce multi-component assemblies must emit a DCP contract closure: an aggregate parent contract whose metadata references child `CompositionContract` records. Private planning metadata such as a binding plan can remain diagnostic, but it is not a substitute for referenced child contracts.

### Runtime Binding (HLD-C2 §C)

Records in `com.unfurl.dcp.runtimebinding`:

```java
public record RuntimeBinding(
        URI bindingId, URI contractId, String contractVersion,
        TargetEnvironment targetEnvironment,
        ProviderInstance providerInstance,
        ConsumerInstance consumerInstance,
        RuntimePolicy runtimePolicy,
        Configuration configuration,
        DeploymentControls deploymentControls,
        Lifecycle lifecycle,
        RuntimeBindingMetadata metadata
) { ... }

public record ProviderInstance(
        URI componentUri, String componentVersion, String instanceName,
        DeploymentKind deploymentKind, URI baseUrl, ConfigRef baseUrlRef,
        SecretRef credentialsRef
) { ... }
public enum DeploymentKind { IN_PROCESS, CONTAINER, REMOTE_SERVICE, EXTERNAL_SAAS, WEBAPP, SIDECAR }

public record SecretRef(@NotBlank String uri) { ... }       // reference only
public record ConfigRef(@NotBlank String uri) { ... }
public record RuntimeBindingMetadata(Map<String, Object> extensions) { ... }
```

`RuntimeBindingValidator` enforces:

- `contractId` references an existing frozen contract; `contractVersion` is pinned and matches.
- Provider/consumer `componentVersion` match the contract's parties.
- **No inline secrets.** Any literal credential value (a non-`SecretRef` field carrying anything that looks like a credential) fails validation. Secrets are *references only*.
- **Runtime-policy firewall.** Runtime binding may set `enabled`, timeouts within contract-permitted bounds, retry/circuit-breaker references, telemetry namespace, and audit flag — it MUST NOT change claim ownership, dependency satisfaction, conflict decisions, trust tier, or invalidation rules. The validator rejects any binding field that attempts to override these.
- `baseUrl` and `baseUrlRef` mutually exclusive; `baseUrlRef` preferred outside local development.
- **Aggregate containment.** Runtime binding metadata uses the same extension bridge as recursive claims: `contains`, `children`, `containsClaimUris`, and `childClaimUris`. Child values may be URI strings or maps with `bindingId`, `claimUri`, `uri`, or `ref`. `RuntimeBindingValidator.validateTree(root, bindingsById, contractsById)` walks the binding tree, validates every child binding, rejects missing child refs and cycles, and applies the inline-secret/runtime-policy firewall across the whole subtree.

This keeps multi-component runtime assembly inside DCP constructs. Products such as Fabric/Flowfoundry may generate an aggregate parent binding, but they must not add product-specific runtime-wiring sidecars; every child edge is a DCP containment ref to another normal runtime binding.

### Negotiation Question Schema (HLD-C2 §E)

Records in `com.unfurl.dcp.questions`:

```java
public record NegotiationQuestionSchema(
        @NotEmpty List<NegotiationQuestion> questions
) { ... }

public record NegotiationQuestion(
        @NotBlank String id, String appliesWhen, @NotBlank String prompt,
        AnswerType answerType, FeedsTarget feeds
) { ... }

public enum AnswerType { DISPOSITION, OWNER, BOOLEAN, SCOPE, FREE_TEXT }
public enum FeedsTarget { BINDING, CONFLICT_CHECK, DEPENDENCY_CHECK, DATA_MAPPING }

public record CapturedAnswer(
        String questionId, Object value, String rationale, Instant capturedAt
) { ... }
```

The canonical v0.2 question set (HLD-C2 §E) is a constant in `NegotiationQuestionSchema.CANONICAL_V0_2`.

Dual rendering — two pure functions producing neutral views, never UI:

```java
public final class InterviewRenderer {
    public InterviewView render(NegotiationQuestionSchema schema, NegotiationContext ctx);
}

public final class ModelPromptRenderer {
    public PromptView render(NegotiationQuestionSchema schema, NegotiationContext ctx);
}
```

The two renderers consume the same `NegotiationContext` (carrying claim references and any prior captured answers) and produce structurally identical content with different presentation envelopes. Identity is enforced by a property test: for any seed input, `InterviewView.normalized() == PromptView.normalized()` after stripping presentation chrome.

`AnswerCorpus` is an append-only collection of `CapturedAnswer`s for one session. It serializes to the LoRA training tuple shape `(claim, request, expected_disposition, expected_redirection, rationale)` via a documented projection — the bridge described in HLD-C §5.3 between the protocol and the experiment.

### Capability Resolver (HLD-C2 §F, settled #6)

In `com.unfurl.dcp.resolver`:

```java
public final class CapabilityResolver {
    public ResolutionResult resolve(ResolutionRequest request);
}

public record ResolutionRequest(
        String need, String requiredKind,
        SemverRange offerVersionRange,
        URI consumerClaimUri,
        Set<URI> candidateProviderClaims
) { ... }

public record ResolutionResult(
        boolean resolved, URI providerClaimUri, String providerCapability,
        String resolvedOfferVersion, String reason
) { ... }
```

Resolution is **strictly structural**:

1. The capability exists in some candidate provider's claim.
2. The offer's `version` satisfies the consumer's `semverRange`.
3. The offer's `consumerAccess` (`ANY` or `NAMED_COMPONENTS_ONLY`) admits the consumer.

No model, no AI, no probabilistic matching. If multiple candidates satisfy, the resolver returns the highest SemVer match by default; ties (theoretically impossible after exact match) fail with a structured `MULTIPLE_MATCHES` reason.

### Cross-Schema Validation (HLD-C2 §F)

In `com.unfurl.dcp.validation`:

```java
public final class CrossSchemaValidator {
    public SchemaValidationReport validate(Claim claim, WebappManifest manifest);
    public SchemaValidationReport validate(CompositionContract contract, Map<URI, Claim> claimsByUri);
    public SchemaValidationReport validate(RuntimeBinding binding, CompositionContract contract);
}
```

Checks performed:

- A manifest's component MUST have a corresponding claim; URIs and versions match.
- A contract's `binding.providerCapability` MUST exist in the provider's claim at a version satisfying `binding.providerCapabilityVersion`.
- A contract's `parties.*.claimVersion` are pinned; the report includes a "claim version drifted" diagnostic if a newer matching claim is supplied alongside the contract.
- A runtime binding's `contractId` and `contractVersion` resolve to a real contract; the binding does not attempt to alter ownership/dependency/conflict/trust/invalidation.

`SchemaValidationReport` is an immutable record carrying structured `Diagnostic`s with severity (`ERROR`, `WARNING`, `INFO`), location (claim URI, contract id, field path), and a stable code.

### Versioning (HLD-C2 §F, settled #6)

In `com.unfurl.dcp.versioning`:

```java
public final class SemverHelpers {
    public boolean satisfies(String version, String range);
    public Optional<String> highestSatisfying(Collection<String> versions, String range);
}

public enum VersionAxis { CLAIM, OFFER, CONTRACT }   // HLD-C §8: three independent axes
```

A contract pins both `parties.*.claimVersion` (CLAIM axis) and `binding.providerCapabilityVersion` (OFFER axis); the contract itself carries `contractVersion` (CONTRACT axis). All three are evaluated independently when judging invalidation.

### Trust And Offline Verification (HLD-C2 §F)

In `com.unfurl.dcp.trust`:

```java
public enum TrustTier { SELF, NEUTRAL }

public enum TrustCreatedBy { FABRIC, EMBEDDED_SELF }

public final class TrustTierDeriver {
    public TrustTier derive(TrustCreatedBy createdBy);
}

public interface ContractSigner {
    SignedContract sign(byte[] canonicalContractBytes, SigningKeyRef keyRef);
}

public final class OfflineContractVerifier {
    public VerificationResult verify(SignedContract signed, VerificationKeySet keys);
}

public record SignedContract(byte[] canonicalBytes, byte[] signature, String algorithm, String signerKeyId) { ... }

public record VerificationResult(boolean valid, TrustTier derivedTier, String reason) { ... }
```

The substrate-side principle (HLD-C §5.5): DCP records *how* a contract was authored. `TrustTier` is derived deterministically from the trust-owned `TrustCreatedBy` input. `contract/` adapts `Provenance.createdBy` into that input; `trust/` never imports `contract.Provenance`. Verification is **offline-only** — `OfflineContractVerifier` consumes a caller-supplied `VerificationKeySet` and never fetches remote keys. Signing keys are referenced (never inlined) so production deployments can wire a customer-owned KMS adapter.

### Runtime Composition Broker (the addition from `unfurl-foundry-substrate` RECONCILIATION)

In `com.unfurl.dcp.broker`:

```java
public interface CompositionBroker {

    /** Plane-3 disposition: deterministic frozen-contract lookup. No model, no reasoning. */
    Disposition present(Claim claim, ExecutionContext context);

    /**
     * Bind an accepted disposition into the host's capability surface.
     * Only DispositionKind.ACCEPT is valid input.
     * The broker re-fetches the frozen contract from its injected ContractStore using the
     * matched contract id/version on the Disposition — the host does not load contracts
     * itself, and the broker re-verifies the signature before registration. Missing,
     * stale, or invalid dispositions fail structurally before any capability is registered.
     */
    RegistrationHandle accept(
            Disposition disposition,
            CapabilityRegistrar registrar,
            ContractInvocableFactory invocableFactory,
            ExecutionContext context
    );

    /** Reverse a prior accept(...). Idempotent on missing handles. */
    void revoke(RegistrationHandle handle, CapabilityRegistrar registrar, ExecutionContext context);
}

/** Default impl is constructor-injected; no static lookup, no service-loader. */
public final class DefaultCompositionBroker implements CompositionBroker {
    public DefaultCompositionBroker(
            ContractStore contractStore,
            OfflineContractVerifier verifier,
            VerificationKeySet keySet,                 // host-supplied at boot; never fetched
            ClaimValidator claimValidator,
            BrokerEventSink eventSink                   // NoopBrokerEventSink by default
    ) { ... }
    // ...
}

public record Disposition(
        DispositionKind kind,
        URI matchedContractId,         // present when ACCEPT
        String matchedContractVersion, // present when ACCEPT
        String redirection,            // present when REFUSE; from fabric's pre-computed owned_by
        String rationale,
        DispositionReason reasonCode
) { ... }

public enum DispositionKind { ACCEPT, REFUSE }

public enum DispositionReason {
    MATCH_FOUND,                       // ACCEPT path
    NO_MATCHING_CONTRACT,              // REFUSE: no frozen contract for (providerUri, providerVersion)
    SIGNATURE_INVALID,                 // REFUSE: offline verification failed
    CLAIM_MALFORMED,                   // REFUSE: claim failed shape validation
    DCP_VERSION_UNSUPPORTED,           // REFUSE: claim.metadata.dcpVersion not supported
    BROKER_ACCEPT_INVALID,             // accept(...): non-ACCEPT, missing id/version, or stale disposition
    CONTRACT_NOT_FOUND                 // accept(...): frozen contract id/version is not in the store
}

public record RegistrationHandle(
        URI contractId, String contractVersion, URI claimUri, String claimVersion,
        List<String> exposedCapabilityNames
) { ... }
```

**`PARTIAL_ACCEPT` is deliberately not in v1.** A `CompositionContract` carries a singular `Binding` (HLD-C2 §B): one consumer need to one provider capability. Multi-offer partial acceptance would require either multiple contracts (handled by the host calling `present`/`accept` per claim, per offer) or a model change to make `Binding` plural. The schema currently chooses singular; the broker therefore offers only `ACCEPT`/`REFUSE`. If multi-binding contracts ever land, the disposition gains an accepted-subset list and a `PARTIAL_ACCEPT` kind; until then the simpler shape is the honest one.

The broker is **purely deterministic**. Given a presented claim, it:

1. Validates the claim shape via the injected `ClaimValidator`.
2. Looks up the frozen `CompositionContract` whose `parties.provider.claimUri == claim.identity.uri` AND `parties.provider.claimVersion == claim.identity.version`, via the injected `ContractStore`.
3. Verifies the contract's offline signature via the injected `OfflineContractVerifier` against the injected `VerificationKeySet`. A verification failure produces `Disposition(REFUSE, reasonCode = SIGNATURE_INVALID)`.
4. If a matching, signature-valid contract exists: returns `Disposition(ACCEPT, MATCH_FOUND, ...)` carrying the contract's id/version.
5. If none matches: returns `Disposition(REFUSE, NO_MATCHING_CONTRACT, ...)` carrying fabric's pre-computed redirection from the provider claim's `refusals[*].owned_by` (when applicable).

No model, no reasoning, no phone-home. Intelligence touched Planes 1 and 2 at design time; the broker is Plane 3.

On `accept(disposition, registrar, factory, context)`, the broker:

- Requires `disposition.kind() == ACCEPT`; any `REFUSE` disposition, missing `matchedContractId`, or missing `matchedContractVersion` fails with `BROKER_ACCEPT_INVALID`.
- Re-fetches the `FrozenContract` from its injected `ContractStore` using `disposition.matchedContractId()` + `matchedContractVersion()`. The host does not load contracts itself. If the exact id/version is absent, it fails with `CONTRACT_NOT_FOUND`.
- Rejects stale dispositions: if the fetched contract's id/version does not exactly match the disposition, it fails with `BROKER_ACCEPT_INVALID`.
- Re-verifies the fetched contract's offline signature before registration. A verification failure fails with `SIGNATURE_INVALID`, and no capability is registered.
- Builds a single `ContractInvocable` for the contract's `Binding` by calling `factory.create(contract, binding, context)`. Where the host needs the full provider claim's offer record (e.g. for `consumer_access` enforcement), it can resolve `binding.providerCapability` against the provider `Claim` and pass the resulting `com.unfurl.dcp.claim.Offer` through the factory implementation's own context — the SPI argument is the `Binding`, not the `Offer`, to keep the SPI orthogonal to claim resolution.
- Wraps the invocable in a `ContractInvocableAdapter` (from `contract/`) that tags every invocation with the frozen contract id plus reserved metadata keys for contract version, trust tier, and registration handle.
- Calls `registrar.register(binding.providerCapability, adaptedInvocable, context)`. The `CapabilityRegistrar` is DCP's mutable port (defined in `spi/`); hosts implement it over their mutable capability manager/registry implementation. This keeps the substrate `CapabilityRegistry` interface read-only.

`revoke(handle, registrar, context)` reverses the registration via `registrar.unregister(...)`. This is what makes the composition genuinely **dynamic**: components can be added and removed at runtime, the host's capability surface changes accordingly, but every accept/reject decision was frozen at design time.

The broker emits `BrokerEvent`s through the injected `BrokerEventSink` (default `NoopBrokerEventSink`): `CLAIM_PRESENTED`, `DISPOSITION_ACCEPTED`, `DISPOSITION_REFUSED`, `CAPABILITY_REGISTERED`, `CAPABILITY_REVOKED`, `CONTRACT_INVALIDATED`. Payloads are metadata-first (claim uri, contract id, capability name, correlation id, reason code) — never full claims or contracts.

### Service-Provider Points (SPI)

In `com.unfurl.dcp.spi`:

```java
public interface ContractStore {
    Optional<FrozenContract> findByProvider(
        URI providerClaimUri,
        String providerClaimVersion,
        String providerCapability);
    Optional<FrozenContract> findById(URI contractId, String contractVersion);
}

public interface ContractInvocableFactory {
    /**
     * Materialize the substrate ContractInvocable for the contract's binding.
     * The argument is the contract's Binding (HLD-C2 §B), not a claim.Offer — the SPI
     * is intentionally orthogonal to claim resolution. Implementations that need the
     * full provider Offer record (for example to read consumer_access or cost_implications)
     * may resolve it themselves from the Binding's capability + version.
     */
    ContractInvocable create(CompositionContract contract, Binding binding, ExecutionContext context);
}

/**
 * DCP's mutable capability-registration port. Hosts implement this as a thin mutable
 * adapter over their mutable capability manager/registry implementation. The substrate's
 * CapabilityRegistry interface itself remains read-only (hasCapability / resolveExecutor);
 * mutability is owned by DCP because DCP owns the registration lifecycle.
 */
public interface CapabilityRegistrar {
    void register(String capabilityName, ContractInvocable invocable, ExecutionContext context);
    void unregister(String capabilityName, ExecutionContext context);
}

public interface BrokerEventSink {
    void publish(BrokerEvent event, ExecutionContext context);
}
```

Concrete adapters live above this layer:

- A `FileContractStore` reading frozen contracts from a directory (typical Fabric-emitted deployable). It indexes by provider claim uri/version plus provider capability so multi-offer providers resolve the exact child contract requested by a runtime binding.
- A `ClasspathContractStore` for embedded test/demo cases.
- Host products (flow, foundry) implement `CapabilityRegistrar` over their own mutable capability manager/registry implementation and inject a `ContractInvocableFactory` that produces invocables backed by their AI executors (`foundry-substrate-offers`' `AgentInvocation` / `ToolInvocation` / `RagInvocation`).

`unfurl-dcp` ships only the no-op defaults plus an `InMemoryContractStore` and an `InMemoryCapabilityRegistrar` in `testing/` (test scope), used by broker tests.

---

## Composition Broker Flow

This is the runtime view, from the broker's side, of the lifecycle described in `unfurl-foundry-substrate/docs/HLD-unfurl-foundry-substrate.md` §5.

The broker's collaborators are all **constructor-injected** at construction time: a `ContractStore`, an `OfflineContractVerifier`, a `VerificationKeySet`, a `ClaimValidator`, and a `BrokerEventSink`. There is no static lookup, no service-loader, and no method-time injection of verifier or keys. Runtime presentation is capability-specific: `present(claim, providerCapability, context)` validates that the claim offers the requested capability, then looks up the matching frozen child contract by provider claim uri/version plus that capability. The convenience `present(claim, context)` path is valid only for single-offer claims.

Given an in-process host (flow or foundry) and a `CompositionBroker` instance:

1. A component capability is presented to the host. The host calls `broker.present(claim, providerCapability, context)`.
2. The broker validates the claim shape (`ClaimValidator`) and asserts `metadata.dcpVersion` is supported.
3. The broker queries its `ContractStore` for a frozen contract whose provider matches the claim's identity. The store is content-addressable: lookup by `(providerClaimUri, providerClaimVersion)` is O(1).
4. If a match exists, the broker verifies the contract's offline signature via its `OfflineContractVerifier` against its `VerificationKeySet`. A verification failure produces `Disposition(REFUSE, reasonCode = SIGNATURE_INVALID)`.
5. On success, the broker returns `Disposition(ACCEPT, matchedContractId, matchedContractVersion, MATCH_FOUND)` and emits `DISPOSITION_ACCEPTED`. On any failure it returns `Disposition(REFUSE, reasonCode = ...)` and emits `DISPOSITION_REFUSED`.
6. If the host accepts, it calls `broker.accept(disposition, registrar, invocableFactory, context)`. **The host does not pass a `CompositionContract`** — the broker re-fetches the frozen contract by id/version from its injected store and re-verifies its signature, which keeps the freeze/verify path entirely inside DCP.
7. The broker asks `invocableFactory.create(contract, contract.binding(), context)` for the contract's single binding, wraps the returned invocable in a `ContractInvocableAdapter` (tagging contract id plus reserved DCP metadata for version, trust tier, and registration handle), and calls `registrar.register(binding.providerCapability(), adaptedInvocable, context)`. It emits `CAPABILITY_REGISTERED`.
8. The broker returns a `RegistrationHandle` that the host stores for later revocation.

Subsequent runtime calls go through the host's `CapabilityRegistry` (substrate port) → `ContractInvocableAdapter` → the host-supplied `ContractInvocable`. The broker is **not in the hot path**; it touched the system once at registration time. This is the design-time/runtime firewall (HLD-C §7) at the broker layer.

On `broker.revoke(handle, registrar, context)`: the broker calls `registrar.unregister(capabilityName, context)` for each entry in the handle and emits `CAPABILITY_REVOKED`. Components added dynamically can be removed dynamically without restarting the host.

**No runtime self-heal.** If a frozen contract becomes invalid at runtime (a `RUNTIME_ASSUMPTION_VIOLATED` invalidation trigger fires from the host), the broker emits `CONTRACT_INVALIDATED` and revokes — it does NOT re-negotiate. Re-negotiation is fabric's job at the next design-time pass (HLD-C §8, settled #2).

---

## Contract Freeze And Load Flow

Authoring path (Fabric calls):

1. Fabric calls `ContractFreezer.freeze(compositionContract, contractSigner)`.
2. The freezer computes the canonical byte form (stable JSON with sorted keys, snake_case names, normalized whitespace).
3. The signer (an adapter to the customer's KMS) produces a signature over the canonical bytes.
4. `FrozenContract` wraps `(canonicalBytes, decodedContract, signedEnvelope)`. Subsequent serialization writes the canonical bytes verbatim; deserialization recomputes nothing.

Loading path (host calls at startup):

1. The host calls `ContractLoader.load(frozenBytes, offlineVerifier)`.
2. The loader parses the canonical bytes back into a `CompositionContract` and a `SignedContract` envelope.
3. The verifier checks the signature against a host-supplied `VerificationKeySet`. Verification result is recorded in the `LoadResult`.
4. `ContractValidator` runs all class-level and cross-schema rules; structural failures produce structured errors, not exceptions.
5. The host inserts the loaded `FrozenContract` into its `ContractStore`. The broker can now match it on `present(...)`.

Byte stability: a freeze followed by load-and-re-freeze must produce identical bytes. A property test enforces this on every public model record.

---

## Validation And Error Model

Use layered validation (HLD-C2 §F):

1. **Field-level constraints** via Jakarta annotations (`@NotNull`, `@NotBlank`, `@NotEmpty`, `@Size`, custom `@Semver` and `@Uri`).
2. **Object-level schema constraints** via class-level custom validators per schema (claim, manifest, contract, runtime binding).
3. **Cross-schema constraints** in `CrossSchemaValidator` services.

Error model:

- Expected failures return structured `SchemaValidationReport` / `LoadResult` / `Disposition` records.
- Top-level error codes: `VALIDATION_FAILED`, `CLAIM_MALFORMED`, `MANIFEST_MISMATCH`, `CONTRACT_PARTIES_INVALID`, `CONTRACT_PROVENANCE_INCONSISTENT`, `CONTRACT_TRUST_INCONSISTENT`, `SIGNATURE_INVALID`, `BINDING_INLINE_SECRET`, `BINDING_OVERRIDES_OWNERSHIP`, `RESOLUTION_FAILED`, `MULTIPLE_MATCHES`, `OFFER_VERSION_UNSATISFIED`, `ACCESS_DENIED`, `NO_MATCHING_CONTRACT`, `DCP_VERSION_UNSUPPORTED`, `BROKER_ACCEPT_INVALID`, `CONTRACT_NOT_FOUND`, `CONTRACT_INVALIDATED`.
- Errors include location fields where applicable: claim URI, contract id, runtime binding id, capability name, field path, version range, signer key id, correlation id.
- Runtime exceptions are reserved for programmer errors and are wrapped at service boundaries into structured failures.

Serialization:

- All public models round-trip JSON and YAML stably via Jackson with a snake_case property naming strategy.
- Wire field names match HLD-C2 §H preferred names. Legacy aliases (HLD-C2 §H compatibility rule) MAY be accepted on input; generated output SHOULD use the preferred names.

---

## Enterprise Trust, Audit, And Compliance Considerations

DCP is the keystone for the residency wedge; this LLD honors HLD-E by structure, not by adding heavyweight integrations.

Contract trust:

- DCP owns the contract schema, `TrustTier` derivation, signing/verification interfaces, and offline verification logic.
- Fabric (outside this repo) owns signing keys and the act of signing.
- Host products own boot-time verification policy (which signers are accepted; how `SELF` tier contracts are handled).
- The protocol records provenance; policy decides what to do with it.

Offline licensing:

- `unfurl-dcp` must not start network calls, background license checks, or vendor callbacks.
- Verification key material is supplied by the host through `VerificationKeySet`; the library never fetches remote keys.

Audit:

- `BrokerEvent`s are metadata-first (claim URI, contract id, capability name, correlation id, trust tier, disposition kind).
- Event payloads must not include full claim text or full contract text by default; hosts opt in via explicit metadata if their audit policy permits.
- CloudEvents mapping is host/adapter-owned (HLD-C §6A "bind hard" rule); the broker emits substrate-neutral events.

Telemetry:

- W3C trace context flows through `ExecutionContext` (`traceContext` map).
- The broker propagates `correlationId` end-to-end: `present → accept → register → ContractInvocableAdapter.invoke`.
- OTLP/OpenTelemetry SDK implementations live in host adapters; the broker only emits through the `BrokerEventSink` port.

Auth and authorization:

- DCP does not validate OAuth/OIDC tokens or fetch JWKS.
- Hosts translate authenticated identity into `ExecutionContext`; the broker's policy decisions consume that context but do not inspect tokens themselves.

No-phone-home:

- No class in `unfurl-dcp` may open sockets, use HTTP clients, call Unfurl services, fetch remote keys, or emit telemetry directly.
- ArchUnit tests enforce the dependency side of this guarantee.

---

## Testing And Architecture Enforcement

Unit tests:

- Claim/manifest/contract/runtime-binding shape and validation: required fields, version pinning, refusal non-emptiness, boundary-principle requirement, intelligent-component negotiation-surface requirement, secret-reference enforcement, runtime-policy firewall.
- Fault model: declarations must affect at least one need/offer/constraint; propagation conditions are required for parent impact; the propagation gate rejects undeclared faults, suppresses `NONE`, and propagates `DEGRADED`/`BLOCKED` deterministically.
- `ComponentDescription` projection: every description produces a valid claim and manifest; the two share identity URI and version; manifest permissions derivable from claim.
- Resolver: structural match success; version-range mismatch; access-policy enforcement; deterministic highest-match.
- Question schema renderer: human-interview and model-prompt views are structurally identical after normalization.
- Contract freeze/load byte stability: round-trip bytes equal.
- Contract signing/verification: valid signature accepted; tampered bytes rejected; wrong key rejected; missing key returns structured error (not exception).
- Broker: `present` returns `ACCEPT(MATCH_FOUND)` when contract matches; `REFUSE(NO_MATCHING_CONTRACT)` with redirection when no match; `REFUSE(SIGNATURE_INVALID)` on tampered/wrong-key contracts; `REFUSE(CLAIM_MALFORMED)` on shape failure; `REFUSE(DCP_VERSION_UNSUPPORTED)` for old/new dcp versions. `accept(disposition, registrar, factory, ctx)` accepts only `DispositionKind.ACCEPT`, rejects missing/stale id/version with `BROKER_ACCEPT_INVALID`, fails absent frozen contracts with `CONTRACT_NOT_FOUND`, re-verifies the signature, and registers exactly the contract's single binding through the `CapabilityRegistrar`; `revoke` calls `unregister`; `CONTRACT_INVALIDATED` revokes without re-negotiating.
- SPI fakes: an in-memory `CapabilityRegistrar` records register/unregister calls so broker tests can assert the registration sequence without depending on flow/foundry.

Property tests (jqwik):

- Claim validator determinism: same input → same diagnostics, regardless of evaluation order.
- Resolver stability: shuffling the candidate provider set produces the same result.
- Renderer identity: for arbitrary generated `NegotiationQuestionSchema` instances, `InterviewRenderer.render(...).normalized() == ModelPromptRenderer.render(...).normalized()`.
- Contract byte stability: freeze → bytes → load → freeze produces the same bytes for any contract shape.
- SemVer range matching matches the underlying library's semantics on generated version sets.

Architecture tests (ArchUnit) — **package-scoped within a single Maven artifact**, since `unfurl-dcp` is one artifact (per the Java build spec). Without separate modules, every architectural boundary in this LLD becomes an ArchUnit assertion or it is not enforced. The required rules:

- No forbidden dependencies: no `unfurl-flow`, `unfurl-foundry`, `unfurl-foundry-substrate`, or `unfurl-fabric` imports.
- No web framework, HTTP client, DB, queue/cache, cloud SDK, auth SDK, AI SDK, or observability SDK usage anywhere.
- No production package depends on `com.unfurl.dcp.testing`.
- Package dependency graph matches the allowed edges in this LLD (a `classes().that().resideInAPackage(...)` rule per outbound edge).
- `broker/` does not import `questions/`, `manifest/`, or `description/`.
- `broker/` does not import `com.unfurl.substrate.ports.CapabilityRegistry`; it must go through `spi.CapabilityRegistrar`.
- `description/`, `claim/`, `manifest/`, `runtimebinding/`, `questions/`, `resolver/`, `versioning/`, `validation/` do not import `broker/` or `spi/`.
- `trust/` does not import `contract.CompositionContract`; only `SignedContract` envelopes cross the boundary; `trust/` is a leaf with no in-artifact upstream dependencies.
- `unfurl-dcp` may only import `unfurl-substrate`'s composition-api and ports artifacts; never the engine.

Enterprise tests:

- No-op `BrokerEventSink` performs no I/O.
- Broker events omit full claims/contracts unless explicitly supplied by caller metadata.
- `correlationId` propagates from `ExecutionContext` through every broker event and contract invocation tag.
- `OfflineContractVerifier` opens no sockets and reads no remote keys.
- Runtime binding rejects any inline credential value; only `SecretRef` is accepted.

---

## Implementation Sequence

1. Create the Maven artifact, JDK 21 compiler settings, dependency management (Jackson, Jakarta Validation, Hibernate Validator, semver4j, JUnit 5, AssertJ, jqwik, ArchUnit), Maven Enforcer rules, and a baseline ArchUnit suite covering the package boundaries listed in §Package And Module Design.
2. Implement `versioning/` (SemverRange, helpers).
3. Implement `trust/` first as a leaf: `TrustTier`, `TrustCreatedBy`, `TrustTierDeriver`, `ContractSigner` interface, `SignedContract`, `OfflineContractVerifier`. (Built before `contract/` so the latter can delegate derivation immediately.)
4. Implement `description/` shared model and the `claim`/`manifest` projection functions.
5. Implement `claim/` records and `ClaimValidator`; round-trip JSON/YAML tests.
6. Implement `manifest/` records and `WebappManifestValidator` with claim-projection cross-checks.
7. Implement `contract/` records, `ContractValidator`, canonical byte serialization, `ContractFreezer`, `ContractLoader`; `Trust.tier` populated via `TrustTierDeriver`.
8. Implement `runtimebinding/` records and `RuntimeBindingValidator` (no-inline-secrets, runtime-policy firewall).
9. Implement `questions/` schema model, canonical v0.2 set, `InterviewRenderer`, `ModelPromptRenderer`, `CapturedAnswer`/`AnswerCorpus`.
10. Implement `resolver/` (`CapabilityResolver`, `ResolutionRequest`, `ResolutionResult`).
11. Implement `validation/` cross-schema service.
12. Implement `fault/` (`FaultPolicy`, declarations, runtime signals, and `FaultPropagationGate`).
13. Implement `spi/` (`ContractStore`, `ContractInvocableFactory`, `CapabilityRegistrar`, `BrokerEventSink`, `NoopBrokerEventSink`).
14. Implement `broker/` (`CompositionBroker` interface, `DefaultCompositionBroker` with constructor injection, `Disposition`, `DispositionReason`, `RegistrationHandle`, `BrokerEvent`).
15. Implement `testing/` fixtures: `InMemoryContractStore`, `InMemoryCapabilityRegistrar`, `EchoContractInvocableFactory`, `RecordingBrokerEventSink`.
16. Complete property tests, ArchUnit tests (package-scoped), and enterprise guardrail tests before downstream repos (`foundry-substrate-offers`, flow, foundry) consume the library.

---

## Cross-Repo Compatibility Notes

These notes flag the deltas this LLD introduces relative to its neighbors. They are not separate work items; they are pointers for the implementers of those repos.

**`unfurl-foundry-substrate` (`OfferFragment` placeholder) — DONE.** `foundry-substrate-offers` no longer ships the `OfferFragment` placeholder; it consumes `unfurl-dcp` directly:

- `OfferFragment` is removed; `com.unfurl.dcp.claim.Offer` is used directly (e.g. `AiOffers.standardAiOffers(...)`).
- `com.unfurl.dcp.spi.ContractInvocableFactory` is implemented by `FoundryContractInvocableFactory`, which materializes `AgentInvocation` / `ToolInvocation` / `RagInvocation` / `ProviderInvocation` from a `CompositionContract` + `Binding`.
- `unfurl-dcp` is on the `foundry-substrate-offers` POM (its sole DCP-importing module), and the prior pom TODO note is gone.
- The module additionally hosts `FoundryClaimProjector` and `FlowClaimProjector`, which synthesize DCP claims (with `contains` containment) from the foundry agent and flow/workflow substrates so both project through the same `DcpProjectionProjector`.

**Host (flow / foundry) compatibility.** Hosts must:

- Implement `com.unfurl.dcp.spi.CapabilityRegistrar` as a thin mutable adapter over their mutable capability manager/registry implementation. It may feed the read-only `com.unfurl.substrate.ports.CapabilityRegistry` that engines resolve against, but it is not implemented by the read-only interface itself. This is the only way capabilities enter the registry from the broker; the substrate's read-only `CapabilityRegistry` interface is unchanged.
- Construct a `DefaultCompositionBroker` with their `ContractStore`, `OfflineContractVerifier`, `VerificationKeySet`, `ClaimValidator`, and `BrokerEventSink`.

**`unfurl-substrate` impact.** None required for the broker to function. The substrate `CapabilityRegistry` interface stays read-only; mutability is owned by `CapabilityRegistrar` in DCP, exactly because DCP also owns the registration lifecycle. If the substrate later wishes to declare a mutable extension interface, the broker can be retargeted; today's design avoids any substrate API change.

**`REPO-unfurl-dcp-java-build-spec.md` — DONE.** The Java build spec has been updated to include the runtime composition broker added by the foundry-substrate RECONCILIATION. It now contains:

- `broker/` and `spi/` in the Java package layout.
- A "Runtime Composition Broker" responsibility section (§8): deterministic `present`/`accept`/`revoke`/`invalidate`, capability registration via SPI, and no-runtime-self-heal.
- Acceptance criteria mirroring §"Testing And Architecture Enforcement" of this LLD (build-spec §8 broker, §9 SPI, §10 architecture/enterprise/property tests).

The build spec and this LLD are now consistent.
