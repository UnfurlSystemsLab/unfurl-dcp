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
- Question schema rendering into two neutral shapes: human-interview view and model-prompt view, from one canonical definition,
  plus a design-time `ActionContext` input model for action-scoped clarification.
- Capability documentation projection models that validate OpenAPI/Swagger/AsyncAPI/MCP documentation inputs against
  accepted contracts, active runtime bindings, registered capabilities, explicit schemas, and declared faults.
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
  documentation/    // capability documentation projection: model, gap validator, projector, renderers, registration report codec
  contract/         // CompositionContract records, freezing, loading, provenance, trust, signature verification
  runtimebinding/   // RuntimeBinding records + RuntimeBindingValidator (no-inline-secrets, policy firewall)
  questions/        // NegotiationQuestion(Schema) + ActionContext + renderers + CapturedAnswer
  resolver/         // CapabilityResolver: need -> capability structural matching with SemVer
  validation/       // CrossSchemaValidator: claim<->manifest, contract<->claim, binding<->contract checks
  versioning/       // SemverRange helpers, claim/offer/contract version axes
  broker/           // CompositionBroker (the runtime composition broker)
  host/             // in-process component host: activation SPI, host-port coverage, pinned artifacts (HLD-C §6.1) [PROPOSED]
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

documentation
  contains:
    model: CapabilityDocumentation, DocumentationSource, DocumentationSurface, DocumentedComponent,
           DocumentedCapability, DocumentedApi, DocumentationReport, ReportEntry, ApiOperationExclusion,
           EntrySurface, EntryStatus, ReasonCode, IdentityMode, Visibility, VisibilityPolicy
    runtime observation: RegistrationReport, Registration, RegistrationObservation, RegistrationReportCodec
    services: CapabilityDocumentationValidator (gap gate), ClaimArtifactValidator, ServiceRootResolver,
              DocumentationProjector, HostDocumentationProjector
    ports + adapters: DocumentationArtifactStore, ContentAddressedArtifactStore, PackagedArtifactStore
    renderers: DocumentationRenderer, OpenApiDocumentRenderer, HumanSummaryRenderer, DocumentationCodec
  may depend on:
    contract, runtimebinding, claim, fault, validation, Jakarta Validation API, Jackson,
    host (ONLY value types: VerifiedComposition, VerifiedContracts, VerifiedContract, ProviderArtifactPin,
          HostReport.AdmittedComponent, PackagedResources),
    unfurl-substrate substrate-ports (ConfigResolver, ExecutionContext)

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
    RuntimeBinding record + nested records mirroring HLD-C2 §C, RuntimeBindingValidator,
    IdentityBinding (reader of the identity-context binding)
  may depend on:
    contract, versioning, Jakarta Validation API

questions
  contains:
    NegotiationQuestion, NegotiationQuestionSchema, AnswerType, FeedsTarget,
    ActionContext, ActionOperation, ActionTarget, ActionCapabilities, ActionConnection,
    InterviewRenderer (renders to a neutral InterviewView shape),
    ModelPromptRenderer (renders to a neutral PromptView shape),
    CapturedAnswer, AnswerCorpus
  may depend on:
    claim, description, Jakarta Validation API

resolver
  contains:
    CapabilityResolver, ResolutionRequest, ResolutionResult, AccessPolicy,
    required offer-detail subset matching for governed capability modes
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
- `description/`, `claim/`, `manifest/`, `documentation/`, `runtimebinding/`, `questions/`, `resolver/`, `versioning/`, `validation/` must not depend on `broker/` or `spi/`.
- `documentation/` may validate references to contracts and runtime bindings, but it must not inspect host registries
  directly or scan implementation classes. Hosts pass registered capability names as projection input, as
  `RegistrationObservation`s (HLD-C2 G.3).
- `documentation/` has no HTTP client and no server. Observing a remote host's registration report is the embedding
  service's job (for example the API catalog); DCP owns the report's shape and codec only.
- `documentation/` may use `host/` only for value types:
  - the verified-set types `VerifiedComposition` and `VerifiedContract`, so an unverified contract set cannot be
    projected;
  - the admission evidence types `HostReport.AdmittedComponent`, `ProviderArtifactPin` and `PackagedResources`, so a
    host view documents hosted components from what the host actually verified.

  It must not use `InProcessComponentHost` or anything that reaches `broker/` or `spi/`.
- `fault/` is a schema/runtime-decision package. It may read claims to evaluate declared fault policy, but it must not depend on broker/SPI or any concrete monitoring adapter.
- `broker/` must not depend on `questions/`, `manifest/`, `documentation/`, or `description/`. Interview rendering,
  manifest projection, and documentation projection are outside the runtime broker; the broker is invocation-only.
- `broker/` must not import `substrate-ports.CapabilityRegistry` directly. It registers/revokes through `spi.CapabilityRegistrar`, which the host implements over its mutable capability manager/registry implementation. This keeps the substrate `CapabilityRegistry` interface read-only and lets DCP own the mutability contract.
- `unfurl-dcp` may depend on `unfurl-substrate` (substrate-composition-api, substrate-ports). It must not depend on `unfurl-flow`, `unfurl-foundry`, `unfurl-foundry-substrate`, or `unfurl-fabric`.
- **Single artifact, package-level enforcement.** Because `unfurl-dcp` ships as one Maven artifact (per the Java build spec), the rules above are not enforced by module boundaries but by ArchUnit assertions on package boundaries. See §"Testing And Architecture Enforcement".

Implementation rules:

- Prefer Java records for immutable data. Use immutable classes with builders only when Jackson/Jakarta Validation ergonomics require them.
- Use defensive copies for collections and map-like payloads.
- Use Jackson (`jackson-databind`, `jackson-dataformat-yaml`) with `snake_case` property naming strategy. Wire field names match HLD-C2 §J preferred names exactly (`ownership_position`, `accepted_providers`, `resolution_guidance`, `answer_grounding`, `requires_human_escalation`, `need`, `consumer_access`, `created_by`, `created_at`).
- Use Jakarta Validation API (Hibernate Validator at test/runtime) for field- and class-level constraints.
- Use a SemVer library (`semver4j` or equivalent) for version range matching.
- Treat `OfferInterface.details` as deterministic capability structure. A consumer need may require a subset of details; the resolver matches scalar values by equality, list values by containment, and map values recursively by subset. Product-specific modes such as `agent.run` `execution_modes: [simple, harness]` are expressed through this generic DCP mechanism, not as product-specific core fields.
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
        boolean metered, String costImplications,
        OfferDocumentation documentation                 // HLD-C2 A.6; null when absent
) { ... }

// HLD-C2 A.6: payload schemas for generated documentation, one entry per invocation operation. Never read by
// CapabilityResolver / OfferDetailMatcher.
public record OfferDocumentation(String summary, List<OperationDocumentation> operations) { ... }
public record OperationDocumentation(String operation, String summary, ArtifactRef requestSchema, ArtifactRef responseSchema) { ... }

// HLD-C2 A.12: one file inside the claim's own artifact, pinned by content hash.
public record ArtifactRef(String path, ArtifactMediaType mediaType, String sha256) {
    // Factory for claims built in code (adapters, Flow packages): pins the exact bytes the build ships at path, so the
    // reference and the file come from one serialization and cannot drift. sha256Of is the one hash rule; the artifact
    // stores verify with it too.
    public static ArtifactRef of(String path, ArtifactMediaType mediaType, byte[] bytes) { ... }
    public static String sha256Of(byte[] bytes) { ... }   // lower-case hex SHA-256
}
public enum ArtifactMediaType {
    SCHEMA_JSON("application/schema+json"),
    OPENAPI_JSON("application/vnd.oai.openapi+json"),
    OPENAPI_YAML("application/vnd.oai.openapi+yaml");
    // @JsonValue is the media-type string, so the wire carries the media type, not the constant name.
}

// HLD-C2 A.13: product HTTP route documents. Claim gains `List<ApiDocument> apiDocuments` (empty when absent).
public record ApiDocument(String apiId, String description, ArtifactRef openapi, List<String> surfaces) { ... }

// Compatibility: Offer keeps its previous 8-argument constructor (documentation = null), and Claim keeps its previous
// 10-argument constructor (apiDocuments = List.of()), so every existing producer compiles unchanged. Producers that
// document their offers use the canonical constructors.
public enum ConsumerAccess { ANY, NAMED_COMPONENTS_ONLY }
public enum Stability { EXPERIMENTAL, EVOLVING, STABLE, DEPRECATED }
public enum InterfaceKind { HTTP_API, EVENT_STREAM, NEGOTIATION, IN_PROCESS, EXTERNAL_PROTOCOL }
// EXTERNAL_PROTOCOL (HLD-C2 A.6): reached over the provider's own standard protocol (details.protocol), never through
// the DCP invocation envelope; it never carries documentation, and its documentation entry is always EXTERNAL (G.1).

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

`ClaimValidator` is a Jakarta-based service that enforces HLD-C2 §H's claim rules:

- All required sections present; `refusals` and `boundaryPrinciples` non-empty.
- `kind == INTELLIGENT_COMPONENT` ⇒ `negotiationSurface` present.
- Concern identifiers unique within the claim.
- `faults` present; each declared fault has code/category/severity/description and affects at least one need, offer, or constraint.
- A fault whose `parentImpact` is not `NONE` has a non-blank `propagatesWhen` gate condition.
- `metadata.dcpVersion >= 0.2.0`; `metadata.claimVersion == identity.version`.
- Refusal specificity: emit a warning (not a hard fail) when a refusal's `concern` is "everything-else" or its `rationale` is below a minimum length.
- Each offer's `version` valid SemVer; `metered` defaults to `false` when omitted; `costImplications` required when `metered == true` or `interfaceKind == NEGOTIATION`.
- `documentation`, when present, has a non-empty `operations` list with non-blank, unique operation names
  (`CLAIM_DOCUMENTATION_INCOMPLETE`, `CLAIM_DOCUMENTATION_OPERATION_DUPLICATE`). Every operation has both schema refs, and
  both use `SCHEMA_JSON` (`CLAIM_DOCUMENTATION_MEDIA_TYPE`). The root `type: object` rule is checked by
  `ClaimArtifactValidator`, which holds the bytes.
