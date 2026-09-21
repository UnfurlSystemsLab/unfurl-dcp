# Domain Claim Protocol (DCP)

The **Domain Claim Protocol (DCP)** is a machine-readable semantic specification for describing, validating, composing, and governing software components in autonomous, agentic AI ecosystems.

Components declare their capabilities, boundaries, dependencies, and faults. These declarations give developers a structural basis for a visual **human harness**, where people can inspect and question proposed multi-agent workflows before they run.

## 🛑 The problem: agent spaghetti and black boxes

When agents chain APIs, code execution, and other agents, the resulting workflow can be difficult to review. Data schemas describe types, but they do not by themselves explain a component's intent, ownership, operational boundaries, or failure behavior. Without explicit domain contracts, systems risk silent failures, context drift, and actions outside their intended scope.

## 🛡️ The solution: semantic component governance

DCP gives each component a structured claim about what it owns, offers, needs, and refuses. Claims can be validated and composed into frozen contracts. Runtime bindings connect those contracts to an environment, while declared faults make failures and their permitted responses visible. Signed contracts can be verified offline for integrity; host policy determines whether to trust and activate them.

This supports deterministic enforcement around probabilistic machine intelligence. Negotiation happens at design time; invocation runs against an accepted contract.

## ⚡ Quick example: a domain claim

This abbreviated YAML illustrates the current claim vocabulary. See the [claim schema](docs/HLD-C2-dcp-schema-spec-updated.md) for required fields and validation rules before authoring a claim.

```yaml
identity:
  uri: "urn:example:financial-ledger-agent"
  name: "Financial Ledger Agent"
  kind: component
  version: 1.0.0
  publisher: "Example Organization"
domain:
  summary: "Reconciles ledger transactions against verified bank feeds."
  concerns:
    - concern: ledger-reconciliation
      description: "Compares ledger entries with verified feed entries."
  boundary_principles:
    - "Reconciliation requires a verified bank feed."
refusals:
  - concern: unverified-feed-reconciliation
    rationale: "Unverified sources cannot establish a trustworthy match."
offers:
  - capability: ledger-reconcile
    description: "Reconciles ledger entries against a verified feed."
    consumer_access: named_components_only
    interface:
      kind: in_process
      details: {}
    stability: experimental
    version: 1.0.0
faults:
  emitted:
    - code: ledger.unverified_source
      category: dependency
      severity: blocking
      description: "The supplied bank feed could not be verified."
      affects:
        needs: []
        offers: [ledger-reconcile]
        constraints: []
      evidence:
        signals: [policy_denial]
      propagation:
        parent_impact: blocked
        propagates_when: "The verified-feed requirement is unmet."
      remediation:
        allowed_actions: [halt_and_signal_human]
metadata:
  dcp_version: 0.2.0
  claim_version: 1.0.0
  effective_from: "2026-01-01T00:00:00Z"
```

The example omits other required sections and is **not** a complete, validated claim. Operational limits and runtime recovery behavior must be expressed through the relevant claim, contract, policy, and binding fields; they are not implied by this illustration.

## 🗺️ Ecosystem topology

Unfurl Systems is organized into separate layers:

- **`unfurl-dcp` (this repository):** The dependency-light Java protocol library with claim and contract models, validators, runtime bindings, and composition interfaces.
- **`unfurl-fabric`:** Design-time negotiation, dependency resolution, and compilation of claims into governed compositions.
- **`unfurl-ui`:** The developer-facing visual workspace for inspecting and reviewing assemblies.

## 🛠️ Project status and contributing

DCP's specification and Java implementation are under active development. Architectural feedback and real failure cases from multi-agent systems are welcome.

- **Review the schemas:** Start with the [schema specification](docs/HLD-C2-dcp-schema-spec-updated.md) and the design documents below.
- **Submit an edge case:** Open an issue describing a production failure mode and the boundary or fault DCP should express.
- **Discuss the protocol:** Share proposals and questions through repository issues and discussions where available.
- **Contribute code or documentation:** Read [CONTRIBUTING.md](CONTRIBUTING.md) first.

## Docs

- [DCP model](docs/HLD-C-dcp-v0.2-internal.md)
- [Schema specification](docs/HLD-C2-dcp-schema-spec-updated.md)
- [Java low-level design](docs/LLD-unfurl-dcp-java.md)
- [Java build specification](docs/REPO-unfurl-dcp-java-build-spec.md)
- [Recursive projection build specification](docs/REPO-recursive-dcp-projection-build-spec.md)
- [Security](docs/SECURITY.md) and [reliability](docs/RELIABILITY.md)

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
