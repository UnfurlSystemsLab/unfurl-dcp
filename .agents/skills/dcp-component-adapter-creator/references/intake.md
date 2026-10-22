# Staged interview and deliverables

Ask a small batch at a time. Skip answers already supplied or discoverable. Offer choices without changing user scope. Never treat elapsed time as an answer.

## First round

- Which component/version and API documentation, source/SDK or sanitized captures should be used?
- Which initial user journey and exact operations matter? Separate reads, writes, bulk actions, streaming and long-running work.
- Which DCP provider capabilities and consumer needs are in scope? Design-only or implementation? Existing repository or a new location?

## After inspecting the evidence

- Which host/runtime, language, transport and deployment model must it integrate with? Should accepted capabilities be projected as host tools?
- Single-user or multi-tenant? Who owns authentication, account grants, sessions/refresh, secrets and configuration references?
- Which existing audit, telemetry, quota and cost services should ports bind to? Recommend alternatives only for missing needs; model/token costs matter only if models are actually used.
- Is a sandbox available and which live effects are authorized? Ask for secret reference names or provisioning instructions, never secret values in chat.

## Operation-specific questions

Reads: freshness, paging, bounds, scope and sensitive fields.

Writes: exact approval, risk limits, idempotency behavior, status lookup, reconciliation and cancellation. Browser success does not establish safe retry or stable supported access.

Events/jobs: cursor/state ownership, retention, deduplication, resume and recovery.

Private APIs: supported integration path, version stability and production authentication design. A captured session cookie is not that design.

## Design evidence matrix

Maintain one row per operation covering:

| Concern | Record |
| --- | --- |
| Identity | Capability/tool name, version, owner and consumer need |
| Evidence | Source path/URL, upstream version, documented versus observed |
| Contract | Method/SDK call, input/output schemas, units, decimals and null behavior |
| Authority | Principal, tenant/account scope, grants, config/secret references |
| Effects | Read/write, approval, idempotency and unknown-outcome handling |
| Execution | Bounds, paging, deadlines, quotas, retries and cancellation |
| Failure | Transport/business distinction, declared faults and redaction |
| Projection | DCP offer, consumer need, binding and optional host tool schema |
| Verification | Tests, fixtures, sandbox/live evidence and remaining gaps |

## Expected outputs

Deliver design documents, operation evidence matrix, working code, configuration/integration instructions and meaningful tests. Reuse existing doc names; do not generate empty documents to match a template.

DCP: claim JSON, catalog, schemas, declared faults/dependencies/ports, neutral invocation, factory when needed, explicit host projection and validated binding examples. Contract examples must be marked illustrative unless the repository actually owns their freeze/sign output.

When a host tool projection is selected, reuse the same capability implementation and preserve accepted-contract visibility, execution, schema agreement and revocation across the projection.