- Every `ArtifactRef` has a safe relative `path` (not absolute or empty; no `..`, `\`, empty segment or URI scheme) and a
  64-character lower-case hex `sha256` (`ARTIFACT_REF_INVALID`). The validator checks only the ref's shape. Whether the
  bytes exist and match is `ClaimArtifactValidator`'s job, because only a reader holding the artifact can check it.
- `apiDocuments`: `apiId` kebab-case and unique, `surfaces` non-empty kebab-case, `openapi` an OpenAPI media type
  (`CLAIM_API_DOCUMENT_INVALID`).
- No offer's `capability` is `registrations`, the name the DCP HTTP binding reserves for `GET /dcp/registrations`
  (HLD-C2 G.3, `CLAIM_CAPABILITY_RESERVED`).
- An `EXTERNAL_PROTOCOL` offer (HLD-C2 A.6) has a non-blank string `details.protocol` and no `documentation`
  (`CLAIM_EXTERNAL_PROTOCOL_INVALID`): it is never invoked through the DCP envelope, so it has no DCP payload.
- An `offerInterface.details` that carries `input_schema` or `output_schema` keys while the offer has no
  `documentation` produces a WARNING, `CLAIM_DOCUMENTATION_HINT_ONLY`, pointing at A.6. It is a warning so existing
  claims keep loading during migration; the hint never counts as documentation.

Canonical hash: `ClaimHash` serializes `NON_NULL`, and `apiDocuments` is serialized only when non-empty, so a claim
without the new fields keeps its canonical hash and existing pins stay valid. No canonicalization setting changes.

Wire-name drift (recorded while specifying HLD-C2 A.13): the deployed claim wire name is `offer_interface`
{`interface_kind`, `details`}, while HLD-C2 A.6 and `claim.schema.yaml` say `interface` {`kind`, `details`}.
`PackagedClaim` reads with `FAIL_ON_UNKNOWN_PROPERTIES` off, so a spec-shaped `interface` block would currently be
**dropped silently**. Before it reads `documentation` the claim codec accepts both spellings (`@JsonAlias("interface")`,
`@JsonAlias("kind")`), and the spec's alignment is recorded as an open decision for the `dcp` repository. The
canonical hash keeps serializing the record component names, so pins do not change.

Validation results are structured records (see §8), never exceptions for expected validation failures.

#### Packaged claim reader (HLD-C §3.10)

A component's claim is the `claim` block of the `META-INF/unfurl-catalog.yaml` resource packaged in its artifact.
`com.unfurl.dcp.claim.PackagedClaim` is the one reader every runtime uses to obtain its own claim, so no runtime builds
a second, profile-dependent claim:

```java
public final class PackagedClaim {
    public static final String RESOURCE = "META-INF/unfurl-catalog.yaml";
    public static Claim load(ClassLoader loader, URI expectedUri);   // reads RESOURCE from the loader
    public static Claim parse(byte[] manifestBytes);                 // reads the claim block of manifest bytes
}
```

- `parse` reads the snake_case YAML manifest leniently (unknown fields ignored, as the catalog block is not DCP's) and
  binds only the top-level `claim` block. A manifest without a `claim` block, or a claim with validation errors under
  `ClaimValidator`, fails with `PackagedClaimException` carrying the diagnostics; it never returns a partial claim.
- `load` reads every `RESOURCE` the loader can see and returns the one whose `identity.uri` is `expectedUri`. It fails
  when none or more than one match, so a runtime on a classpath that also holds other components' jars still gets
  exactly its own claim and never another's.
- The reader never edits the claim. Hashing for pinning stays with the catalog that admits the artifact.

#### Bundled claims reader (HLD-C2 G.1 input 2)

A deployment bundle carries the provider claims of its children as `claims/*.claim.{json,yaml}` beside the contracts
(written by Fabric's `DcpRuntimeBundleWriter`). `com.unfurl.dcp.claim.BundledClaims.loadDirectory(Path)` returns them
keyed by `ClaimKey(uri, version)`:
- every file is validated by `ClaimValidator`;
- two files with the same key, or a file that does not parse, fail the whole load;
- an absent directory is an empty map.

The reader does not check pins. Callers compare `ClaimHash.canonical` with each child's pinned hash, which is what
`DocumentationProjector.plan` does. Flow's private reader in `DcpDeploymentHydrator.claims` moves here, so Flow and the API
catalog share one reader.

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
public enum RuntimeViolationPolicy { HARD_FAIL }   // settled; HLD-C2 §H
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

Validation rules (HLD-C2 §H) enforced by `ContractValidator`:

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
// HLD-C2 C target_environment. tenant, region and namespace are optional and omitted when null, so existing bindings
// serialize unchanged; documentation filters by tenant and environment. A TargetEnvironment(name) constructor remains.
public record TargetEnvironment(String name, String tenant, String region, String namespace) { ... }
public enum DeploymentKind { IN_PROCESS, CONTAINER, REMOTE_SERVICE, EXTERNAL_SAAS, WEBAPP, SIDECAR }

public record SecretRef(@NotBlank String uri) { ... }       // reference only
public record ConfigRef(@NotBlank String uri) { ... }
// configuration.values (non-secret), config_refs (config://) and secret_refs (secret://, HLD-C2 configuration): a
// binding that needs several secrets, such as an identity binding's per-service client credentials, names each one
// in secret_refs instead of inlining it in values. The validator requires every secret_refs entry to be secret://.
public record Configuration(Map<String, Object> values, Map<String, ConfigRef> configRefs, Map<String, SecretRef> secretRefs)
public record RuntimeBindingMetadata(Map<String, Object> extensions) { ... }
```

`RuntimeBindingValidator` enforces:

- `contractId` references an existing frozen contract; `contractVersion` is pinned and matches.
- Provider/consumer `componentVersion` match the contract's parties.
- **No inline secrets.** Any literal credential value (a non-`SecretRef` field carrying anything that looks like a credential) fails validation. Secrets are *references only*.
- **Runtime-policy firewall.** Runtime binding may set `enabled`, timeouts within contract-permitted bounds, retry/circuit-breaker references, telemetry namespace, and audit flag — it MUST NOT change claim ownership, dependency satisfaction, conflict decisions, trust tier, or invalidation rules. The validator rejects any binding field that attempts to override these.
- `baseUrl` and `baseUrlRef` mutually exclusive; `baseUrlRef` preferred outside local development.
- **HTTP endpoint shape (HLD-C2 C, G.3).** For `deploymentKind` `CONTAINER`, `REMOTE_SERVICE` or `SIDECAR` with a
  `baseUrl`, the URL's path MUST end with `/dcp/<binding.providerCapability>` of the validated contract
  (`RUNTIME_BINDING_ENDPOINT_SHAPE`). A `baseUrlRef` is checked where it is resolved (`ServiceRootResolver`), because
  the validator does no I/O.
- **Identity binding reader (HLD-needs-driven-identity §5).** `IdentityBinding.from(List<CompositionContract> children,
  Map<URI, RuntimeBinding> bindings)` (callers pass the verified children; `runtimebinding` stays free of `host/`) returns `Optional<IdentityBinding>`: empty when no child's `providerCapability` is
  `identity-context`. Otherwise it returns the complete settings:
  - `issuer`, and `audiences` keyed by provider claim URI;
  - the tenant, roles and permissions claim names;
  - `tokenEndpointRef` and `verificationKeysRef` (`config://`), and `clientCredentialsRef` (`secret://`);
  - `serviceCredentials` keyed by claim URI.

  It throws `IDENTITY_BINDING_INCOMPLETE` when the binding is missing or incomplete, or when the audience keys differ
  from the contract's `neededBy`. Today this logic exists twice, in Flow's `IdentitySettings` and Foundry's
  `CompositionIdentity`. It moves here so the API catalog reuses it instead of adding a third copy, and Flow and Foundry
  switch to it in the same change. It reads values only; it never resolves references or calls the identity
  provider.
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

`ActionContext` is a design-time input to the same question-rendering path. It captures selected UI/authoring actions such
as add, remove, replace, connect, disconnect, and configure runtime without making those actions runtime commands:

```java
public record ActionContext(
        ActionOperation operation,
        ActionTarget target,
        ActionCapabilities capabilities,
        ActionConnection connection,
        Map<String, Object> session,
        Map<String, Object> constraints
) { ... }

public enum ActionOperation {
    ADD_COMPONENT, REMOVE_COMPONENT, REPLACE_COMPONENT, CONNECT, DISCONNECT, CONFIGURE_RUNTIME
}
```

Renderers use this context to choose targeted clarification questions. Captured answers still feed normal contract,
runtime-binding, or product-intent creation; `ActionContext` is never serialized into Plane 3 invocation.

`AnswerCorpus` is an append-only collection of `CapturedAnswer`s for one session. It serializes to the LoRA training tuple shape `(claim, request, expected_disposition, expected_redirection, rationale)` via a documented projection — the bridge described in HLD-C §5.3 between the protocol and the experiment.

### Capability Documentation Projection (HLD-C2 §G)

Design: HLD-C2 G.1–G.5 and the `dcp` repository's DCP Documentation and DCP HTTP Binding specs. The package turns a
verified composition, its claims and shipped artifacts, its runtime bindings, and one registration observation per
service root into one deterministic model. It then renders that model as OpenAPI, a human summary and a report. It has
no HTTP code: observing remote hosts belongs to the embedding service (the API catalog), and a host's own view
(`HostDocumentationProjector`) uses its local registrations.

#### Model (`com.unfurl.dcp.documentation`, snake_case on the wire, HLD-C2 G.2 / G.4)

```java
public record CapabilityDocumentation(
        URI documentId, TargetEnvironment targetEnvironment, DocumentationSource source, IdentityMode identity,
        List<DocumentationSurface> surfaces, List<DocumentedComponent> components,
        DocumentationReport report, DocumentationLifecycle lifecycle) { ... }

public record DocumentationSource(URI rootContract,            // absent in a host view (no composition files)
                                  List<URI> contracts, List<URI> runtimeBindings,
                                  List<String> registeredCapabilities) { ... }
public record DocumentationSurface(SurfaceKind kind, Visibility visibility, ConfigRef baseUrlRef, List<String> includes) { ... }
// One per host (service root) with at least one documented capability; a host with none appears only in the report.
public record DocumentedComponent(URI componentUri, String componentVersion, String name, String claimHash,
                                  URI serviceRoot, boolean protectedComponent,   // identity contract protects it
                                  List<DocumentedCapability> capabilities, List<DocumentedApi> apiDocuments) { ... }
public record DocumentedCapability(String capability, String capabilityVersion, String description, URI contractId,
                                   URI bindingId,                                // absent in a host view
                                   EntrySurface surface, Visibility visibility, String path, URI hostedBy,
                                   URI providerComponentUri, String providerComponentVersion,
                                   List<DocumentedOperation> operations, List<String> faults) { ... }
public record DocumentedOperation(String operation, String summary, ArtifactRef requestSchema, ArtifactRef responseSchema) { ... }
// A host's DocumentedComponent lists its own capabilities and those it hosts; a hosted one has
// surface HOSTED_IN_PROCESS, hostedBy = the host and providerComponentUri = the hosted component.
public record DocumentedApi(String apiId, ArtifactRef openapi, Visibility visibility, List<String> servedSurfaces) { ... }

public record DocumentationReport(List<ReportEntry> entries, List<ApiOperationExclusion> apiOperations) { ... }
public record ReportEntry(URI contractId, URI bindingId, String capability, URI providerComponentUri,
                          EntrySurface surface, EntryStatus status, ReasonCode reason, String detail,
                          URI endpoint, Instant observedAt) { ... }
public record ApiOperationExclusion(URI componentUri, String apiId, String operation, ReasonCode reason) { ... }

public enum EntrySurface { HTTP, HOSTED_IN_PROCESS, EXTERNAL }
public enum EntryStatus { ACTIVE, INACTIVE, NOT_PROBED }
public enum ReasonCode { ACTIVE, POLICY_PRIVATE, BINDING_MISSING, BINDING_DISABLED, BINDING_ENDPOINT_MISSING,
                         ENDPOINT_PATH_MISMATCH, HOST_UNKNOWN, PROVIDER_UNREACHABLE, REGISTRATION_UNAUTHORIZED,
                         HOST_CLAIM_MISMATCH, CLAIM_MISMATCH, NOT_REGISTERED, EXTERNAL_SURFACE, SURFACE_NOT_SERVED }
public enum IdentityMode { IN_CONTRACT, ABSENT }
public enum Visibility { PUBLIC, INTERNAL, PRIVATE }
public enum SurfaceKind { OPENAPI, HUMAN_SUMMARY, SWAGGER_UI, ASYNCAPI, MCP_TOOLS, SDK_METADATA }

// Product policy input (HLD-C2 G.1 input 7): explicit entries win; `defaultVisibility` covers the rest.
public record VisibilityPolicy(Visibility defaultVisibility, Map<String, Visibility> capabilities,
                               Map<String, Visibility> apiDocuments) { ... }
```

`TargetEnvironment` and `ConfigRef` are reused from `runtimebinding`, and `ArtifactRef` from `claim`. The record
constructors enforce the G.4 invariants: `status == ACTIVE` exactly when `reason == ACTIVE`, and `NOT_PROBED` exactly
when `reason == EXTERNAL_SURFACE`. Lists are sorted (components by URI, capabilities by name, report entries by contract
id) and copied, so equal inputs give equal records.

#### Registration report (HLD-C2 G.3)

```java
public record RegistrationReport(URI componentUri, String componentVersion, String claimHash, Instant observedAt,
                                 List<Registration> registrations, List<String> servedSurfaces) { ... }
public record Registration(URI contractId, String contractVersion, String capability, URI providerClaimUri,
                           String providerClaimVersion, String providerClaimHash,
                           EntrySurface surface, String path) { ... }
// Invariant (constructor-checked by RegistrationReport): for surface HTTP, providerClaim{Uri,Version,Hash} equal the
// report's componentUri/componentVersion/claimHash. For HOSTED_IN_PROCESS they are the hosted component's identity and
// the hash the host verified against the contract's ProviderArtifactPin at admission (InProcessComponentHost).
// A host never reports a hosted component under its own identity.

// Sum type (sealed): the only ways an observation can end. The embedding service maps transport outcomes onto it.
public sealed interface RegistrationObservation {
    record Reported(RegistrationReport report, Instant observedAt) implements RegistrationObservation { }
    record Unreachable(String detail, Instant observedAt) implements RegistrationObservation { }   // connect/timeout/5xx/malformed
    record Unauthorized(int status, Instant observedAt) implements RegistrationObservation { }     // 401/403
}

public final class RegistrationReportCodec {   // the one JSON codec for the report, used by hosts and observers
    public static byte[] encode(RegistrationReport report);
    public static RegistrationReport decode(byte[] json);   // throws RegistrationReportException on a malformed body
}
```

A host builds its report from what it has actually registered: the registrations its `CapabilityRegistrar` currently
holds, its own `ClaimHash.canonical` of its packaged claim, and the surfaces it serves. For each hosted registration
it also reports the provider evidence it already holds: the `ProviderArtifactPin.claimHash()` that
`InProcessComponentHost` verified against the packaged claim of the admitted artifact, and that component's identity
and version. Observers decode the body with
`RegistrationReportCodec`; a body that does not decode is `Unreachable` with the decode error as its detail.

Decode errors are content-free, because the body is an untrusted provider response and the error becomes an
observation detail that projectors report and log. The message states a broken rule (`WireRuleViolation`, the fixed-text
failure that the report records and `WireEnums` throw), the name of a missing required field, or the location of an
unreadable field (`registrations[0].surface`). It never quotes a value, and it chains no parser cause, because Jackson's
own messages quote the input.

Wire enumerations (HLD-C2 G.2). `EntrySurface`, `EntryStatus`, `Visibility`, `IdentityMode` and `SurfaceKind` write
their lower-case schema value through `@JsonValue` (`http`, `hosted_in_process`, `in_contract`, ...) and read only that
value through `@JsonCreator`, the same pattern as `ArtifactMediaType`. `ReasonCode` stays upper case, as G.4 lists it.
Both `RegistrationReportCodec` and `DocumentationCodec` therefore write exactly what their schemas state.

#### Artifact store (Port + Adapters)

```java
public interface DocumentationArtifactStore {
    /** The verified bytes of {@code ref}; throws ArtifactUnavailableException (missing / hash mismatch), never falls back. */
    byte[] read(ArtifactRef ref);
}
// Adapter: deployment bundle layout `artifacts/sha256/<sha256>` (HLD-C2 A.12), resolved by hash.
public final class ContentAddressedArtifactStore implements DocumentationArtifactStore { ... }
// Adapter: a component's own artifact, resolved by `path`. Several factories, one behaviour:
//   fromClassLoader(loader)         - a running host reading its own jar (the host view);
//   fromArchive(Path jar)           - a reader of a jar as data, never loading classes (Fabric's catalog scan);
//   fromArchiveBytes(byte[], name)  - the same over bytes the reader already holds and hashed (Fabric's scanner).
// The archive factories hold the jar's bytes once and locate an entry only when a reference names it; only a referenced
// entry over 16 MiB fails (ARTIFACT_UNAVAILABLE), so an unrelated large entry such as a fat jar's nested jar is harmless.
//   fromResources(resources)        - an admitted component's verified in-memory snapshot.
public final class PackagedArtifactStore implements DocumentationArtifactStore { ... }
```

Payload schemas also undergo draft 2020-12 meta-schema validation through the package-private `SchemaSyntaxValidator`
port. `HarrelSchemaSyntaxValidator` is its offline adapter, using `dev.harrel:json-schema:1.9.1`, the existing Jackson
provider, and the library's bundled dialect. External schema resolution is refused; no logging facade, network client
or provider runtime is introduced. Invalid keyword shapes, including nested schemas, produce
`SCHEMA_DOCUMENT_INVALID` diagnostics with instance pointers. Networknt remains test-only.

Both adapters verify `sha256` over the exact bytes before returning them. `ClaimArtifactValidator.validate(claim,
store)` checks every ref of a claim:
- it is readable, and its hash matches;
- **a JSON Schema** parses as draft 2020-12, and every `$ref` is a JSON-Pointer fragment (`#` or `#/...`) that resolves
  inside the file. `$id`, `$anchor`, `$dynamicRef`, `$dynamicAnchor` and `$recursiveRef` are rejected (A.12). A schema
  used as an operation's request or response schema has a root `type: object` (A.6); otherwise the code is
  `SCHEMA_ROOT_NOT_OBJECT`;
