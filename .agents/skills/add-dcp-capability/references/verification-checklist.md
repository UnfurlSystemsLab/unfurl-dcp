# Verification checklist

## Boundary

- [ ] Selected capability is concrete and versioned.
- [ ] Consumer need and version range are concrete.
- [ ] Owned state and decisions are explicit.
- [ ] Adjacent concerns are explicitly refused.
- [ ] Required and recommended dependencies are distinguished.

## Artifacts

- [ ] Claim loads and passes DCP validation.
- [ ] Consumer need resolves to the provider offer structurally.
- [ ] Composition contract binds to an existing provider capability/version.
- [ ] Frozen contract loads, verifies offline, and round-trips byte-stably when freeze/sign code changed.
- [ ] Catalog metadata points to real implementation artifacts.
- [ ] Runtime binding references the correct contract and party versions.
- [ ] Secrets use references only.
- [ ] Aggregate children use DCP containment metadata.
- [ ] Public documentation metadata identifies schemas, visibility, and capability source.

## Implementation

- [ ] Capability implements `ContractInvocable` or is produced by `ContractInvocableFactory`.
- [ ] Client/host loads frozen contracts into `ContractStore`.
- [ ] Vendor SDK and host-runtime types remain behind adapters.
- [ ] Registration occurs through `CapabilityRegistrar`.
- [ ] Contract, capability, component, correlation, and trace metadata survive translation.
- [ ] Errors map to structured results or declared faults.
- [ ] Generated docs project from accepted/runtime-bound capabilities, not classpath or registry scans.

## Lifecycle and trust

- [ ] Valid contract: capability-specific present, accept, invoke, and revoke succeed.
- [ ] Multi-offer claims are presented with explicit provider capability.
- [ ] Missing contract is refused deterministically.
- [ ] Malformed claim is refused.
- [ ] Invalid or tampered signature is refused offline.
- [ ] Stale disposition fails re-verification.
- [ ] Invalidation unregisters without runtime renegotiation.

## Tests and architecture

- [ ] Repository-native build and tests pass.
- [ ] Claim, composition-contract, and runtime-binding serialization round-trip.
- [ ] Cross-schema validation covers claim, contract, and runtime binding.
- [ ] No inline credential values are accepted.
- [ ] Declared faults and propagation policy are tested.
- [ ] OpenAPI/Swagger/AsyncAPI/MCP docs expose only public capabilities with explicit schemas.
- [ ] Architecture tests reject vendor/host imports in DCP-facing core.
- [ ] Audit events avoid full sensitive payloads and propagate correlation id.

## Handoff

- [ ] Generated artifacts contain no unexplained placeholders.
- [ ] Assumptions and unsupported areas are documented.
- [ ] External keys, contracts, endpoints, and deployment steps are identified.
