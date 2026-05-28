# HLD-C2 — DCP Schema Specification

> [!WARNING]
> **DEPRECATED / HISTORICAL:** This document is archived and is not the active source of truth.
>
> See `Docs/files/INDEX.md` for canonical documents.


**Document status:** Internal schema specification  
**Audience:** Engineering, product, and platform teams  
**Purpose:** Define how a component describes itself, how two components are connected, and how that connection is deployed.

DCP is used to make components easier to plug into a host platform without changing host code every time. A component should clearly say:

- who it is
- what it owns
- what it does not own
- what it needs from other components
- what it offers to other components
- how conflicts are resolved
- how its UI can be mounted
- how it is wired in a real environment

This document defines five related schemas:

1. **Claim** — what a component says about itself.
2. **Composition Contract** — what two components agreed to use.
3. **Runtime Binding** — how the contract is wired in an environment.
4. **Webapp Manifest** — how the component appears in the host UI.
5. **Negotiation Questions** — questions used to resolve ownership, dependency, and mapping decisions.

---

## 1. Simple Mental Model

Use this model while reading the schema:

```text
Claim              = What the component is and what it can do
Composition Contract = Agreement between two components
Runtime Binding   = Environment-specific wiring for that agreement
Webapp Manifest   = Frontend projection for the host shell
Policy            = Organization rules outside this schema
```

Example:

```text
RAG component says: "I need authentication."
Keycloak adapter says: "I provide authentication validation."
Fabric creates a contract between them.
Runtime binding says which Keycloak realm, URL, and secret to use in production.
Webapp manifest tells the shell where to mount the RAG UI.
```

---

## 2. Naming Rules

Use `snake_case` for all fields.

Prefer names that are easy for operators and product teams to understand:

| Prefer | Avoid | Reason |
|---|---|---|
| `capability` | `offer` | Capability is easier to understand in UI and documentation. |
| `need` | `dependency` | Need is simpler for non-protocol readers. |
| `accepted_providers` | `satisfied_by` | Clearer when shown in configuration screens. |
| `ownership_position` | `claimant_position` | Easier during conflict review. |
| `resolution_guidance` | `negotiation_notes` | Explains how the field is used. |
| `answer_grounding` | `grounding_guarantees` | Easier for non-AI specialists. |
| `hosted_in_shell` | `hosted` | Clearer for UI modules. |
| `standalone_app` | `standalone` | Clearer for UI modules. |

Compatibility rule:

- Generated schemas and examples should use the preferred names.
- Migration code may temporarily accept old field names as aliases.

---

# A. Claim Schema

A claim is the main description of a component. It is published per component version. Other components and the Fabric use it to decide whether this component can be used safely.

## A.1 Top-level structure

```yaml
identity:            # required
domain:              # required
refusals:            # required, non-empty
dependencies:        # required, lists may be empty
offers:              # required, may be empty
conflict_resolution: # required
negotiation_surface: # required only for intelligent_component
integration_ports:   # optional, strongly recommended
metadata:            # required
```

---

## A.2 `identity`

Identifies the component.

```yaml
identity:
  uri: uri
  name: string
  kind: enum[intelligent_component, component, infrastructure]
  version: semver
  publisher: string
  publisher_uri: uri?
```

Rules:

- `uri` must be globally unique and stable across versions.
- `version` changes when the component changes.
- If `kind` is `intelligent_component`, `negotiation_surface` is required.

Example:

```yaml
identity:
  uri: dcp://components/keycloak-adapter
  name: Keycloak Adapter
  kind: component
  version: 2.1.0
  publisher: Unfurl Systems
  publisher_uri: https://unfurl.systems
```

---

## A.3 `domain`

Describes what the component owns.

```yaml
domain:
  summary: string

  concerns:
    - concern: string
      description: string
      scope_notes: string?
      owns_state:
        - resource: string
          description: string
          sensitivity: enum[public, internal, confidential, secret]
      owns_decisions:
        - decision: string
          description: string
          authority: enum[exclusive, consulted, advisory]

  boundary_principles:
    - string
```

Rules:

- `concerns` must not be empty.
- Each `concern` name must be unique within the claim.
- `boundary_principles` must not be empty.
- A concern should clearly say what state and decisions it owns.

Example:

```yaml
domain:
  summary: Provides authentication integration with Keycloak for applications using DCP.

  concerns:
    - concern: authentication
      description: Validates user login and access tokens using Keycloak.
      scope_notes: Applies only to identity verification and token validation.
      owns_state:
        - resource: keycloak-session-reference
          description: Session and token references received from Keycloak.
          sensitivity: confidential
      owns_decisions:
        - decision: token-validity
          description: Decides whether a token is valid according to Keycloak.
          authority: exclusive

  boundary_principles:
    - This component verifies identity but does not decide business permissions.
    - Authorization rules must stay with the host application or policy engine.
```

---

## A.4 `refusals`

Describes what the component will not own.

```yaml
refusals:
  - concern: string
    rationale: string
    owned_by: string?
```

Rules:

- `refusals` must not be empty.
- Each refusal should be specific.
- `owned_by` should name the kind of component that should own the concern.

**Refusal-quality note (decision point):** refusals are the most distinctive and most useful part of a claim (per the v0.1 authoring observations) and are exactly what the DCP refusal-reasoning LoRA targets. Validation MUST at minimum warn on vague refusals (e.g. a one-word concern, or a rationale under a minimum length). Whether weak refusals are a hard validation *failure* rather than a warning is a deliberate decision: the stricter gate produces better claims and better training data, at the cost of rejecting hastily-written claims. Recommended: warn by default, with a strict mode (fail) available for claims destined to seed the training corpus.

Example:

```yaml
refusals:
  - concern: business-authorization
    rationale: This adapter only validates identity. It does not decide whether a user can edit, publish, approve, or delete business content.
    owned_by: policy-engine

  - concern: audit-retention
    rationale: This adapter can emit audit events, but it does not store long-term audit history.
    owned_by: audit-service
```

---

## A.5 `dependencies`

Describes what the component needs, recommends, or forbids.

```yaml
dependencies:
  required:
    - need: string
      description: string
      accepted_providers:
        - kind: enum[infrastructure, component, intelligent_component]
          example_claims: list<uri>?
          required_properties: list<string>

  recommended:
    - need: string
      description: string
      benefits_if_present: string

  forbidden:
    - forbidden_need: string
      reason: string
```

Rules:

- Required needs must be satisfied before a contract can be created.
- Forbidden needs are blocking conflicts when present.
- A component can require an intelligent component, but that does not make this component intelligent.

Example:

```yaml
dependencies:
  required:
    - need: keycloak-realm
      description: A configured Keycloak realm is required for token validation.
      accepted_providers:
        - kind: infrastructure
          example_claims:
            - dcp://infrastructure/keycloak-realm
          required_properties:
            - realm_url
            - client_id
            - jwks_endpoint

  recommended:
    - need: audit-service
      description: Authentication events should be sent to an audit service.
      benefits_if_present: Improves traceability and compliance reporting.

  forbidden:
    - forbidden_need: second-authentication-owner-for-same-realm
      reason: Only one authentication owner should validate tokens for the same realm.
```

---

## A.6 `offers`

Describes capabilities this component provides to others.

```yaml
offers:
  - capability: string
    description: string
    consumer_access: enum[any, named_components_only]
    interface:
      kind: enum[http_api, event_stream, negotiation, in_process]
      details: map<string, any>
    stability: enum[experimental, evolving, stable, deprecated]
    version: semver
    cost_implications: string?
```

Rules:

- `capability` must be unique within the claim.
- `version` is required because consumers bind to capability versions.
- `cost_implications` is required when the capability uses metered resources.

Example:

```yaml
offers:
  - capability: validate-token
    description: Validates access tokens and returns normalized user identity.
    consumer_access: any
    interface:
      kind: http_api
      details:
        method: POST
        path: /auth/validate-token
        request_schema: schemas/validate-token-request.json
        response_schema: schemas/validate-token-response.json
    stability: stable
    version: 1.0.0
```