- **an OpenAPI document** parses as 3.1.0 and obeys A.13:
  - every operation has an `x-dcp-surface` from the declared surfaces and an `x-dcp-access` of `public` or
    `protected`, and any `x-dcp-permission` is a non-blank string;
  - no path starts with `/dcp/`, and there are no `servers` or security;
  - every `$ref` targets an existing `#/components/<section>/<name>[...]`, and there are no `$id` or anchor keywords;
  - no method and path repeats across the claim's `api_documents` (`API_DOCUMENT_PATH_CONFLICT`);
  - every path-level member other than `parameters` (for example `summary` or `description`) that several of the
    claim's documents declare for the same path is identical after component renaming (`API_DOCUMENT_PATH_CONFLICT`).
    The renderer merges those path items, so checking here refuses the claim at admission rather than at render.

It returns a `SchemaValidationReport` with the codes `ARTIFACT_UNAVAILABLE`, `ARTIFACT_HASH_MISMATCH`,
`SCHEMA_DOCUMENT_INVALID`, `API_DOCUMENT_INVALID` and `API_DOCUMENT_PATH_CONFLICT`; each detail names the JSON Pointer
of the offending node. Fabric runs it at catalog scan, and projectors run it when they plan.

#### Service roots (Strategy)

`ServiceRootResolver.resolve(RuntimeBinding, CompositionContract, ConfigResolver)` returns a `ResolvedEndpoint`, which is
one of `ServiceRoot(URI endpoint, URI root)`, `Missing(detail)` or `Mismatch(URI endpoint)`. A null `ConfigResolver`
means the deployment mounts no configuration, so a reference is `Missing`.
- `baseUrl` is used as written.
- `baseUrlRef` is resolved through substrate's `ConfigResolver`, under the reference's own tenant
  (`config://<tenant>/...`), as Flow resolves its identity references. The resolved document's
  `value` is the endpoint, following the same convention `ClientCredentialsTokenSource` uses for a token endpoint
  reference. An absent reference, or a document without a string `value`, is `Missing`.
- The endpoint's path must end with `/dcp/<providerCapability>`. The root is the endpoint with that suffix removed.

There is no other endpoint source: no environment variable, profile or lookup (HLD-C2 C).

#### Gap gate

`CapabilityDocumentationValidator.gaps(VerifiedComposition, Map<ClaimKey, Claim>, List<RuntimeBinding>, VisibilityPolicy)`
takes the bindings because they decide each entry's surface, and returns every
`DocumentationGap(contractId, capability, providerClaimUri)`. The surface is `Entries.surfaceOf(contract, binding,
offer)`: `EXTERNAL` when the provider offer's interface kind is `EXTERNAL_PROTOCOL`, whatever the binding's deployment
kind (HLD-C2 G.1), and otherwise `EntrySurface.of(deploymentKind)`. The gap gate and `DocumentationProjector.plan` use
that one helper, so they cannot disagree; an `EXTERNAL` entry is never a gap and never probed. A gap is an entry whose surface is `HTTP` or
`HOSTED_IN_PROCESS`, whose visibility is not `PRIVATE`, and whose provider offer has no `documentation` or an empty
`operations` list (HLD-C2 G.1).
An overload, `gaps(List<CompositionContract> children, claims, bindings, policy)`, applies the same rule to child
contracts that are not loaded as a verified set yet: Fabric's binding generator holds the signed children and the claims
they pin, but no runtime bundle. The check is static. Fabric calls it before emitting a visibility policy, and `DocumentationProjector.plan` calls it and
throws `DocumentationGapException` (`DOCUMENTATION_GAP`, naming every gap) when the result is not empty.

#### Projector (Builder: static plan, then cheap re-projection)

```java
public final class DocumentationProjector {
    public DocumentationPlan plan(DocumentationInputs inputs);   // static; throws on any input that cannot be projected
}
public record DocumentationInputs(VerifiedComposition composition, Map<ClaimKey, Claim> providerClaims,
                                  List<RuntimeBinding> runtimeBindings, DocumentationArtifactStore artifacts,
                                  VisibilityPolicy visibility, ConfigResolver configResolver, URI documentId,
                                  String generatedBy) { ... }

public final class DocumentationPlan {            // immutable; safe to share across observation threads
    public Set<URI> serviceRoots();                // exactly the roots to observe; nothing else may be probed
    public Map<URI, URI> hostComponentByRoot();    // the pinned host of each root, for per-host authorization
    public DocumentationArtifactStore artifacts(); // verified bytes captured at plan time; renderers read them, no I/O
    public CapabilityDocumentation project(Map<URI, RegistrationObservation> observations, Instant generatedAt);
}
```

`plan` refuses the whole projection with a `DocumentationException` whose `code()` is one of:
- **claims:** `CLAIM_MISSING`, `CLAIM_PIN_MISSING`, `CLAIM_PIN_MISMATCH`, `CLAIM_OFFER_MISSING`;
- **artifacts:** `ARTIFACT_INVALID`;
- **topology:** `SERVICE_ROOT_CONFLICT` (two pinned providers on one root, or one host bound at two roots),
  `BINDING_DUPLICATE` (two bindings for one contract) and `TARGET_ENVIRONMENT_INCONSISTENT` (bindings naming different
  environments);
- **identity and gaps:** `IDENTITY_BINDING_INCOMPLETE` and `DOCUMENTATION_GAP`.

`plan` does all static work once:

1. **Claims.** For every child, the provider claim is looked up by `(claimUri, claimVersion)`. Its
   `ClaimHash.canonical` must equal the child's pinned hash (`VerifiedContract.pin()`). A missing or mismatched claim
   throws `DocumentationInputException(CLAIM_PIN_MISMATCH)`.
2. **Artifacts.** `ClaimArtifactValidator` runs over every provider claim, and any error throws.
3. **Gaps.** The gap gate runs, and a non-empty result throws `DocumentationGapException`.
4. **Entries.** Each child gets its binding and surface (HLD-C2 G.1 table), its static reason
   (`BINDING_MISSING`, `BINDING_DISABLED`, `BINDING_ENDPOINT_MISSING`, `ENDPOINT_PATH_MISMATCH`, `HOST_UNKNOWN`,
   `POLICY_PRIVATE`, `EXTERNAL_SURFACE`) or, for an entry that can still be documented, its service root.
   `hosted_in_process` entries take the service root of their consumer host, which is the provider of some `http`
   entry; if none exists the reason is `HOST_UNKNOWN`.
   - **One host per root.** Every service root gets exactly one expected host pin, `HostPin(claimUri, version,
     claimHash)`, taken from the provider pins of the `http` entries that resolve to it.
   - **Conflicts.** Two `http` entries on one root with different provider pins throw
     `DocumentationInputException(SERVICE_ROOT_CONFLICT)`, naming both contracts.
   - **Each entry's own pin.** Each entry also keeps its own expected provider pin, `ProviderPin(claimUri, version,
     claimHash)`, from its contract and `VerifiedContract.pin()`.
5. **Identity.** `IN_CONTRACT` when a child's `providerCapability` is `identity-context`, otherwise `ABSENT`. The
   protected audiences are read from that child's binding exactly as hosts read them (`configuration.values.audiences`).

`project` applies the observations to the entries that can still be documented:
- `Unreachable` gives `PROVIDER_UNREACHABLE`, and `Unauthorized` gives `REGISTRATION_UNAUTHORIZED`.
- **Host check (per root).** If the report's `componentUri`, `componentVersion` or `claimHash` differs from the root's
  `HostPin`, every entry on that root gets `HOST_CLAIM_MISMATCH`. The detail names the field.
- **Provider check (per entry).**
  - If the report does not list the entry's contract id and version, the reason is `NOT_REGISTERED`.
  - If the registration's `providerClaimUri`, `providerClaimVersion` or `providerClaimHash` differs from the entry's
    `ProviderPin`, the reason is `CLAIM_MISMATCH`.
  - For an `http` entry the two pins coincide. For a `hosted_in_process` entry the host check uses the host's pin
    while the provider check uses the hosted component's own, so a correctly admitted adapter is `ACTIVE`.
- Otherwise the reason is `ACTIVE`.
- An absent observation (a root never observed yet) is `PROVIDER_UNREACHABLE` with detail `not yet observed`. It is
  never treated as active.

Product API operations are filtered by the reported `servedSurfaces`, and each excluded operation is listed as
`SURFACE_NOT_SERVED`. `project` does no I/O. It only reads the artifacts the plan has already loaded and verified, so
re-projecting after every observation is cheap and deterministic.

**Every observation is applied** (HLD-C2 G.1, Freshness). Callers re-project on every observation, not only on status
changes, because three kinds of change matter without any status changing:
- a provider can stay `ACTIVE` while its `servedSurfaces` change;
- an inactive entry can move from one failure reason or detail to another;
- `observedAt` advances on every observation.

The rendered OpenAPI and summary carry no observation times, so their bytes (and the caller's ETags) change exactly
when documented content changes. The report changes on every observation.

`HostDocumentationProjector.project(HostDocumentationInputs, Instant generatedAt)` builds the host view (HLD-C2 G.2) from
the same records and renderers. It returns `ProjectedDocumentation(CapabilityDocumentation documentation,
DocumentationArtifactStore artifacts)`, so callers render without I/O and the projector keeps no state. The inputs also
carry the host's `TargetEnvironment`, the document id and the projector identity:

```java
public record HostDocumentationInputs(
        Claim ownClaim, DocumentationArtifactStore ownArtifacts,   // the host's packaged claim and its own jar
        RegistrationReport local,                                  // built from the host's registrar right now
        List<HostedComponentDocumentation> hosted,                 // one per component the host admitted in process
        IdentityMode identity, Set<URI> protectedComponents, VisibilityPolicy policy, URI serviceRoot) { ... }

// What the host verified at admission, never reconstructed: the packaged claim InProcessComponentHost compared with the
// pin, the pin itself, and the component's artifacts read from the same verified in-memory jar snapshot.
public record HostedComponentDocumentation(Claim claim, ProviderArtifactPin pin, DocumentationArtifactStore artifacts) {
    public static HostedComponentDocumentation from(HostReport.AdmittedComponent admitted) { ... }
}
```

Rules, all checked before anything is rendered:
- **Matching (one direction).**
  - Every `local` registration with surface `HOSTED_IN_PROCESS` must match exactly one `hosted` item, by claim
    identity URI and version.
  - That item's `ClaimHash.canonical(claim)` must equal both `pin.claimHash()` and the registration's
    `providerClaimHash`.
  - A registration without a matching item, two matching items, or any hash disagreement throws
    `DocumentationInputException(HOST_VIEW_INPUT_MISMATCH)`, naming the component.
- **Retained evidence is not an error.** A `hosted` item with no current registration is admission evidence kept after
  its contracts were revoked or invalidated, because `HostReport.components()` outlives revocation while the
  registration report does not. It is ignored for rendering, validation and gaps, and the rest of the view is
  unaffected.
- **Only registered capabilities are rendered.** For a matched hosted component, only the capabilities that `local`
  currently lists are rendered, even if its claim offers more.
- **Rendered hosted claims are validated like any provider's.** `ClaimArtifactValidator` runs over each matched hosted
  claim and its store, and the gap gate runs over its rendered offers (`DOCUMENTATION_GAP`).
- **Source of the documentation.** Descriptions, operations, schemas and faults of a hosted capability come from the
  hosted claim. The registration contributes only identity, hashes and the fact of registration.

The host view needs no composition files, so it also works for an embedded profile that has none. In that case only
capabilities in `local` are documented, which means only capabilities the host's broker accepted (or the host's
component host admitted).

**`HostReport` exposes admission evidence** (In-Process Component Host). `HostReport.components()` maps each admitted
component (`<claimUri>@<claimVersion>`) to `AdmittedComponent(Claim claim, ProviderArtifactPin pin, PackagedResources
resources)`:
- `claim` is the packaged claim `InProcessComponentHost` verified against `pin`.
- `resources` is a read-only view, `Optional<byte[]> read(String path)`, over the same in-memory snapshot of the
  claim-bearing jar that `PinnedJarArtifacts` hashed and loaded. Nothing is read from disk again.

`PackagedArtifactStore.fromResources(PackagedResources)` adapts it, verifying each `artifact_ref`'s `sha256` as the
other factories do. `host/` still has no dependency on `documentation/`.

#### Renderers (Strategy)

```java
public interface DocumentationRenderer<T> { T render(CapabilityDocumentation docs, DocumentationArtifactStore artifacts); }
public final class OpenApiDocumentRenderer   // one OpenAPI 3.1.0 document per DocumentedComponent with a serviceRoot
        implements DocumentationRenderer<Map<URI, ObjectNode>> { ... }
