# Adapter patterns

Select the narrowest pattern supported by the third-party application.

## In-process Java

Use when application APIs can be linked safely in the same process.

```text
ContractInvocation -> vendor adapter -> application service API -> ContractInvocationResult
```

Keep vendor models inside the adapter. Translate at the boundary.

## HTTP

Use when the application exposes a stable HTTP API.

```text
ContractInvocation -> HTTP ContractInvocable -> remote endpoint
HTTP response/error -> declared result/fault
```

Take endpoints, timeouts, and credential references from runtime binding. Do not freeze secrets into contracts or claims.

## Queue or event broker

Use for asynchronous work. Preserve correlation id, contract identity, capability name, trace context, and idempotency metadata. Define timeout and delivery failures as declared faults.

## CLI or child process

Use only when no stable library or service API exists. Make command construction deterministic, constrain accepted inputs, capture exit status and bounded output, and avoid secrets in command-line arguments.

## Function or serverless target

Treat the function invocation SDK or endpoint as a transport behind `ContractInvocable`. Deployment provider types must not leak into the claim or neutral invocation interface.

## Host adapters

Translate an accepted `ContractInvocable` to the host-native surface:

- Flow: `ContractInvocation` to `NodeExecutionRequest`/`NodeExecutionResult` through the Flow-owned DCP adapter
- HTTP host: invocation to request/response handler
- Queue host: invocation to consumer/producer envelope
- Function host: invocation to function handler

Preserve contract and trace metadata in both directions. Never bypass a frozen binding to call product internals directly.

## Client-side wiring

Use this pattern when the repository owns the runtime that consumes a provider capability:

```text
frozen contract + runtime binding -> ContractStore
provider claim + provider capability -> broker.present(...)
accepted disposition -> broker.accept(..., CapabilityRegistrar, ContractInvocableFactory, ...)
host registry -> accepted ContractInvocable -> native runtime call
```

Do not let the client call provider HTTP endpoints, SDKs, or product internals directly when a DCP contract binding exists.
Runtime endpoint and credential choices come from runtime binding, not claim or contract.

## Module layout

A typical integration may use:

```text
adapter-dcp/       claim, contract/routing metadata, catalog, neutral invocation adapter
adapter-vendor/    vendor SDK or application-specific implementation
adapter-client/    ContractStore/runtime-binding loader and broker bootstrap
adapter-host/      optional host-native execution bridge
```

Adapt this layout to the repository's existing module conventions; preserve the dependency direction even when modules are combined.
