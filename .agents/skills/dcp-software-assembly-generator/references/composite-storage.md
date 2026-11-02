# Reusable composites: identity, releases and deployment bindings

Read this when creating, publishing, reusing, packaging or binding a reusable composite. Ordinary adapter authoring
does not require publishing an assembly release. Fabric remains the compiler/storage authority; skill progress,
package manifests and release indexes are different artifacts.

## Discover the implementation

Inspect the selected Fabric checkout/build's `FabricCli` dispatcher, `AssemblyCommand`, `AssemblyStorageService`,
`assemblies/README.md`, `docs/HLD-reusable-composite-dcp-contracts.md`, and
`docs/LLD-composite-artifact-storage.md`. These commands are locally implemented; that does not mean the default
branch, installed JAR, coordinator or a running Studio service contains them. If needed, locate the existing worktree
and verified build. Do not silently run an older launcher or invent API endpoints. Missing support is a release-step
prerequisite, not a reason to stop independent authoring.

The commands below use `fabric` as the project's actual Fabric CLI launcher. This reference does not install one.
Run local commands only within the user's authorized workspace/key scope. In a host-managed pipeline, use its
admitted execution/artifact ports; direct shell calls do not replace missing coordinator bindings. The guarded
`api_calls.py` HTTP runner does not currently expose these storage commands. Do not manufacture operation IDs,
HTTP routes or grants in `api-calls.json` to make them appear available.

## Keep the identities separate

| Meaning | Existing field or artifact |
| --- | --- |
| Stable logical component | Claim `identity.uri`; readable name in `identity.name` |
| Published claim revision | `identity.version` equals `metadata.claim_version` |
| Public capability compatibility | Each `offers[].version`; consumer needs carry compatible ranges |
| Protocol version | `metadata.dcp_version`, unrelated to product/release versions |
| Frozen composition | `contract_id` / `contract_version`; leaf parties pin claim versions and binding pins offer version |
| Immutable assembly release | Fabric signed index's `release_version`, associated with publisher source and exact payload hashes |
| Capability profile | Named `profiles/{profile}.yaml` Fabric needs document, not a `SubstrateProfile` |
| Runtime substrate | Compiled substrate profile and its signed hash relationship |
| Environment binding revision | Separate signed deployment index plus DCP runtime-binding set |

Do not encode every child/product/capability version into the logical URI or directory name. A compatible dependency
upgrade changes pinned artifacts/release; it need not change public offer compatibility. Breaking public behavior
requires appropriate offer/claim versioning, new compilation and binding review. Different selected profiles require
different immutable release versions. The current generated aggregate provider URI is not automatically the publisher
claim URI: the release index associates source with realization without rewriting runtime identity.

## Three storage locations

| Location | Contents and owner |
| --- | --- |
| Publisher workspace: `assemblies/{family}/{name}/` | Author-edited `definition.yaml`, `profiles/{profile}.yaml`, author-owned `tests/` |
| Release repository: `releases/{sha256(logical-uri)}/{release-version}/` | Fabric-signed `manifest.json`, source definition and selected profile, signed compiler envelope, substrate profile, frozen root/child contracts and exact selected JARs |
| Deployment repository: `deployments/{tenant}/{environment}/{sha256(logical-uri)}/{release-version}/{revision}/` | Deployment-owner-signed `manifest.json` and validated `runtime-bindings.yaml` |

Use independently configured repositories outside source. Families are `flow-only`, `foundry-only`, and
`flow-foundry`; the compiled closure must expose the corresponding `workflow.execute` and/or `agent.run` capabilities.
Neither-runtime applications retain their selected host workflow; do not force them into one of these families.
`definition.yaml` is the existing Fabric `claim` + `catalog` manifest, not a new DCP schema. Profile YAML uses existing
Fabric needs conventions. DCP wire fields remain snake_case; do not mechanically rename Fabric needs or extension keys.
The publisher supplies real claims/needs and conformance tests; initialization creates no implementation or test evidence.

## Lifecycle through existing Fabric commands

Use actual project paths and caller-controlled signing keys, never fixture keys. Keep private keys outside source,
released artifacts and portable packages. Compiler, release and deployment signers can differ; trust keys must pin
all signers required by the operation. Package/release names and locator versions must satisfy the target validator.