public final class HumanSummaryRenderer implements DocumentationRenderer<String> { ... }   // Markdown, every entry
public final class DocumentationCodec { ... }   // snake_case JSON/YAML of CapabilityDocumentation (the report view)
```

`OpenApiDocumentRenderer` produces one document per component:
- `openapi: 3.1.0`, `info` from the component's claim identity, and `servers: [{url: <service_root>}]`.
- For each `ACTIVE` `http` capability, one operation `POST /dcp/<capability>` (HLD-C2 G.2, DCP invocation operations),
  built by `EnvelopeRenderer`:
  - **Per-operation envelopes.** For each documented operation `o`, `<capability>.<o>.envelope` is the G.3 request
    with `invocation.operation: {const: o}` and `invocation.input: {$ref: <capability>.<o>.request}`.
    `<capability>.<o>.success` is the G.3 result with `success: {const: true}` and
    `output: {$ref: <capability>.<o>.response}`, and `<capability>.<o>.result` is
    `oneOf: [<capability>.<o>.success, dcp.invocation.failure]`.
  - **Failure branch.** It is written once per document as `dcp.invocation.failure`:
    - `success: {const: false}`;
    - `errorCode: {type: string}`, which is required; the operation's declared faults are listed in its
      `description` and in `x-dcp-faults`, but not as an `enum`, because host failure codes are also valid;
    - `errorMessage: {type: string}`;
    - `output: {type: object}`, unconstrained, normally `{}`;
    - `metadata: {type: object}`.

    The operation's response schema is never applied to a failure.
  - **Request body.** With one operation, the body is its envelope. With several, the body is
    `oneOf: [<each envelope>]`; the `const` operations make the branches mutually exclusive, so an input such as `{}`
    can never match two branches.
  - **`200` response.** With one operation, the response is its result envelope. With several, it is `anyOf` over the
    results, each branch marked `x-dcp-operation: o`.
  - **Extensions.** `x-dcp-operations: {o: {request: <envelope ref>, response: <result ref>}}`, plus
    `x-dcp-capability`, `x-dcp-contracts` (sorted) and `x-dcp-faults` (the claim's faults for this offer).
  - **Grouping (HLD-C2 G.2, several contracts).** The renderer groups the component's `HTTP` capabilities by `path`
    before rendering, so one path item is written per path whatever the number of contracts. The envelope's
    `invocation.contractId` is `enum: [<sorted contract ids>]`. Entries of one group must have equal capability,
    provider component URI and version, description, operations and faults (`Grouped` Value Object, checked once);
    otherwise `DocumentationException(CAPABILITY_DOCUMENTATION_CONFLICT)`. Schema names stay
    `<capability>.<operation>.*`, written once per group, so they can never collide across contracts.
  - **Transport responses:** `400`, `404` and `503`.
- **Registration report operation.** Every document has `GET /dcp/registrations` (HLD-C2 G.2), with `operationId:
  dcp.registrations`, tag `dcp`, and a `200` response whose schema is `dcp.registrations.report` and whose
  `Cache-Control` header is `no-store`. The renderer writes the schema once per document from the G.3 shape:
  - the snake_case report fields, all required as G.3 states;
  - `claim_hash` and `provider_claim_hash` as 64 lower-case hex characters, `observed_at` as `date-time`;
  - `surface` an `enum` of `http` and `hosted_in_process`, and `path` (`^/dcp/.+$`) required when `surface` is `http`
    (`if`/`then`);
  - no `additionalProperties: false`, because readers ignore unknown fields and a newer host's report stays valid.
- **Hosted capabilities.** For each `ACTIVE` `HOSTED_IN_PROCESS` capability of the component, an item in the top-level
  `x-dcp-hosted-capabilities`, with fields `capability`, `provider_component_uri`, `provider_claim_version`,
  `description`, `contracts` (sorted), `faults` and `operations: [{operation, request: $ref, response: $ref}]`, grouped
  by provider component and capability like the HTTP operations. Its schemas are embedded under
  `hosted.<provider slug>.<capability>.<operation>.request|response`. No path is generated, because readers never call
  a hosted capability directly.
- **Offer schemas are embedded with rebased references** (HLD-C2 G.2), by `SchemaEmbedder` (*Visitor* over the
  Jackson tree):
  - Each schema lands at `components.schemas.<name>`, where `<name>` is `<capability>.<operation>.request` or
    `.response` (prefixed `hosted.<provider slug>.` for hosted capabilities); characters outside `[A-Za-z0-9._-]` map to
    `_`, and a duplicate name throws `SCHEMA_NAME_COLLISION`.
  - The schema is copied whole, `$defs` included.
  - Every `$ref` value `#<p>` becomes `#/components/schemas/<escaped name><p>`; JSON-Pointer escaping turns `~` into
    `~0` and `/` into `~1`.
- **Product API documents are merged with renamed components**, by `ApiDocumentMerger`:
  - Each entry of each components section (`schemas`, `responses`, `parameters`, `requestBodies`, `headers`,
    `examples`, `links`, `callbacks`, `pathItems`) becomes `<api_id>.<name>`.
  - Every `$ref` `#/components/<section>/<name><rest>`, in paths and in components alike, becomes
    `#/components/<section>/<api_id>.<name><rest>`.
  - Paths sharing a URL are merged by HTTP method. Duplicate methods and unequal shared path metadata fail with
    `API_DOCUMENT_PATH_CONFLICT`; equal shared metadata is retained once. Parameters inherited by only one document
    are moved to that document's operations before merging, preserving their scope and operation overrides.
- **Guard.** `ReferenceResolver` resolves every `$ref` in the finished document. An unresolved one throws
  `RENDER_REFERENCE_UNRESOLVED` with its pointer, and nothing is served.
- **Security is per operation** (HLD-C2 G.1). With `IdentityMode.IN_CONTRACT` and a protected component, the renderer:
  - declares `components.securitySchemes.bearerAuth` (`type: http, scheme: bearer, bearerFormat: JWT`);
  - gives every `POST /dcp/<capability>`, `GET /dcp/registrations` and every product operation marked
    `x-dcp-access: protected` `security: [{bearerAuth: []}]` plus `401`/`403`;
  - gives `GET /dcp/registrations` `x-dcp-permission: dcp.registrations.read`;
  - gives every operation marked `x-dcp-access: public` `security: []`;
  - copies `x-dcp-permission` through.

  No top-level `security` is ever written, so no operation inherits a requirement. For an unprotected component, or
  with `ABSENT`, no operation has security, there is no scheme, and with `ABSENT` the `info.description` states that
  the composition runs unauthenticated on a trusted network.
- Output is serialized with sorted keys and fixed ordering, so the same inputs give the same bytes.

Reserved kinds (`ASYNCAPI`, `MCP_TOOLS`, `SDK_METADATA`) have no renderer.
`DocumentationRenderer.requireRenderable(kind)` throws `DocumentationException(UNSUPPORTED_SURFACE)` for them.

Supporting types:
- `DocumentationFilter(tenant, environment, contract, binding, surface)` (*Specification*) narrows a view, DCP
  Documentation §5. It never changes a reason. A tenant or environment outside the projection's target environment keeps
  nothing.
- `Slugs.of(URI)` gives the URL-safe component slug. The API catalog's `{slug}` routes and the hosted schema prefixes
  both use it.
- `DocumentationCodec` writes the snake_case model as JSON or YAML (sorted, nulls omitted), and writes rendered trees
  with `treeToJson` / `treeToYaml`.

**Merging product documents that share a path.**
- Operations from different documents are merged by method into one path item.
- A repeated method is `API_DOCUMENT_PATH_CONFLICT`.
- Path-level `parameters` are pushed into each source document's own operations, preserving operation-level overrides
  by name and location, so they never leak to another document's operations.
- Every other path-level member must be identical after component renaming. `ClaimArtifactValidator` enforces this at
  admission (`API_DOCUMENT_PATH_CONFLICT`), and the renderer refuses it again as a guard.

`CapabilityDocumentationValidator` and the projector never scan classpaths, plugin jars, tool registries, provider
registries, prompts, model outputs, logs, DTOs, `offerInterface.details` hints, environment variables, DNS or
orchestrator APIs. Those sources can inform authoring, but they are not valid documentation proof.

### Capability Resolver (HLD-C2 settled #6)

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
4. The offer's `interface.details` satisfies any required detail subset on the consumer need.

No model, no AI, no probabilistic matching. Required details are deterministic: scalar equality, list containment, and recursive map subset matching. If multiple candidates satisfy, the resolver returns the highest SemVer match by default; ties (theoretically impossible after exact match) fail with a structured `MULTIPLE_MATCHES` reason.

### Cross-Schema Validation (HLD-C2 §H)

In `com.unfurl.dcp.validation`:

```java
public final class CrossSchemaValidator {
    public SchemaValidationReport validate(Claim claim, WebappManifest manifest);
    public SchemaValidationReport validate(CompositionContract contract, Map<URI, Claim> claimsByUri);
    public SchemaValidationReport validate(RuntimeBinding binding, CompositionContract contract);
}
```

The documentation re-check lives in `documentation/` as `CapabilityDocumentationValidator.validate(CapabilityDocumentation,
DocumentationInputs)`, not here, because `documentation/` already depends on `validation/`. A method here would make the
two packages depend on each other.

Checks performed:

- A manifest's component MUST have a corresponding claim; URIs and versions match.
- A contract's `binding.providerCapability` MUST exist in the provider's claim at a version satisfying `binding.providerCapabilityVersion`.
- A contract's `parties.*.claimVersion` are pinned; the report includes a "claim version drifted" diagnostic if a newer matching claim is supplied alongside the contract.
- A runtime binding's `contractId` and `contractVersion` resolve to a real contract; the binding does not attempt to alter ownership/dependency/conflict/trust/invalidation.
- A capability documentation projection references accepted contracts, active runtime bindings, registered capabilities,
  explicit schemas, and declared faults; missing or implementation-discovered-only schemas fail validation.
- `CapabilityDocumentationValidator.validate(CapabilityDocumentation, DocumentationInputs)` re-checks a produced
  projection against its inputs:
  - every `ACTIVE` capability is a child of the verified root, has an enabled binding and pinned, hash-verified schema
    refs, and appears in the observed report;
  - every child has exactly one report entry;
  - no field carries a `secret://` reference or a credential.

`SchemaValidationReport` is an immutable record carrying structured `Diagnostic`s with severity (`ERROR`, `WARNING`, `INFO`), location (claim URI, contract id, field path), and a stable code.

### Versioning (HLD-C2 settled #6)

In `com.unfurl.dcp.versioning`:

```java
public final class SemverHelpers {
    public boolean satisfies(String version, String range);
    public Optional<String> highestSatisfying(Collection<String> versions, String range);
}

public enum VersionAxis { CLAIM, OFFER, CONTRACT }   // HLD-C §8: three independent axes
```

A contract pins both `parties.*.claimVersion` (CLAIM axis) and `binding.providerCapabilityVersion` (OFFER axis); the contract itself carries `contractVersion` (CONTRACT axis). All three are evaluated independently when judging invalidation.

### Trust And Offline Verification (HLD-C2 §H)

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
- **Is exception-safe [implemented].** If `register` throws, the broker calls `registrar.unregister` for that capability before rethrowing, since a registrar may have partly registered. If anything after `register` succeeds throws — today, publishing `CAPABILITY_REGISTERED` — the broker unregisters the capability, then rethrows. A failed `accept` therefore never leaves a registration without a returned handle. Unregister failures are attached as suppressed exceptions.
- **Checks signed-body equality [proposed change].** Signature verification covers `signedContract().canonicalBytes()`, but the broker registers from the decoded `contract()`. It requires the canonical bytes of `contract()` to equal the signed bytes, refusing with `SIGNATURE_INVALID` otherwise, so a store that does not enforce this (unlike `VerifiedContracts`) cannot smuggle a tampered body past a valid signature. `present` applies the same check.

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

## In-Process Component Host (HLD-C §6.1)

Status: **implemented in DCP.** Implemented: `ClaimHash`, `Dependencies.ownerOf`/`isExternallyOwned` (slice 1);
`VerifiedContracts`, `VerifiedContract`, `ContractSource`, the pin values, `HostFailureCode` and
`ComponentHostException` (slice 2); `SharedApi`, `PinnedJarArtifacts`, `PinnedArtifactClassLoader` and the
`ComponentActivation`, `HostPorts` and `ActivationContext` interfaces (slice 3); component configuration,
`com.unfurl.dcp.componentconfig` (slice 4); `InProcessComponentHost`, `HostReport`, rollback and the exception-safe
broker `accept` (slice 5). All five slices are implemented; hosts and composers adopt it next. This is the generic mechanism by which any runtime (Foundry, Flow, an embedding
app) hosts a component the composition placed in-process. It sits on top of the broker and changes nothing in it. The
worked example is the Kotak Neo read adapter hosted by Foundry as agent tools.

### Why it is in DCP

Every host would otherwise repeat the same steps — read frozen child contracts, verify the provider artifact and its
packaged claim against the contract's pins, find the component's factory, check its `owner=host` needs are covered,
then `present`/`accept` and later revoke — and each copy would drift. Only two things differ by host: which neutral host
ports it can bind, and what shape accepted operations take for its callers. Both are parameters here.

### Types (`com.unfurl.dcp.host`)

