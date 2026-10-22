# Calling authoring APIs and running hooks

Use `scripts/api_calls.py` for calls listed in [api-calls.json](../assets/api-calls.json).
This development-time runner is separate from the offline `suite.py` validators. Its host
profile must be operator-controlled and its SHA-256 pinned by the caller. Possession of a
profile file is not a substitute for real upstream authentication or DCP authorization.

## Setup

Use the suite's isolated Python dependencies. Do not paste secrets into chat, arguments or
profiles. The host provisions environment-backed SecretRefs outside model context. The gateway
secret is distinct from any Fabric/Flow authentication credential. Start with read grants only;
add state-changing operations within the user's authorized project scope.

```text
python api_calls.py init-profile <private-dir>/authoring-profile.json --tenant <tenant> --principal <principal> --fabric-origin <fabric-origin> --flow-origin <flow-origin> --grant fabric.catalog-query --gateway-secret-ref secret://env/UNFURL_AUTHORING_GATEWAY_TOKEN
python api_calls.py --profile <profile.json> --profile-sha256 <returned-sha256> call fabric.catalog-query --call-id <unique-id> --arguments <arguments.json>
python api_calls.py --profile <profile.json> --profile-sha256 <returned-sha256> status <call-id>
python api_calls.py --profile <profile.json> --profile-sha256 <returned-sha256> serve --port 8788
```

Add `--fabric-auth-ref`/`--flow-auth-ref` during initialization when host ingress requires an
Authorization credential. These accept SecretRefs, never credential values. Origins must be
HTTPS or explicit loopback HTTP. Initialization refuses overwriting an existing profile.
Do not place the profile beside raw captures or commit the call journal to the repository.

## Hooks and outcomes

Before dispatch, the runner checks grants, trusted tenant/principal consistency, input schema,
secret-bearing fields, endpoint/credential configuration and request limits. It freezes request
bytes and persists intent in a full-synchronous SQLite transaction. The physical transport uses
one bounded exchange and follows no redirects. It does not automatically retry.

Afterwards it validates the envelope and tool/business status, redacts nested secret fields and
resolved credential values, and persists metadata-only outcome evidence. `success: true` plus
`output.status: GAP` remains GAP. Tool-level schema/DTO validation is still performed by the
real Fabric/Flow API: the runner's operation schemas validate the common envelope and tenant,
not every downstream business rule. Model proposals remain advisory.

The journal binds call id to profile/operation/arguments digest. Reuse never dispatches a second
time. A write timeout or crash after DISPATCHING is OUTCOME_UNKNOWN; inspect/reconcile actual
service state. A new call id is not permission to blindly replay an ambiguous state change.
The runner never executes recorded evidence commands or exposes GTT/broker writes by default.

## Native Flow integration

Generate resources with
`python scripts/runtime_bundle.py <empty-directory> --tenant <tenant> --model-ref <model-ref>`.
Each authoring operation has a native Flow subgraph:

`connector.request /preflight -> tool.result.gate -> connector.request /calls -> tool.result.gate`.

Preflight is a diagnostic, not a reusable grant; dispatch repeats the mandatory checks. Bind
`authoring-guarded` to the gateway using the concrete `AuthoringConnectorTransport` in the
canonical repo's `tools/skill-runtime-validation` module. It implements Flow's existing port,
pins tenant/origin, resolves a host `SecretRef` named `token`, refuses caller headers/paths and
never marks a transport failure retryable. Use a real host secret resolver; do not copy bearer
values into node config. Host code injects this transport and connector definitions into the
existing `ProfileLoader` constructor. The generator does not silently register a connector.

The generated Foundry resources include `.agent.yaml` files, a tenant-scoped runtime skill
envelope, prompts, per-agent toolPolicy and a profile fragment for the existing file approval
store/default policy chain. Merge the fragment into an admitted host profile deliberately;
tool/model/prompt/audit bindings still have to resolve. Profile fragments are not complete
runtime profiles. Read-only reasoning agents cannot directly register graphs or place trades.

The Kotak application graphs are native drafts with explicit missing capabilities. Native
structural validation does not prove registry resolution, supported MAP input/output mapping,
live login, model availability, user approval or broker submission. Check binding-requirements
and business needs before activation. Code-generation gaps return to local Codex, not an
invented server-side development tool.
