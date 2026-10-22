# Connection providers and trusted service context

## Owners

| Owner | Responsibility |
| --- | --- |
| API adapter | Native API wrappers, schemas, DCP claim/ports, errors and provider-specific auth endpoint adapter |
| Strategy component | Customer rules, calculations and proposals as distinct DCP capabilities |
| Fabric/assembly | Capability selection, contracts and governance/connection bindings |
| Flow/Foundry host | Governed invocation, connection-provider integration and approval orchestration |
| Host-bound connection provider | Secure login, secret resolution, session/token lifecycle and account-scoped context |

Developer app credentials may be configured for the adapter using SecretRefs, not embedded values.
Client credentials originate from a secure user-login surface, are verified with the provider and
stored/resolved by the host. Tools receive a logical connection reference; a trusted port resolves
the service context. Never accept a model-supplied account, role or token as authority.

## Protocol choice

Inspect supported APIs. Use OAuth 2.0 where provided; OIDC authenticates application users but
does not grant access to downstream broker accounts. If a provider supplies proprietary client
token/session APIs, implement a provider-token connection adapter instead of inventing an OAuth
grant. Developer app credentials identify the application, not the customer's trading authority.
Do not assume Kotak implements a protocol from the shape of a captured header.

Declare requirements through existing DCP integration ports and runtime bindings before wiring
concrete provider code. Connection authoring records describe requirements; they do not introduce
a new protocol service-context schema or prove that Flow/Foundry already implements the binding.
Inspect current host ports and return precise product-owner gaps where absent.

## Context and lifecycle requirements

The resolved context must preserve tenant, authenticated principal, provider account, connection
and binding identity, authorized operations, expiry and correlation. Keep token material private
to the transport/connection adapter. Check account/audience/grant before dispatch; isolate refresh
per tenant/account and handle expiry, revocation, logout and reauthentication explicitly. A refresh
success must not trigger automatic replay of an ambiguous mutating API call.

Tests cover missing credentials, wrong principal/account/audience, expired/revoked sessions,
concurrent refresh isolation, provider rejection, secret-free errors and mutation uncertainty.
Controlled connection doubles can validate wrapper behavior without a live login. Label evidence.

## Authoring versus execution

Write the initial graph from requirements and explicit capability needs immediately. Missing
connection implementations, credentials and data freshness remain visible dependencies. Implement
evidenced wrappers and strategy components independently; ask only the questions that block their
contracts. Runtime acceptance, live data access and mutations remain blocked until real bindings,
policy and exact-action approval are present. Structural authoring is not authorization.