---

## A.7 `conflict_resolution`

Explains how ownership conflicts should be handled.

```yaml
conflict_resolution:
  overlapping_concerns:
    - concern: string
      ownership_position: enum[exclusive, negotiable, deferring, consulted]
      resolution_guidance: string
  precedence_rules:
    - string
  requires_human_escalation: bool
```

Ownership positions:

| Value | Meaning |
|---|---|
| `exclusive` | Only one component may own this concern in the same scope. |
| `negotiable` | Ownership may be shared or split by scope. |
| `deferring` | This component yields to a higher-level owner. |
| `consulted` | This component participates but does not own the decision. |

Example:

```yaml
conflict_resolution:
  overlapping_concerns:
    - concern: authentication
      ownership_position: exclusive
      resolution_guidance: This component is exclusive only within a single Keycloak realm. Different realms may use different authentication providers.

    - concern: authorization
      ownership_position: deferring
      resolution_guidance: Authorization decisions should be owned by the host application or policy engine.

  precedence_rules:
    - If a host application defines a policy engine, authorization must defer to that policy engine.
    - If two authentication providers claim the same realm, contract creation must fail.

  requires_human_escalation: true
```

---

## A.8 `negotiation_surface`

Required only when `identity.kind` is `intelligent_component`.

This section describes the bounded reasoning interface of the component. It tells Fabric what questions the component can answer about itself.

```yaml
negotiation_surface:
  endpoint: string
  protocols_supported: list<enum[http_post_json]>
  supported_intents:
    - intent: string
      description: string
      example: string?
  answer_grounding: list<string>
  limitations: list<string>
```

Rules:

- Live-state questions must be answered from live introspection, not training data.
- Limitations must clearly say what the component refuses to answer.
- Having AI ports does not automatically make a component an `intelligent_component`. This section is what makes the reasoning boundary explicit.

Example for an intelligent RAG component:

```yaml
negotiation_surface:
  endpoint: /dcp/negotiate
  protocols_supported:
    - http_post_json
  supported_intents:
    - intent: explain-owned-concerns
      description: Explains which RAG concerns are owned by this component.
      example: Does this component own document authorization?

    - intent: validate-provider-fit
      description: Checks whether a provider can satisfy a required RAG need.
      example: Can Keycloak validate users for this RAG component?

  answer_grounding:
    - Static ownership answers come from this claim.
    - Runtime status answers come from live health and configuration checks.
    - The component must not answer live deployment questions from model training data.

  limitations:
    - Refuses to decide business authorization.
    - Refuses to expose secrets or tenant-specific credentials.
    - Redirects authorization-policy questions to the configured policy engine.
```

---

## A.9 `integration_ports`

Common enterprise integration surfaces.

These ports help a host understand how the component connects to authentication, authorization, telemetry, monitoring, and AI systems.

They do **not** make a component intelligent by themselves.

```yaml
integration_ports:
  authentication:
    required: bool
    accepted_modes: list<enum[jwt, oidc, api_key, mtls, session_cookie]>
    notes: string?

  authorization:
    required: bool
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
    intents: list<string>?
    model_access: enum[none, external_provider, embedded_model, host_provided]?
    cost_metered: bool?
    notes: string?
```

Example:

```yaml
integration_ports:
  authentication:
    required: true
    accepted_modes:
      - jwt
      - oidc
    notes: Requires a valid bearer token from the host application.

  authorization:
    required: true
    decision_owner: external_policy
    permission_prefixes:
      - rag
    notes: The component asks the policy engine before returning protected content.

  telemetry:
    required: true
    signals:
      - logs
      - metrics
      - traces
      - audit_events
    correlation_id_required: true

  monitoring:
    required: true
    health_endpoints:
      - /health/live
      - /health/ready
    readiness_required: true
    liveness_required: true

  ai:
    required: true
    intents:
      - summarize
      - answer-question
      - negotiate
    model_access: host_provided
    cost_metered: true
```

---

## A.10 `metadata`

Technical metadata for versioning and references.

