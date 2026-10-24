---
name: create-dcp-software-package
description: Build a DCP software package through the host-bound Flow coordinator, with specialist handoffs, resumable questions, verified artifacts and precise readiness. Use for the single-entry package-building workflow; report missing host bindings explicitly.
---

# Create DCP Software Package

Own one user-facing conversation while the configured host runs bounded specialist stages.
Read saved run/progress records before interviewing again. Use the existing
[assembly workflow](../dcp-software-assembly-generator/SKILL.md) for the interview and component,
runtime, UI and governance decisions; reuse its adapter and graph specialist handoffs.

Read [coordinator bindings and calls](references/coordinator.md) before admission or resume.
Discover the actually accepted package capabilities and their schemas. A skill is guidance,
not executable infrastructure: absence of a bound coordinator is a scoped prerequisite, not
permission to pretend that local skill handoffs are isolated server execution.

Admit `create_dcp_software_package` with a stable requestKey, safe packageName, approved planRef
and targetRef. Physical paths, workflows, credentials and caller identity do not belong in these
arguments. The host pins the ordered native stage definitions and specialist profiles. Keep the
returned runId and revision; the parent receives compact progress and typed questions, not raw
specialist histories. Do not spawn unrestricted peers or route around admitted host tools.

Poll status and call `software.package.advance` with the current expectedRevision to advance
one bounded stage. Present pending questions without repeating known answers. Route answers to
the matching requestId. An approval requires an actual authenticated host decision tied to exact
intent and expiry; a model-generated `accept` or conversational acknowledgement is insufficient.
Credentials go through the host's secure login surface and SecretRefs.

On a blocker, show the exact missing operation/binding/evidence. Resume only after it is resolved
and the host rechecks pins. After an unknown start call `resume`: the coordinator restarts the stage
only when Flow's store proves it never ran, adopts a single matching execution, and asks for an
explicit executionId when several exist. Never start a replacement coding turn yourself. On
`PACKAGE_STALE_REVISION`, read status and retry with the current revision. Unknown answers require continuation-state reconciliation,
not automatic resubmission. Cancellation is complete only after host-confirmed child containment.

At completion retrieve `software.package.result`. Verify the package/manifest/evidence digests
through the configured artifact surface. Report readiness separately from run completion:
authored artifacts are useful, but runnable requires verified bindings and actual startup checks.
Live trades, production deployment and other external effects require their own authorization.
