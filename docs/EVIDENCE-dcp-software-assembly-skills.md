# Software assembly skill suite verification

## API and hook execution — 19 October 2026

- 42 Python tests pass, including real authenticated loopback HTTP, preflight denial,
  scope/secret/grant rejection, request pins, business GAP versus PASS, metadata-only audit,
  no-replay across restart, failed intent/outcome persistence, actual redirect/body/deadline
  bounds, profile/manifest drift and pinned call-plan handoffs.
- Native Java module `mvn -B -o -f tools/skill-runtime-validation/pom.xml test`: four tests,
  zero failures/errors/skips. Generated 28 Flow definitions, four Foundry agents and four skill
  definitions deserialize into real Java models and pass native structural validators.
- Native connector/result-gate integration uses actual local HTTP. A generated authoring graph
  runs through Flow's real embedded engine; a business GAP stops before the effect call.
- Current Foundry domain artifact was rebuilt/tested locally (28 tests passed) after detecting
  a stale cached model. The standalone validator orders its direct domain dependency before
  Flow's shaded artifact so the intended current domain classes are inspected.

No Foundry model provider or broker connection was invoked. Native model validation and the
controlled Flow smoke test do not establish live deployment, DCP admission, model execution,
supported broker authentication or a completed Kotak application. Generated bindings are requirements
and profile fragments, not installed live grants. Development code tasks stay local Codex handoffs.

## Responsibility revision — 19 October 2026

After the connection/strategy boundary review, the suite passes 28 isolated tooling tests and
all skill metadata, local link, schema and registry structure checks. Added regressions cover
draft/authored graph handoffs without credentials, strict readiness denial for unverified
connections, empty/unresolved binding rejection at validation, SecretRef-only custom-token
metadata, host lifecycle ownership, early undecided choices and separate strategy coverage.
The earlier 85-test Java gate below was run on 18 October; no Java code changed in this revision.
These checks do not establish a working host connection implementation or live graph execution.

Date: 18 October 2026.

## Completed checks

- Skill creator `quick_validate.py`: all four skills passed.
- `python .agents/skills/dcp-software-assembly-generator/scripts/check_suite.py`:
  four skills, invocation metadata, local references, five JSON schemas and registry structure passed.
- `python -m unittest discover -s .agents/skills/dcp-software-assembly-generator/scripts/tests -v`:
  22 tests passed. Runtime/UI combinations are exercised as subcases.
- Registry `query --verify` against the local Kotak adapter: covered `gtt.instructions.list`,
  missing `gtt.instructions.create`. Claim/catalog identity, capability versions/effects,
  six operation schema pins and recorded evidence bytes verified.
- `suite.py install --destination <Codex-home>/skills --replace-local`: all four canonical skills
  synchronized to the user's discoverable skill directory. Installed CLI registry verification passed.
- `mvn -B -o verify`: BUILD SUCCESS, 85 tests, zero failures/errors/skips.

Python validation dependencies were installed in a temporary isolated target, outside Maven and
the protocol runtime. Normal setup uses the development requirements file in the suite scripts.

## What the isolated scenarios establish

Tests cover complete versus partial operation discovery, verified registration of missing
component/governance fixtures, atomic rejection of invalid index updates, operation/schema drift,
packaged version mismatch, required governance and evidence, dependency cycles, uncovered operations,
mutation approval/reconciliation policies, runtime/UI selections, read-only handoff identity and
coverage, resume pin verification, completion evidence, path confinement, duplicate input rejection,
installer conflict preflight, stale managed-file cleanup, preservation of unrelated files and an
installed CLI invocation with both successful and failing inputs.

## Limits

These are deterministic offline tooling scenarios with explicitly synthetic project artifacts.
Their graph/UI/startup records exercise handoff guards; they are not native graph validation,
browser QA or successful application startup. No autonomous end-to-end user interview was executed,
no production environment was provisioned, and no broker operation or other external mutation ran.
Generated projects must still run their real Fabric, Flow/Foundry, UI and startup checks.

The Kotak index references its existing dated verification report. Registry pin checks do not rerun
that adapter's historical tests or certify live API access. The Java gate above tests `unfurl-dcp`.