```yaml
metadata:
  dcp_version: semver
  claim_version: semver
  supersedes: list<uri>?
  effective_from: timestamp
  references:
    - title: string
      uri: uri
      purpose: enum[spec, api-spec, protocol, doc]
```

Example:

```yaml
metadata:
  dcp_version: 0.2.0
  claim_version: 2.1.0
  effective_from: 2026-05-22T00:00:00Z
  references:
    - title: Keycloak Adapter API
      uri: https://developer.example.com/keycloak-adapter/openapi.json
      purpose: api-spec
```

---

# B. Composition Contract Schema

A composition contract is the agreement between two components. It is created by Fabric after checking claims, dependencies, refusals, and conflicts.

The contract is immutable. If either component claim changes, the contract must be reviewed or regenerated.

```yaml
contract:
  contract_id: uri
  contract_version: semver

  parties:
    consumer:
      claim_uri: uri
      claim_version: semver
    provider:
      claim_uri: uri
      claim_version: semver

  binding:
    consumer_need: string
    provider_capability: string
    provider_capability_version: semver

  data_mapping:
    inbound: map<string, string>
    outbound: map<string, string>

  transport:
    kind: enum[in_process, http_json, grpc]
    details: map<string, any>?

  expectations:
    timeout_ms: int?
    idempotent: bool
    async: bool
    correlation_id_required: bool

  provenance:
    created_by: enum[fabric, embedded_self]
    mode: enum[c2c, h2c, h2h]
    model_id: string?
    fabric_version: semver?
    human_in_loop: bool
    created_at: timestamp

  trust:
    tier: enum[neutral, self]

  invalidation:
    triggers: list<enum[claim_version_changed, pattern_unsupported, runtime_assumption_violated]>
    on_runtime_violation: enum[hard_fail]

  proof:                                  # signing / tamper-evidence (see HLD-E §1)
    type: enum[ed25519-2020, ecdsa-p256]  # asymmetric signature suite
    key_id: uri                           # which Fabric key signed (e.g. urn:unfurl:keys:fabric-root-public)
    integrity_hash: string                # sha256 of the governed definition
    signature: string                     # base64 signature over contract + integrity_hash
```

Rules:

- `parties.consumer.claim_version` and `parties.provider.claim_version` are pinned.
- `transport.kind: in_process` is valid only when the two components are packaged together.
- `mode: c2c` requires `model_id`.
- `mode: h2c` requires `human_in_loop: true`.
- `trust.tier: self` is used when the provider created the contract through its own embedded reasoning.
- Runtime violation always results in `hard_fail`. Runtime does not self-negotiate.
- **A deployed contract MUST carry a valid `proof`.** Fabric signs every frozen contract at design-time; the runtime verifies the signature against a statically-configured Fabric public key and recomputes `integrity_hash` against the local definition, entirely in memory, with no network callback (HLD-E §1). This is tamper-evidence on the compiled artifact (verification), NOT runtime authorization or negotiation — the runtime verifies a design-time decision, it does not make one.
- **Implementation note (substrate interface):** the frozen contract is the artifact the substrate's in-process composition mechanism executes. The `unfurl-dcp` `FrozenContract` type MUST implement the `ContractInvocable` interface defined in `unfurl-substrate`, so the substrate can execute a contract as an in-process call without importing `unfurl-dcp` (dependency inversion: substrate defines the interface, dcp satisfies it). This is a code-level contract, not a schema field; it is noted here so the schema and the build specs stay consistent.

Example: RAG component uses Keycloak adapter for token validation.