```text
fabric assembly-init --workspace <publisher-workspace> --family flow-foundry --name <name> --definition <catalog-manifest.yaml> --needs <needs.yaml> --profile default

fabric compile --catalog <catalog-dir> --needs <publisher-workspace>/assemblies/flow-foundry/<name>/profiles/default.yaml --out <build-dir>/compiled.yaml --substrate-profile-out <build-dir>/substrate.yaml
fabric sign --contract <build-dir>/compiled.yaml --key <compiler-private.pem> --public-key <compiler-public.pem> --out <build-dir>/signed.yaml

fabric assembly-publish --source <publisher-workspace>/assemblies/flow-foundry/<name> --profile default --version 1.0.0 --signed-contract <build-dir>/signed.yaml --substrate-profile <build-dir>/substrate.yaml --catalog <catalog-dir> --repository <release-repository> --key <publisher-private.pem> --public-key <publisher-public.pem> --trust-keys <trusted-public-keys-dir>
fabric assembly-verify --repository <release-repository> --uri <logical-uri> --version 1.0.0 --trust-keys <trusted-public-keys-dir>

fabric runtime-bindings --signed-contract <retained-signed-envelope> --out <build-dir>/runtime-bindings.yaml --tenant <tenant> --environment <environment> --flow-base-url <flow-url> --foundry-base-url <foundry-url>
fabric assembly-bind --repository <release-repository> --deployment-repository <deployment-repository> --uri <logical-uri> --version 1.0.0 --tenant <tenant> --environment <environment> --revision r1 --bindings <build-dir>/runtime-bindings.yaml --key <deployment-private.pem> --public-key <deployment-public.pem> --trust-keys <trusted-public-keys-dir>
fabric assembly-verify-binding --repository <release-repository> --deployment-repository <deployment-repository> --uri <logical-uri> --version 1.0.0 --tenant <tenant> --environment <environment> --revision r1 --trust-keys <trusted-public-keys-dir>
```

For reuse, verify the saved release before consuming its retained payloads; do not recompile merely to load it.
Review generated runtime bindings and supply real endpoint/config/SecretRef wiring before binding admission. Defaults
are development conventions, not proof of a connected environment. Binding validation requires exact released contract
coverage, matching environment and DCP validation; deployment activation still belongs to the host/operator.

Publication is immutable local repository admission, not remote promotion or production deployment. A complete identical
retry preserves existing bytes, including randomized signatures. Changed source/profile/artifact/compiler output needs
a new release; changed environment wiring needs a new binding revision. Do not overwrite, repair or silently re-sign
saved artifacts after drift. Preserve the original signed outputs: fresh compilation includes time in generated IDs
and fresh signing can produce different bytes, so source equality alone is not byte-reproducible release evidence.

## Constraints and evidence limits

Required and optional positive constraints reuse `Need.requiredCapabilities` / `optionalCapabilities` and offer-detail
matching. Current storage source parsing rejects unsupported profile fields, structured dependency fields beyond Java
`dependencies.needs`, and `trustPolicyRef` until a pinned policy-input lifecycle exists. Inspect the target revision for
changes rather than assuming HLD-C2 structured required/recommended/forbidden dependencies are implemented.

`refusals` describe responsibility boundaries, not forbidden capabilities. A `read-only`, no-tools or no-RAG profile
name, omitted offer or successfully stored release does not enforce runtime behavior. Such guarantees require explicit
accepted constraints, reachable child/workload/tool validation and owning runtime permission/data-access enforcement.
Do not invent unsupported exclusion fields or advertise guarantees that the validators ignore or reject.

Pin saved release/deployment indexes, retained payloads and verification reports through existing `artifacts` references;
record locators/version/profile/environment/revision in existing progress `decisions`. Use existing `checks` for evidence.
Do not add unrecognized release fields to closed handoff/package schemas. For portable packages, include verified
referenced payloads under package-relative paths without editing signed index contents; preserve private-path policies.
A manifest copied without its indexed payloads is incomplete. Skill install `suite-source.json` hashes and authoring
package manifests are not Fabric signed release indexes.

`assembly-verify` / `assembly-verify-binding` authenticate indexes, byte hashes and contract/binding closure. Their
`verified=true` does not establish endpoint connectivity, secret resolution, workload behavior, accepted runtime
registration, authorization or startup. `suite.py --artifacts` digest checks do not replace these Fabric verifiers.
Report storage verification separately from `authored`, `validated`, `runnable`, and deployed/live evidence.
