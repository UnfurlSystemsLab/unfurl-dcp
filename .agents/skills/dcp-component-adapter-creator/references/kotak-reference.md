# Kotak Neo reference

Locate `unfurl-adapter-kotak-neo` in the workspace, siblings or user-supplied path. Its original location is `C:/Users/vinay/Work/unfurl`; this is a lookup hint, never a required destination. Verify current files before relying on the snapshot below.

Read HLD, Java LLD, INTEGRATION and governance plan under `docs/`. Inspect `pom.xml` and the `kotak-neo-core`, `kotak-neo-http` and `kotak-neo-foundry` modules. Search for `Operation`, `Ports`, `KotakInvocableFactory`, `KotakToolRegistrar`, claim/catalog resources and lifecycle tests.

The reference separates neutral operations and governance ports, upstream HTTP/session adapters, and Foundry runtime projection. Its delivered reads cover holdings, MTF holdings and GTT instruction listing. This is not evidence of order/GTT mutation, automatic credential refresh, an MCP server or live production certification. Verify changes in the current checkout.

Preserve these boundaries for Unfurl integrations:

1. Adapter-produced DCP claim JSON/catalog describe capabilities and governance needs for discovery. Fabric/design-time composition uses them for assembly authoring; the assembly owner creates and freezes/signs consumer-specific contracts and bindings.
2. Runtime uses neutral substrate invocation and explicit host projections, with real broker acceptance and revocation rather than static tool registration bypasses.
3. Configuration/secrets are host-managed references. Trusted scope and authorization come from validated bindings/context, not model-selected tenant/account arguments.
4. Audit, telemetry, authorization, quotas, session and transport concerns have explicit ports and owners. Declaring them is not implementing enforcement.
5. Session expiry, upstream business failure and unknown outcomes are explicit. Read behavior does not prove mutation retry safety or exactly-once execution.
6. Claim/catalog/schemas match operations. Revocation fences both discovery and retained executors.

Reuse current `unfurl-dcp/docs/templates/AGENTS-dcp-adapter.md`, its capability-authoring skill, schemas and substrate interfaces rather than introducing parallel formats.

Do not copy broker endpoints, credentials, customer captures, dependency versions, account assumptions or financial business rules into other adapters. Match the target component's stack and current DCP interfaces; copying Java modules does not establish completeness.
