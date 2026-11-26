# HLD-C2 — DCP Schema Specification

**Document status:** Concrete schema specification. Downstream of HLD-C (DCP v0.2 internal). 
**Audience:** Internal. The field-by-field definition that `unfurl-dcp` implements and validates against.
**Authority:** This copy is the authoritative DCP schema specification (decided 2026-11-25, §I.1 divergence #6).
`dcp/docs/HLD-C2-dcp-schema-spec-updated.md` points here; a schema change is made here and nowhere else.
**Decisions inherited as settled (from HLD-C §11, resolved):**
1. **state/decisions fold under each concern** — no separate `state_owned`/`decisions_owned` top-level lists.
2. **Invalidation:** hard-fail at runtime, re-negotiate at design-time. No runtime self-heal.
3. **Trust:** the protocol records provenance + a trust tier (`neutral`/`self`); policy decides what to do with it.
4. **Flywheel mechanics:** deferred, out of scope here.
5. **Transport:** fixed enum `in_process` / `http_json` / `grpc`; most-efficient-mutually-supported.
6. **Capability versioning:** standard semver range matching (the `packaging` library's rules).

This document defines the DCP schemas, specified together because they are projections of one model:
- **A. Claim schema** (Plane 1) — a component's self-description.
- **B. Composition contract schema** (Plane 2 output) — the frozen artifact.
- **C. Runtime binding schema** (deployment/environment projection) — the mutable environment-specific wiring.
- **D. Webapp manifest schema** (frontend projection of the claim).
- **E. Negotiation question schema** (Plane 2 structure; H2C interview = C2C prompt).
- **F. Design-time action context schema** (Plane 2 input) -- selected authoring/edit intent used to ask targeted questions.
- **G. Capability documentation projection** (runtime-facing docs) -- Swagger/OpenAPI/AsyncAPI/MCP docs from accepted DCP surfaces only, the DCP HTTP binding they document, and the registration report that proves a capability is registered.

Notation: types are given as `string`, `int`, `bool`, `enum[...]`, `list<T>`, `map<K,V>`, `uri`, `semver`, `semver_range`, `timestamp`, `secret_ref`, and `config_ref`. `?` marks optional. All schemas are expressed in YAML; JSON is equivalent.

### Naming convention for end-user understanding

DCP uses `snake_case` for all schema fields. This keeps YAML/JSON readable for operators, product teams, and integration engineers, and avoids mixing backend-style names with frontend-style names.

Field names should follow these rules:
- Prefer plain domain words over implementation words: `component`, `capability`, `runtime`, `policy`, `permission`.
- Use `claim_*` only when referencing the claim artifact itself.
- Use `component_*` when the field is visible to an operator or shell.
- Use `binding_*` only for environment/runtime wiring.
- Avoid vague names such as `details` for user-authored configuration. `details` is allowed only for extensible, kind-specific machine payloads.
- Keep protocol-level names stable even if a UI displays friendlier labels. For example, UI may show "Can be opened inside host shell", while schema stores `hosted_in_shell: true`.

---

## A. Claim Schema

A claim is static, published per component version, and the single source of truth (§4 of HLD-C).

### A.1 Top-level structure

```yaml
identity:            # A.2  required
domain:              # A.3  required
refusals:            # A.4  required, non-empty
dependencies:        # A.5  required (may have empty sub-lists)
offers:              # A.6  required (may be empty)
conflict_resolution: # A.7  required
negotiation_surface: # A.8  optional (required if kind == intelligent_component)
integration_ports:   # A.9  optional standard ports exposed/required by the component
faults:              # A.10 required, may be empty; declared runtime fault vocabulary
metadata:            # A.11 required
api_documents:       # A.13 optional; product HTTP API documents shipped with the component
```

`artifact_ref` (A.12) is a shared value type used by `offers[].documentation` and `api_documents`; it is not a
top-level section.

### A.2 identity (required)

```yaml
identity:
  uri:           uri        # required, globally unique, stable across versions
  name:          string     # required, human-readable
  kind:          enum[intelligent_component, component, infrastructure]  # required
  version:       semver     # required
  publisher:     string     # required
  publisher_uri: uri?       # optional
```

Rules:
- `uri` is the identifier other claims and contracts reference. It MUST NOT change across versions; `version` carries the version.
- `kind == intelligent_component` REQUIRES `negotiation_surface` (A.8) to be present.

### A.3 domain (required) — the heart of the claim

```yaml
domain:
  summary: string            # required, one-paragraph plain-language statement

  concerns:                  # required, non-empty list  (A.3.1)
    - concern:        string       # required, kebab-case identifier, unique within claim
      description:    string       # required
      scope_notes:    string?      # optional but strongly recommended: where it begins/ends
      owns_state:                  # optional list of state this concern owns  (A.3.2)
        - resource:     string       # required
          description:  string       # required
          sensitivity:  enum[public, internal, confidential, secret]  # required
      owns_decisions:              # optional list of decisions this concern makes  (A.3.3)
        - decision:     string       # required, kebab-case
          description:  string       # required
          authority:    enum[exclusive, consulted, advisory]  # required

  boundary_principles:       # required, non-empty list  (A.3.4)
    - string                   # natural-language statement of where the domain ends
```

**A.3.1 — concerns** is the primary ownership unit. State and decisions are folded *into* each concern (settled decision #1), so a concern is a complete statement of "what I own, the state it owns, the decisions it makes." This removes the v0.1 fracturing across `state_owned`/`decisions_owned`.

**A.3.2 — owns_state.sensitivity** uses the v0.1 vocabulary (public/internal/confidential/secret). A concern may own zero or more state resources.

**A.3.3 — owns_decisions.authority** records whether the concern owns the decision exclusively, is consulted on it, or merely advises. This is distinct from the claim-level `claimant_position` (A.7), which is about overlap with *other claims*; `authority` here is about the decision *within* this claim.

**A.3.4 — boundary_principles** are REQUIRED and non-empty (settled per HLD-C §3.2 / observation #4). These natural-language statements do the heaviest interpretive work and small models depend on them. A claim with no boundary principles fails validation.

### A.4 refusals (required, non-empty)

```yaml
refusals:
  - concern:    string     # required, what is refused
    rationale:  string     # required, why it is out of domain
    owned_by:   string?    # optional but strongly recommended: what kind of component owns it
```

Rules (settled per HLD-C §3.3):
- `refusals` MUST be non-empty. A component that refuses nothing has not stated a boundary.
- Each refusal MUST be specific. Validation SHOULD warn on vague refusals (e.g., a refusal whose `concern` is "everything-else" or whose `rationale` is under a minimum length).
- `owned_by` names a component *kind* or domain, not a specific instance ("a policy engine", not "acme-policy-svc-prod").

### A.5 dependencies (required)

```yaml
dependencies:
  required:                  # list, may be empty
    - need:        string        # required, kebab-case
      description: string        # required
      accepted_providers:        # required, non-empty list of acceptable satisfiers
        - kind:        enum[infrastructure, component, intelligent_component]  # required
          example_claims: list<uri>?     # optional, claims known to satisfy
          required_properties: list<string>  # required, non-empty
  recommended:               # list, may be empty
    - need:        string
      description: string
      benefits_if_present: string   # required for recommended deps
  forbidden:                 # list, may be empty
    - forbidden_need: string
      reason:      string        # required
```

Rules:
- `required` deps that are unsatisfied at validation time are blockers (Plane 2 cannot produce a contract).
- `forbidden` is where exclusive-ownership conflicts are pre-declared (e.g., a second identity provider for the same scope). A forbidden dependency that IS present is a blocking conflict.
- Intelligence-as-a-dependency is expressed here (a `required` dep of `kind: intelligent_component` with `required_properties` describing the reasoning model), per HLD-C §3.4 / observation #2.

### A.6 offers (required, may be empty)

```yaml
offers:
  - capability:   string     # required, kebab-case, unique within claim
    description:  string     # required
    consumer_access: enum[any, named_components_only]  # required
    interface:                 # required
      kind:    enum[http_api, event_stream, negotiation, in_process, external_protocol]  # required
      details: map<string, any>   # required, kind-specific (endpoints, spec_url, format, transport...)
    stability:    enum[experimental, evolving, stable, deprecated]  # required
    version:      semver       # required (the offer is a versioned contract)
    cost_implications: string?   # optional but required when the offer incurs measurable cost
    documentation:             # optional; required when the offer is documented (G.1)
      summary: string?         # optional one-line summary of the capability for generated documentation
      operations:              # required, non-empty: one entry per `invocation.operation` value the offer accepts
        - operation:       string         # required; the exact `invocation.operation` value; unique within the offer
          summary:         string?        # optional one-line summary of this operation
          request_schema:  artifact_ref   # required: JSON Schema of this operation's `invocation.input` (A.12, G.3)
          response_schema: artifact_ref   # required: JSON Schema of this operation's successful `output` (A.12, G.3)
```

Rules:
- `version` on an offer is REQUIRED because an offer is a capability contract subject to semver matching (settled #6). Consumers bind by `semver_range` against this.
- `cost_implications` MUST be present when the offer consumes metered resources (e.g., a negotiation surface that consumes model tokens), per HLD-C §3.5 / observation #6.
- A capability offer is the surface a Plane 2 contract binds against (a consumer *need* → a provider *offer*).
- `interface.details` is part of the offer's deterministic structure, not free-form prose. A consumer need may require a subset of detail keys, and the resolver must accept only offers whose details satisfy that subset. Scalar values match by equality; list values match by containment; map values match recursively by subset.
- Capabilities that support multiple execution modes declare them in details so mode selection is governed by DCP rather than hidden in local deployment metadata. Standard keys are `execution_modes`, `default_execution_mode`, and `mode_policies`.
- `documentation` states the offer's invocation payload schemas for generated documentation (G). It is not part of the
  offer's matchable structure: the resolver never matches on it, and adding or changing it never changes which needs
  the offer satisfies. It changes the claim hash like any other claim field, so a contract's pinned claim hash also pins
  the schemas.
- Schemas are per **operation**, and an operation is selected by the envelope's `invocation.operation` (G.3).
  - Each entry's `request_schema` describes `invocation.input` and its `response_schema` describes a successful
    result's `output`, *for that operation only*.
  - A payload schema never inspects `invocation.operation`, because it only ever sees `input` or `output`. The
    projection renders the selection at the envelope level (G.2), so operations whose inputs look alike (for example
    `{}`) stay unambiguous.
  - An offer with one operation lists exactly one entry.
- A single operation that chooses its behaviour from a field *inside* `input` (for example an `action` field) states
  that in its own request schema. That schema is a `oneOf` whose branches are mutually exclusive on that field (each
  branch fixes it with `const`, or, for a defaulted value, requires it to be absent or equal to the default). This is
  ordinary JSON Schema over `input`.
- Both schemas of every operation MUST have a root `type: object`, because `input` and `output` are maps on the wire
  (G.3). A component whose natural result is text or a list wraps it in a named property, for example
  `{"markdown": "..."}` or `{"items": [...]}`.
- `operation` values are non-blank and unique within the offer. The invocation envelope itself is defined once by the
  DCP HTTP binding (G.3) and is not repeated per operation. Fault codes are taken from
  `faults.emitted[].affects.offers` (A.10) and are not repeated here.
- All schema refs MUST use `media_type: application/schema+json`. An offer without `documentation`, or with no
  operations, has no documentable payload; whether that is acceptable is decided by the documentation projection
  (G.1), never by inferring a schema.
- Free-form schema hints inside `interface.details` (for example `input_schema` / `output_schema` path strings) are not
  documentation proof. Claims MUST state payload schemas through `documentation`.
- `kind: external_protocol` declares an offer that consumers reach over a standard protocol of the provider's own, never
  through the DCP invocation envelope (G.3). For example, an identity provider's `identity-context` is reached over
  OAuth 2.0: consumers verify the tokens it issues and call its token endpoint.
  - Its `details` MUST carry `protocol`, a non-blank name of that protocol (for example `oauth2`). Other detail keys are
    matchable as for any kind, so a need may require `protocol: oauth2`.
  - It has no DCP payload, so it MUST NOT carry `documentation`.
  - The kind is a fact about the offer, not about its deployment. The same claim holds whether the provider is deployed
    with the composition (for example as a container) or is one the customer already runs; the binding's
    `deployment_kind` (C) records which, and documentation lists the offer the same way in both cases (G.1).

Example:

```yaml
offers:
  - capability: agent.run
    interface:
      kind: in_process
      details:
        operation: start
        input_shape: AgentInput
        output_shape: AgentOutput
        execution_modes: [simple, harness]
        default_execution_mode: simple
        mode_policies:
          harness:
            max_turns_default: 4
            max_turns_max: 16
```

### A.7 conflict_resolution (required) — where the protocol's value lives

```yaml
conflict_resolution:
  overlapping_concerns:      # required, may be empty
    - concern:           string   # required, references a concern from A.3 or a known shared concern
      ownership_position: enum[exclusive, negotiable, deferring, consulted]  # required
      resolution_guidance: string # required, how to resolve / what scope applies
  precedence_rules:          # required, may be empty
    - string                   # natural-language precedence statement
  requires_human_escalation: bool  # required, whether unresolved conflicts go to a human operator
```

Rules (settled per HLD-C §3.6 / observation #3, treated as central):
- `ownership_position` is the vocabulary Plane 2 negotiation operates on:
  - **exclusive** — only one claimant per scope; overlap = blocking conflict.
  - **negotiable** — ownership may be split/shared; the negotiation surface advises.
  - **deferring** — yields to a higher-level owner if present.
  - **consulted** — participates but does not own.
- `resolution_guidance` MUST state the *scope* qualifier where relevant (e.g., "exclusive for users in managed realms; multiple providers may coexist across different realms").

### A.8 negotiation_surface (required iff kind == intelligent_component)

```yaml
negotiation_surface:
  endpoint:            string     # required
  protocols_supported: list<enum[http_post_json]>  # required, non-empty
  supported_intents:               # required, non-empty
    - intent:      string             # required, kebab-case
      description: string             # required
      example:     string?            # optional
  answer_grounding: list<string>  # required, non-empty: what is answered from live state vs training vs retrieval
  limitations:      list<string>  # required, non-empty: what it refuses even with access
```

Rules:
- `answer_grounding` MUST state that live-state questions are answered from live introspection, not training data (HLD-C §3.7).
- `limitations` MUST include the refusal-redirect behavior (e.g., "refuses authorization questions; redirects to the authorization system").

### A.9 integration_ports (optional, strongly recommended)

`integration_ports` gives common enterprise integration surfaces first-class names. These ports do **not** make a component intelligent by themselves. They make the component easier to integrate, configure, observe, and govern. A component is an `intelligent_component` only when it exposes a bounded reasoning/negotiation capability through `negotiation_surface`.

```yaml
integration_ports:
  ports: map<string, any>?          # the component's own named port wiring (for example Foundry's runtime ports,
                                    # name -> Java type); opaque to DCP, see the rules below

  authentication:
    required: bool                  # does this component require an authenticated caller?
    accepted_modes: list<enum[jwt, oidc, api_key, mtls, session_cookie]>
    notes: string?

  authorization:
    required: bool                  # does this component require authorization checks?
    decision_owner: enum[self, external_policy, host_application, not_applicable]
    permission_prefixes: list<string>?
    notes: string?

  telemetry:
    required: bool
    signals: list<enum[logs, metrics, traces, audit_events]>
    correlation_id_required: bool
    notes: string?

  monitoring:
    required: bool
    health_endpoints: list<string>?
    readiness_required: bool
    liveness_required: bool
    notes: string?

  ai:
    required: bool
    intents: list<string>?          # e.g., summarize, classify, recommend, negotiate
    model_access: enum[none, external_provider, embedded_model, host_provided]?
    cost_metered: bool?
    notes: string?
```

Rules:
- **Two kinds of entry.** `ports` is the component's own named wiring, for example the runtime ports a host binds
  (name to implementation type). DCP carries it unchanged and never interprets it: it is not an integration surface, no
  planner matches on it, and no view projects it. The other five entries are the typed integration surfaces below.
- **Absent means not declared.** Each typed port is optional. An absent port says the claim does not declare that
  surface; it is never read as `required: false` or as an empty list, and a reader never fills one in.
- **A declared port is complete.** Within a declared port, every member written without `?` is required: `required`
  in all five; `accepted_modes` in `authentication`; `decision_owner` in `authorization`; `signals` and
  `correlation_id_required` in `telemetry`; `readiness_required` and `liveness_required` in `monitoring`. A missing
  one makes the claim invalid (`CLAIM_INTEGRATION_PORT_INVALID`, naming the member), and is never defaulted. An empty
  list is a value (for example `accepted_modes: []`), distinct from an absent one. Optional members (`?`) stay absent
  when not written.
- **Wire values.** Enum values are written upper case on the wire, as every DCP enum is in the `unfurl-dcp` codecs (for
  example `JWT`, `AUDIT_EVENTS`, `EXTERNAL_POLICY`). Field names are snake_case.
- **Monitoring.** Each `health_endpoints` entry is an absolute path the component serves (`/health`, `/health/ready`),
  never a URL: the host and port are deployment facts (§C). `readiness_required` and `liveness_required` say whether
  the component serves a readiness or liveness check a deployment must use.
- **Telemetry.** `signals` lists only what the component really emits. `correlation_id_required` is true when the
  component refuses or cannot trace a call without a correlation id.
- `authentication`, `authorization`, `telemetry`, and `monitoring` are integration surfaces, not intelligence markers.
- `ai.required == true` means the component requires AI capability to operate; it does not automatically mean the component can negotiate its own boundaries.
- If `identity.kind == intelligent_component`, the claim MUST still provide `negotiation_surface`.
- Values under `integration_ports.authorization.permission_prefixes` MUST align with the webapp manifest `permissions` when a frontend projection exists.

### A.10 faults (required, may be empty)

`faults` gives DCP a first-class operational-fault vocabulary. A runtime failure is not automatically a DCP fault. A
DCP fault is a declared, machine-readable signal whose impact can be traced to needs, offers, constraints, contracts, or
parent claims.

```yaml
faults:
  emitted:                  # list, may be empty
    - code: string          # required, stable machine code, e.g. storage.timeout
      category: enum[dependency, capability, constraint, health, security, policy, runtime]
      severity: enum[info, warning, degraded, critical, blocking]
      description: string   # required
      affects:
        needs: list<string>        # declared needs affected by this fault
        offers: list<string>       # declared offers/capabilities affected by this fault
        constraints: list<string>  # declared constraints affected by this fault
      evidence:
        signals: list<enum[health, invocation_error, metric_threshold, policy_denial, contract_invalidation, external_event]>
      propagation:
        parent_impact: enum[none, degraded, blocked]
        propagates_when: string    # required when parent_impact != none
        suppresses_when: string?   # optional suppression condition
      remediation:
        allowed_actions: list<string>  # declared actions the runtime/operator may take
```

Rules:
- `faults` MUST be present even when `emitted` is empty. Product examples may use `faults: { emitted: [] }`.
- A declared fault MUST affect at least one need, offer, or constraint.
- `parent_impact != none` requires `propagates_when`; this is the deterministic gate condition a runtime or parent
  resolver evaluates before escalating the child fault upward.
- `remediation.allowed_actions` must only name actions already permitted by the claim boundary or host policy.
- DCP records fault meaning and propagation policy. It does not prescribe concrete monitoring adapters or auto-remediate
  outside a declared allowed action.

Runtime fault signals use the same vocabulary and are emitted by hosts/adapters when a declared fault is observed:

```yaml
fault_signal:
  fault_id: string
  source_claim_uri: uri
  source_instance: string?
  contract_id: uri?
  binding_id: uri?
  capability: string?
  code: string
  category: enum[dependency, capability, constraint, health, security, policy, runtime]
  severity: enum[info, warning, degraded, critical, blocking]
  observed_at: timestamp
  affected_needs: list<string>
  affected_offers: list<string>
  affected_constraints: list<string>
  evidence_refs: list<string>
  correlation_id: string?
```

The fault propagation gate is deterministic: `(claim.faults, fault_signal) -> propagation_decision`. It may propagate,
suppress, or reject an undeclared fault, but it must never renegotiate a contract in the runtime path.

### A.11 metadata (required)

```yaml
metadata:
  dcp_version:    semver       # required, the DCP version this claim conforms to (>= 0.2.0)
  claim_version:  semver       # required, matches identity.version
  supersedes:     list<uri>?   # optional
  effective_from: timestamp    # required, ISO 8601
  references:                  # optional
    - title:   string
      uri:     uri
      purpose: enum[spec, api-spec, protocol, doc]
```

`metadata.references` are informative links for readers. They are never documentation proof (G.1); a machine-readable
API description is shipped through `api_documents` (A.13) instead.

### A.12 artifact_ref (value type)

An `artifact_ref` names one file shipped inside the component's own artifact, pinned by content hash.

```yaml
artifact_ref:
  path:       string   # required; relative path inside the artifact that carries the packaged claim (HLD-C §3.10)
  media_type: enum[application/schema+json, application/vnd.oai.openapi+json, application/vnd.oai.openapi+yaml]  # required
  sha256:     string   # required; lower-case hex SHA-256 of the file's exact bytes (64 characters)
```

Rules:
- `path` is resolved against the root of the artifact that carries the claim. It uses `/` separators and MUST NOT be
  absolute or empty, or contain `..`, `\`, empty segments or a URI scheme. It SHOULD live under `dcp/`. There is no
  default base and no lookup outside that artifact.
- `sha256` freezes the content: a reference by path alone is mutable and is invalid. The claim hash covers the ref, so a
  contract's pinned claim hash (B) transitively pins the file bytes.
- A reader MUST verify `sha256` against the bytes before using the file. A missing file or a hash mismatch is a
  validation failure. It is never a fallback to another copy, another version or an inferred document.
- A composer that packages a deployment copies each referenced file unchanged and stores it content-addressed, as
  `artifacts/sha256/<sha256>`, beside the deployment's claims. Deployment readers resolve a ref by its `sha256` in that
  store; `path` keeps its meaning inside the component artifact. The claim itself is never rewritten.
- JSON Schema files (`application/schema+json`) use JSON Schema draft 2020-12 and MUST be self-contained. Every `$ref`
  is a JSON Pointer fragment into the same file (`"#"` or `"#/<pointer>"`). `$id`, `$anchor`, `$dynamicRef`,
  `$dynamicAnchor` and `$recursiveRef` MUST NOT appear. A projector embeds the file inside a larger document and
  rebases each pointer (G.2), and that is well-defined only for pointer fragments.
- OpenAPI files use OpenAPI 3.1.0, whose schema dialect is the same draft, and MUST also be self-contained. Every
  `$ref` targets the file's own `#/components/<section>/<name>` (optionally followed by a deeper pointer). The same
  `$id` and anchor keywords are forbidden in their schemas.
- Child and referenced DCPs: each claim's refs resolve only inside that claim's own artifact. A parent never resolves
  a child's ref against the parent artifact, or the reverse.

### A.13 api_documents (optional)

`api_documents` ships machine-readable descriptions of the component's **product HTTP routes**: the routes it serves
that are not DCP capability invocations, such as a workflow-management or operations API.

```yaml
api_documents:
  - api_id:      string         # required, kebab-case, unique within the claim
    description: string         # required
    openapi:     artifact_ref   # required; media_type MUST be an OpenAPI media type (A.12)
    surfaces:    list<string>   # required, non-empty, kebab-case; the served surfaces this document covers
```

Rules:
- Paths in the document are relative to the component's service root (G.3). DCP invocation and registration paths
  (`/dcp/...`, G.3) MUST NOT appear; those are projected from offers.
- Every operation MUST carry the extension `x-dcp-surface: <surface>`, naming one entry of `surfaces`. At runtime a host
  reports which surfaces it serves (G.3 registration report), and generated documentation includes only operations
  whose surface is served. An operation without a declared surface fails validation.
- The document MUST NOT declare `servers`, `security` or `components.securitySchemes`. The documentation projection
  derives the server from the runtime binding and the security scheme from the composition's identity contract (G.1),
  so a shipped document cannot claim a location or an authentication mode that the deployment does not have.
- Every operation MUST carry `x-dcp-access`. There is no default:
  - `public`: the host never requires a token for it, on either identity path (for example health routes, the host's
    own documentation, static UI assets);
  - `protected`: the host requires a bearer token whenever the composition has an identity contract that protects
    this component.

  An operation MAY carry `x-dcp-permission: <name>`, naming the permission the host checks; it is rendered as
  documentation only. The projection turns access into security requirements (G.1), so the rendered document states
  each exception exactly.
- Within one component, no two `api_documents` may declare the same method and path. A conflict is a validation failure
  (`API_DOCUMENT_PATH_CONFLICT`).
- When several of a component's `api_documents` declare the same path, the projection merges their path items. Every
  path-level member they share, other than `parameters`, must therefore be identical after component renaming (G.2).
  A difference is a validation failure (`API_DOCUMENT_PATH_CONFLICT`). Path-level `parameters` apply only to their own
  document's operations.
- `api_documents` describe routes. They do not offer capabilities, are never matched by the resolver and never create
  contracts. A product route that another component must call is a capability and belongs in `offers`.
- An API document is documented only for a component that is the provider party of at least one documented child
  contract (G.1), because the runtime binding of such a contract is the only source of the component's service root.
- Child and referenced DCPs: a child's `api_documents` describe only that child. A parent claim never re-exports a
  child's documents.

---

## B. Composition Contract Schema

The frozen artifact Plane 2 produces and Plane 3 consumes (HLD-C §5.2). It is immutable once produced and travels with the deployment.

```yaml
contract:
  contract_id:    uri          # required, unique
  contract_version: semver     # required

  parties:                     # required, exactly two
    consumer:
      claim_uri: uri            # required
      claim_version: semver     # required (pinned)
    provider:
      claim_uri: uri            # required
      claim_version: semver     # required (pinned)

  binding:                     # required
    consumer_need: string       # required, the consumer need being satisfied
    provider_capability: string # required, the capability name from provider's claim (A.6)
    provider_capability_version: semver  # required (pinned, the resolved version)

  data_mapping:                # required
    inbound:  map<string, string>   # provider-input-field -> reference ($.consumer... etc.)
    outbound: map<string, string>   # consumer-field -> reference into provider output

  transport:                   # required
    kind: enum[in_process, http_json, grpc]   # required (settled #5)
    details: map<string, any>?                # endpoint/path when not in_process

  expectations:                # required
    timeout_ms:    int?
    idempotent:    bool
    async:         bool          # true if the offer resolves asynchronously (WAITING + signal)
    correlation_id_required: bool   # true when async

  provenance:                  # required  (HLD-C §5.5)
    created_by:    enum[fabric, embedded_self]   # required
    mode:          enum[c2c, h2c, h2h]           # required
    model_id:      string?       # the model used, if any
    fabric_version: semver?
    human_in_loop: bool          # true for h2c/h2h
    created_at:    timestamp     # required

  trust:                       # required  (settled #3)
    tier: enum[neutral, self]    # neutral = fabric/third-party negotiated; self = self-negotiated
    # NOTE: what to DO with the tier is a policy decision, not encoded here.

  invalidation:                # required  (settled #2)
    triggers: list<enum[claim_version_changed, pattern_unsupported, runtime_assumption_violated]>
    on_runtime_violation: enum[hard_fail]   # settled: hard_fail only; no runtime self-heal

  metadata:                    # optional
    extensions:
      contains: [uri | {ref: uri}]?
      children: [uri | {ref: uri}]?
      containsClaimUris: [uri | {claimUri: uri}]?
      childClaimUris: [uri | {claimUri: uri}]?
```

Rules:
- Aggregate contracts use the same DCP containment bridge as aggregate claims. A parent contract references child contracts with `metadata.extensions.contains`, `children`, `containsClaimUris`, or `childClaimUris`; each child remains a normal composition contract with its own parties, binding, transport, expectations, provenance, trust, and invalidation.
- Assembly compilers MUST NOT represent child contract closure only as private planner metadata. A deployable multi-component contract is a DCP contract tree: aggregate parent contract plus referenced child contracts.
- `parties.*.claim_version` are PINNED. If either changes, the contract is invalidated and must be re-negotiated at design-time.
- `transport.kind == in_process` is valid ONLY when the parties are co-packaged into one deployable. Fabric sets this at packaging time.
- `provenance.mode == c2c` REQUIRES `model_id`. `mode == h2c` REQUIRES `human_in_loop == true`.
- `trust.tier == self` when `provenance.created_by == embedded_self`; `neutral` otherwise. The protocol records it; policy (outside DCP) decides whether `self` contracts need extra sign-off.
- `on_runtime_violation` is fixed to `hard_fail`. Runtime never re-negotiates (preserves the residency firewall, HLD-C §7).

---

## C. Runtime Binding Schema

The runtime binding is the mutable deployment/environment projection of a frozen composition contract. The contract says **what is valid**. The runtime binding says **where and how it is wired** in a specific environment, tenant, or deployment target.

A runtime binding MUST NOT change the semantic agreement captured by the contract. It may supply environment-specific endpoints, secret references, feature enablement, scaling policy, telemetry routing, and operational overrides that are permitted by the contract.

```yaml
runtime_binding:
  binding_id: uri                 # required, unique runtime binding id
  contract_id: uri                # required, references B.contract.contract_id
  contract_version: semver        # required, pinned to the contract being deployed

  target_environment:             # required
    environment: string           # e.g., dev, qa, staging, production
    tenant: string?               # optional tenant/realm/customer scope
    region: string?               # optional deployment region
    namespace: string?            # optional Kubernetes/runtime namespace

  provider_instance:              # required
    component_uri: uri            # required, MUST match contract provider claim_uri
    component_version: semver     # required, MUST match contract provider claim_version
    instance_name: string         # required, operator-friendly name
    deployment_kind: enum[in_process, container, remote_service, external_saas, webapp, sidecar]
    base_url: uri?                # direct value allowed for non-secret endpoint
    base_url_ref: config_ref?     # preferred for environment-specific endpoint
    credentials_ref: secret_ref?  # reference only; never inline secrets

  consumer_instance:              # optional but recommended
    component_uri: uri            # MUST match contract consumer claim_uri when present
    component_version: semver     # MUST match contract consumer claim_version when present
    instance_name: string?

  runtime_policy:                 # required
    enabled: bool                 # whether this binding is active
    timeout_ms: int?              # may override only within policy limits
    retry_policy_ref: string?
    circuit_breaker_ref: string?
    rate_limit_ref: string?
    bulkhead_ref: string?
    telemetry_namespace: string?
    audit_enabled: bool?

  configuration:                  # optional, non-secret config values or references
    values: map<string, any>?
    config_refs: map<string, config_ref>?
    secret_refs: map<string, secret_ref>?   # secret:// references only, never inline secrets; e.g. an identity
                                            # binding's per-service client credentials, keyed by service claim URI

  deployment_controls:            # optional
    rollout_strategy: enum[manual, automatic, canary, blue_green]?
    min_instances: int?
    max_instances: int?
    autoscaling_policy_ref: string?

  lifecycle:
    created_by: string
    created_at: timestamp
    updated_by: string?
    updated_at: timestamp?

  metadata:                       # optional
    extensions:
      contains: [uri | {ref: uri}]?
      children: [uri | {ref: uri}]?
      containsClaimUris: [uri | {claimUri: uri}]?
      childClaimUris: [uri | {claimUri: uri}]?
```

Rules:
- `runtime_binding.contract_id` MUST reference an existing composition contract.
- `contract_version`, `provider_instance.component_version`, and `consumer_instance.component_version` are pinned. If the claim or contract changes, the runtime binding must be reviewed or regenerated.
- Secrets MUST be referenced through `credentials_ref` or another `secret_ref`. They MUST NOT be stored inline in the binding.
- Runtime binding may disable a binding with `runtime_policy.enabled: false`, but it cannot alter claim ownership, dependency satisfaction, conflict decisions, trust tier, or invalidation rules.
- Environment-specific values belong here, not in the claim or composition contract.
- `base_url` and `base_url_ref` are mutually exclusive. Prefer `base_url_ref` outside local development.
- A provider reached through the DCP HTTP binding (G.3) has `deployment_kind` `container`, `remote_service` or
  `sidecar`. Its endpoint, whether given as `base_url` or resolved from `base_url_ref`, MUST be exactly
  `<service_root>/dcp/<provider_capability>`, where `<provider_capability>` is the contract's
  `binding.provider_capability`. The service root is never configured separately: it is that endpoint with the
  `/dcp/<provider_capability>` suffix removed.
- An `in_process` provider has no endpoint: it is reached through its host, the contract's consumer party. Providers
  with `external_saas` or `webapp` kind are not DCP-HTTP invocable. Their bindings carry config and secret references,
  not a DCP endpoint.
- A provider whose offer is `external_protocol` (A.6) is not DCP-HTTP invocable either, whatever its `deployment_kind`.
  Its binding has no `base_url`: the protocol's own endpoints are configuration values and references (for identity,
  `token_endpoint` and `verification_keys`).
- A binding that needs an endpoint and has none is incomplete. Documentation projection (G) uses the binding as its
  only endpoint source; endpoints taken from environment variables, profiles, DNS or orchestrator lookups, or network
  probing are not DCP state and are never documented.
- Aggregate runtime bindings use the same DCP containment bridge as aggregate claims. Parent bindings reference child runtime bindings with `metadata.extensions.contains`, `children`, `containsClaimUris`, or `childClaimUris`; child bindings remain normal runtime bindings with their own contract pins, endpoint/config refs, secret refs, and runtime policy.
- Assembly-level products MUST NOT introduce product-specific runtime wiring sections for child bindings. A multi-component deployment is represented as a DCP runtime-binding tree: one aggregate parent binding plus referenced child bindings, all resolved by walking the DCP containment refs.

Example:

```yaml
runtime_binding:
  binding_id: dcp://bindings/prod/acme/rag-auth
  contract_id: dcp://contracts/rag-auth-v1
  contract_version: 1.0.0

  target_environment:
    environment: production
    tenant: acme
    region: central-india
    namespace: qppnextgen-prod

  provider_instance:
    component_uri: dcp://components/keycloak-adapter
    component_version: 2.1.0
    instance_name: acme-keycloak-adapter
    deployment_kind: remote_service
    base_url_ref: config://prod/acme/keycloak/base-url
    credentials_ref: secret://prod/acme/keycloak/client-credentials

  runtime_policy:
    enabled: true
    timeout_ms: 3000
    retry_policy_ref: policy://runtime/retry/standard-read
    circuit_breaker_ref: policy://runtime/circuit-breaker/auth-validation
    telemetry_namespace: qppnextgen.rag.auth
    audit_enabled: true

  lifecycle:
    created_by: fabric
    created_at: 2026-05-22T10:00:00Z
```

---

## D. Webapp Manifest Schema (frontend projection of the claim)

The frontend facet of the same self-description (HLD-C §4, HLD-D §8A). Derived from / validated against the claim — NOT authored independently.

```yaml
webapp:
  component_uri: uri          # required, MUST match the claim's identity.uri
  component_version: semver   # required, MUST match claim version

  route_prefix: string         # required, e.g. "/rag"
  routes:                     # required, non-empty
    - path:  string
      title: string

  navigation:                 # required
    section: string            # which shell nav section
    title:   string
    icon:    string?

  permissions: list<string>   # required, MUST be derivable from the claim's access boundaries
                              # (e.g. rag.view, rag.admin) — not invented separately

  theme_contribution:          # optional  (the suggest-vs-decide projection of negotiation)
    mode:   enum[suggestive]   # suggestive only: component proposes, host/theme disposes
    tokens: map<string, string>

  bootstrap:                  # required
    standalone_app:  bool       # can it run as its own webapp
    hosted_in_shell: bool       # can it mount in the host shell
```

Rules (the projection discipline):
- `component_uri` and `component_version` MUST match the claim. A manifest cannot describe a component the claim does not.
- `permissions` MUST map to access boundaries implied by the claim (its concerns' decision `authority` and offers' `consumer_access`). Validation cross-checks this; permissions with no basis in the claim fail.
- `theme_contribution.mode` is `suggestive` only — this is the frontend instance of the DCP suggest-vs-decide negotiation pattern. A component cannot *force* theme; it proposes, the active theme decides (HLD-D §10.6 theme-policy engine, which shares shape with the negotiation engine).
- The manifest is generated from, or validated against, the shared component-description definition. Building it independently is forbidden.

---

## E. Negotiation Question Schema

The structure of Plane 2 (HLD-C §5.3). Defined once; rendered as a human interview (H2C) and a model prompt (C2C). Because they are identical, H2C answers map directly into C2C training format.

```yaml
negotiation_questions:
  - id:          string        # stable question id
    applies_when: string?      # condition under which the question is asked
    prompt:      string        # the question text (rendered to human or model)
    answer_type: enum[disposition, owner, boolean, scope, free_text]  # required
    # disposition: accept | refuse | partial_accept
    # owner:       names the component kind that should own a refused concern
    # scope:       a scope qualifier (e.g. "per-realm")
    feeds:       enum[binding, conflict_check, dependency_check, data_mapping]  # what contract field it informs
```

The canonical question set (v0.2 minimum):

```yaml
- id: owns-concern
  prompt: "Does <provider> own <concern> according to its claim?"
  answer_type: disposition
  feeds: binding

- id: refused-owner
  applies_when: "answer to owns-concern == refuse"
  prompt: "If <provider> refuses <concern>, what kind of component should own it?"
  answer_type: owner
  feeds: binding

- id: exclusive-conflict
  prompt: "Does any other claim assert exclusive ownership of <concern> in the same scope?"
  answer_type: boolean
  feeds: conflict_check

- id: conflict-scope
  applies_when: "answer to exclusive-conflict == true"
  prompt: "What scope qualifier distinguishes the claimants (if any)?"
  answer_type: scope
  feeds: conflict_check

- id: dependency-satisfied
  prompt: "Is <consumer>'s required dependency <dep> satisfied by <provider>'s offers?"
  answer_type: boolean
  feeds: dependency_check

- id: data-shape
  prompt: "How does <consumer>'s input map to <provider>'s offer input, and the output back?"
  answer_type: free_text
  feeds: data_mapping
```

**Connection to the LoRA experiment:** the refusal-reasoning training tuple
`(claim, request, expected_disposition, expected_redirection, rationale)` is exactly an
instance of answering `owns-concern` (-> disposition) + `refused-owner` (-> owner) with a
rationale. The LoRA learns to answer this question set; H2C sessions produce labeled
answers to it. The schema is the bridge between the protocol and the experiment.

---

## F. Design-Time Action Context Schema

`action_context` is an optional Plane 2 input used by authoring tools, Studio surfaces, and agents when the operator
is acting on a selected component, substrate, connection, or runtime configuration. It is not a claim, composition
contract, runtime binding, or runtime invocation payload. It helps the authoring layer ask the right DCP questions
before producing or changing contracts and bindings.

```yaml
action_context:
  operation: enum[add_component, remove_component, replace_component, connect, disconnect, configure_runtime]

  target:
    catalog_entry_id: uri?
    component_id: string?
    component_label: string?
    replacement_catalog_entry_id: uri?

  capabilities:
    offers: list<string>?
    needs: list<string>?
    substrate_needs: list<string>?

  connection:
    from_port: string?
    to_port: string?
    protocol: string?

  session:
    tenant: string?
    assembly_id: string?
    draft_session_id: string?
    correlation_id: string?

  constraints:
    target_environment: string?
    policy_refs: list<string>?
    notes: string?
```

Rules:
- `action_context` is design-time only. It MUST NOT appear in Plane 3 invocation, hot-path runtime payloads, or frozen
  contract semantics.
- `action_context` may be attached to a Fabric/Foundry authoring request, a human interview request, or any equivalent
  design-time composition assistant.
- The response to `action_context` is still governed by the negotiation question schema. The authoring layer asks
  clarification questions, records answers, and then proposes normal DCP artifacts or Studio intents.
- Answers that affect endpoints, credentials, provider choices, runtime target, telemetry, audit, or secret/config
  values flow into runtime bindings as references. They do not become private UI state.
- Add/replace/connect/configure flows SHOULD ask for provider/config/secret/runtime binding details before proposing
  a contract or binding change.
- Remove/disconnect flows SHOULD ask about dependent contracts, child bindings, replacement-before-removal, state
  cleanup, and revocation impact before proposing deletion.
- Product UIs may use friendlier operation labels, but protocol payloads SHOULD use the enum values above.

---

## G. Capability Documentation Projection

Runtime-facing documentation such as OpenAPI, Swagger UI, AsyncAPI, MCP tool documentation, or generated SDK metadata
is a projection of accepted and bound DCP capabilities. It is not produced by scanning implementation code alone.

The projection is a deterministic function of the deployment's DCP state plus one runtime observation per service
(G.3): the same inputs always produce the same documents, byte for byte. It works for any component in a verified
composition; nothing in it is specific to one product.

### G.1 Projection inputs and the documentation decision

A projector reads only these inputs:

1. **The verified composition**: the signed aggregate root contract and exactly its child closure (B, DCP
   Aggregation), with every envelope verified against the deployment's trusted keys. An unverified set is never
   projected.
2. **Provider claims**: the claim of each child's provider party. Its canonical hash must equal the hash the child
   contract pins. A mismatched or missing claim is a projection failure, never a skipped entry.
3. **Claim artifacts**: every `artifact_ref` (A.12) those claims name, read from the deployment's content-addressed
   store and verified by `sha256`.
4. **Runtime bindings** for the children (C).
5. **Registration reports** (G.3): one per service root, observed at runtime. This is the only runtime input, and it
   is the "registered in the host runtime" proof.
6. **Identity presence**: whether the verified set contains an identity contract (a child whose
   `binding.provider_capability` is `identity-context`). This decides security schemes, and it is never read from
   configuration.
7. **Visibility policy**: a product-supplied map from capability names and `api_id`s to `public`, `internal` or
   `private`, with a stated default. DCP records the policy in the output (`surfaces`); the product decides its values.

Every child contract of the root is one **documentation entry**. The aggregate root has no entry of its own: it is the
projection's `source.root_contract`.

All `http` entries that share a service root MUST pin the same provider component: the same claim URI, version and
claim hash. A root is one host. Two different pinned providers on one root fail the projection with
`SERVICE_ROOT_CONFLICT`, because the host check would otherwise have no single expected identity.

An entry's **invocation surface** follows from its provider offer (A.6) and its runtime binding (C). An entry whose
provider offer has interface kind `external_protocol` is `external`, whatever its deployment kind: it is listed, never
probed and never a documentation gap, both when the provider is deployed with the composition and when the customer
runs it. Every other entry takes its surface from the binding's `deployment_kind`:

| Binding `deployment_kind` | Surface | Documented as |
|---|---|---|
| `container`, `remote_service`, `sidecar` | `http` | An OpenAPI operation `POST /dcp/<capability>` on the provider's service root (G.3) |
| `in_process` | `hosted_in_process` | A capability of its host, the consumer party. It has no HTTP operation of its own; it appears in the host's document under `x-dcp-hosted-capabilities` and in the human summary, documented from the hosted component's own claim. The host's registration report proves its registration. |
| `external_saas`, `webapp` | `external` | Listed in the report and the human summary only. It is not DCP-HTTP invocable and is never probed. |

An entry is **documented** (status `active`, G.4) only when every one of these holds:

- it is in the verified child closure;
- it has a runtime binding with `runtime_policy.enabled: true`;
- for `http`: its endpoint is present, resolvable and of the shape `<service_root>/dcp/<capability>` (C);
- **host check:** the registration report for the entry's service root was obtained, and it identifies the host the
  deployment pins for that root. Its `component_uri`, `component_version` and `claim_hash` equal the pin of the
  component that provides the root's `http` entries. For a `hosted_in_process` entry, the root is its consumer host's;
- **provider check:** the report lists the entry's contract id and version, and that registration's
  `provider_claim_uri`, `provider_claim_version` and `provider_claim_hash` equal the entry's own provider pin.
  - For an `http` entry the provider is the host itself.
  - For a `hosted_in_process` entry the provider is the hosted component, and the hash is the one the host verified
    when it admitted that component.

  A host therefore never has to impersonate the components it hosts;
- its visibility is `public` or `internal`.

Otherwise the entry is still in the output, in the report (G.4), with the reason it is not documented. **No entry is
ever dropped silently.**

**Documentation gap.** An entry with an `http` or `hosted_in_process` surface, a `public` or `internal` visibility, and
a provider offer that has no `documentation` (A.6) is a documentation gap. A gap is decided from static inputs only:
the verified set, the claims and the visibility policy. A tool that emits a deployment's visibility policy (for
example as the projector's runtime binding configuration) MUST refuse to emit a policy that yields a gap. A projector
MUST refuse to produce any documentation for a deployment that has one. A schema is never inferred.

**Product API documents.** For each component that is the provider of at least one documented `http` entry, each
`api_documents` item (A.13) whose `api_id` is visible is projected. Only operations whose `x-dcp-surface` appears in the
component's reported `served_surfaces` are included. The others are listed in the report as `SURFACE_NOT_SERVED`.

**Security schemes.** Security is rendered per operation, never as a document-wide default.

- **With an identity contract** in the verified set, a component is **protected** when the identity binding names its
  claim URI as an audience. For a protected component:
  - its DCP invocation operations (`POST /dcp/<capability>`) carry an OAuth 2.0 bearer requirement and the `401`/`403`
    responses;
  - its product operations marked `x-dcp-access: protected` carry the same;
  - its product operations marked `x-dcp-access: public` carry an explicit empty requirement (`security: []`), so the
    document states the exception instead of implying a token.

  An unprotected component's operations carry no requirement.
- **Without an identity contract**, no operation carries security, no scheme is emitted, and the output states that the
  composition runs unauthenticated on a trusted network.

The two cases are mutually exclusive and are decided by the verified set alone. `x-dcp-permission` is copied through as
documentation.

**Freshness.** Every observation is applied. It is never skipped because a status stayed the same.
- The OpenAPI documents and the human summary depend only on what is documented: entries, schemas, served surfaces and
  security. Their bytes therefore change exactly when that content changes; for example, a provider that stays
  `active` but starts or stops serving a surface changes its document.
- The report also carries each root's last observation time and every entry's reason and detail. It changes whenever
  any of those change, including a move from one failure reason to another.

### G.2 Projection output

```yaml
capability_documentation:
  document_id: uri
  target_environment:              # copied from the runtime bindings; one projection per environment
    environment: string
    tenant: string?
    namespace: string?

  source:
    root_contract: uri?            # the verified aggregate root; absent in a host view
    contracts: list<uri>           # every child contract id in the closure
    runtime_bindings: list<uri>    # every child binding id used
    registered_capabilities: list<string>   # union of capabilities named by the registration reports

  identity: enum[in_contract, absent]   # G.1 input 6

  surfaces:
    - kind: enum[openapi, human_summary, swagger_ui, asyncapi, mcp_tools, sdk_metadata]
      visibility: enum[public, internal, private]
      base_url_ref: config_ref?
      includes: list<string>       # capability names and api_ids this surface includes

  components:                      # one per host (service root) with at least one documented capability, listing its
                                   # own and its hosted capabilities; anything else appears only in the report
    - component_uri: uri
      component_version: semver
      name: string                 # the host's claim identity name
      claim_hash: string
      service_root: uri
      protected: bool              # the identity contract names this component as an audience
      capabilities:
        - capability: string
          capability_version: semver
          description: string      # the offer's description from the provider claim
          contract_id: uri
          binding_id: uri?         # absent in a host view
          surface: enum[http, hosted_in_process, external]
          visibility: enum[public, internal, private]
          path: string?            # "/dcp/<capability>" for http
          hosted_by: uri?          # host component_uri for hosted_in_process
          provider_component_uri: uri      # the providing component; differs from the enclosing component when hosted
          provider_component_version: semver
          operations:              # required non-empty when documented
            - operation: string
              summary: string?
              request_schema: artifact_ref
              response_schema: artifact_ref
          faults: list<string>     # fault codes whose affects.offers names this capability (A.10)
      api_documents:
        - api_id: string
          openapi: artifact_ref
          visibility: enum[public, internal, private]
          served_surfaces: list<string>

  report: documentation_report     # G.4

  lifecycle:
    generated_by: string
    generated_at: timestamp
```

Enumeration values are written exactly as listed, in lower case (`http`, `hosted_in_process`, `in_contract`, `active`).
Reason codes (G.4) are the upper-case identifiers. The registration report (G.3) follows the same rule.

The rendered outputs are views of this one model:

- **OpenAPI**: one OpenAPI 3.1.0 document per component that has a `service_root`. It holds the component's
  documented `http` capabilities, its projected product API operations, and its documented `hosted_in_process`
  capabilities (below). The `servers` entry is the service root, and
  the schemas are the referenced artifacts, embedded with their references rebased (below). Components are rendered as separate documents rather than
  one merged document, because independent components may legitimately serve the same product path (for example a
  health route).
- **Human summary**: every entry, including `hosted_in_process` and `external` ones, with description, status,
  visibility, operations and faults.

**DCP invocation operations.** For each documented `http` capability the document has one operation, `POST
/dcp/<capability>`.
- **Several contracts, one operation.** A component may serve one capability under several contracts at once, for
  example a host's own profile contract beside a composition's child contracts, or two consumers' contracts with the
  same provider. They share the one path and the provider's one offer, so they render as one operation: the renderer
  groups a component's documented entries by capability (by path for `http`, and by provider component and capability
  for `hosted_in_process`). The operation's `x-dcp-contracts` lists every grouped contract id in sorted order, and the
  request envelope's `invocation.contractId` is an `enum` of exactly those ids. Grouped entries must agree on the
  provider component and version, description, operations and faults; when they do not, rendering fails with
  `CAPABILITY_DOCUMENTATION_CONFLICT` rather than choosing one. The report and the human summary keep one entry per
  contract.
- **Request body.**
  - With one operation, the body is that operation's envelope: `invocation.operation` is fixed with `const` and
    `invocation.input` refers to the operation's request schema.
  - With several operations, the body is a `oneOf` over the per-operation envelopes, each fixing
    `invocation.operation` with `const`. The branches are mutually exclusive whatever `input` holds, so selection
    happens at the envelope level and never inside a payload schema.
- **`200` response.** Every result is either a success or a capability fault, and the two are separate branches:
  - **Success** of operation `o` has `success: {const: true}`, with `output` referring to `o`'s response schema.
  - **Failure** is one shared branch per document: `success: {const: false}`, `errorCode` required (a declared fault
    code from A.10 or a host failure code), `errorMessage` optional, and `output` any object (normally `{}`). The
    operation's response schema is never applied to a failure, so a fault whose `output` is `{}` always validates.
  - Each operation's result is `oneOf: [success of o, failure]`. The two branches are mutually exclusive on the
    `success` constant.
  - With one operation, the response is that result. With several, it is an `anyOf` over the per-operation results,
    each marked `x-dcp-operation`. `anyOf` is used because the result envelope carries no operation field, so two
    success branches may both match.
- **Operation map.** `x-dcp-operations` maps each operation name to its request envelope and result envelope, for
  tools that need the exact correlation OpenAPI cannot state.

**Registration report operation.** Every rendered document also has `GET /dcp/registrations`. A component has a
document only as a DCP HTTP host, and every DCP HTTP host serves its report (G.3), so the route is documented wherever
it is served.
- It has `operationId: dcp.registrations` and tag `dcp`. Its `200` response is the G.3 `registration_report` as the
  JSON Schema `dcp.registrations.report`, written once per document by the renderer (a component never ships it), with
  a `Cache-Control: no-store` response header. The schema allows unknown fields, as readers do, so a newer host's
  report stays valid.
- It is secured like the component's invocation operations. For a protected component under an identity contract it
  carries the bearer requirement, `x-dcp-permission: dcp.registrations.read`, and `401` and `403`. Otherwise it has
  no security.

**Hosted capabilities.** A component's documented `hosted_in_process` capabilities have no HTTP path of their own: they
are invoked through the host, never by the reader. They appear in the host's document under the top-level extension
`x-dcp-hosted-capabilities`. Each item gives:
- `capability`, `provider_component_uri`, `provider_claim_version` and the provider's description;
- `contracts`: every contract the capability is hosted under, sorted;
- `faults`;
- for each operation, `operation` with `$ref`s to its embedded request and response schemas.

The schemas are embedded like any other offer schema, so readers and tools see the complete, verified contract surface
of every hosted component.
- **Report**: G.4.

**Embedding and reference rebasing.** The renderer embeds every shipped schema so that every reference still resolves.
- **Offer schemas.** Each one is placed at `#/components/schemas/<name>`, where `<name>` is
  `<capability>.<operation>.request` or `<capability>.<operation>.response`. For a hosted capability the name is
  prefixed `hosted.<provider slug>.`, so two hosted components can never collide with each other or with the host.
  The generated envelopes are `<capability>.<operation>.envelope` and `<capability>.<operation>.result`. Characters
  outside `[A-Za-z0-9._-]` map to `_`, and a collision fails with `SCHEMA_NAME_COLLISION`.
  - The schema is copied whole, `$defs` included.
  - Every `$ref: "#<pointer>"` inside it becomes `#/components/schemas/<name><pointer>`, with `<name>` JSON-Pointer
    escaped. So a reference to `#/$defs/Input` becomes `#/components/schemas/<name>/$defs/Input` and resolves inside the
    embedded copy.
- **Product API documents.** Every entry of every `components` section (`schemas`, `responses`, `parameters`,
  `requestBodies`, `headers`, `examples`, `links`, `callbacks`, `pathItems`) is copied as `<api_id>.<name>`. Every
  `$ref: "#/components/<section>/<name><rest>"` anywhere in the document, in paths and components alike, becomes
  `#/components/<section>/<api_id>.<name><rest>`.
- **Guard.** After rebasing, the renderer resolves every `$ref` in the output. An unresolved one fails with
  `RENDER_REFERENCE_UNRESOLVED`. The A.12 and A.13 validation makes that unreachable for valid inputs; the guard keeps it
  from ever reaching a reader.

**Merge and schema validation.** Product documents sharing a path retain every distinct HTTP method. Unequal shared
path metadata is a projection error; inherited parameters stay scoped to their source operations with operation-level
overrides preserved. Payload schemas must pass draft 2020-12 meta-schema validation offline before projection.

**Host view.** A DCP HTTP host MAY render documentation of itself (for example its own `/openapi.json`) from the same
model and renderers. Its inputs are:
- its own packaged claim and artifacts;
- its own current registrations, in place of an observed report;
- for every component it hosts in process, that component's **verified claim and its shipped artifacts**. The hosted
  component's descriptions, operation schemas and faults come from its own claim, never from the host's or from the
  registration, which carries only identity and hashes.

The host view renders only **currently registered** capabilities. Matching runs in one direction:
- every current `hosted_in_process` registration must have the hosted claim the host admitted, and that claim's
  identity and canonical hash must equal both the contract's pin and the registration's `provider_claim_*`. A
  registration without admitted evidence, or with mismatched evidence, fails the host view
  (`HOST_VIEW_INPUT_MISMATCH`);
- admitted evidence *without* a current registration is normal: a revoked or invalidated contract leaves the report
  while its admission evidence stays with the host. Such a component is retained and simply not rendered. It is never an
  error, and it never blocks the rest of the view.

Artifact verification (A.12) and the gap rule (G.1) apply to every hosted component that is rendered. A host registers only contracts its broker accepted against enabled bindings, so its registrations
are the acceptance proof for this view. A host view documents only the host and the components it hosts in process. It
never documents another service, and it applies the same identity rule and visibility policy as the full projection.

AsyncAPI, MCP tool documentation and SDK metadata are output kinds the model reserves. A projector produces them only
once the owning offer kinds are specified for documentation: `event_stream` offers for AsyncAPI, tool surfaces for MCP.
Until then a projector MUST NOT emit them from other sources (I).

Filtering (DCP Documentation §4): every output can be narrowed by tenant, environment, contract id, binding id and
capability surface. Filtering only removes entries from a view. It never changes an entry's status.

### G.3 DCP HTTP binding

`transport.kind: http_json` (B, HLD-C §6) maps a contract invocation to HTTP as follows. A component that serves any
capability over HTTP is a **DCP HTTP host**, and its **service root** is derived from its runtime bindings (C).

**Invoke.** `POST <service_root>/dcp/<capability>` with `Content-Type: application/json`.

```yaml
dcp_http_request:
  invocation:            # required; field names are the deployed wire names
    contractId: string
    operation: string
    consumerComponent: string?
    providerComponent: string?
    input: map<string, any>          # described by documentation.operations[operation == invocation.operation].request_schema
    correlationId: string?
    traceContext: map<string, string>?
    integrityHash: string?
    metadata: map<string, any>?
  context:               # optional; allowed only when the composition has no identity contract
    tenantId: string?
    userId: string?
    roles: list<string>?
    permissions: list<string>?
    correlationId: string?
    requestId: string?
    traceContext: map<string, string>?
    metadata: map<string, any>?

dcp_http_response:       # HTTP 200 for every invocation that reached the capability
  success: bool
  output: map<string, any>           # success: described by that operation's response_schema
                                     # failure: any object, normally {}; never checked against the response_schema
  errorCode: string?                 # a declared fault code (A.10) or a host failure code
  errorMessage: string?
  metadata: map<string, any>?
```

- The envelope keeps its deployed wire names, which are an explicit exception to the snake_case convention. Renaming
  them would be a breaking wire change; see I.
- With an identity contract: the request MUST carry `Authorization: Bearer <token>`, and a body `context` is rejected
  with `400`. The host derives the caller's context from the verified token. Missing or invalid tokens get `401`, and an
  insufficient permission gets `403`. Without an identity contract: there is no bearer requirement, and `context` is
  accepted.
- `400` is a malformed envelope. `404` means the capability is not registered on this host. `503` means the host is not
  ready. These statuses are transport failures. Capability faults are returned in a `200` body with `success: false`.
- `X-Correlation-Id` and W3C `traceparent` headers, when present, are propagated.

**Registration report.** `GET <service_root>/dcp/registrations` returns what the host has registered right now. Every
DCP HTTP host MUST serve it.

```yaml
registration_report:
  component_uri: uri             # the host's claim identity uri
  component_version: semver
  claim_hash: string             # canonical hash of the host's own packaged claim
  observed_at: timestamp         # when the host produced the report
  registrations:                 # capabilities currently registered through the host's registrar
    - contract_id: uri
      contract_version: semver
      capability: string
      provider_claim_uri: uri    # the host itself, or a component it hosts in process
      provider_claim_version: semver
      provider_claim_hash: string    # canonical claim hash of that provider (see below)
      surface: enum[http, hosted_in_process]
      path: string?              # "/dcp/<capability>" when surface == http
  served_surfaces: list<string>  # api_documents surfaces (A.13) this instance serves
```

- `provider_claim_*` are per-registration evidence:
  - for `surface: http` they equal the report's own `component_uri`, `component_version` and `claim_hash`;
  - for `hosted_in_process` they are the hosted component's identity and the claim hash the host verified against the
    contract's pin when it admitted the component.

  A host never reports a hosted component under its own identity.
- A registration appears only while the capability is registered: a revoked or invalidated contract is absent. The
  report lists contracts only. It never includes inputs, outputs, credentials, secret references or caller data.
- With an identity contract, the report requires a bearer token with permission `dcp.registrations.read`. Projectors
  obtain one with the OAuth 2.0 client-credentials grant for their own service identity. Without an identity contract
  it is served unauthenticated, under the same trusted-network rule as invocation.
- The response carries `Cache-Control: no-store`.
- `registrations` is a reserved capability name. The report and the invoke paths share the `/dcp/` namespace, so a
  claim that offers a capability named `registrations` is invalid (`CLAIM_CAPABILITY_RESERVED`).
- The host's own OpenAPI document describes this route (G.2, registration report operation).

**Observation rules for projectors.**
- A projector probes only the service roots derived from the verified set's runtime bindings. It never scans networks
  or ports, never queries DNS for other names, and never reads orchestrator APIs (container engines, cluster endpoint
  objects). The deployment's bindings are the whole topology.
- It probes each service root once per interval, with a timeout no larger than that root's binding
  `runtime_policy.timeout_ms`. Failures back off up to a bounded maximum. Both values come from the projector's own
  runtime binding.
- An observation replaces the previous one for that root. A projector never carries forward a stale `active` status
  past one failed observation.

### G.4 Documentation report and reason codes

```yaml
documentation_report:
  entries:
    - contract_id: uri
      binding_id: uri?
      capability: string
      provider_component_uri: uri
      surface: enum[http, hosted_in_process, external]
      status: enum[active, inactive, not_probed]
      reason: string              # one code from the table below
      detail: string?             # human-readable cause; no secrets
      endpoint: uri?              # the endpoint exactly as the binding declares it
      observed_at: timestamp?     # last observation of the entry's service root
  api_operations:                 # product API operations not projected
    - component_uri: uri
      api_id: string
      operation: string           # "<METHOD> <path>"
      reason: SURFACE_NOT_SERVED | POLICY_PRIVATE
```

| Reason | Status | Meaning |
|---|---|---|
| `ACTIVE` | `active` | Documented. |
| `POLICY_PRIVATE` | `inactive` | Visibility policy marks it `private`. |
| `BINDING_MISSING` | `inactive` | No runtime binding names the contract. |
| `BINDING_DISABLED` | `inactive` | The binding has `runtime_policy.enabled: false`. |
| `BINDING_ENDPOINT_MISSING` | `inactive` | An `http` entry with no `base_url`, or a `base_url_ref` that does not resolve. |
| `ENDPOINT_PATH_MISMATCH` | `inactive` | The endpoint is not `<service_root>/dcp/<capability>`. |
| `HOST_UNKNOWN` | `inactive` | A `hosted_in_process` entry whose consumer has no service root in the set. |
| `PROVIDER_UNREACHABLE` | `inactive` | The registration report could not be obtained: a connection failure, timeout, `5xx`, or a malformed body. |
| `REGISTRATION_UNAUTHORIZED` | `inactive` | The host answered `401` or `403` to the projector. |
| `HOST_CLAIM_MISMATCH` | `inactive` | The report's `component_uri`, `component_version` or `claim_hash` differs from the host pinned for that service root. Every entry on the root gets this reason. |
| `CLAIM_MISMATCH` | `inactive` | The host check passed, but the registration's provider claim URI, version or hash differs from the entry's pin. |
| `NOT_REGISTERED` | `inactive` | The report was obtained but does not list the contract id and version. |
| `EXTERNAL_SURFACE` | `not_probed` | An `external` entry: an `external_saas` or `webapp` binding, or an `external_protocol` offer (G.1). It is listed, never probed. |

A documentation gap (G.1) is not a report reason, because no report is produced when one exists. The projector fails
with `DOCUMENTATION_GAP`, naming every gap.

### G.5 Rules

- Documentation generators MUST include only capabilities that are accepted by frozen composition contracts, active in
  runtime bindings, and registered in the host runtime, as decided in G.1.
- Every public or internal capability documentation entry MUST have explicit request and response schema references
  (A.6), pinned by hash (A.12).
- Declared DCP faults that can cross the documented boundary SHOULD be included in the generated documentation.
- Plugin jars, classpath discovery, tool registries, provider registries, model catalogs, RAG/vector stores, prompts,
  logs, model outputs, implementation DTOs, `interface.details` hints, `metadata.references`, environment variables and
  network or orchestrator discovery are not sufficient proof that a capability is callable or safe to expose.
- If a capability is accepted and intended to be documented but lacks an explicit schema, generation MUST fail with a
  documentation gap rather than infer a public contract.
- Documentation visibility is policy-controlled. DCP records the projection inputs; product policy decides which
  accepted capabilities are public, internal, or private.
- Every entry the projection does not document appears in the report with its reason. Nothing is pruned silently.
- Generated documentation MUST NOT include raw credentials, secret references, private prompts, retrieved chunks or
  model outputs. Endpoints appear only as the binding declares them.
- Child and referenced DCPs: the projection walks the verified containment closure. Each child is documented under its
  own provider component, and an aggregate parent adds no surfaces of its own.

---

## H. Validation Rules Summary

`unfurl-dcp` MUST enforce, at minimum:

Claim:
- All required sections present; `refusals` and `boundary_principles` non-empty.
- `faults` present; every declared fault affects at least one need, offer, or constraint.
- Faults with parent impact require a propagation condition.
- `kind == intelligent_component` => `negotiation_surface` present.
- Concern identifiers unique within the claim.
- `dcp_version >= 0.2.0`; `claim_version == identity.version`.
- Refusal specificity warning (vague concern / short rationale).

Contract:
- Exactly two parties; claim versions pinned.
- `in_process` transport only when co-packaged.
- Provenance consistency (c2c=>model_id, h2c=>human_in_loop).
- `trust.tier` consistent with `provenance.authored_by`.
- `on_runtime_violation == hard_fail`.

Runtime binding:
- `contract_id` references an existing contract and `contract_version` is pinned.
- Provider and consumer versions match the referenced contract parties.
- Secrets are references only; no inline secret values.
- Environment-specific configuration appears only in runtime binding, not in claims/contracts.
- Runtime policy cannot override ownership, dependency satisfaction, conflict decisions, trust tier, or invalidation rules.

Manifest:
- `component_uri`/`version` match the claim.
- `permissions` derivable from the claim.
- `theme_contribution.mode == suggestive`.

Cross-schema:
- A manifest's component MUST have a corresponding claim.
- An offer referenced by a contract `binding.provider_capability` MUST exist in the provider's claim at a version satisfying the constraint.
- A documentation projection may expose only capabilities backed by accepted contracts, active runtime bindings, and
  host-registered capabilities, and every exposed capability must have explicit request/response schema refs.
- Every `artifact_ref` (A.12) resolves inside its claim's artifact, and its `sha256` matches the bytes. `offers[].
  documentation` refs are JSON Schemas, and `api_documents[].openapi` refs are OpenAPI documents.
- `api_documents` operations each carry `x-dcp-surface` from the document's `surfaces` and an `x-dcp-access` of
  `public` or `protected`. They use no `/dcp/` path, declare no `servers` or security, and repeat no method and path of
  another document of the same component.
- JSON Schema files use only JSON-Pointer `$ref`s and no `$id`, anchor or dynamic-reference keywords. OpenAPI files
  reference only their own `#/components/...`.
- `offers[].documentation.operations` is non-empty. Operation names are non-blank and unique within the offer, and
  every request and response schema has a root `type: object`.
- `http` entries that share a service root pin one provider component (G.1).
- An HTTP-served binding's endpoint has the shape `<service_root>/dcp/<provider_capability>` (C, G.3).
- A visibility policy that makes a documented entry's offer lack `documentation` is a documentation gap (G.1).
- `action_context`, when present, must stay on the design-time path and must not be serialized into runtime invocation
  payloads or used as a substitute for contract/runtime-binding fields.

---

## I. What is deferred

- **Federated flywheel mechanics** (consent, scrubbing, local-vs-general model contribution) — deferred per settled #4; needs product/legal input, does not block this schema.
- **Trust policy** — the protocol records `trust.tier`; the *rules* for what a deployment does with `self`-tier contracts are policy, specified outside DCP.
- **Wire-level encoding details** beyond "YAML/JSON equivalent" — the `unfurl-dcp` repo build spec covers serialization specifics.
- **The exact `details` maps** for each transport kind and interface kind — enumerated in the repo build spec against real offers.
- **Provider-specific runtime adapters** — runtime binding defines the portable shape; concrete adapters for Kubernetes, Docker Compose, Azure Container Apps, Helm, or external SaaS are specified separately.
- **AsyncAPI, MCP tool and SDK documentation outputs** (G.2) — reserved output kinds. They are specified once
  `event_stream` offers and tool surfaces have documentation fields of their own; until then no projector emits them.
- **Invocation envelope naming** (G.3) — the HTTP envelope keeps its deployed camelCase wire names. Moving it to
  snake_case is a breaking wire change for every DCP HTTP host and needs a versioned transition, so it is not part of
  this draft.

### I.1 Known divergences and Studio-blocking gaps

This register records the places where the DCP sources disagree, and the fields DCP does not define yet, that block
the views and perspective overlays in `unfurl-ui/docs/LLD-studio-viewpoints-and-perspectives.md`. Each one is resolved
by its own DCP design change, made here first. Until then, Fabric projects only the shape this document defines and
reports anything else as a diagnostic. Studio shows the affected content as unavailable, never approximated.

**Divergences.** The three sources are this document, the YAML schemas in `dcp/docs/schemas/`, and the Java model in
`unfurl-dcp/src/main/java/com/unfurl/dcp/`.

| # | Area | This document | Schema YAML | Java model | Blocks |
|---|---|---|---|---|---|
| 1 | Composition contract | §B: `parties`, `binding`, `data_mapping`, `transport`, `expectations`, `provenance`, `trust`, `invalidation` | `contract.schema.yaml`: `participants`, `accepted_bindings`, `policy_context`, `signatures` | `contract/CompositionContract` follows §B; no `policy_context` or `signatures` | Data & State mappings, Substrate & Topology transport, Compliance & Governance policy context |
| 2 | Runtime binding | §C: `target_environment`, `provider_instance`, `runtime_policy` (with retry, circuit-breaker, rate-limit and bulkhead refs), `deployment_controls` | `runtime-binding.schema.yaml`: `providers[].transport`, `secret_refs`, `ports`, `verification` | `runtimebinding/RuntimePolicy` has only `enabled`, `timeoutMs`, `telemetryNamespace`, `auditEnabled` | Substrate & Topology bindings, Resilience & Faults policy refs |
| 3 | Dependencies | A.5: `required`, `recommended`, `forbidden` | `claim.schema.yaml` `dependencies` | `claim/Dependencies`: string lists `needs` and `optional`, with `?owner=` and `?substrate=` qualifiers | Typed external owners in Flow & Composition |
| 4 | Integration ports | A.9: typed `authentication`, `authorization`, `telemetry`, `monitoring`, `ai` | `claim.schema.yaml` `integration_ports` | **Resolved 2026-11-25.** `claim/IntegrationPorts` types the five A.9 ports and keeps `ports` as the opaque wiring map every existing claim uses (A.9 rules); claims that declare only `ports` keep their claim hash | (was Runtime & Telemetry; Security & Trust authentication and authorization) |
| 5 | Faults | A.10: `propagation.parent_impact`, `propagates_when`, `remediation.allowed_actions` | `fault.schema.yaml`: `propagation.default_action`, `remediation.boundary` | `fault/FaultPropagation` follows A.10 | Resilience & Faults |
| 6 | This document itself | **Resolved 2026-11-25:** this copy is authoritative; `dcp/docs/HLD-C2-dcp-schema-spec-updated.md` points here | n/a | n/a | (was every row above) |

**Fields DCP does not define yet:**

| Gap | Today | Blocks |
|---|---|---|
| Residency and region constraints on a claim or on needs | Only `runtime_binding.target_environment.region` (§C), an environment fact rather than a constraint | Compliance & Governance residency |
| Concurrency semantics (instance model, for example single instance vs horizontally scalable; ordering and delivery guarantees) | Only contract `expectations.idempotent`, `async`, `correlation_id_required` (§B) | Substrate & Topology concurrency badges |
| Quantities with units and canonical normalization (throughput, latency, memory, CPU) | Only `expectations.timeout_ms` and free-text `cost_implications` | Performance & Load envelopes; Substrate & Topology resource budgets |

---

## J. Field Name Review for End-User Understanding

The current names are mostly good for protocol implementers, but a few were adjusted for consistency and operator readability. The following names are now preferred:

| Legacy / less clear name | Preferred name | Reason |
|---|---|---|
| `routePrefix` | `route_prefix` | Keeps all schemas in `snake_case`. |
| `themeContribution` | `theme_contribution` | Keeps frontend projection consistent with backend schemas. |
| `bootstrap.hosted` | `bootstrap.hosted_in_shell` | Clearer for operators than generic `hosted`. |
| `bootstrap.standalone` | `bootstrap.standalone_app` | Clearer that this means independently runnable UI. |
| `offers.offer` | `offers.capability` | Better end-user term; `offer` is protocol language, `capability` is product language. |
| `dependencies.required[].dependency` | `dependencies.required[].need` | End users understand “need” more easily than “dependency”. |
| `satisfied_by` | `accepted_providers` | More readable in configuration screens. |
| `properties_required` | `required_properties` | More natural English ordering. |
| `consumers` | `consumer_access` | Clarifies that this controls who may consume the capability. |
| `claimant_position` | `ownership_position` | Easier to understand during conflict review. |
| `negotiation_notes` | `resolution_guidance` | Better explains how the field is used. |
| `human_escalation` | `requires_human_escalation` | Boolean field reads naturally. |
| `grounding_guarantees` | `answer_grounding` | More understandable for non-AI specialists. |
| `authored_by` | `created_by` | More common operational term. |
| `authored_at` | `created_at` | Consistent lifecycle naming. |

Compatibility rule:
- `unfurl-dcp` MAY accept legacy field aliases during migration, but generated schemas and documentation SHOULD use the preferred names.
- Public examples SHOULD use the preferred names only.
- Internal protocol notes may still refer to “claim”, “offer”, and “dependency” as concepts, but YAML fields should favor end-user readability where possible.

---

## K. What comes next

- **`unfurl-dcp` repo build spec** — the Claude-Code-ready phased build plan: schema models, validators, the resolver integration, the question-schema renderer (human + model), forbidden imports, acceptance criteria. Built against these schemas.
- The claim, runtime binding, and manifest schemas here are specified together (HLD-C §4); the repo build spec MUST generate both from one shared component-description definition, never independently.
