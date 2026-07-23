# HLD-C — Domain Claim Protocol (DCP) v0.2, Internal Specification

**Document status:** Internal specification. Supersedes DCP v0.1 (the version referenced in `keycloak-domain-claim-example.md`).
**Audience:** Internal. Written against concrete Unfurl products (flow, foundry, Fabric, audit, intelligent-keycloak) for clarity. A distilled, product-neutral public specification will be derived from this once the model is proven.
**Position in the portfolio:** DCP is the keystone. It is the Component Description Protocol named in HLD-A. The substrate, the products, Fabric, and the entire Intelligent Components thesis depend on the model defined here. This document defines *what the protocol is*; the concrete schemas (`unfurl-dcp` repo) and the build plan are downstream of it.

---

## 1. What DCP is, and what it is not

DCP is the protocol by which components describe themselves, negotiate how they compose, and invoke one another. It exists so that independent products can be genuine peers — composable without hard-wiring, deployable together or apart — which is the structural expression of the Intelligent Components thesis.

More precisely, the Domain Claim Protocol is a **dynamic composite protocol** for declaring, composing, validating, and binding enterprise capabilities across multiple levels of a system. A DCP may contain descendant DCPs, and those descendants may be assembled, replaced, enabled, disabled, or rebound dynamically based on configuration, policies, runtime context, tenant needs, deployment environment, or selected capabilities.

The key principle is:

> DCP enables late-bound enterprise composition.

Composition is not hardcoded in application code or fixed topology. Composition is resolved through claims, requirements, policies, and bindings.

DCP is **three protocols under one name**, joined by a cache:

1. **Description** — a component publishes a static *claim* describing its domain.
2. **Negotiation** — two claims plus context produce a frozen *composition contract*. This is the only place intelligence is involved.
3. **Invocation** — components call each other against a frozen contract. Fast, deterministic, no intelligence.

The single most important property: **negotiation is a compile step; invocation is execution.** You compile once and run the binary many times. Intelligence touches Planes 1 and 2 at authoring/first-contact time and never touches Plane 3. This is simultaneously the performance solution (no AI in the hot path) and the residency solution (no model in the perimeter). See §7.

### Dynamic composite model

DCP should not be understood as one fixed containment ladder such as:

```text
Company DCP
  -> Substrate DCP
       -> Module DCP
            -> Component DCP
```

That hierarchy can exist, but it is not the protocol's limit. The more general model is a dynamically governable composition graph:

```text
Company DCP
  -> Substrate DCP: Azure
  -> Substrate DCP: AWS
  -> Module DCP: Identity
       -> Component DCP: Keycloak
       -> Component DCP: Auth0
  -> Module DCP: Storage
       -> Component DCP: Azure Blob
       -> Component DCP: S3
  -> Module DCP: AI
       -> Component DCP: Azure OpenAI
       -> Component DCP: Bedrock
       -> Component DCP: Local Model Gateway
```

At deployment or runtime activation time, different tenants can resolve different valid compositions from the same larger claim graph:

| Tenant | Resolved composition |
|---|---|
| Tenant A | Azure substrate + Keycloak + Azure Blob + Azure OpenAI |
| Tenant B | AWS substrate + Auth0 + S3 + Bedrock |
| Tenant C | Private substrate + Keycloak + MinIO + Local Model Gateway |

This gives DCP four important behaviors:

1. **Dynamic discovery** - find available descendant DCPs.
2. **Dynamic selection** - choose the right descendant based on capability, policy, tenant, or environment.
3. **Dynamic validation** - verify compatibility before activation.
4. **Dynamic binding** - connect selected components without changing application code.

Illustrative shape:

```yaml
dcp:
  id: module.ai-layout
  type: module
  compositionMode: dynamic

  requires:
    - capability: ai.text-generation
    - capability: object-storage
    - capability: telemetry
    - capability: auth-context

  compatibleDescendants:
    - component.azure-openai
    - component.aws-bedrock
    - component.local-llm-gateway

  selectionPolicy:
    strategy: policy-driven
    rules:
      - if: tenant.dataResidency == "india"
        prefer: component.local-llm-gateway
      - if: environment == "azure"
        prefer: component.azure-openai
      - if: costMode == "optimized"
        prefer: component.local-llm-gateway

  bindings:
    mode: late-bound
    validation: required-before-activation
```

