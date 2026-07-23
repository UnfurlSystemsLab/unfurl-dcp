# HLD-C2 — DCP Schema Specification

**Document status:** Concrete schema specification. Downstream of HLD-C (DCP v0.2 internal). 
**Audience:** Internal. The field-by-field definition that `unfurl-dcp` implements and validates against.
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
- **G. Capability documentation projection** (runtime-facing docs) -- Swagger/OpenAPI/AsyncAPI/MCP docs from accepted DCP surfaces only.

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
```

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
      kind:    enum[http_api, event_stream, negotiation, in_process]  # required
      details: map<string, any>   # required, kind-specific (endpoints, spec_url, format, transport...)
    stability:    enum[experimental, evolving, stable, deprecated]  # required
    version:      semver       # required (the offer is a versioned contract)
    cost_implications: string?   # optional but required when the offer incurs measurable cost
```

Rules:
- `version` on an offer is REQUIRED because an offer is a capability contract subject to semver matching (settled #6). Consumers bind by `semver_range` against this.
- `cost_implications` MUST be present when the offer consumes metered resources (e.g., a negotiation surface that consumes model tokens), per HLD-C §3.5 / observation #6.
- A capability offer is the surface a Plane 2 contract binds against (a consumer *need* → a provider *offer*).
- `interface.details` is part of the offer's deterministic structure, not free-form prose. A consumer need may require a subset of detail keys, and the resolver must accept only offers whose details satisfy that subset. Scalar values match by equality; list values match by containment; map values match recursively by subset.
- Capabilities that support multiple execution modes declare them in details so mode selection is governed by DCP rather than hidden in local deployment metadata. Standard keys are `execution_modes`, `default_execution_mode`, and `mode_policies`.

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

```yaml
capability_documentation:
  document_id: uri
  target_environment:
    environment: string
    tenant: string?
    namespace: string?

  source:
    contracts: list<uri>
    runtime_bindings: list<uri>
    registered_capabilities: list<string>

  surfaces:
    - kind: enum[openapi, swagger_ui, asyncapi, mcp_tools, sdk_metadata]
      visibility: enum[public, internal, private]
      base_url_ref: config_ref?
      includes: list<string>

  schemas:
    - capability: string
      request_schema_ref: uri
      response_schema_ref: uri
      faults: list<string>

  lifecycle:
    generated_by: string
    generated_at: timestamp
```

Rules:
- Documentation generators MUST include only capabilities that are accepted by frozen composition contracts, active in
  runtime bindings, and registered in the host runtime.
- Every public or internal capability documentation entry MUST have explicit request and response schema references.
- Declared DCP faults that can cross the documented boundary SHOULD be included in the generated documentation.
- Plugin jars, classpath discovery, tool registries, provider registries, model catalogs, RAG/vector stores, prompts,
  logs, model outputs, and implementation DTOs are not sufficient proof that a capability is callable or safe to expose.
- If a capability is accepted and intended to be documented but lacks an explicit schema, generation MUST fail with a
  documentation gap rather than infer a public contract.
- Documentation visibility is policy-controlled. DCP records the projection inputs; product policy decides which
  accepted capabilities are public, internal, or private.

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
- `action_context`, when present, must stay on the design-time path and must not be serialized into runtime invocation
  payloads or used as a substitute for contract/runtime-binding fields.

---

## I. What is deferred

- **Federated flywheel mechanics** (consent, scrubbing, local-vs-general model contribution) — deferred per settled #4; needs product/legal input, does not block this schema.
- **Trust policy** — the protocol records `trust.tier`; the *rules* for what a deployment does with `self`-tier contracts are policy, specified outside DCP.
- **Wire-level encoding details** beyond "YAML/JSON equivalent" — the `unfurl-dcp` repo build spec covers serialization specifics.
- **The exact `details` maps** for each transport kind and interface kind — enumerated in the repo build spec against real offers.
- **Provider-specific runtime adapters** — runtime binding defines the portable shape; concrete adapters for Kubernetes, Docker Compose, Azure Container Apps, Helm, or external SaaS are specified separately.

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
