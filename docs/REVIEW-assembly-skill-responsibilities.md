# Assembly skill responsibility review — 19 October 2026

## Findings and fixes

| Gap | Correction |
| --- | --- |
| Graph generation waited for accepted bindings/live data | Author initial graph from explicit needs; refine incrementally; keep execution gated |
| Captures became a demand for complete customer datasets | Use captures to establish API wrapper contracts; unknown coverage affects execution evidence |
| Strategy ownership was implicit | Separate DCP strategy components from broker API adapters; validate their artifact pins and coverage |
| Connection lifecycle and custom auth were unspecified | Host-bound connection provider, provider-token wrappers, trusted scoped context and SecretRefs |
| Developer credentials could be mistaken for account authority | Secure client login/grants establish account authority; app credentials alone do not |
| OIDC and downstream authorization could be conflated | Record app identity separately from supported provider authorization/token protocol |
| Graph bindings were mandatory even for drafts | Permit empty draft/authored bindings; require actual bindings and resolved needs for validation/readiness |
| Interview schema forced premature runtime/UI choices | Permit undecided while interviewing; reject it at completion |

## Kotak acceptance scenario

Author this capability-level structure before demanding complete transaction samples:

Portfolio read -> acquisition history -> strategy eligibility -> 52-week market data ->
strategy target/charge/tax estimates -> exception review -> exact-action approval ->
GTT API wrapper -> outcome verification.

This is a responsibility sketch, not an executable Flow/Foundry definition. Native graph
generation uses the actual runtime models. Broker reads/writes and auth endpoints belong in
the API/connection adapters. User-specific age, price-offset and return rules belong in strategy.
Unbound data, tax or connection services remain visible needs. No synthetic accepted registration
or live trading authorization is created by an authored graph.

## Validation boundary

Regression scenarios cover authoring without live credentials, rejection of false readiness,
SecretRef-only custom token requirements, host lifecycle ownership, undecided interview choices,
separate strategy capability coverage and structural evidence for authored status. These test
the authoring guards, not a real OAuth/token provider or a native graph runtime deployment.
