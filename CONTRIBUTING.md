# Contributing To unfurl-dcp

Thank you for helping improve `unfurl-dcp`, the Java implementation of the
Domain Claim Protocol.

Protocol behavior is specified in the public DCP specification repository:
[`UnfurlSystemsLab/dcp`](https://github.com/UnfurlSystemsLab/dcp). This
repository implements that protocol as a dependency-light Java library.

## Contribution License

This repository is licensed under the [Apache License 2.0](LICENSE).

Unless you explicitly state otherwise, any contribution intentionally submitted
for inclusion in this repository is submitted under Apache 2.0, matching the
repository license.

Apache 2.0 allows UnfurlSystemsLab and downstream users to use, distribute, and
include this library in commercial offerings while keeping the library itself
open and reusable under Apache 2.0.

## Before Changing Protocol Behavior

If a change affects DCP concepts, schemas, validation rules, runtime binding,
composition contracts, documentation projection, faults, or broker semantics:

1. update or propose the protocol change in
   [`UnfurlSystemsLab/dcp`](https://github.com/UnfurlSystemsLab/dcp);
2. update the relevant HLD, LLD, or build spec in this repository;
3. implement the Java change;
4. update examples, tests, and migration notes as needed.

Do not define new protocol behavior only in Java code.

## Pull Request Checklist

- The change follows `docs/LLD-unfurl-dcp-java.md` and
  `docs/REPO-unfurl-dcp-java-build-spec.md`.
- DCP core remains host-neutral: no product imports, model SDKs, HTTP clients,
  background callbacks, or deployment-specific behavior.
- Tests cover schema, validation, broker, or compatibility behavior touched by
  the change.
- Docs are updated in the same change when behavior changes.
- Sensitive data, credentials, prompts, raw model outputs, and customer
  artifacts are not committed.