The protocol therefore turns enterprise architecture from a static assembly into a dynamically governable composition graph. Fabric and Studio make that graph inspectable and operable; Flow executes only the validated, bound result.

### Relationship to MCP

Per HLD-A, DCP is complementary to MCP, not a replacement. MCP is the right protocol for "an LLM invokes a tool at runtime." DCP is the right protocol for "an intelligent agent reasons about composition at design time." MCP operates in the moment of execution; DCP operates in the moment of assembly. A component may speak both: DCP to describe and compose itself, MCP to expose runtime tools. They sit at different altitudes and do not conflict.

### What DCP is not

- It is not a runtime data-exchange format for business payloads (that is the component's own API, governed by the contract but not defined by DCP).
- It is not an auth protocol (components consume the customer's auth via ports; DCP describes the dependency, it does not implement it).
- It is not a hosting or deployment system (that is Fabric).

---

## 2. The three planes

The planes have **opposite performance budgets**, which is why they are separated.

| Plane | Frequency | Cost budget | Intelligence | Output |
|---|---|---|---|---|
| 1 — Description | Once per component version | Near-zero (fetch + cache + schema-validate) | None | A claim |
| 2 — Negotiation | Once per (component-pair, integration-pattern) | High; latency-tolerant | Yes — the decision layer | A frozen composition contract |
| 3 — Invocation | Every call (thousands+) | Minimal; the hot path | None | Data exchange against the contract |

The cache between Plane 2 and Plane 3 is the whole design. Negotiation produces an artifact (the contract); invocation reuses that artifact indefinitely. Re-negotiation happens only when something invalidates the contract (§8). The accidental failure mode this design exists to prevent is putting Plane 2 cost into Plane 3 — if every invocation required reasoning, the system would be unusably slow and would break the residency wedge. The contract is the firewall that keeps them apart.

---

## 3. Plane 1 — The Claim

A claim is a component's self-description. It is static, published per version, and the single source of truth about what the component owns and refuses. The claim model below formalizes the structure validated in `keycloak-domain-claim-example.md`, revised per the v0.1 observations (§10).

A claim has these sections:

### 3.1 Identity

Who the component is. URI, name, kind (`intelligent_component`, `component`, `infrastructure`), version, publisher. The URI is the stable global identifier other claims reference.

### 3.2 Domain Assertion

What the component owns. This is the heart of the claim and has three parts:

- **Ownership scope / primary concerns** — the concerns the component owns, each with a description and natural-language `scope_notes` that state where the concern begins and ends.
- **Boundary principles** — natural-language statements of where the domain ends ("the domain ends at the boundary of 'who is this user'"). Per v0.1 observation #4, these did the most explanatory work in the example and small models in particular benefit from them. They are a required, first-class part of the assertion in v0.2, not optional prose.
- **Ownership of state and decisions** — see the §3.6 resolution of the v0.1 overlap.

### 3.3 Refusals

What the component explicitly does *not* own. Per v0.1 observation #1, this was the hardest and most useful section to write — stating refusals forces the boundary into focus and resists the temptation to claim broadly. Each refusal carries a `concern`, a `rationale`, and an `owned_by` pointing at the kind of component that should own it instead.

Refusals are elevated in v0.2: they are the behavior most distinctive to DCP, the behavior most legible to evaluate, and the behavior the refusal-reasoning LoRA experiment targets. A claim with weak refusals is a weak claim. **Refusals are required and must be specific** — "we don't do everything else" is not a refusal; "we do not own authorization decisions; those belong to a policy engine" is.

### 3.4 Dependencies

What the component needs to function. `required`, `recommended`, and `forbidden`. Each dependency states what satisfies it (`satisfied_by`: kind + properties required + optional example claims). Per v0.1 observation #2, writing dependencies surfaces structural truths — e.g., "this component requires a reasoning model" makes the intelligence itself a dependency rather than an intrinsic property. The `forbidden` list is where exclusive-ownership conflicts are pre-declared (e.g., "no second identity provider claiming the same realm").

### 3.5 Offers

What the component exposes for others to consume. Each offer has a name, description, allowed consumers (`any` / `named_components_only`), an interface (kind + details), a stability level, and optional `cost_implications`. Per v0.1 observation #6, the cost_implications field is small but important — it is honest about token/inference cost in a way most service specs are not. Offers are the surface that Plane 2 negotiation binds against: a contract maps a consumer's *need* to a provider's *offer*.

### 3.6 Conflict Resolution

How the component relates to other claims. Per v0.1 observation #3, **this is where the protocol's distinctive value lives**, and v0.2 treats it as central rather than incidental.

The key vocabulary is `claimant_position` on each overlapping concern:

- **exclusive** — only one claimant may own this concern in a given scope; an overlap is a blocking conflict.
- **negotiable** — ownership can be split or shared; the negotiation surface advises where to draw the line.
- **deferring** — the component yields to a higher-level owner if one exists.
- **consulted** — the component participates in the decision but does not own it.

These positions are the vocabulary Plane 2 negotiation operates on. Without them, claims are isolated statements. With them, claims are participants in a negotiation. Plus `precedence_rules` for resolving conflicts the positions don't settle, and an escape hatch to a human operator.

### 3.7 Negotiation Surface

The component's conversational interface (for intelligent components). Supported intents, grounding guarantees (what is answered from live introspection vs. training vs. retrieval), and limitations (what it refuses to do even with access). This is the Plane-2 entry point for an intelligent component negotiating on its own behalf.

### 3.8 Fault vocabulary and propagation policy

DCP treats operational faults as first-class protocol signals, not as untyped logs. A component claim declares the faults
it can emit, the declared needs/offers/constraints each fault can affect, the evidence signals that justify the fault,
the deterministic propagation gate for parent impact, and the remediation actions that may be taken without leaving the
claim boundary.

This matters because a lower-level failure is not automatically a higher-level fault. A child fault propagates upward
only when the claim says the fault affects a parent-visible need, dependency, constraint, offer, or service expectation.
The propagation decision is deterministic and runtime-safe:

```text
(declared fault vocabulary, observed fault signal) -> propagate | suppress | reject
```

The gate does not perform design-time negotiation and does not self-heal at runtime. If a fault invalidates a frozen
contract assumption, runtime hard-fails and emits an invalidation signal; Fabric may renegotiate at the next design-time
pass. This preserves the design-time/runtime firewall while giving operators and AI assistants a structured explanation
of blast radius, affected claims, and allowed remediation actions.

### 3.9 Metadata

DCP version, claim version, supersedes list, effective-from, references.

### Resolving the v0.1 state/decisions/concerns overlap (v0.1 observation #5)

v0.1 had `state_owned` and `decisions_owned` as separate subsections that overlapped with `concerns`. **v0.2 resolution (decision point — flag for review):** keep `concerns` as the primary ownership unit, and make `state` and `decisions` *attributes of a concern* rather than parallel top-level lists. A concern owns some state and some decisions; expressing them under the concern they belong to removes the overlap and keeps related facts together. The cost is that a concern entry becomes larger. The alternative (keep them separate) is retained as an option if review prefers the flatter shape. This is the one genuine modeling fork in v0.2 and is left explicit rather than silently decided.

---

## 4. The claim is the source of truth; the webapp manifest is a projection

(Stated from the DCP side; the frontend side is HLD-D §8A.)

A component has **one self-description with two projections**: the backend facet is the DCP claim; the frontend facet is the webapp manifest. They are not two systems kept in sync — they are one definition with two views.

```text
        Component Self-Description (one source of truth)
                          |
            +-------------+-------------+
            v                           v
     DCP claim (backend)          webapp manifest (frontend)
     capabilities / offers   -->  routes / navigation
     access boundaries       -->  permissions
     negotiation surface     -->  (human-facing config screens)
     conflict positions      -->  (theme contribution: suggest-vs-decide)
```

**Consequence for the schema work:** the DCP claim schema and the webapp manifest schema must be specified *together*, in `unfurl-dcp`, derived from one component-description definition. Specifying the claim in isolation and the manifest later guarantees drift. The claim is primary; the manifest is generated from or validated against it.

---

## 5. Plane 2 — Negotiation and the Composition Contract

This is the piece DCP v0.1 is missing, and the most important addition in v0.2.

### 5.1 The negotiation function

Negotiation is one mechanism regardless of who performs it:

```text
(claim_A, claim_B, context) -> composition_contract
```

It reads two claims (their offers, needs, refusals, and conflict positions), reasons about whether and how they compose, and produces a frozen contract. It runs at design-time (in Fabric) or, for autonomous embedded intelligent components, on first contact. It is never in the runtime path.

### 5.2 The composition contract (the frozen artifact)

The contract is what negotiation produces and invocation consumes. It is the firewall between the two. Conceptually it carries:

- **Parties** — the two claims and their versions, by URI.
- **Binding** — which *need* of one party is satisfied by which *offer* of the other.
- **Data mapping** — how the consumer's input shapes the provider's offer input, and how the provider's output maps back. (This is the same shape as the workflow data-mapping in the substrate resolver — `$.x.y` references — reused here at the component boundary.)
- **Transport** — how invocation happens: in-process call when co-packaged, network RPC (HTTP/JSON default, gRPC when both support it) when separate. Transport is a packaging decision recorded in the contract, not an architecture decision.
- **SLAs / expectations** — what the consumer may assume about the provider (timeouts, idempotency, async-ness).
- **Provenance** — *how this contract was authored* (see §5.5).
- **Versioning** — claim versions pinned, contract version, invalidation triggers (§8).

"Frozen" means: once produced, the contract is immutable and is the canonical execution artifact. It travels with the deployment. Runtime never reaches back to Fabric or a model to reinterpret it.

### 5.2A Resource bindings and named providers

Some dependencies are not component-to-component business calls; they are **named runtime resources** that a host/runtime must bind before execution. Examples include named storage providers, connector providers, state stores, work queues, audit sinks, identity providers, and telemetry exporters.

DCP treats these as contract-bound dependencies:

- The consuming component declares the logical name it requires, the operations it needs, and the guarantees it expects.
- A provider or host/runtime claim offers a resource capability that satisfies those requirements.
- The composition contract freezes the binding from the logical name to the concrete provider offer.
- Runtime uses the frozen binding only. It does not discover, choose, or renegotiate providers in the hot path.

Storage example:

```text
need: reports-store
kind: storage.provider
required operations: put, get, delete
guarantees: customer-resident, durable, version/etag if available
binding: reports-store -> host.storage.customer-reports
```

Connector example:

```text
need: zendesk-prod
kind: connector.provider
required operations: createTicket, updateTicket
guarantees: customer-owned credentials, customer-resident transport path, idempotency key support where available
binding: zendesk-prod -> host.connector.zendesk-prod
```

The workflow or component keeps using the logical name (`reports-store`, `zendesk-prod`). The host/runtime/Fabric binding supplies the concrete implementation (local file, S3, Azure Blob, GCS, MinIO, database, HTTP connector, SDK connector, private API connector, etc.) and owns credentials, residency, lifecycle, and operational guarantees.

### 5.3 The negotiation question schema

Negotiation is structured by a defined set of questions a composition must answer — "does this party own this concern?", "is this an exclusive conflict?", "what owns the refused concern instead?", "is this dependency satisfied?". This schema is defined once in `unfurl-dcp` and rendered two ways:

- As a **human interview** in H2C (Fabric asks the human).
- As a **model prompt** in C2C (the model answers).

Because the questions are identical, H2C answers map directly into C2C training format with no translation. This is the hinge of the training-data flywheel (§5.4) and it connects DCP directly to the refusal-reasoning LoRA experiment: the LoRA learns to answer exactly these questions, and the experiment's (claim, request, disposition, redirection, rationale) tuple *is* an instance of the question schema being answered.

For named resource dependencies, the schema must also answer:

- Does this component require named runtime resources?
- What logical names are required?
- What kind of resource is each name (`storage.provider`, `connector.provider`, `state.store`, `work.queue`, etc.)?
- Which provider offer satisfies each logical name?
- Are the required operations, schemas, versions, SLAs, idempotency expectations, residency constraints, and lifecycle expectations satisfied?
- Who owns credentials and rotation?
- Who owns audit and failure accountability for calls made through the binding?

### 5.3A Action-scoped authoring context

Authoring surfaces frequently begin from a selected UI action rather than a blank composition prompt: add this
component, remove that component, replace a provider, connect two ports, disconnect a pipe, or configure the runtime
substrate. DCP treats that selected action as Plane 2 context, not as a runtime command.

The `action_context` input gives the authoring layer enough grounded structure to ask the right negotiation questions:
selected catalog entry or component id, offered capabilities, required needs, substrate needs, selected ports, target
environment, policy refs, tenant/session identifiers, and correlation id. The output is still ordinary DCP work:
clarification questions, captured answers, proposed composition contracts, runtime bindings, or product intents that
create those artifacts.

This keeps the interaction intuitive without weakening the firewall:

- The UI can ask "what must I know before adding/removing/replacing/connecting this?"
- The authoring agent asks configuration, binding, cleanup, credential-reference, runtime, telemetry, audit, and
  impact questions.
- Accepted answers flow into DCP claims, contracts, and runtime bindings.
- Runtime invocation never sees `action_context` and never re-negotiates.

Adapters that need operator choices should expose enough claim, offer, fault, schema, and runtime-binding metadata for
the authoring layer to ask these questions. They must not hide required configuration behind host-specific UI state.

### 5.4 The three negotiation modes

The answerer is swapped depending on how many parties are intelligent. All three produce an identical contract; invocation cannot tell which mode authored it.

- **C2C (Component to Component)** — both sides intelligent; they (or Fabric's model on their behalf) negotiate autonomously. The thesis-pure case.
- **H2C (Human to Component)** — one side intelligent, one not. The human represents the non-intelligent side, answering the question schema. **Every H2C session is a labeled structural-reasoning training example**, gathered against the dev environment's *integration structure* (not business data). flow, as the seed Intelligent Component, makes most early negotiations H2C and thereby seeds the corpus that makes future components intelligent.
- **H2H** — neither side intelligent; the human drives composition manually, Fabric structures it.

The flywheel must be **federated**: H2C structural examples improve the model locally for that customer by default; only anonymized, consented, scrubbed examples flow to the general model. This preserves the residency wedge — see master doc §7.

### 5.5 Provenance and the trust asymmetry

Every contract records *how it was authored*: by Fabric (which model, which version, when, with a human in the loop for H2C) or autonomously by an embedded model. Invocation ignores provenance; trust and audit use it.

The asymmetry that matters: a contract a component negotiated **about itself** (self-negotiated) is less trustworthy than one a neutral party (Fabric) negotiated, because a component has an incentive to over-claim its own ownership. v0.2 position: self-negotiated contracts may warrant stricter validation than neutrally-negotiated ones. This is flagged as a policy the contract schema must support (a trust level derived from provenance), not yet a fixed rule.

---

### 5.6 Capability documentation is a projection of accepted DCP state

Generated runtime-facing documentation, including OpenAPI, Swagger UI, AsyncAPI, MCP tool documentation, and SDK
metadata, is downstream of the frozen DCP state. It must be generated from accepted composition contracts, active
runtime bindings, host-registered capabilities, explicit request/response schemas, and declared faults.

This is deliberately stricter than implementation discovery. A plugin jar, classpath scan, tool registry, provider
registry, model catalog, RAG/vector store, prompt, log, or implementation DTO may help an authoring agent understand
what could be added, but it is not proof that a capability is accepted, bound, executable, or safe to publish.

The documentation projection keeps Flow, Foundry, Fabric, and third-party adapters aligned:

- Product hosts expose only capabilities that the DCP broker accepted and registered.
- Public/internal/private visibility is a product policy decision over the DCP projection.
- Missing request/response schemas are documentation gaps, not opportunities to infer a public contract from code.
- Faults declared in the claim are the operational errors documented across the boundary.

---

## 6. Plane 3 — Invocation

Invocation executes a frozen contract. No claim parsing, no negotiation, no model — the contract pre-resolved everything.

- When the two components are **co-packaged** (Fabric collapsed them into one service, master doc §9), the contract compiles to an **in-process function call** — memory-speed, no serialization, no egress. This is why the performance concern dissolves in the single-service case: the components that would have talked over DCP are in the same process.
- When the components are **separate**, the same contract executes over the wire (HTTP/JSON default, gRPC when negotiated).

The component's actual business API is what flows here; DCP governs the call (via the contract) but does not define the payload format — that is the component's own API surface, bound by the contract's data mapping.

The isolation discipline (master doc §12) holds even in-process: an in-process invocation is an *optimization of the contract*, not a license to bypass it and reach into another component's internals. CI forbids cross-component internal imports; only the substrate and the contract layer are shared.

---

## 6A. Transport bindings — DCP is transport-agnostic by design

**DCP does not bind to a transport protocol.** This is a deliberate decision, and it follows the same principle as the rest of the architecture: *own the model, not the mechanism.* DCP does not own auth (it consumes the customer's IdP through an OIDC-shaped port), does not own audit (it emits to the customer's sink), does not own telemetry (it exports OTLP to the customer's collector). The DCP **wire transport** is treated the same way — DCP defines *what is exchanged and what it means*, and a *transport binding* defines *how the bytes move* for one mechanism.

Binding DCP to a single transport (e.g. gRPC/protobuf) would be the equivalent of binding the auth port to one specific IdP: it collapses a clean abstraction into one implementation and forfeits the flexibility that is the point. Three reasons it would actively hurt:

1. **The in-process case has no wire at all.** The primary deployment model (co-packaged into one service, master doc §9) compiles the contract to an in-process function call — no serialization, no protobuf, no gRPC. If DCP were *defined* as gRPC, the most important case would be an exception to its own protocol.
2. **The customer's perimeter dictates acceptable transports, and Unfurl does not control it.** Some enterprises mandate a service mesh with mTLS; some are gRPC-hostile and want plain HTTP/JSON through their gateway; some air-gapped environments have approved-protocol lists. Binding DCP to gRPC would make a decision *for* the customer's platform team — exactly the posture the residency wedge avoids everywhere else.
3. **It future-proofs against transports not yet chosen.** A future "DCP over the customer's existing event bus" must not require changing DCP itself. Keep the model stable; let transports come and go beneath it.

DCP therefore commits at **three descending levels of bindingness**:

### Bind hard — the customer-facing semantic standards

Where DCP and the runtime touch the customer's own systems, conform to universal standards. Using a non-standard shape here would create exactly the integration friction the architecture exists to avoid; conforming is pure upside ("a predictable jigsaw piece that slides into their stack"). These are required:

- **W3C Trace Context** (`traceparent` / `tracestate`) for trace propagation — carried by whatever transport moves the call.
- **OTLP + OpenTelemetry Semantic Conventions** for metrics/traces export (e.g. `rpc.server.duration`, `process.cpu.utilization`); do not invent custom metric names.
- **CloudEvents (CNCF)** for the audit event envelope (see HLD-E; this is what makes `unfurl-audit` output natively readable by the customer's SIEM).
- **OAuth 2.0 / OIDC**, asymmetric **JWT (RS256/ES256)** validation against the customer's JWKS, and **RFC 8693 token exchange** for scoping downstream tokens.

These bind to the *edges* (the customer-facing surfaces), not to the DCP wire between Unfurl components.

### Bind soft — a small, named set of supported DCP transports

The contract's `transport.kind` enum (HLD-C2 §B) is the binding point: **`in_process` | `http_json` | `grpc`**. Each has a defined mapping from a contract invocation to that mechanism:

- **`in_process`** — contract compiles to a direct function call (co-packaged; the substrate's composition mechanism). No serialization.
- **`http_json`** — the default network transport; a contract invocation maps to an HTTP request with a JSON body. Works from any language and through any standard API gateway.
- **`grpc`** — an optional network transport for environments that prefer it; a contract invocation maps to a gRPC call. A protobuf schema is *the gRPC transport binding's* serialization, NOT the definition of DCP.

The transport is chosen at **design-time** (recorded in the frozen contract) from the set both parties and the target environment support, and changes nothing about the contract's meaning. New transports can be added to the enum without altering the DCP model.

### Don't bind — the DCP semantic model itself

The claim, the composition contract, the invocation semantics, and the negotiation question schema are defined in terms of *what they mean*, independent of serialization. A claim is a claim whether it is YAML on disk, JSON over HTTP, or a protobuf message on a gRPC channel. This is already how HLD-C and HLD-C2 are written.

### The framing, stated once

> DCP defines **what is exchanged and what it means**. A *transport binding* defines **how the bytes move** for one mechanism. gRPC/protobuf is one transport binding; HTTP/JSON is another; in-process is a third. The customer's environment, at design-time, selects which transport binding a contract uses; the choice is recorded in the contract and changes nothing about the contract's meaning.

**Consequence for the open transport question (and for runtime language):** this resolves it by *deciding not to decide at the protocol level*. Because `http_json` works from any language, the substrate/runtime language stays open; gRPC is an optional transport binding for environments that want it, not a commitment the protocol or the runtime language must make. An external proposal to define DCP *as* a `.proto` file is declined: that protobuf is welcome as the `grpc` transport binding's mapping, but it is not the protocol.

---

## 7. The design-time / runtime firewall (why this preserves the wedge)

Restated from the DCP angle, because it is the reason the protocol is shaped this way:

- **Negotiation (Plane 2) is design-time.** It happens in Fabric, against the dev environment's integration structure. It may use any model. It produces frozen contracts.
- **Invocation (Plane 3) is runtime.** It happens in the customer's perimeter. It runs no model, reaches no vendor server, and executes only frozen contracts. Its only outbound calls are to the customer's own systems (auth, audit, telemetry).
- **The contract is the firewall.** It is authored under intelligence and executed without it.

This is what lets the system be both intelligent and residency-safe: runtime data never meets the reasoning layer because the reasoning happened earlier, elsewhere, on structure rather than data. It is also why the small-model reasoning experiment affects Fabric cost and quality, not whether the wedge works — no model ever runs in the perimeter regardless of the experiment's outcome.

---

## 8. Versioning and invalidation

Three independent version axes:

- **Claim version** — bumps when a component's self-description changes.
- **Capability/offer version** — a capability is a contract; `http.request@1.x` and `@2.x` may differ in shape. Consumers bind by constraint.
- **Contract version** — the negotiated artifact's own version.

A contract pins the claim versions it was negotiated against. **Invalidation** is triggered when a pinned claim version changes, when a new integration pattern is needed the contract doesn't cover, or when runtime detects the contract's assumptions are violated.

**Open question (master doc §17.2):** how aggressive is invalidation? Conservative (re-validate against current claim versions periodically) vs. aggressive (valid until explicit version-bump). And on a runtime contract-assumption violation: hard-fail vs. self-heal by re-negotiation. *v0.2 lean: hard-fail at runtime (preserves the dumb-runtime rule and the residency guarantee — no runtime re-negotiation means no runtime intelligence), and re-negotiate at the next design-time pass in Fabric.* Self-healing at runtime is rejected for the perimeter case because it would reintroduce design-time AI cost into the hot path and breach no-phone-home.

---

## 9. Worked example: flow composes with intelligent-keycloak

A concrete walk-through, to make the planes tangible.

**Setup.** A customer is assembling a deployment in Fabric. They want `unfurl-flow` (the orchestrator) to use `unfurl-intelligent-keycloak` for identity. flow is the seed Intelligent Component; intelligent-keycloak is intelligent too — so this is C2C in principle, but assume the customer's own application is also in the mix as a non-intelligent party, making parts of it H2C.

**Plane 1 (description).** Both publish claims. intelligent-keycloak's claim (from `keycloak-domain-claim-example.md`) asserts ownership of identity verification, session lifecycle, token issuance; refuses authorization decisions, audit storage, non-identity credentials; offers OIDC endpoints, an admin API, an authentication event stream. flow's claim asserts ownership of workflow execution; offers `workflow.execute` (durable); declares a dependency on identity that intelligent-keycloak's offer can satisfy.

**Plane 2 (negotiation, in Fabric).** Fabric reads both claims. The question schema is answered: Does flow need identity? Yes. Does intelligent-keycloak offer it? Yes, via OIDC endpoints. Is there a conflict? intelligent-keycloak claims `user-identity-verification` as **exclusive** — Fabric checks no other claim asserts the same for the same realm; none does, so no blocking conflict. flow wants to emit authentication-related workflow events; intelligent-keycloak refuses audit storage and points (`owned_by`) to an audit component — so Fabric notes that audit is a *separate* binding, not intelligent-keycloak's job. The output is a frozen composition contract: flow's identity-need bound to intelligent-keycloak's OIDC offer, with the data mapping, the transport (in-process if co-packaged, else network), and provenance (negotiated by Fabric vX, H2C for the customer-app parts, on this date).

**Plane 3 (invocation, in the customer's perimeter).** At runtime, flow validates tokens against intelligent-keycloak per the frozen contract. No reasoning happens. If co-packaged, it is an in-process call; if separate, an internal-network OIDC call. The customer's data never leaves the perimeter; Fabric is not contacted.

**If intelligent-keycloak is later upgraded** and its claim version bumps, the pinned contract is invalidated; at the next Fabric pass the contract is re-negotiated and re-frozen. The running deployment is unaffected until the customer re-deploys the new artifact.

---

## 10. Changelog: v0.1 -> v0.2, and folded-in observations

v0.2 incorporates the seven "observations from writing this" appended to `keycloak-domain-claim-example.md`:

1. **Refusals are hardest and most useful** -> Refusals elevated to required, specific, first-class (§3.3).
2. **Dependencies reveal structural truths** -> Dependencies model keeps `satisfied_by` and explicitly allows intelligence-as-a-dependency (§3.4).
3. **Conflict Resolution is where the value lives** -> `claimant_position` vocabulary made central and identified as what Plane 2 operates on (§3.6, §5).
4. **Boundary principles did real work; small models benefit** -> boundary_principles made a required first-class part of the Domain Assertion (§3.2).
5. **state_owned / decisions_owned overlap concerns** -> v0.2 proposes folding state and decisions under the concern they belong to; left as an explicit decision point (§3.6).
6. **Cost implications matter** -> cost_implications retained as a first-class offer field (§3.5).
7. **Writing a claim takes deliberate effort** -> acknowledged; the H2C flywheel (§5.4) is partly a response — Fabric's structured interview lowers the authoring cost and captures the effort as training data.

**Net new in v0.2 (not in v0.1 at all):**

- The three-plane model (§2).
- The composition contract (§5.2) — the artifact v0.1 had no concept of.
- The negotiation question schema and its dual rendering (§5.3).
- The three negotiation modes and the federated flywheel (§5.4).
- Provenance and the self-negotiation trust asymmetry (§5.5).
- The design-time/runtime firewall as an explicit protocol property (§7).
- The claim-as-source-of-truth / manifest-as-projection principle (§4).

---

## 11. Open questions deferred to the schema spec and beyond

1. **state/decisions under concern, or separate?** (§3.6) — the one modeling fork; decide before the schema spec.
2. **Contract invalidation aggressiveness and runtime-violation behavior** (§8) — lean hard-fail + design-time re-negotiation; confirm.
3. **Trust levels from provenance** (§5.5) — how much stricter is self-negotiated validation; needs a concrete rule.
4. **Federated flywheel mechanics** (§5.4) — consent, scrubbing, local-vs-general model contribution; needs design and likely legal input.
5. **Transport negotiation** — the set of supported transports and how two parties pick a mutually-supported one; conceptual here, concrete in the schema spec.
6. **Capability versioning semantics** (§8) — exact constraint-matching rules; concrete in the schema spec.

---

## 12. What comes next

Two documents are downstream of this one:

- **DCP schema specification (`unfurl-dcp`)** — the concrete claim schema, contract schema, webapp-manifest projection schema, and question schema, field by field. Built from the model defined here. The claim and manifest schemas are specified *together* per §4.
- **`unfurl-dcp` repo build spec** — the Claude-Code-ready phased build plan with forbidden imports and acceptance criteria, against the schemas.

The model defined here should be settled (especially the §11 open questions) before either is written, because both encode this model into schema and code.