```yaml
contract:
  contract_id: dcp://contracts/rag-keycloak-token-validation
  contract_version: 1.0.0

  parties:
    consumer:
      claim_uri: dcp://components/rag-assistant
      claim_version: 1.3.0
    provider:
      claim_uri: dcp://components/keycloak-adapter
      claim_version: 2.1.0

  binding:
    consumer_need: user-authentication
    provider_capability: validate-token
    provider_capability_version: 1.0.0

  data_mapping:
    inbound:
      token: $.consumer.request.headers.authorization
      tenant: $.consumer.context.tenant_id
    outbound:
      user_id: $.provider.response.user.id
      roles: $.provider.response.user.roles
      auth_status: $.provider.response.status

  transport:
    kind: http_json
    details:
      method: POST
      path: /auth/validate-token

  expectations:
    timeout_ms: 3000
    idempotent: true
    async: false
    correlation_id_required: true

  provenance:
    created_by: fabric
    mode: h2c
    fabric_version: 0.2.0
    human_in_loop: true
    created_at: 2026-05-22T10:00:00Z

  trust:
    tier: neutral

  invalidation:
    triggers:
      - claim_version_changed
      - pattern_unsupported
      - runtime_assumption_violated
    on_runtime_violation: hard_fail
```

---

# C. Runtime Binding Schema

A runtime binding wires a contract into a real environment.

The contract says **what is allowed**.  
The runtime binding says **where and how it runs**.

Runtime bindings are environment-specific and mutable. They can change when URLs, secrets, scaling policies, or environment settings change.

```yaml
runtime_binding:
  binding_id: uri
  contract_id: uri
  contract_version: semver

  target_environment:
    environment: string
    tenant: string?
    region: string?
    namespace: string?

  provider_instance:
    component_uri: uri
    component_version: semver
    instance_name: string
    deployment_kind: enum[in_process, container, remote_service, external_saas, webapp, sidecar]
    base_url: uri?
    base_url_ref: config_ref?
    credentials_ref: secret_ref?

  consumer_instance:
    component_uri: uri
    component_version: semver
    instance_name: string?

  runtime_policy:
    enabled: bool
    timeout_ms: int?
    retry_policy_ref: string?
    circuit_breaker_ref: string?
    rate_limit_ref: string?
    bulkhead_ref: string?
    telemetry_namespace: string?
    audit_enabled: bool?

  configuration:
    values: map<string, any>?
    config_refs: map<string, config_ref>?

  deployment_controls:
    rollout_strategy: enum[manual, automatic, canary, blue_green]?
    min_instances: int?
    max_instances: int?
    autoscaling_policy_ref: string?

  lifecycle:
    created_by: string
    created_at: timestamp
    updated_by: string?
    updated_at: timestamp?
```

Rules:

- `contract_id` must reference an existing contract.
- Provider and consumer versions must match the contract.
- Secrets must be references only. Do not store secret values inline.
- Environment-specific values belong here, not in the claim or contract.
- `base_url` and `base_url_ref` are mutually exclusive. Prefer `base_url_ref` outside local development.
- Runtime policy can disable a binding, but it cannot change ownership, dependency decisions, trust tier, or invalidation rules.

Example:

```yaml
runtime_binding:
  binding_id: dcp://bindings/prod/acme/rag-keycloak-token-validation
  contract_id: dcp://contracts/rag-keycloak-token-validation
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

  consumer_instance:
    component_uri: dcp://components/rag-assistant
    component_version: 1.3.0
    instance_name: acme-rag-assistant

  runtime_policy:
    enabled: true
    timeout_ms: 3000
    retry_policy_ref: policy://runtime/retry/standard-read
    circuit_breaker_ref: policy://runtime/circuit-breaker/auth-validation
    telemetry_namespace: qppnextgen.rag.auth
    audit_enabled: true

  configuration:
    config_refs:
      realm: config://prod/acme/keycloak/realm
      client_id: config://prod/acme/keycloak/client-id

  deployment_controls:
    rollout_strategy: canary
    min_instances: 2
    max_instances: 8
    autoscaling_policy_ref: policy://autoscaling/auth-adapter-standard

  lifecycle:
    created_by: fabric
    created_at: 2026-05-22T10:00:00Z
```

---

# D. Webapp Manifest Schema

A webapp manifest tells the host shell how to show and mount the component UI.

It must be generated from, or validated against, the claim. It should not be written independently.

```yaml
webapp:
  component_uri: uri
  component_version: semver

  route_prefix: string
  routes:
    - path: string
      title: string

  navigation:
    section: string
    title: string
    icon: string?

  permissions: list<string>

  theme_contribution:
    mode: enum[suggestive]
    tokens: map<string, string>

  bootstrap:
    standalone_app: bool
    hosted_in_shell: bool
```

