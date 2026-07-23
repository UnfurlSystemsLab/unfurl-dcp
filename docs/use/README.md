# Using `unfurl-dcp`

Practical guide to the Domain Claim Protocol library. For schema and trust design see [HLD-C-dcp-v0.2-internal.md](../HLD-C-dcp-v0.2-internal.md) and [HLD-C2-dcp-schema-spec-updated.md](../HLD-C2-dcp-schema-spec-updated.md). For implementation specifics see [LLD-unfurl-dcp-java.md](../LLD-unfurl-dcp-java.md).

---

## What this library is

`unfurl-dcp` is the runtime side of DCP — claim validation, contract storage, offline signature verification, and the **composition broker** that decides whether a presented claim matches a frozen contract and, if so, exposes the provider's capability on a host `CapabilityRegistry`.

This is **not** the design-time negotiator. Accept / reject reasoning during fabric assembly lives in `unfurl-fabric`. Here, decisions are deterministic against frozen, signed contracts.

```
fabric (design-time)     →  frozen, signed contracts
unfurl-dcp (runtime)     →  validate claim, look up contract, verify signature,
                            expose capability via ContractInvocable
host product (flow)      →  CapabilityRegistrar, ContractInvocableFactory,
                            CapabilityRegistry that the host's engine uses
```

## When to use what

| You are... | You need |
|---|---|
| Authoring or validating a domain claim | `com.unfurl.dcp.claim` |
| Storing or loading frozen contracts | `com.unfurl.dcp.spi.ContractStore` + `com.unfurl.dcp.contract` |
| Verifying signed contracts offline | `com.unfurl.dcp.trust.OfflineContractVerifier` |
| Running the present / accept / revoke lifecycle on a host | `com.unfurl.dcp.broker.CompositionBroker` |
| Loading or rendering DCP documents (claims, contracts, questions) | `com.unfurl.dcp.description` |
| Asking action-scoped authoring questions | `com.unfurl.dcp.questions.ActionContext` + question renderers |
| Projecting Swagger/OpenAPI/AsyncAPI/MCP docs | `com.unfurl.dcp.documentation` |

## Export artifact tiers

DCP consumers should treat export files as three distinct tiers:

| Tier | Purpose | Examples |
|---|---|---|
| Handoff | Small, governed artifacts that another product or deployment step consumes directly. | Root `CompositionContract`, frozen child contracts, signed contract envelopes, runtime bindings. |
| Support | Required companion files that help package or hydrate the handoff without changing the contract model. | Claim bundles, runtime bundles, substrate profiles, trust-key references. |
| Diagnostic | Debug or replay files that explain how an artifact was produced. They are comparable to compiler `.pdb` files: useful for support, not the production contract. | Compiler envelopes, decision audits, response snapshots, trace reports. |

Default export surfaces should stay handoff-first. A product may emit richer diagnostic envelopes, but those files must not
be the only way to understand or verify the DCP contract. Runtime aggregation should remain DCP-native: parent contracts
reference child contracts, and runtime bindings reference child runtime bindings, instead of hiding closure state inside a
product-specific sidecar.

## Install

`unfurl-dcp` is a **single-jar** library (not multi-module). One coordinate, JDK 21:

```xml
<dependency>
  <groupId>com.unfurl.dcp</groupId>
  <artifactId>unfurl-dcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

It transitively brings in `substrate-composition-api` (for `ContractInvocable`) and `substrate-ports` (for `ExecutionContext`). It does **not** pull `substrate-engine`.

---

## 1. Build and validate a claim

A `Claim` is what a component presents to a host. It declares identity, the domain it covers, refusals (things it explicitly does not claim), dependencies, and the capabilities it offers.

```java
import com.unfurl.dcp.claim.*;
import com.unfurl.dcp.validation.SchemaValidationReport;
import java.net.URI;
import java.util.List;

