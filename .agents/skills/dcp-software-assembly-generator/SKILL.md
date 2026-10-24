---
name: dcp-software-assembly-generator
description: Interview users and build runnable software assemblies from Domain Claim Protocol capabilities. Use for end-to-end component discovery, missing-adapter creation, governance choices, Fabric composition, optional Flow/Foundry graphs and UI, and project validation.
---

# DCP Software Assembly Generator

Coordinate the complete software-building journey. DCP declares capabilities and governance;
Fabric composes them; product runtimes execute accepted bindings. Do not become a parallel compiler.

For a configured host-managed pipeline, use the single-entry
[create-dcp-software-package facade](../create-dcp-software-package/SKILL.md). Its Flow coordinator
handles durable isolated stages; this workflow remains the reusable interview/assembly specialist.

## Execute APIs with hooks

Read [api-execution.md](references/api-execution.md) when calling Fabric/Flow APIs. Use the
implemented `api_calls.py` runner and pinned host profile for source-grounded operations in
the call manifest. Do not bypass its scope/schema/intent/result/redaction/outcome checks with
an ungoverned HTTP call. CLI and optional loopback gateway use the same implementation.
Missing credentials block actual calls, not graph/adapter development. Runtime host/adapter
authorization is still mandatory and cannot be replaced by these development-time hooks.

## Start or resume

Read [handoffs.md](references/handoffs.md) for progress and child-result contracts and the CLI.
Read existing progress before asking questions. Verify recorded artifact hashes on resume; stop
dependent work on drift and revalidate/recompose explicitly rather than silently replacing pins.
Read repository AGENTS.md and governing HLD/LLD/build specs before implementing the project.

Ask two or three relevant questions at a time, skipping known answers:

1. Which core application/component and version should we start with?
2. Which user flow and outcomes should we target first? For Kotak Neo, distinguish GTT listing
   from creating, changing or cancelling an instruction; the latter are different operations.
3. Who uses the software, in which environment, and with which required acceptance examples?

Then ask about backend-only, reusable Unfurl UI or custom UI, and Flow, Foundry, both or neither.
Save decisions and exact required operations, including approved scope of local/live validation.
Do not require every infrastructure answer before starting independent discovery.

Draft the end-to-end capability graph now, before collecting a complete customer dataset or
establishing live connections. Identify API-wrapper, strategy, connection-provider and host
responsibilities separately. Refine the graph alongside adapter/governance work; unresolved
needs block activation, not independent authoring. Do not repeatedly ask for more captures
when the API contract is sufficient to implement and test a wrapper.

## Discover and create adapters

Locate the canonical registry through the installed `suite-source.json` marker or this checkout's
`adapters/adapter-registry.json`. Use CLI query by domain and required operations. Verify candidate
artifacts, current claims, capability versions, refusals and dependencies before selection.
An entry is not authorization, Fabric admission, or proof that a whole flow is supported.
Also inspect the existing Fabric catalog and component/workspace artifacts before declaring
support absent. Register inspected existing adapters through verified upsert rather than
generating a duplicate merely because the shared index has not listed them yet.

If support is complete, reuse it. If partial, request only the missing capabilities. If absent,
invoke [dcp-component-adapter-creator](../dcp-component-adapter-creator/SKILL.md), giving the
project/request identity, exact flow and missing operations, known evidence and governance
requirements. This means applying the child skill within the current task; it does not authorize
spawning agents. Respect any requested design-only boundary.

Route each gap by ownership before invoking the adapter creator: an existing application's API
feature needs a wrapper; a customer business rule needs an assembly-owned strategy component,
implemented using [add-dcp-capability](../add-dcp-capability/SKILL.md); a connection gap needs
a provider-specific connection adapter and host binding. Do not route every missing strategy
operation into the vendor adapter or pretend the wrapped application already implements it.

Validate the returned adapter result and pinned artifacts. Record limitations and remaining host
bindings, update selected versions, then resume this workflow without repeating answered questions.
Use the same path for business and governance components; do not invent adapter implementations.

## Resolve governance and dependencies

Keep business rules in separate DCP strategy components, recorded as `strategyComponents`,
not inside vendor wrappers. For Kotak, holding-age eligibility, 52-week price formulas,
net-return checks and proposal creation are strategy capabilities. Broker API wrappers expose
data/actions; they do not hardcode this particular investment strategy.

Read [governance-and-composition.md](references/governance-and-composition.md). For each concern,
show what is implemented and bound versus merely declared. Present grounded existing, customer,
registry and new-adapter choices with ownership and operational tradeoffs. Ask the user to select
among materially different options; do not select a vendor based on familiarity.

Resolve needs recursively using stable versioned identities and dependency edges. Track active
and completed dependencies; report cycles and unresolved needs instead of repeating generation.
External host/customer systems become explicit bindings, not fabricated peer components.

## Compose and build

Use the existing Fabric tools/CLI and validators discovered in the target checkout. Import verified
artifacts into its catalog, author needs, resolve candidates, compile, sign with authorized keys and
verify. Keep root contracts, profile and diagnostics in their actual documented roles. Missing
keys, product APIs or required services block completion; do not forge signed outputs.

They block acceptance/execution rather than drafting graphs or implementing independent wrappers.
Read [connections.md](references/connections.md) before designing login/session wiring. Use
host-bound connection providers for lifecycle, including provider-token APIs where OAuth is
unavailable. OIDC user login alone does not establish broker-account permission.

If runtimes were selected, invoke [dcp-runtime-graph-generator](../dcp-runtime-graph-generator/SKILL.md)
with the available assembly artifact references, declared needs plus accepted inventory where
available, governance/connection decisions, required flow, runtime/UI choices and tests. Invoke
it during authoring even when live bindings are missing. Validate its result and ensure selections
match progress; an `authored` result is not a runnable assembly or accepted registration.
For neither runtime, implement the selected host's accepted invocation path without generating graphs.

Implement the chosen UI using existing Unfurl UI libraries where applicable, or a custom UI when
selected. Ask for the required screens/interactions; adapters own backend capabilities, not reusable
UI infrastructure. Read current UI design and integration contracts first. Never expose secrets
or treat client-provided scope as authority.

Package a real application with exact adapter versions/digests, verified Fabric artifacts,
runtime bindings, selected UI, startup/shutdown instructions and test evidence. Reuse current
deployment emitters and scripts instead of inventing product APIs. No unrelated product API
implementation, publishing, production provisioning or live mutation is implied by this workflow.

## Acceptance

Run project-specific schema, lifecycle, governance, graph, UI and startup checks. Inspect a real
local smoke result when dependencies are available. CLI validation checks authoring consistency;
it does not replace native validators or prove software behavior. Keep status blocked if required
services, credentials, keys, operations or evidence are missing. Report exactly what passed and
what remains; never present synthetic fixtures or unstarted software as a live runnable result.