Rules:

- `component_uri` and `component_version` must match the claim.
- `permissions` must be based on the claim and integration ports.
- `theme_contribution.mode` is always `suggestive`.
- The component may suggest theme tokens, but the host theme decides whether to use them.

Example:

```yaml
webapp:
  component_uri: dcp://components/rag-assistant
  component_version: 1.3.0

  route_prefix: /rag
  routes:
    - path: /
      title: RAG Assistant
    - path: /admin
      title: RAG Administration

  navigation:
    section: intelligence
    title: RAG Assistant
    icon: search-sparkle

  permissions:
    - rag.view
    - rag.admin

  theme_contribution:
    mode: suggestive
    tokens:
      accent_color: var(--theme-accent)
      surface_color: var(--theme-surface)
      border_radius: var(--theme-radius-md)

  bootstrap:
    standalone_app: true
    hosted_in_shell: true
```

---

# E. Negotiation Question Schema

Negotiation questions are used by Fabric to decide whether a contract can be created.

The same question structure can be used in two ways:

- as a human interview
- as a model prompt

```yaml
negotiation_questions:
  - id: string
    applies_when: string?
    prompt: string
    answer_type: enum[disposition, owner, boolean, scope, free_text]
    feeds: enum[binding, conflict_check, dependency_check, data_mapping]
```

Answer types:

| Type | Meaning |
|---|---|
| `disposition` | `accept`, `refuse`, or `partial_accept` |
| `owner` | The component kind that should own a refused concern |
| `boolean` | Yes/no answer |
| `scope` | Scope qualifier such as tenant, realm, or region |
| `free_text` | Mapping or explanation text |

Minimum question set:

```yaml
negotiation_questions:
  - id: owns-concern
    prompt: Does <provider> own <concern> according to its claim?
    answer_type: disposition
    feeds: binding

  - id: refused-owner
    applies_when: answer to owns-concern == refuse
    prompt: If <provider> refuses <concern>, what kind of component should own it?
    answer_type: owner
    feeds: binding

  - id: exclusive-conflict
    prompt: Does any other claim assert exclusive ownership of <concern> in the same scope?
    answer_type: boolean
    feeds: conflict_check

  - id: conflict-scope
    applies_when: answer to exclusive-conflict == true
    prompt: What scope qualifier separates the claimants, if any?
    answer_type: scope
    feeds: conflict_check

  - id: dependency-satisfied
    prompt: Is <consumer>'s required need <need> satisfied by <provider>'s capabilities?
    answer_type: boolean
    feeds: dependency_check

  - id: data-shape
    prompt: How does <consumer>'s input map to <provider>'s capability input, and how does the output map back?
    answer_type: free_text
    feeds: data_mapping
```

Example answers for the RAG + Keycloak case:

```yaml
answers:
  - question_id: owns-concern
    answer: accept
    rationale: Keycloak Adapter owns authentication token validation.

  - question_id: exclusive-conflict
    answer: false
    rationale: No other authentication owner is configured for tenant acme and realm acme-prod.

  - question_id: dependency-satisfied
    answer: true
    rationale: RAG needs user-authentication and Keycloak Adapter offers validate-token.

  - question_id: data-shape
    answer: |
      Map the incoming Authorization header to provider input token.
      Map tenant_id from consumer context to provider input tenant.
      Map provider user.id to consumer user_id.
      Map provider user.roles to consumer roles.
```

### E.1 The answer set is the training data (flywheel hinge)

The captured answer object above is not only used to build the contract. Each answer carries `question_id`, `answer`, and `rationale` — which is exactly the refusal-reasoning training tuple `(claim, request, disposition, redirection, rationale)` that the DCP LoRA experiment learns from.

This is the hinge that makes H2C a data flywheel:

- In **H2C**, a human answers the question set. The captured answers build the contract AND serialize to a labeled training example.
- In **C2C**, the model answers the same question set.