Claim claim = new Claim(
    new Identity(
        URI.create("unfurl://component/billing-stripe"),
        "1.0.0",
        ComponentKind.COMPONENT),
    new DomainAssertion("billing", "charge customers via Stripe"),
    List.of(new Refusal("refunds", "not in scope", null)),
    null,                                        // dependencies
    List.of(new Offer(
        "billing.charge",                         // capability name
        "1.0.0",
        List.of(new OfferInterface(
            InterfaceKind.CONTRACT_INVOCABLE,
            "ContractInvocable",
            Map.of())))),
    null, null, null,                             // conflict / negotiation / ports
    new ClaimMetadata(Map.of("team", "payments")));

SchemaValidationReport report = new ClaimValidator().validate(claim);
if (!report.valid()) {
    report.diagnostics().forEach(d ->
        System.err.println(d.code() + ": " + d.message()));
}
```

`SchemaValidationReport.valid()` is the gate. Diagnostics carry an `ErrorCode` (e.g. `DCP_VERSION_UNSUPPORTED`, `CLAIM_IDENTITY_MISSING`) that the broker maps to a `DispositionReason`.

---

## 2. Store frozen contracts

`FrozenContract` bundles the canonical bytes (what was signed), the `CompositionContract` record (what to interpret), and the `SignedContract` (signature envelope). Implement `ContractStore` to keep them — in memory, in a database, on disk.

```java
import com.unfurl.dcp.spi.ContractStore;
import com.unfurl.dcp.contract.FrozenContract;

public final class InMemoryContractStore implements ContractStore {
    private final Map<String, FrozenContract> byProvider = new ConcurrentHashMap<>();
    private final Map<String, FrozenContract> byId       = new ConcurrentHashMap<>();

    public void put(FrozenContract frozen) {
        var c = frozen.contract();
        byProvider.put(key(c.parties().provider().claimUri(), c.parties().provider().claimVersion()), frozen);
        byId.put(key(c.contractId(), c.contractVersion()), frozen);
    }

    @Override public Optional<FrozenContract> findByProvider(URI uri, String version) {
        return Optional.ofNullable(byProvider.get(key(uri, version)));
    }
    @Override public Optional<FrozenContract> findById(URI id, String version) {
        return Optional.ofNullable(byId.get(key(id, version)));
    }

    private static String key(URI uri, String version) { return uri + "@" + version; }
}
```

---

## 3. Verify a signed contract offline

```java
import com.unfurl.dcp.trust.*;

VerificationKeySet keys = VerificationKeySet.fromPem(/* trusted public keys */);
OfflineContractVerifier verifier = new OfflineContractVerifier();

VerificationResult result = verifier.verify(frozen.signedContract(), keys);
if (!result.valid()) {
    throw new IllegalStateException("contract verification failed: " + result.reason());
}
```

The verifier is **offline** by design — no network, no key-server callouts. Rotate `VerificationKeySet` out-of-band.

---

## 4. Run the broker on a host

The broker is the runtime workhorse. Wire it once at host startup:

```java
import com.unfurl.dcp.broker.*;
import com.unfurl.dcp.spi.NoopBrokerEventSink;

CompositionBroker broker = new DefaultCompositionBroker(
    contractStore,
    new OfflineContractVerifier(),
    keys,
    new ClaimValidator(),
    new NoopBrokerEventSink()); // replace with a real sink for audit
```

### Present a claim → get a Disposition

```java
import com.unfurl.dcp.broker.Disposition;
import com.unfurl.substrate.policy.ExecutionContext;

Disposition disposition = broker.present(claim, ExecutionContext.empty());

switch (disposition.kind()) {
    case ACCEPT -> { /* contract matched; proceed to accept() */ }
    case REFUSE -> System.err.println(
        "refused: " + disposition.reasonCode() + " — " + disposition.rationale());
}
```

Refusal reasons you'll see most: `NO_MATCHING_CONTRACT`, `CLAIM_MALFORMED`, `DCP_VERSION_UNSUPPORTED`, `SIGNATURE_INVALID`.

### Accept → register the capability

The host supplies two SPIs: a `CapabilityRegistrar` that knows how to expose a `ContractInvocable` on its registry, and a `ContractInvocableFactory` that builds the actual invocable from the matched contract + binding.

```java
import com.unfurl.dcp.spi.*;