```java
/**
 * SPI (Abstract Factory): supplied by the component, exactly one per component. Declared by a single line naming its
 * class in META-INF/services/com.unfurl.dcp.host.ComponentActivation inside one of the component's pinned artifacts;
 * the host reads that file as data and never uses java.util.ServiceLoader. Public no-argument constructor required.
 * Adapts neutral host ports to the component's own ports.
 */
public interface ComponentActivation {
    URI claimUri();                                   // must equal the packaged claim's identity
    String claimVersion();
    /** Each owner=host dependency capability this activation covers, and the host port types it needs for it. */
    Map<String, Set<Class<?>>> hostDependencies();    // empty set = self-provided from resolved configuration
    /** Called by the broker on accept; the context carries the validated runtime binding and bound host ports. */
    ContractInvocableFactory factory(ActivationContext context);
    /** The registrar shapes this component supports, e.g. a tool registrar for agent hosts. Empty if unsupported. */
    <R extends CapabilityRegistrar> Optional<R> registrar(Class<R> shape, Party consumer);   // consumer = the host
}

/** Port: the neutral host services a host has actually bound. Never returns a default or no-op. */
public interface HostPorts {
    <T> Optional<T> port(Class<T> type);              // e.g. OperationAuditSink.class, ConfigResolver.class
    Set<Class<?>> boundTypes();
}

public interface ActivationContext {
    CompositionContract contract();
    RuntimeBinding runtimeBinding();                  // validated against the contract by RuntimeBindingValidator
    HostPorts host();
    EffectiveConfiguration configuration();           // packaged defaults merged with deployment values (below)
}

/**
 * Value: a frozen contract whose signature verified against the host's trusted keys, whose schema validated, and whose
 * decoded body canonicalizes to exactly its signed bytes. Only VerifiedContracts creates one; nothing else in host/
 * accepts a bare FrozenContract or CompositionContract. It stays equal to its signed bytes: the contract graph is
 * deeply immutable (below), and byte arrays are copied on every read.
 */
public final class VerifiedContract {
    public String source();                           // the envelope name it was loaded from
    public FrozenContract frozen();
    public CompositionContract contract();            // the signed body, never a separately decoded copy
    public String id();                               // <contractId>@<contractVersion>, for diagnostics
    /** Read from the verified body only. Empty when unpinned; a malformed pin throws PROVIDER_PIN_INVALID here. */
    public Optional<ProviderArtifactPin> pin();
}

/** Value: one envelope's bytes and the name failures report (typically its file name). Carries no trust. */
public record ContractSource(String name, byte[] bytes) { }

/**
 * Repository: the verified contract set, loaded all-or-nothing (an invalid, unsigned, duplicate or body-mismatched
 * envelope fails the load with CONTRACT_UNVERIFIED, naming it). It is also the broker's ContractStore, so admission and
 * broker lookup read the same verified bytes. Generalizes Foundry's DirectoryFrozenContractStore, which delegates here.
 */
public final class VerifiedContracts implements ContractStore {
    public static VerifiedContracts load(List<ContractSource> sources, VerificationKeySet keys);
    public static VerifiedContracts loadDirectory(Path directory, VerificationKeySet keys);   // *.json, non-recursive, name order
    public List<VerifiedContract> all();                                                       // ordered by contract id, then version
    public Optional<VerifiedContract> verified(URI contractId, String contractVersion);
}

/**
 * Repository (Composite): a signed composition as a host receives it, its verified aggregate root plus exactly the
 * children the root names. VerifiedContracts proves each envelope; this proves the set. Every envelope is verified as by
 * VerifiedContracts; the root must have no parent among them; and the children must be exactly the root's transitive
 * childContractIds() closure, none missing and none extra, matched by contract id. So removing a child's file (for example
 * an optional component's contract, such as identity-context) cannot turn that component off: the load fails with
 * CONTRACT_UNVERIFIED, naming the missing child. A host decides whether an optional component is present from children()
 * only after this load succeeds.
 */
public final class VerifiedComposition {
    public static VerifiedComposition load(ContractSource root, List<ContractSource> children, VerificationKeySet keys);
    public static VerifiedComposition loadDirectory(Path rootFile, Path childDirectory, VerificationKeySet keys);
    public VerifiedContract root();
    public VerifiedContracts children();
    public List<VerifiedContract> childrenFor(String providerCapability);   // ordered as children().all()
}

/** The single failure type: one HostFailureCode (table below) and the subject it concerns. */
public final class ComponentHostException extends RuntimeException {
    public HostFailureCode code();
    public String subject();
}

/**
 * Value: the provider's complete runtime artifact set from a verified child contract (HLD-C §6.1 rule 2): the
 * claim-bearing artifact plus every companion it needs at runtime (decision D1), each with its SHA-256.
 */
public record ProviderArtifactPin(ArtifactPin claimArtifact, String claimHash, List<ArtifactPin> companions) { }
public record ArtifactPin(String coordinates, String sha256) { }   // sha256: 64 lower-case hex
// Read from metadata.extensions: artifactCoordinates, artifactSha256, claimHash (all three, or none = unpinned) and
// optional companionArtifacts: [{ coordinates, sha256 }]. Coordinates are unique across the set.
// public static Optional<ProviderArtifactPin> fromExtensions(Map<String,Object> extensions): the one parser, also used
// by deployment tooling that delivers the pinned artifacts (unfurl-deploy-emitter HLD-E §4.0b).

/**
 * Value: the packages a component's code may resolve from the host instead of its own pinned artifacts. DCP's base
 * set is fixed; a host may add exact package prefixes for its registrar shape (Foundry: com.unfurl.foundry.substrate.).
 * Anything not listed must come from the component's pinned artifacts.
 */
public record SharedApi(Set<String> packagePrefixes, ClassLoader apiLoader) {
    /** All of DCP and substrate: DCP's own types reference substrate composition, ports, policy and domain types. */
    public static final Set<String> DCP_BASE = Set.of("com.unfurl.dcp.", "com.unfurl.substrate.");
}

/** Facade: hosts one verified set of in-process components; all-or-nothing. */
public final class InProcessComponentHost implements AutoCloseable {
    /** Builds its own DefaultCompositionBroker over `contracts` and `keys`, so the broker cannot see another store. */
    public InProcessComponentHost(VerifiedContracts contracts, VerificationKeySet keys, BrokerEventSink events, HostPorts host);
    /**
     * Checks, covers and admits every verified contract whose provider is in-process; returns the report or throws.
     * `host` is the host's own identity from its packaged claim (PackagedClaim.load); every admitted contract's
     * consumer party and runtime binding consumer must equal it.
     */
    public <R extends CapabilityRegistrar> HostReport<R> admit(
            Map<URI, RuntimeBinding> bindings, PinnedJarArtifacts artifacts,
            Class<R> registrarShape, Party host, ExecutionContext startup);
    @Override public void close();                    // rollback path below: every attempted registration is removed
}

/**
 * Repository, in two stages so no component code runs before every check that needs none has passed.
 * Each delivered file is read into memory once; hashing, inspection and loading all use that one snapshot.
 * Stage 1 (data only): hash jars against pins, read packaged claims, activation declarations and class listings
 * from the zip entries, and check the artifact rules below.
 * Stage 2 (code): build each component's PinnedArtifactClassLoader and instantiate its activation.
 */
public final class PinnedJarArtifacts implements AutoCloseable {
    public static PinnedJarArtifacts inspect(Collection<Path> jars, VerifiedContracts contracts, SharedApi shared);   // stage 1
    public Optional<Claim> packagedClaim(ProviderArtifactPin pin);
    public Optional<byte[]> packagedConfiguration(ProviderArtifactPin pin);                            // unfurl-config.yaml bytes
    public List<String> ignoredArtifacts();                                                            // jars no hosted contract pins
    public ComponentActivation activate(ProviderArtifactPin pin);                                     // stage 2
    @Override public void close();                                                                     // closes every loader
}
```

**Contracts are deeply immutable.** `Transport.details` and `CompositionContractMetadata.extensions` are the only
open-ended maps in a contract. Their records copy them through `value/DeepImmutable`: nested maps, lists and sets
become unmodifiable copies that share nothing with the source (nested `null`s are kept; a top-level `null` value is
still rejected). A shallow `Map.copyOf` is not enough, because a holder could edit a nested value, such as a
`companionArtifacts` hash, and every later read, including the broker's store lookups, would return a body that no
longer matches its signed bytes. Values other than maps and collections must be immutable; a contract decoded by
`ContractCodec` holds only strings, numbers and booleans there.

The same rule covers what a host holds besides contracts. The claim records (`ClaimMetadata.extensions`,
`IntegrationPorts.ports`, `OfferInterface.details`) and the runtime-binding records (`Configuration.values`,
`DeploymentControls.values`, `RuntimeBindingMetadata.extensions`) freeze through `DeepImmutable` too, so a packaged
claim whose hash was checked, or a validated binding, cannot change afterwards. Freezing does not change canonical
bytes: map entries are sorted on serialization, and the golden claim-hash test still passes.

**Which contracts are hosted.** A verified contract's provider *binds in-process* when the contract's signed
`transport.kind` is `IN_PROCESS`. Only those contracts take part in inspection and admission; any other verified
contract is left to the host's remote paths. A *component* is one provider identity (claim URI and version) across its
hosted contracts. Every hosted contract of a component that carries a pin must carry the same one, else
`PROVIDER_PIN_INVALID`; a hosted contract with no pin is refused at admission (`PROVIDER_PIN_MISSING`).

**Artifact rules (stage 1, from zip data only).** `inspect` takes pins only from `VerifiedContracts`. For each
component it requires every artifact in its `ProviderArtifactPin` (claim-bearing and companions) to be present with a
matching SHA-256 → else `COMPONENT_DEPENDENCY_MISSING` naming the coordinates; extra jars are ignored and reported
(`ignoredArtifacts()`). It reads `META-INF/unfurl-catalog.yaml` from the claim-bearing artifact with
`PackagedClaim.parse` (`packagedClaim(pin)`), and hands the host the bytes of `META-INF/unfurl-config.yaml` from the
same artifact when present (`packagedConfiguration(pin)`). Each of these metadata entries, and the activation
declaration, is read with a 1 MiB bound; an oversized or unreadable entry, an invalid packaged claim, or a malformed
declaration is `ARTIFACT_METADATA_INVALID`.

**Activation declaration.** `META-INF/services/com.unfurl.dcp.host.ComponentActivation` is UTF-8 text. Blank lines and
text from `#` to the end of a line are ignored; exactly one binary class name must remain. Two or more names in one
file count as several declarations (`COMPONENT_ACTIVATION_DUPLICATE`).

**Effective class set.** Every class rule below, the loader and eager loading use one definition of an artifact's
classes for the running Java feature version `R = Runtime.version().feature()`, matching how the JDK itself reads a
JAR:

- The artifact is multi-release only if its main manifest has `Multi-Release: true`. Otherwise only
  `META-INF/versions/**` is ignored; every other entry, including the rest of `META-INF/`, is read at its base path.
- In a multi-release artifact, a path outside `META-INF/` has as effective entry `META-INF/versions/N/<path>` for the
  highest integer `N` with `9 <= N <= R`, else the base `<path>`. Versioned entries with `N > R` or a non-integer `N`
  are ignored. Paths under `META-INF/` are not versioned and always resolve to their base entry.
- The packaged claim (`META-INF/unfurl-catalog.yaml`), the packaged configuration (`META-INF/unfurl-config.yaml`) and
  the activation declaration (`META-INF/services/com.unfurl.dcp.host.ComponentActivation`) are therefore always read
  from their base paths. A copy of any of them under `META-INF/versions/` is refused (`ARTIFACT_VERSIONED_METADATA`), so
  no reader can see a different claim, configuration or activation than the host checked.
- Of the effective `.class` paths, module descriptors (`module-info.class`, at the root or under any version) are
  excluded: they are not classes (`ACC_MODULE`) and cannot be loaded as one. `package-info.class` entries are excluded
  from eager loading and from the duplicate rule, as they hold package annotations and no code.
- Each remaining effective path gives one binary class name (strip `.class`, `/` → `.`); a base entry and its
  versioned override are the same class, not two.

Using that set, it refuses:

- a manifest `Class-Path` (or `Launcher-Agent-Class`) attribute in any pinned artifact → `ARTIFACT_CLASS_PATH_FORBIDDEN`,
  since the JAR specification lets it extend the search path to unpinned files;
- a class or resource under a `SharedApi` package prefix in any entry, base or versioned for any `N` (so a newer Java
  version cannot later reveal a shadow) → `ARTIFACT_SHADOWS_SHARED_API`;
- the same effective class name in two artifacts of one component → `ARTIFACT_CLASS_DUPLICATE` (each modular companion
  carrying its own `module-info.class` is not a duplicate, since descriptors are excluded);
- anything other than exactly one effective `ComponentActivation` declaration across the component's artifacts, or a
  declared class outside the declaring artifact's effective class set → `COMPONENT_ACTIVATION_MISSING` /
  `COMPONENT_ACTIVATION_DUPLICATE` / `ACTIVATION_ORIGIN_MISMATCH`.

**`PinnedArtifactClassLoader` (stage 2), one per component.** Its parent is the JDK *platform* class loader, not the
application loader, so host classes are unreachable by default. Loading is defined as:

1. Classes in `java.*` and other platform modules come from the parent.
2. A class under a `SharedApi` prefix is loaded from `shared.apiLoader()` only (the host's single copy of the API the
   component and host exchange objects through).
3. Every other class is defined only from the component's pinned artifacts: the in-memory snapshots stage 1 hashed
   and parsed, using each artifact's effective entry for the running Java version, so lookups select exactly the
   entries enumerated in stage 1. Not found there means `ClassNotFoundException`; there is no fallback to
   any other loader.
4. `getResource`/`getResources` search only the pinned artifacts, so service files and other resources from the host
   classpath are never visible. A returned URL names the selected entry itself (its real, possibly versioned, name),
   and reading it reads the snapshot, never a file.
5. The artifact list is exactly the pinned artifacts and is never extended (manifest `Class-Path` was refused in
   stage 1, and the loader is not a `URLClassLoader`).
6. While the host runs component code (constructing the activation, and in slice 5 its factory and invocables), the
   thread's context class loader is that component's loader, so libraries that look classes up through it stay inside
   the pinned set.

**One snapshot.** `inspect` reads each delivered file into memory exactly once. Those bytes are hashed against the
pins, parsed for the stage-1 rules, and later used to define classes and serve resources. No file is opened again
after `inspect` returns. Hashing a path and then loading from that path, even with the file held open, can verify
one file and load another where an open file can be replaced atomically; with one snapshot the verified bytes are
the loaded bytes by construction, and a file replaced or deleted after inspection changes nothing. The archive is
read sequentially, and one that names the same entry twice is refused (`ARTIFACT_METADATA_INVALID`), so every path
has exactly one reading. A component's decompressed entries stay in memory while it is admitted; bytes of jars that
no hosted contract pins are dropped as soon as they are hashed. A class's code source is an identifier built from
the pinned hash and file name, never a location anything is read from.

**Instantiation.** `activate(pin)` loads the declared class with `Class.forName(name, false, loader)` (no static
initialization yet) and requires its class loader to be that component's loader and the class to have been defined
from the declaring artifact's snapshot → else `ACTIVATION_ORIGIN_MISMATCH`. The class must be a public `ComponentActivation`
with a public no-argument constructor, and that constructor must not throw → else `COMPONENT_ACTIVATION_FAILED`. Before
calling it, `activate` loads every class in the component's
effective class sets the same way, without initialization (module descriptors and `package-info` excluded, versioned
overrides selected for `R`), so a missing superclass or interface fails startup with `COMPONENT_DEPENDENCY_UNRESOLVED`
before any component code runs. Only then does it call the public no-argument
constructor. References resolved lazily later can still only resolve under rules 1–3, so they fail closed rather than
escape the pinned set. Third-party libraries a component uses (Kotak: Jackson) are therefore either pinned companions or,
if a host chooses to share them, `SharedApi` prefixes the host adds explicitly.