Because the question schema is identical in both modes, every H2C session produces a labeled example for the model that will eventually do C2C. The `owns-concern` answer (disposition) + `refused-owner` answer (owner) + `rationale` together are one refusal-reasoning training row. Implementations MUST be able to serialize a captured answer set to this training-tuple shape. This is why H2C is built before C2C, and why the answer object must always include `rationale`.

---

# F. End-to-End Example

This section shows how the pieces fit together.

## F.1 Consumer claim summary: RAG Assistant

```yaml
identity:
  uri: dcp://components/rag-assistant
  name: RAG Assistant
  kind: intelligent_component
  version: 1.3.0
  publisher: Unfurl Systems

domain:
  summary: Answers questions using approved enterprise content.
  concerns:
    - concern: rag-answering
      description: Retrieves approved content and generates grounded answers.
      owns_state:
        - resource: rag-index-reference
          description: References to indexed content and retrieval metadata.
          sensitivity: internal
      owns_decisions:
        - decision: answer-generation
          description: Decides how to compose an answer from retrieved content.
          authority: exclusive
  boundary_principles:
    - This component does not decide whether a user is allowed to access content.
    - This component does not own identity, login, or business authorization policy.

refusals:
  - concern: authentication
    rationale: Login and token validation must be handled by an authentication provider.
    owned_by: authentication-component
  - concern: business-authorization
    rationale: Access decisions must be handled by policy engine or host application.
    owned_by: policy-engine

dependencies:
  required:
    - need: user-authentication
      description: The component needs a trusted authenticated user before returning answers.
      accepted_providers:
        - kind: component
          required_properties:
            - token_validation
            - normalized_user_identity
  recommended: []
  forbidden: []

offers:
  - capability: answer-question
    description: Answers a user question using indexed enterprise content.
    consumer_access: any
    interface:
      kind: http_api
      details:
        method: POST
        path: /rag/answer
    stability: evolving
    version: 1.1.0
    cost_implications: Uses model tokens for answer generation.

conflict_resolution:
  overlapping_concerns:
    - concern: business-authorization
      ownership_position: deferring
      resolution_guidance: Always defer to the configured policy engine or host application.
  precedence_rules:
    - Host authorization policy overrides RAG retrieval preferences.
  requires_human_escalation: true

negotiation_surface:
  endpoint: /dcp/negotiate
  protocols_supported:
    - http_post_json
  supported_intents:
    - intent: validate-provider-fit
      description: Checks whether a provider can satisfy a RAG dependency.
  answer_grounding:
    - Claim questions are answered from this claim.
    - Runtime questions are answered from live configuration.
  limitations:
    - Does not decide authorization policy.
    - Does not expose secrets.

integration_ports:
  authentication:
    required: true
    accepted_modes:
      - jwt
      - oidc
  authorization:
    required: true
    decision_owner: external_policy
    permission_prefixes:
      - rag
  telemetry:
    required: true
    signals:
      - logs
      - traces
      - audit_events
    correlation_id_required: true
  monitoring:
    required: true
    health_endpoints:
      - /health/live
      - /health/ready
    readiness_required: true
    liveness_required: true
  ai:
    required: true
    intents:
      - answer-question
      - summarize
      - negotiate
    model_access: host_provided
    cost_metered: true

metadata:
  dcp_version: 0.2.0
  claim_version: 1.3.0
  effective_from: 2026-05-22T00:00:00Z
```

## F.2 Provider claim summary: Keycloak Adapter