CapabilityRegistrar registrar = new MyHostRegistrar(myCapabilityRegistry);
ContractInvocableFactory factory = (contract, binding, ctx) -> new MyInvocable(contract, binding);

RegistrationHandle handle = broker.accept(
    disposition,
    registrar,
    factory,
    ExecutionContext.empty());

// handle.stableId(), handle.contractId(), handle.exposedCapabilityNames()
```

Re-verification happens at `accept` — a stale `Disposition` whose contract has been replaced will fail.

### Revoke → unregister

```java
broker.revoke(handle, registrar, ExecutionContext.empty());
```

Use `revoke` on shutdown, on a fabric "withdraw" signal, or when invalidation conditions are met.

---

## 5. Implement the host SPI

Three interfaces are yours to fill in.

### `CapabilityRegistrar`

Bridges the broker to the host's capability registry. In `unfurl-flow` this proxies to a `MutableCapabilityManager` that the flow engine queries.

```java
public final class MyHostRegistrar implements CapabilityRegistrar {
    private final CapabilityRegistry hostRegistry;       // your registry

    @Override
    public void register(String name, ContractInvocable invocable, ExecutionContext ctx) {
        hostRegistry.put(name, new ContractInvocableNodeExecutor(invocable));
    }
    @Override
    public void unregister(String name, ExecutionContext ctx) {
        hostRegistry.remove(name);
    }
}
```

### `ContractInvocableFactory`

Decides how the matched contract becomes an invocable. Direct in-process call, HTTP transport, queue-backed proxy — your choice. The broker hands you the `CompositionContract` and `Binding`.

```java
ContractInvocableFactory factory = (contract, binding, ctx) -> new ContractInvocable() {
    @Override public String contractId()      { return contract.contractId().toString(); }
    @Override public String contractVersion() { return contract.contractVersion(); }
    @Override public ContractInvocationResult invoke(
            ContractInvocation invocation, ExecutionContext context) {
        // route to the real provider per binding.providerCapability()
        return ContractInvocationResult.success(Map.of("ok", true));
    }
};
```

### `BrokerEventSink`

Optional. Receives every broker decision (`CLAIM_PRESENTED`, `DISPOSITION_ACCEPTED`, `DISPOSITION_REFUSED`, `CAPABILITY_REGISTERED`, `CAPABILITY_REVOKED`) for audit. The default `NoopBrokerEventSink` swallows them — fine for tests, replace in production.

---

## 6. End-to-end example

```java
// One-time wiring
InMemoryContractStore store = new InMemoryContractStore();
store.put(loadFrozenContract("billing-stripe-charge-v1.json"));

CompositionBroker broker = new DefaultCompositionBroker(
    store, new OfflineContractVerifier(), keys,
    new ClaimValidator(), new NoopBrokerEventSink());

MyHostRegistry hostRegistry = new MyHostRegistry();
CapabilityRegistrar registrar = new MyHostRegistrar(hostRegistry);

// Per component coming online
Disposition disposition = broker.present(claim, ctx);
if (disposition.kind() != DispositionKind.ACCEPT) {
    return refuse(disposition);
}
RegistrationHandle handle = broker.accept(disposition, registrar,
    (contract, binding, c) -> new StripeChargeInvocable(contract, binding),
    ctx);

// Workflow engine can now resolve "billing.charge" through hostRegistry
// and invoke it through the contract.

// On shutdown:
broker.revoke(handle, registrar, ctx);
```

---

## 7. Loading DCP documents

The `description` package ships codecs for the document shapes — claims, manifests, contracts, questions, runtime bindings. Use them when you receive DCP YAML/JSON over a transport.

```java
import com.unfurl.dcp.description.*;