**The shared boundary.** Two rules keep the two class spaces from meeting:

- A `SharedApi` must be closed over its own signatures. Every non-JDK type in a public signature of a shared package
  is itself shared. That is why `DCP_BASE` is all of `com.unfurl.dcp.` and `com.unfurl.substrate.`: DCP and the
  foundry-substrate ports reference substrate `domain` types as well as `composition`, `ports` and `policy`. A shared
  class resolves its own dependencies, such as DCP's semver4j, Hibernate Validator and Jackson, in the host's loader,
  so those are never companions.
- Component code exchanges only shared or JDK types with the host. Shared methods whose signatures expose a non-shared
  library type (`ContractCodec.canonicalMapper()`, the documentation renderer's `ObjectNode` results, and the
  documentation codec's `JsonNode` inputs) are host-side only. These Jackson types are explicitly enumerated by
  `SharedApiBoundaryTest`; they are not added to the shared class-loader prefixes. A component that needs that
  library brings its own copy as a companion and never passes its objects across the boundary. A DCP test enumerates
  the public signatures of `DCP_BASE` packages and lists every non-shared type they expose, so that set stays
  explicit. For Kotak, only `kotak-neo-deployment`, which is not a runtime artifact, calls `canonicalMapper()`.

Two rules that only Fabric implemented have moved into DCP (**implemented**), and Fabric delegates to them, so the
composer and every host share one definition:

- `ClaimHash.canonical(Claim)` (`claim/`): lower-case hex SHA-256 of the claim serialized as YAML with sorted map
  entries and properties, line splitting and the document marker off, `NON_NULL` inclusion and ISO dates, using record
  component names — byte for byte what Fabric's `CatalogManifestCodec.computeClaimHash` produced (checked on all 28
  claims reachable to the build before the switch). `ClaimHashTest` pins it with a golden value; changing it is a
  breaking protocol change.
- `Dependencies.ownerOf(String dependency)` (`claim/`): the first `owner=` qualifier, trimmed, or `""`; and
  `Dependencies.isExternallyOwned` for `host`, `fabric`, `customer-controlled` and `customer-*`.

### Component configuration (HLD-C §3.10, §6.1 rule 8) [implemented; host wiring in slice 5]

A component's configuration has three layers, like a packaged `application.yml` overridden by external configuration,
except that the component, not the deployment, decides what may be overridden:

| Layer | Where | Holds | Changes with |
|---|---|---|---|
| Packaged | `META-INF/unfurl-config.yaml` in the claim-bearing artifact | the schema of every key, and defaults for `source: packaged` keys | a new artifact, so a new pin, recompile and re-sign |
| Deployment | the trusted runtime binding's `configuration.values` (inline) or `configuration.config_refs` (a `config://` reference) | `source: deployment` keys, and tightened packaged keys | a new binding revision, or the referenced value |
| Effective | `ActivationContext.configuration()` | the merge, validated | admission, and only admission |

**The packaged file.** It is read from the artifact's base `META-INF/` path (never versioned; a copy under
`META-INF/versions/` is `ARTIFACT_VERSIONED_METADATA`), by the same zip-data read as the packaged claim. Kotak:

```yaml
config_version: 1
schema:
  portfolioBase: { type: https-uri, source: packaged }               # no `override`: fixed
  tradingBase:   { type: https-uri, source: packaged }
  allowedHosts:  { type: list<host>, source: packaged }
  currency:      { type: iso-currency, source: packaged }
  timeoutMs:     { type: integer, min: 1, max: 60000, source: packaged, override: tighten, stricter: lower }
  maxBytes:      { type: integer, min: 128, max: 16777216, source: packaged, override: tighten, stricter: lower }
  maxRows:       { type: integer, min: 1, max: 100000, source: packaged, override: tighten, stricter: lower }
  accountRef:    { type: string, max_length: 128, source: deployment }
  sessionRef:    { type: secret-ref, source: deployment }
defaults:
  portfolioBase: https://<kotak-portfolio-host>
  tradingBase:   https://<kotak-trading-host>
  allowedHosts:  [<kotak-portfolio-host>, <kotak-trading-host>]
  currency:      INR
  timeoutMs:     30000
  maxBytes:      1048576
  maxRows:       10000
```

- Types: `string`, `integer`, `boolean`, `https-uri`, `host`, `iso-currency`, `duration`, `list<T>`, `secret-ref`.
  `min`/`max`/`max_length`/`max_items` bound them (table below).
- `source: packaged` requires a default that satisfies the schema. `source: deployment` forbids one, and the
  deployment must supply it.
- `override` is absent (fixed), `tighten` (only toward `stricter`: `lower` or `higher`; for a list, a subset), or
  `replace` (any valid value). `replace` must be explicit, so widening a security-relevant value is always a visible
  choice by the component author.
- A `secret-ref` value is always a `secret://` reference, passed through unresolved. The component resolves it through
  `SecretResolver` when it uses it, so no secret is ever materialized into configuration.

| Type | Accepted value | Effective Java type | Bounds |
|---|---|---|---|
| `string` | a YAML string | `String` | `max_length` (characters) |
| `integer` | an integral number within 64 bits; never a fraction or a string | `Long` | `min`, `max` |
| `boolean` | `true` or `false` | `Boolean` | — |
| `https-uri` | an absolute `https` URI with a host and no user info, query or fragment | `java.net.URI` | — |
| `host` | a lower-case DNS host name (labels of letters, digits and inner hyphens; at most 253 characters) | `String` | — |
| `iso-currency` | an upper-case ISO 4217 code the JDK knows | `String` | — |
| `duration` | a non-negative ISO-8601 duration string, such as `PT30S` | `java.time.Duration` | `min`, `max` (ISO-8601 strings) |
| `secret-ref` | `secret://` followed by a non-empty reference without whitespace | `String` | — |
| `list<T>` | a YAML list whose elements each satisfy scalar `T` (no nested lists) | immutable `List<T>` | `max_items`, plus `T`'s bounds per element |

The schema itself is checked strictly, and every problem in it is `CONFIG_INVALID` (a packaged default that is not a
`secret://` reference for a `secret-ref` key is `CONFIG_SECRET_INLINE`):

- Top-level keys are exactly `config_version` (must be `1`), `schema` (non-empty) and `defaults`; the YAML has unique
  keys. Key names match `[A-Za-z][A-Za-z0-9_-]{0,63}`.
- A key's attributes are `type` and `source` (required), `override`, `stricter`, `min`, `max`, `max_length`,
  `max_items`; anything else is refused. A bound on a type it does not apply to is refused, as is `min > max`.
- `override` needs `source: packaged`. `tighten` applies to `integer` and `duration` (with `stricter` required) and to
  any list (subset; `stricter` forbidden). `stricter` anywhere else is refused.
- `defaults` holds exactly the `source: packaged` keys, each valid for its key.

Failure details name the key and the rule it broke; they never repeat a configuration value, so a secret typed into
the wrong place is not echoed into logs.

A component with no configuration ships no file, and its binding's `values`/`config_refs` are passed through as today.

**Where deployment values come from.** A deployment key or override is given either inline in `configuration.values`
or as a reference in `configuration.config_refs`, never both. A reference is resolved by the host's bound
`ConfigResolver` at admission, before any component code runs; the resolved document must be exactly `{ value: … }`. So
the source is the host's choice and DCP is indifferent to it:

| Store | How it reaches `ConfigResolver` |
|---|---|
| Files on the host, e.g. a Kubernetes ConfigMap or Secret mounted as a volume | A file-backed resolver, such as Foundry's `deployment-config-resolver`. The ConfigMap is just files, so no Kubernetes client is needed. |
| Kubernetes API, Vault, Azure App Configuration, AWS Parameter Store, Spring Cloud Config | A host-contributed resolver adapter (Foundry: `FoundryAdapterFactoryContributor`). |

**Binding trust boundary.** The host accepts runtime-binding bytes only from its trusted deployment provisioning
path. `RuntimeBindingCodec` decodes them and `RuntimeBindingValidator` checks structure and contract identity; neither
verifies a signature. `assembly-bind` can store a deployment-owner-signed repository index covering the binding's
payload hash. Provisioning that uses this mechanism must verify that index against trusted deployment-owner keys,
verify the payload hash and requested release/tenant/environment/revision, then install those exact bytes into the
protected overlay. The proposed host does not consume that signed index itself. A read-only mount restricts runtime
writes but does not authenticate the source. An unauthenticated overlay must not be provisioned as trusted input.

**The binding document (`runtimebinding/RuntimeBindingCodec`, implemented).** Bindings travel as one YAML document,
`{ runtime_binding_set: { root_binding_id, generated_at, source_signed_contract, runtime_bindings: [...] } }`, in
snake_case. `RuntimeBindingCodec.read` is strict: exactly one document with unique keys, no field the model does not
define, and unique binding and contract ids (`RuntimeBindingSet`). `root_binding_id` is absent in a host overlay, which
holds only that host bindings. `write` is deterministic (sorted properties, nulls omitted, number-like strings
quoted). A host reads its overlay with this codec and takes `RuntimeBindingSet.byContractId()` to `admit`. Fabric still
writes the document with its own mapper and parses it as a tree in `assembly-bind`; both delegate here when Fabric
implements hosted bindings (Fabric HLD-in-process-component-hosting §6).

Changing an inline value takes a new provisioned binding revision. A referenced value can change in its store without
one. It is still checked by every rule below, so a ConfigMap edit can tighten `maxRows` but cannot redirect
`portfolioBase`, widen `allowedHosts` or inline a secret. Inline values have cryptographic integrity only when the
deployment provisioning path verifies their signed revision and payload hash; merely using inline values is not enough.

**Merge and checks (stage 1, host code only).** For each key in the schema:

| Check | Failure |
|---|---|
| A `source: deployment` key has a value. | `CONFIG_REQUIRED_MISSING` |
| A key is not given both inline and as a reference. | `CONFIG_SOURCE_AMBIGUOUS` |
| A deployment value names a key in the schema. | `CONFIG_KEY_UNKNOWN` |
| A packaged key without `override` is not overridden. | `CONFIG_KEY_FIXED` |
| A `tighten` override is not looser than the packaged default. | `CONFIG_OVERRIDE_WIDENS` |
| A `secret-ref` is a `secret://` reference. | `CONFIG_SECRET_INLINE` |
| A reference resolves to exactly `{ value: … }`. | `CONFIG_REF_UNRESOLVED` |
| Every value satisfies its type and bounds. | `CONFIG_INVALID` |

All failures are collected, so one run lists every bad key. The result is an immutable `EffectiveConfiguration` (typed
values, plus each key's origin: `packaged`, `inline` or `reference` with its resolver revision). It is reported in
`HostReport` and audited without values for `secret-ref` keys. It is fixed for the admission. A changed reference takes
effect at the next admission, which is a restart, because the admitted set never changes underneath a running
component.

Composers read the same file at catalog scan, so they can refuse a missing deployment key before writing bindings. They
reuse DCP's `ComponentConfigurationSchema` and validator, so composer and host apply one rule set.

**Types (`com.unfurl.dcp.componentconfig`).** A design-time package: it imports neither the broker nor `host/`, so
composers use it without the host.

```java
/** Value: the parsed, validated packaged file. RESOURCE = "META-INF/unfurl-config.yaml". */
public final class ComponentConfigurationSchema {
    public static ComponentConfigurationSchema parse(byte[] yaml);    // throws ComponentConfigurationException
    public Map<String, ConfigKey> keys();                             // sorted by name
    public Map<String, Object> defaults();                            // typed, for source: packaged keys
}
public record ConfigKey(String name, ConfigValueType type, boolean list, Source source, OverridePolicy override,
                        Stricter stricter, Comparable<?> min, Comparable<?> max, Integer maxLength, Integer maxItems) { }
public enum ConfigValueType { STRING, INTEGER, BOOLEAN, HTTPS_URI, HOST, ISO_CURRENCY, DURATION, SECRET_REF }   // Strategy per type

/** Specification: the merge and its checks. */
public final class ComponentConfigurationValidator {
    /** Composer check: every rule decidable without resolving references (references are checked for form only). */
    public static List<ConfigFailure> check(ComponentConfigurationSchema schema, Configuration deployment);
    /** Host merge: resolves references through `resolver` (null when the host binds none) and returns the result. */
    public static EffectiveConfiguration merge(ComponentConfigurationSchema schema, Configuration deployment,
                                               ConfigResolver resolver, ExecutionContext context);   // throws, all failures
}

/** Value: immutable result. toString() and redacted() never show a secret-ref value. */
public final class EffectiveConfiguration {
    public static EffectiveConfiguration unmanaged(Configuration deployment);   // component ships no schema
    public boolean managed();
    public Map<String, Object> values();
    public <T> T require(String key, Class<T> type);
    public <T> List<T> requireList(String key, Class<T> elementType);
    public Map<String, Origin> origins();          // PACKAGED | INLINE | REFERENCE(configRef, revision)
    public Map<String, Object> redacted();
}
public record ConfigFailure(ConfigFailureCode code, String key, String detail) { }
public final class ComponentConfigurationException extends RuntimeException { public List<ConfigFailure> failures(); }
```

`ConfigFailureCode` has the eight `CONFIG_*` names of the table above; the host reports each under the
`HostFailureCode` of the same name. A reference is unresolved (`CONFIG_REF_UNRESOLVED`) when it is not a `config://`
URI, the host binds no `ConfigResolver`, the resolver throws or finds nothing, or the document is not exactly
`{ value: … }` for that reference. `unmanaged` passes the binding's inline values through unchanged with origin
`INLINE`; its `config_refs` stay on the runtime binding for the component to read, as today.

### Admission flow

Admission runs in three stages, each over the whole set (contracts sorted by id for determinism) before the next starts.
No component code runs until stages 0 and 1 have passed for every contract.

**Stage 0 — contracts (before anything else).** `VerifiedContracts` already exists, so every contract's signature
verified against the host's keys, its schema validated and its body equals its signed bytes. A host cannot pass
unverified contracts: no `admit` overload takes them. The host's broker is built over this same store.

**Stage 1 — data checks, no component code.** For each verified contract `c` whose provider binds in-process:

1. `pin = c.pin()`; missing → `PROVIDER_PIN_MISSING`, malformed → `PROVIDER_PIN_INVALID`. The artifact rules above have already run for every pinned
   artifact set (`COMPONENT_DEPENDENCY_MISSING`, `ARTIFACT_*`, activation declaration checks).
2. `claim = artifacts.packagedClaim(pin)`, read from the hash-matched jar's zip entry; none →
   `COMPONENT_ARTIFACT_MISSING`. Its identity must equal `c.contract.parties.provider` → else `COMPONENT_CLAIM_MISMATCH`;
   `ClaimHash.canonical(claim)` must equal `pin.claimHash` → else `CLAIM_HASH_MISMATCH`.
3. **Consumer is this host.** `c.contract.parties.consumer` (claim URI and version) must equal `host` → else
   `CONSUMER_MISMATCH`, naming both identities. The composer authors the consumer party of a contract whose provider is
   hosted in-process as the hosting service's claim identity (HLD-C §6.1 rule 7), so a contract compiled for a
   different consumer, or with a placeholder consumer, is never registered here.
4. `binding = bindings.get(c.contractId)` validated with `RuntimeBindingValidator` against `c.contract`; invalid or
   disabled → `RUNTIME_BINDING_INVALID` / `RUNTIME_BINDING_DISABLED`. Its `consumer_instance` component URI and version
   must also equal `host` → else `CONSUMER_MISMATCH`. Then the effective configuration is built from the packaged
   `META-INF/unfurl-config.yaml` and the binding (*Component configuration*), resolving references through the host's
   `ConfigResolver`; any failure → the `CONFIG_*` codes, all collected.
5. `d = broker.present(claim, providerCapability, startup)`; require `ACCEPT` whose matched id and version equal `c` →
   else `ADMISSION_REJECTED` carrying the broker's `DispositionReason`. (Presenting needs no component code, so it runs
   here, before any is loaded.)