```yaml
identity:
  uri: dcp://components/keycloak-adapter
  name: Keycloak Adapter
  kind: component
  version: 2.1.0
  publisher: Unfurl Systems

domain:
  summary: Validates user tokens using Keycloak.
  concerns:
    - concern: authentication
      description: Validates JWT/OIDC tokens and returns normalized identity.
      owns_state:
        - resource: keycloak-token-metadata
          description: Token metadata needed for validation.
          sensitivity: confidential
      owns_decisions:
        - decision: token-validity
          description: Decides whether a token is valid.
          authority: exclusive
  boundary_principles:
    - This component owns token validation only.
    - It does not own business authorization.

refusals:
  - concern: business-authorization
    rationale: Business permissions must be decided by a policy engine or host application.
    owned_by: policy-engine

dependencies:
  required:
    - need: keycloak-realm
      description: A configured Keycloak realm is required.
      accepted_providers:
        - kind: infrastructure
          required_properties:
            - realm_url
            - jwks_endpoint
            - client_id
  recommended: []
  forbidden:
    - forbidden_need: second-authentication-owner-for-same-realm
      reason: Two authentication owners for the same realm would create conflicting identity decisions.

offers:
  - capability: validate-token
    description: Validates a token and returns normalized user identity.
    consumer_access: any
    interface:
      kind: http_api
      details:
        method: POST
        path: /auth/validate-token
    stability: stable
    version: 1.0.0

conflict_resolution:
  overlapping_concerns:
    - concern: authentication
      ownership_position: exclusive
      resolution_guidance: Exclusive within one Keycloak realm.
    - concern: authorization
      ownership_position: deferring
      resolution_guidance: Authorization belongs to the policy engine or host application.
  precedence_rules:
    - If the host defines a policy engine, authorization must defer to it.
  requires_human_escalation: true

integration_ports:
  authentication:
    required: true
    accepted_modes:
      - jwt
      - oidc
  authorization:
    required: false
    decision_owner: not_applicable
  telemetry:
    required: true
    signals:
      - logs
      - metrics
      - traces
      - audit_events
    correlation_id_required: true
  monitoring:
    required: true
    health_endpoints:
      - /health/live
      - /health/ready
    readiness_required: true
    liveness_required: true
  ai:
    required: false
    model_access: none

metadata:
  dcp_version: 0.2.0
  claim_version: 2.1.0
  effective_from: 2026-05-22T00:00:00Z
```

---

# G. Validation Rules Summary

## Claim validation

- Required sections must be present.
- `refusals` must not be empty.
- `boundary_principles` must not be empty.
- Concern names must be unique inside the claim.
- `claim_version` must match `identity.version`.
- `dcp_version` must be `0.2.0` or higher.
- If `kind` is `intelligent_component`, `negotiation_surface` is required.
- Vague refusals should produce warnings.

## Contract validation

- There must be exactly two parties: consumer and provider.
- Claim versions must be pinned.
- `in_process` transport is allowed only when components are packaged together.
- `mode: c2c` requires `model_id`.
- `mode: h2c` requires `human_in_loop: true`.
- `trust.tier` must match `provenance.created_by`.
- `on_runtime_violation` must be `hard_fail`.

## Runtime binding validation

- `contract_id` must reference an existing contract.
- `contract_version` must match the deployed contract.
- Provider and consumer versions must match the contract parties.
- Secrets must be references only.
- Environment-specific configuration must stay in runtime binding.
- Runtime policy cannot change ownership, dependency, conflict, trust, or invalidation decisions.

## Webapp manifest validation

- `component_uri` and `component_version` must match the claim.
- `permissions` must be derived from the claim or integration ports.
- `theme_contribution.mode` must be `suggestive`.

## Cross-schema validation

- A webapp manifest must have a matching claim.
- A contract must reference a provider capability that exists in the provider claim.
- The provider capability version must satisfy the version constraint used during contract creation.

---

# H. Deferred Items

These are intentionally outside this schema:

- Federated learning or flywheel mechanics.
- Organization-specific trust policy.
- Wire-level serialization details beyond YAML/JSON compatibility.
- Exact `interface.details` structure for every interface kind.
- Provider-specific deployment adapters such as Kubernetes, Helm, Docker Compose, Azure Container Apps, or external SaaS adapters.

---

# I. Recommended Next Steps

1. Build `unfurl-dcp` schema models using this simplified field set.
2. Add validators for claim, contract, runtime binding, and manifest.
3. Generate webapp manifest from claim wherever possible.
4. Add examples as test fixtures.
5. Build a small resolver that can create the RAG + Keycloak contract shown in this document.
6. Add a human-readable UI later that displays these fields with friendly labels.

