# Build Spec: Recursive DCP Projection

## Context

DCP needs a protocol-level way for an aggregate claim to refer to the full nested descendant subtree it governs. Fabric Studio can render semantic zoom, but the hierarchy itself belongs in DCP because it describes claim composition, not UI layout.

The target product shape is:

```text
Smart City -> Smart Colony -> Smart Home -> components in a home
```

That means aggregates of aggregates with no fixed level count.

## Ownership

The subtree model belongs in `unfurl-dcp`.

Fabric owns catalog admission, matching, deployment resolution, and Studio-specific rendering adapters. It should ask DCP for the claim subtree projection and then translate that protocol read model into Studio wire records.

## Current DCP API

The first implementation is intentionally backward-compatible:

- child references live in `ClaimMetadata.extensions`;
- supported keys are `contains`, `children`, `containsClaimUris`, and `childClaimUris`;
- children may be URI strings or maps with `claimUri`, `uri`, or `ref`;
- `DcpProjectionProjector` accepts the current claim document and a repository of loaded claims;
- projection output includes nodes, `CONTAINS` edges, descendant claim URI lists, depth, parent claim URI, focus claim URI, and warnings.

## Target

A recursive composition model where any DCP claim may contain child claims to arbitrary depth, expressed as a bounded `CONTAINS` subtree.

The projection API must:

- aggregate all descendants for each projected node;
- retain explicit child edges;
- support a current/focus claim URI;
- cap depth and total node count;
- protect against cycles;
- provide deterministic ordering.

## Fabric Consumption

Fabric synthesizes DCP claim documents from the Studio assembly/catalog read model, calls the DCP projector, and adapts the result to `StudioDynamicDcpProjection`.

The Studio API remains:

```text
GET /studio/tenants/{tenantId}/assemblies/{assemblyId}/dynamic-dcp
```

but its recursive hierarchy now comes from DCP projection semantics.

## Future Schema Hardening

The metadata-extension form is the compatibility bridge. A later DCP schema revision should promote nested child references into first-class claim fields once the shape stabilizes.

Likely future fields:

```text
composition.contains[]
composition.level
composition.kind
```

Until then, the extension keys above are the canonical bridge.

## Verification

- DCP unit tests cover recursive descendants, cycle guards, missing refs, focus fallback, and depth caps.
- Fabric tests cover adapting DCP projection output into Studio nodes with `parentNodeId`, `depth`, and recursive `CONTAINS` edges.
- UI clients receive widened node shape: `level: string`, optional `parentNodeId`, optional `depth`.