**Stage 2 — activation code.** Only now are component class loaders built. For each contract:

6. `activation = artifacts.activate(pin)` (one per component, reused across its operations); origin and dependency
   failures → `ACTIVATION_ORIGIN_MISMATCH` / `COMPONENT_DEPENDENCY_UNRESOLVED`. Its `claimUri`/`claimVersion` must equal
   the packaged claim's → else `COMPONENT_CLAIM_MISMATCH`.
7. Coverage: for every need in `claim.dependencies.needs` whose owner is `host` (`Dependencies.ownerOf`),
   `activation.hostDependencies()` must contain its capability and every listed type must be in `host.boundTypes()`.
   Optional `owner=host` dependencies are covered when the activation declares them and their types are bound, and
   otherwise left unbound. Every gap is collected → `HOST_DEPENDENCY_UNMET` listing all of them.
8. `registrar = activation.registrar(registrarShape, host)`, one per component; empty →
   `REGISTRAR_SHAPE_UNSUPPORTED`. The host wraps it in a `TrackingRegistrar` (below) before any use. Invocations through
   it carry `host.claimUri` as the consumer component, which step 3 guarantees equals the contract's consumer party.
9. `broker.accept(d, tracking, activation.factory(context(c, binding)), startup)`; keep the `RegistrationHandle`.

Success returns `HostReport`:

```java
public record HostReport<R extends CapabilityRegistrar>(
        Map<String, R> registrars,              // by component, "<claimUri>@<claimVersion>"; the component's own registrar
        Map<String, ComponentScope> scopes,     // same keys: run every call into the registrar, and into what it returns, here
        Map<String, AdmittedComponent> components, // same keys: the verified claim, pin and packaged resources (documentation)
        List<AdmittedOperation> operations,     // one per admitted contract, ordered by contract id
        List<String> ignoredArtifacts,          // delivered jars no hosted contract pins; never loaded (closed world)
        List<String> notHostedContracts) {      // verified contracts whose transport is not IN_PROCESS
    public record AdmittedOperation(URI componentUri, String componentVersion, String capability,
                                    URI contractId, String contractVersion,
                                    Map<String, Object> configuration,                    // redacted: no secret-ref values
                                    Map<String, EffectiveConfiguration.Origin> configurationOrigins) { }
    // Admission evidence for documentation (HLD-C2 G.2 host view): exactly what was verified, never re-read from disk.
    public record AdmittedComponent(Claim claim, ProviderArtifactPin pin, PackagedResources resources) { }
}
// Read-only view over one pinned jar's in-memory snapshot (PinnedJarArtifacts.packagedResources(pin)); data only.
public interface PackagedResources { Optional<byte[]> read(String path); }
```

`components` has the same keys as `registrars` (the constructor checks it). `PinnedJarArtifacts` gains
`packagedResources(ProviderArtifactPin)`, which returns a `PackagedResources` view over the claim-bearing artifact's
snapshot. The view reads entries as data with the same multi-release rules as stage 1, and never loads a class.

An unpinned jar is never opened for loading, so an activation it declares is ignored with it and appears only as an
ignored artifact.

**Lifecycle.** A host admits once. `admit` either returns the report or throws after rolling back, and a second call,
or a call after `close()`, is an `IllegalStateException`. The host takes ownership of the `PinnedJarArtifacts` passed
to `admit` and closes it on failure and on `close()`.

**Failure reporting.**

- Configuration failures for one contract are thrown as one `ComponentHostException` whose code is the first failure's
  `CONFIG_*` code, whose message lists every failure, and whose cause is the `ComponentConfigurationException` holding
  them all.