DescriptionCodec codec = new DescriptionCodec();
Claim claim = codec.fromYaml(yamlBytes, Claim.class);
CompositionContract contract = codec.fromYaml(contractYaml, CompositionContract.class);
```

For programmatic generation of negotiation question views see `com.unfurl.dcp.questions.*` — `InterviewRenderer` and `PromptRenderer` render the same `QuestionSet` two ways with identity-preserving content.

---

## 8. Action-context and capability docs

`ActionContext` is a design-time input for authoring tools. It lets a Studio or agent surface say "the user is adding,
removing, replacing, connecting, disconnecting, or configuring this selected component" so the same DCP question
renderers can ask targeted configuration and impact questions. Captured answers feed normal contracts, runtime bindings,
or explicit product intents. They are not runtime invocation payloads.

Generated runtime-facing docs are a DCP projection too. OpenAPI, Swagger UI, AsyncAPI, MCP tool docs, and SDK metadata
should come from accepted contracts, active runtime bindings, registered capabilities, explicit request/response schemas,
and declared faults. Do not publish docs from classpath scans, plugin jars, raw tool registries, prompts, logs, model
outputs, or DTOs alone.

---

## Surface map

| Package | Purpose | Key types |
|---|---|---|
| `com.unfurl.dcp.claim` | Claim shape + validator | `Claim`, `Identity`, `Offer`, `Refusal`, `ClaimValidator` |
| `com.unfurl.dcp.contract` | Composition contract + invocable adapter | `CompositionContract`, `FrozenContract`, `Binding`, `Parties`, `ContractInvocableAdapter` |
| `com.unfurl.dcp.trust` | Offline signature trust | `OfflineContractVerifier`, `VerificationKeySet`, `SignedContract`, `VerificationResult` |
| `com.unfurl.dcp.broker` | Runtime broker lifecycle | `CompositionBroker`, `DefaultCompositionBroker`, `Disposition`, `DispositionKind`, `DispositionReason`, `RegistrationHandle`, `BrokerEvent` |
| `com.unfurl.dcp.spi` | Host integration interfaces | `ContractStore`, `CapabilityRegistrar`, `ContractInvocableFactory`, `BrokerEventSink` |
| `com.unfurl.dcp.validation` | Schema validation reports | `SchemaValidationReport`, `ErrorCode` |
| `com.unfurl.dcp.manifest` | Component / runtime manifests | `ComponentManifest`, `RuntimeManifest` |
| `com.unfurl.dcp.description` | DCP doc codecs | `DescriptionCodec` |
| `com.unfurl.dcp.questions` | Negotiation question and action-context views | `QuestionSet`, `ActionContext`, `InterviewRenderer`, `PromptRenderer` |
| `com.unfurl.dcp.documentation` | Governed capability documentation projection | `CapabilityDocumentation`, `DocumentationSurface`, `CapabilityDocumentationValidator` |
| `com.unfurl.dcp.runtimebinding` | Runtime binding shapes | `RuntimeBinding`, `BindingResolver` |
| `com.unfurl.dcp.resolver` | DCP-level reference resolution | `DcpReferenceResolver` |
| `com.unfurl.dcp.versioning` | Semver helpers | `VersionRange`, `VersionMatcher` |

---

## What `unfurl-dcp` does *not* do

- It is **not** a runtime workflow engine — it gives a host the ability to register capabilities, but the host runs work. See `unfurl-flow`.
- It is **not** a design-time negotiator — `unfurl-fabric` decides which contracts get frozen.
- It does **not** transport calls — `ContractInvocableFactory` is *your* job; the broker only hands you the matched contract and binding.
- It does **not** persist anything — `ContractStore` is your responsibility.
- It does **not** call out to networked key servers — verification is fully offline.

If you find yourself writing transport code, persistence, or claim authoring intelligence inside `unfurl-dcp`, you're in the wrong repo.
