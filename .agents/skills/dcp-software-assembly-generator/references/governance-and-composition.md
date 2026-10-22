# Governance interview and composition

Review each concern explicitly. Ask which existing systems the user wants to reuse before
recommending products. Show available options derived from actual claims, source and registry:

| Choice | Explain and verify |
| --- | --- |
| Existing component implementation | Enforcement code, operation coverage, owner and actual binding |
| Customer/host service | Port contract, access model, tenant scope, provisioning and evidence |
| Existing adapter | Compatible capability version, dependencies, validation and deployment needs |
| New adapter | Upstream system, evidence/API access and bounded missing integration work |

If recommending additional open-source systems, check current primary documentation and explain
operational requirements. Do not invent available products or silently provision one. Building a
new governance product is distinct from adapting an existing system.

## Concerns

- Authentication: principal trust boundary, service versus end-user identity, session lifecycle.
- Authorization: tenant/account/operation grants, least privilege and fail-closed behavior.
- Secrets: host references, resolution/rotation, downstream credential/session ownership.
- Policy: data/effect restrictions, permitted environments and runtime enforcement.
- Approvals: exact action intent, validity, rejection and unknown-outcome reconciliation.
- Audit: metadata policy, durability, retention, access and mandatory-write failure behavior.
- Monitoring: telemetry/traces, correlation, redaction, exporters and actionable failure signals.
- Quotas: API limits, concurrency and per-tenant resource bounds.
- Costs: provider/model costs where applicable, budget ownership and enforcement.

Record each as required or justified not-applicable. A required concern is complete only when
implemented and bound with evidence. An adapter declaring an audit sink does not provide a durable
audit service; an IdP authenticating users does not establish downstream account authorization.
Never ask for raw credential values. Customer-controlled dependencies remain external runtime
bindings and must still be included in packaging prerequisites.

## Fabric source map

Read current `unfurl-fabric/docs/HLD-unfurl-fabric.md`, build spec and Studio API LLD.
Inspect CLI help or tools before invoking them. Fabric supports discovery/matching, deployment
resolution, compilation, signing and verification; the source is authoritative for arguments.
Its current handoff is a signed root DCP contract plus substrate profile and applicable support
artifacts, not a privately invented assembly document. The suite's progress JSON records authoring
state only. Ambiguous valid composition candidates require a deliberate user/policy selection.

No supplied signing key means a blocked signing prerequisite. Never use fixture keys as project
trust or fabricate an accepted runtime registration. Invalidate derived compilation/graph outputs
after relevant adapter, contract, binding or governance drift.