- A broker refusal in `present`, a `BrokerException` from `accept`, or a failure of a host collaborator inside
  `present` or `accept` (such as the broker's event sink) is `ADMISSION_REJECTED`. `admit` therefore throws only
  `ComponentHostException`, whatever fails.
- Component code that throws during admission (an activation method, its factory, or its registrar's `register`), or
  returns `null` where a value is required, is `COMPONENT_ACTIVATION_FAILED`.

**Component code and the context class loader.** The host sets the component's loader as the thread's context class
loader around every call it makes into component code: the activation's methods, the factory's `create`, each
invocation of the returned `ContractInvocable` (a Decorator added before the broker's own adapter), and the registrar's
`register`/`unregister`. A host that calls shape-specific registrar methods itself reaches component logic only through
that decorated invocable.

**The host's own calls.** DCP scopes what it calls (activation, factory, invocables, `register`/`unregister`), but a
host also calls its registrar shape's own methods, such as a tool registrar's `definitions` and `executor`, and the
objects they return. It runs each of those through the component's `ComponentScope` from `HostReport.scopes()`.
Only DCP knows the component's loader; the registrar's class may come from shared code.

**Testing seam.** A package-private constructor takes a `CompositionBroker`, so tests can run the host over a broker
double that is not exception-safe and prove the tracking registrar alone leaves no registration behind.

### Rollback and close

A registration can exist without a returned handle: `accept` may fail after `registrar.register` has run (for example,
the `CAPABILITY_REGISTERED` event sink throws), or `register` itself may fail after partly registering. Rollback
therefore does not rely on handles alone:

- **`TrackingRegistrar`** (Decorator over the component's registrar) records the capability name *before* delegating
  each `register` call, so every attempted registration is known, including the one that failed.
- **Rollback** (any failure in stage 2, and `close()`): for every recorded attempt, in reverse order, call the
  component registrar's `unregister`; then `broker.revoke` each returned handle so revocation events are emitted.
  Registrars must make `unregister` idempotent and must fence any handle a failed `register` created. Every step runs
  even if an earlier one throws anything at all, including an `Error` such as an `AssertionError` from a component's
  registrar, because a partly rolled-back host would keep operations registered and artifacts open with no way to
  retry. Failures are attached as suppressed exceptions to the one `ComponentHostException`
  (or close failure) that is thrown. Then `artifacts.close()` closes the classloader.
- **Broker `accept` is exception-safe** (change to `DefaultCompositionBroker`, see the broker section): if anything
  after `registrar.register` fails, it unregisters that capability before rethrowing, and if `register` itself throws
  it calls `unregister` before rethrowing. The tracking registrar still covers a registrar or broker that does not.

### What stays with each host

- Building `HostPorts` from its own bound services (Foundry: profile bindings; Flow: its runtime profile).
- Choosing the registrar shape (Foundry: `ToolCapabilityRegistrar` from `foundry-substrate-offers`; Flow: its
  capability registrar) and exposing accepted operations to its callers.
- Where the artifacts, frozen contracts and runtime bindings come from (Fabric's emitted overlay), and when to close.

### Neutral host ports

`HostPorts` carries types from `unfurl-substrate` `substrate-ports`, never host product types:
`PolicyEvaluator`, `TraceProvider`, `MetricsProvider` (existing) and, in `com.unfurl.substrate.ports.host`,
`OperationAuditSink`, `SecretResolver` (moved from Foundry unchanged), `ConfigResolver` and `OperationAdmission` (new;
substrate LLD *Neutral host ports*). A component's activation adapts these to its own ports.

### Failure codes

| Code | Cause |
|---|---|
| `CONTRACT_UNVERIFIED` | `VerifiedContracts` load: an envelope is invalid, unsigned, signed by an untrusted key, duplicated, or its body differs from its signed bytes. Nothing is admitted. |
| `PROVIDER_PIN_MISSING` | The verified contract has no `artifactSha256`/`claimHash` pin. |
| `PROVIDER_PIN_INVALID` | The pin is present but partial, mistyped, not a lower-case SHA-256, or repeats coordinates, so it pins nothing. |
| `COMPONENT_ARTIFACT_MISSING` | No verified artifact carries the pinned provider's claim. |
| `COMPONENT_CLAIM_MISMATCH` / `CLAIM_HASH_MISMATCH` | The packaged claim's identity or canonical hash differs from the contract. |
| `COMPONENT_DEPENDENCY_MISSING` | An artifact in the provider's pinned set (claim-bearing or companion) is absent or its SHA-256 differs. |
| `ARTIFACT_CLASS_PATH_FORBIDDEN` | A pinned artifact's manifest has `Class-Path` or `Launcher-Agent-Class`. |
| `ARTIFACT_SHADOWS_SHARED_API` | A pinned artifact contains a class or resource under a `SharedApi` prefix. |
| `ARTIFACT_CLASS_DUPLICATE` | One class name appears in two artifacts of the same component. |
| `ARTIFACT_VERSIONED_METADATA` | A pinned artifact has a copy of `META-INF/unfurl-catalog.yaml`, `META-INF/unfurl-config.yaml` or the activation declaration under `META-INF/versions/`. |
| `ARTIFACT_METADATA_INVALID` | A packaged claim, packaged configuration or activation declaration is unreadable, over 1 MiB or malformed. |
| `COMPONENT_ACTIVATION_MISSING` / `COMPONENT_ACTIVATION_DUPLICATE` | Zero or several activation declarations across the component's artifacts. |
| `COMPONENT_ACTIVATION_FAILED` | The declared class is not a public `ComponentActivation` with a public no-argument constructor, constructing it threw, or its methods, factory or registrar threw or returned `null` during admission. |
| `ACTIVATION_ORIGIN_MISMATCH` | The declared class is not in the declaring artifact, or loads from another loader or location. |
| `COMPONENT_DEPENDENCY_UNRESOLVED` | Loading a pinned class without initialization finds a superclass or interface outside the pinned set and `SharedApi`. |
| `CONSUMER_MISMATCH` | The contract's consumer party, or its runtime binding's consumer instance, is not this host's identity. |
| `CONFIG_*` | `REQUIRED_MISSING`, `SOURCE_AMBIGUOUS`, `KEY_UNKNOWN`, `KEY_FIXED`, `OVERRIDE_WIDENS`, `SECRET_INLINE`, `REF_UNRESOLVED`, `INVALID` (*Component configuration*). |
| `HOST_DEPENDENCY_UNMET` | `owner=host` needs not declared by the activation or needing unbound port types; lists every gap. |
| `RUNTIME_BINDING_INVALID` / `RUNTIME_BINDING_DISABLED` | The binding fails `RuntimeBindingValidator` for its contract, or is disabled. |
| `REGISTRAR_SHAPE_UNSUPPORTED` | The component offers no registrar of the host's shape. |
| `ADMISSION_REJECTED` | The broker's disposition is not `ACCEPT` or matches another contract; carries the `DispositionReason`. |

### Open decisions

| # | Decision | Recommendation |
|---|---|---|
| D1 | **Required by this design:** every artifact a component needs at runtime is pinned (`ProviderArtifactPin.companions`), since nothing else is loadable. Open is only how the composer learns the set (Kotak: `kotak-neo-http`, `kotak-neo-foundry`, Jackson). Either the component ships one bundle carrying its claim, or the catalog block names companion coordinates and the composer hashes and freezes them into the child contract (`metadata.extensions.companionArtifacts`). | Catalog-named companions. **Designed** in `unfurl-fabric/docs/HLD-in-process-component-hosting.md` §3, §5: `catalog.hosted_activation.companions`, resolved by `pom.properties` and frozen as `companionArtifacts`. |
| D2 | Where a component declares the configuration references its runtime binding must carry (Kotak: `adapterConfig`). | **Resolved:** a packaged `META-INF/unfurl-config.yaml` (schema and defaults) beside the claim, merged with the binding's deployment values (*Component configuration*). The claim is unchanged and no HLD-C2 change is needed. This replaces the earlier `runtime_configuration_refs` proposal. |
| D3 | How the composer records which selected service hosts an in-process component. Whatever it decides becomes the consumer party of that component's child contracts and the consumer instance of their runtime bindings (HLD-C §6.1 rule 7); Fabric today always writes `urn:unfurl:host@0.0.0`, which no host will accept. | Explicit in the deployment policy, inferred only when exactly one selected service can host; otherwise compile refuses. Fabric then compiles those child contracts with that service's packaged claim identity. **Designed** in `unfurl-deployment` HLD-D §6.2a (placement) and the Fabric HLD §4 (consumer authoring). |

### Tests

Broker-level fixtures in `testing/`: a synthetic component jar with a packaged claim, one `owner=host` need and an
activation. Cover every failure code, all-or-nothing rollback, closed-world ignoring, revocation through the broker, and
that a jar changed after compile is never loaded. In particular:

- **No code before verification.** An activation whose constructor or static initializer records that it ran: with an
  unsigned, untrusted-key or body-tampered contract pinning its jar, admission fails with `CONTRACT_UNVERIFIED` and the
  activation never ran. The same holds for every stage-1 failure.
- **No orphaned registration.** A `BrokerEventSink` that throws on `CAPABILITY_REGISTERED`, and a registrar whose
  `register` registers and then throws: in both cases admission fails and the registrar holds no registration and no
  live handle afterwards, both through the exception-safe broker and with a non-exception-safe broker double (the
  tracking registrar alone).
- **Rollback continues.** A registrar whose `unregister` throws for one capability: every other attempted registration
  is still removed, and the thrown exception carries the failure as suppressed.
- **Loading stays inside the pinned set.** Each refused in stage 1, before any code runs: a pinned jar whose manifest
  `Class-Path` names an unpinned jar; a jar defining a class in `com.unfurl.dcp.`; a class duplicated across two
  artifacts. In stage 2: an activation class that the host classpath also contains loads from the pinned jar, never the
  host; a pinned class whose superclass exists only on the host classpath fails `COMPONENT_DEPENDENCY_UNRESOLVED`; a
  service file present only on the host classpath is invisible to the component.
- **Multi-release and modular jars.** A companion like Jackson, with `Multi-Release: true` and
  `META-INF/versions/9/module-info.class`, is admitted and eagerly loaded without error. Two modular companions with their
  own `module-info.class` are not duplicates. A class with a `META-INF/versions/11/` override loads the override on Java
  11+ (CodeSource is still the pinned jar); an override for a version above the running one is ignored; a jar without
  `Multi-Release: true` has its `META-INF/versions/**` entries ignored while its base packaged claim and activation
  declaration are still read and admitted. A multi-release jar that also carries
  `META-INF/versions/11/META-INF/unfurl-catalog.yaml` or a versioned activation declaration fails
  `ARTIFACT_VERSIONED_METADATA`. A shared-API class present only under a version above the running one is still refused
  as shadowing.
- **Configuration.** Kotak-like schema: binding with `accountRef` and `sessionRef` only → effective config equals the
  packaged defaults plus those two; `maxRows: 2000` accepted and `50000` refused (`CONFIG_OVERRIDE_WIDENS`);
  `portfolioBase` override refused (`CONFIG_KEY_FIXED`); `sessionRef` given as a plain string refused
  (`CONFIG_SECRET_INLINE`); `maxRows` both inline and as a reference refused; an unknown key refused; a missing
  `accountRef` and a bad `maxBytes` reported together. A `config_refs` value resolved through a file resolver over a
  mounted directory works, and its revision appears in the report. No component code runs on any of these failures.
- **Consumer identity.** A contract compiled with consumer `urn:unfurl:host@0.0.0`, and one whose runtime binding names
  another consumer instance, both fail `CONSUMER_MISMATCH` before any code runs; a matching one invokes with the host
  as consumer and passes the component's own consumer check.

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

Use layered validation (HLD-C2 §H):

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
- Wire field names match HLD-C2 §J preferred names. Legacy aliases (HLD-C2 §J compatibility rule) MAY be accepted on input; generated output SHOULD use the preferred names.

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
- Question schema renderer: human-interview and model-prompt views are structurally identical after normalization;
  action-context inputs select targeted questions while remaining absent from Plane 3 invocation artifacts.
- Capability documentation projection: generated OpenAPI/Swagger/AsyncAPI/MCP docs expose only accepted contracts,
  active runtime bindings, host-registered capabilities, explicit schemas, and declared faults. Concretely:
  - one test per reason code (G.4), each driving exactly one input to the failing state;
  - an absent observation is never `ACTIVE`;
  - a gap throws `DOCUMENTATION_GAP` and names every gap, and a `PRIVATE` offer without schemas is not a gap;
  - a claim-hash or artifact-hash mismatch throws;
  - `ServiceRootResolver` handles `baseUrl`, a resolved and an unresolved `baseUrlRef`, and a wrong suffix;
  - `api_documents` operations are filtered by served surfaces and the excluded ones are reported;
  - the OpenAPI output is golden-tested byte for byte in both identity modes: with an identity contract it has
    `bearerAuth` and 401/403; without one it has neither and carries the unauthenticated statement;
  - schemas are embedded with rebased references, and product-API component names are prefixed with every reference rewritten;
  - the host view documents only locally registered capabilities;
  - **hosted components:** a correctly admitted hosted adapter on a host is `ACTIVE`. A host whose own hash differs
    gives `HOST_CLAIM_MISMATCH` on every entry of its root. A hosted registration with the wrong provider hash gives
    `CLAIM_MISMATCH` on that entry only. Two pinned providers on one root throw `SERVICE_ROOT_CONFLICT`;
  - **freshness:**
    - a served-surface change while `ACTIVE` changes the OpenAPI bytes;
    - a move from `PROVIDER_UNREACHABLE` to `REGISTRATION_UNAUTHORIZED` changes the report;
    - a timestamp-only observation changes the report but not the OpenAPI bytes;
  - **reference rebasing:**
    - an offer schema with `$defs` and an internal `#/$defs/Input` ref, nested refs, and a capability name needing
      escaping all resolve after embedding;
    - a product document whose schemas, responses and parameters reference each other has every ref rewritten under
      `<api_id>.`;
    - a remote ref, an `$id`, an `$anchor` or a ref to `#/paths/...` is rejected by `ClaimArtifactValidator`;
    - a deliberately broken tree trips `RENDER_REFERENCE_UNRESOLVED`;
  - **operations:**
    - a two-operation capability whose inputs are both `{}` renders a `oneOf` whose `const` operations make every
      envelope match exactly one branch;
    - the response is an `anyOf` with `x-dcp-operation` on each branch, and `x-dcp-operations` maps both operations;
    - **faults:**
      - a fault result `{success: false, errorCode: X, output: {}}` validates against every operation's result,
        including operations whose response schema requires fields (the catalog's `summary` requires `markdown`);
      - a success whose `output` lacks a required field does not validate;
      - `{success: false}` without `errorCode` does not validate;
    - a single-operation capability renders no `oneOf`;
    - a single operation branching on an input `action` field validates with its own `oneOf`;
    - a non-object root schema is `SCHEMA_ROOT_NOT_OBJECT`, and duplicate operation names are rejected;
  - **host view with hosted components:**
    - a host with two admitted components renders both under `x-dcp-hosted-capabilities`, with descriptions, operations,
      faults and schemas taken from their own claims, embedded under `hosted.<slug>.`;
    - a hosted registration without its admitted component, or a hash disagreement, is `HOST_VIEW_INPUT_MISMATCH`;
    - **revocation:** a component whose last contract was revoked (admitted, but with no registration) is not
      rendered and causes no error, and the host's own capabilities and other hosted components still render. A
      component with one of two contracts revoked renders only the remaining capability. A retained component with a
      bad artifact does not fail the view;
    - a hosted claim with a bad artifact fails;
    - `PackagedArtifactStore.fromResources` reads from the snapshot and re-verifies each hash;
    - `HostReport.components` keys equal the `registrars` keys;
  - **per-operation security:** with identity, a protected component's public operations render `security: []` and
    its protected ones `bearerAuth` plus 401/403. An unprotected component has none. There is never top-level security.
    A missing `x-dcp-access` is rejected;
  - **registration report operation:** every document, with and without identity, has `GET /dcp/registrations`; it
    carries `bearerAuth`, `x-dcp-permission: dcp.registrations.read` and 401/403 exactly when the component is
    protected under an identity contract; a report written by `RegistrationReportCodec` (one HTTP and one hosted
    registration) validates against `dcp.registrations.report`, and an HTTP registration without `path` does not;
  - the codec round-trips reports and projections, and writes lower-case enumerations (`hosted_in_process`,
    `in_contract`), never the Java constant names.
- Claim documentation fields:
  - `Offer`/`Claim` compatibility constructors;
  - a claim without the new fields keeps its pre-change canonical hash (golden hash of an existing claim);
  - `ArtifactRef` path and hash shape rules;
  - `CLAIM_DOCUMENTATION_HINT_ONLY` is a warning, not an error;
  - an `EXTERNAL_PROTOCOL` offer without `details.protocol`, or with `documentation`, is
    `CLAIM_EXTERNAL_PROTOCOL_INVALID`; one with a protocol and no documentation is valid;
  - an offer named `registrations` is `CLAIM_CAPABILITY_RESERVED`;
  - `@JsonAlias` reads both `offer_interface` and `interface`.
- `RuntimeBindingValidator` rejects an HTTP-kind `baseUrl` without the `/dcp/<capability>` suffix
  (`RUNTIME_BINDING_ENDPOINT_SHAPE`).
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
- `broker/` does not import `documentation/`; docs generation is a host projection over accepted runtime state.
- `broker/` does not import `com.unfurl.substrate.ports.CapabilityRegistry`; it must go through `spi.CapabilityRegistrar`.
- `description/`, `claim/`, `manifest/`, `documentation/`, `runtimebinding/`, `questions/`, `resolver/`, `versioning/`, `validation/` do not import `broker/` or `spi/`.
- `documentation/` imports from `host/` only `VerifiedComposition`, `VerifiedContracts`, `VerifiedContract`,
  `ProviderArtifactPin`, `HostReport.AdmittedComponent` and `PackagedResources`, and nothing from `broker/` or `spi/`.
- `host/` does not import `documentation/`.
- `documentation/` uses no HTTP client or server types (the general no-HTTP rule, asserted for this package by name).
- `trust/` does not import `contract.CompositionContract`; only `SignedContract` envelopes cross the boundary; `trust/` is a leaf with no in-artifact upstream dependencies.
- `unfurl-dcp` may only import `unfurl-substrate`'s composition-api and ports artifacts; never the engine.

Enterprise tests:

- No-op `BrokerEventSink` performs no I/O.
- Broker events omit full claims/contracts unless explicitly supplied by caller metadata.
- `correlationId` propagates from `ExecutionContext` through every broker event and contract invocation tag.
- `OfflineContractVerifier` opens no sockets and reads no remote keys.
- Runtime binding rejects any inline credential value; only `SecretRef` is accepted.
- `DocumentationPlan.project` performs no I/O, and two projections of equal inputs are byte-identical.

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
9. Implement `questions/` schema model, `ActionContext`, canonical v0.2 set, `InterviewRenderer`, `ModelPromptRenderer`, `CapturedAnswer`/`AnswerCorpus`.
10. **[implemented]** Implement `documentation/` capability documentation projection. It depends on `host/`'s
    verified-set types, so it lands after the in-process host's `VerifiedComposition` (already implemented). Order:
    1. claim fields (`OfferDocumentation`, `ArtifactRef`, `ApiDocument`, compatibility constructors, `@JsonAlias`);
    2. the runtime-binding endpoint-shape rule;
    3. the model records and codecs, including `RegistrationReportCodec`;
    4. the artifact stores and `ClaimArtifactValidator`;
    5. `ServiceRootResolver`;
    6. the gap gate;
    7. `DocumentationProjector` / `DocumentationPlan`;
    8. `HostDocumentationProjector`;
    9. the OpenAPI and summary renderers.
11. Implement `resolver/` (`CapabilityResolver`, `ResolutionRequest`, `ResolutionResult`).
12. Implement `validation/` cross-schema service.
13. Implement `fault/` (`FaultPolicy`, declarations, runtime signals, and `FaultPropagationGate`).
14. Implement `spi/` (`ContractStore`, `ContractInvocableFactory`, `CapabilityRegistrar`, `BrokerEventSink`, `NoopBrokerEventSink`).
15. Implement `broker/` (`CompositionBroker` interface, `DefaultCompositionBroker` with constructor injection, `Disposition`, `DispositionReason`, `RegistrationHandle`, `BrokerEvent`).
16. Implement `testing/` fixtures: `InMemoryContractStore`, `InMemoryCapabilityRegistrar`, `EchoContractInvocableFactory`, `RecordingBrokerEventSink`.
17. Complete property tests, ArchUnit tests (package-scoped), and enterprise guardrail tests before downstream repos (`foundry-substrate-offers`, flow, foundry) consume the library.

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

**Capability documentation consumers (HLD-C2 §G).** The `documentation/` package is consumed by:

- **`unfurl-api-catalog`** (new): embeds `DocumentationProjector`. It owns the HTTP observation of
  `/dcp/registrations`, the polling and the serving of the outputs (catalog HLD/LLD).
- **`unfurl-flow`, `unfurl-foundry`**: these serve `GET /dcp/registrations` by building a `RegistrationReport` from their
  registrar, and render their own `/openapi.json` through `HostDocumentationProjector` + `OpenApiDocumentRenderer`,
  replacing `FlowOpenApiProjector`'s and `FoundryOpenApiProjector`'s hand-written maps. They ship their schemas and
  product OpenAPI as `artifact_ref`s in their packaged claims.
- **`unfurl-fabric`**: runs `ClaimArtifactValidator` at catalog scan, copies artifacts into the bundle's
  `artifacts/sha256/` store, and runs the gap gate before it emits a visibility policy.
- **`IdentityBinding`** replaces Flow's `IdentitySettings.from` and Foundry's `CompositionIdentity` reading logic.
  Their product-specific wiring (token exchange, inbound security installation) stays in each product.
- **Adapters (`unfurl-adapter-kotak-neo`, `unfurl-adapter-codex`) and Flow packages**: their claims are built in code, so
  they serialize each schema once and pin those bytes with `ArtifactRef.of` in `documentation`, writing the same bytes
  into the jar; the `offer_interface.details.input_schema` / `output_schema` hints are removed. The adapter registry's
  validator (`.agents/skills/dcp-software-assembly-generator/scripts/suite.py`) reads operation schemas from the typed
  `documentation` pins only.
