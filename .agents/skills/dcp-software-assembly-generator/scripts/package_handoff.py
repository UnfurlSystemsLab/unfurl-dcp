"""Verifier Adapter: canonical child handoffs become pinned Flow coordinator verification inputs.

This read-only utility verifies authoring schema/semantics and file pins. Hosts must additionally
run applicable native/build/startup checks independently; recorded commands are never executed.
"""

import argparse
import json
from pathlib import Path

from jsonschema import ValidationError

import suite


def verify(kind, handoff, workspace, project_id, progress=None):
    """Projector: validate scoped payloads and rebase exact artifact pins into the fenced run root."""
    workspace = Path(workspace).resolve(strict=True)
    handoff = Path(handoff).resolve(strict=True)
    suite.require(handoff.is_relative_to(workspace), "handoff escapes workspace")
    document = suite.load(handoff)
    suite.validate(kind, document, handoff.parent, True)
    suite.require(document["projectId"] == project_id, "handoff projectId differs")
    if kind in {"adapter-result", "graph-result"}:
        suite.require(progress is not None, "child result requires current canonical progress")
        progress = Path(progress).resolve(strict=True)
        suite.require(progress.is_relative_to(workspace), "progress escapes workspace")
        suite.handoff(progress, handoff if kind == "adapter-result" else None,
                      handoff if kind == "graph-result" else None)

    def rebase(reference):
        """Path Strategy: preserve bytes while translating payload-relative references to run-relative paths."""
        path = suite.verify_artifact(handoff.parent, reference)
        suite.require(path.is_relative_to(workspace), "artifact escapes workspace")
        return suite.pin(workspace, path.relative_to(workspace).as_posix(), reference["kind"])

    references = {}
    for reference in suite.artifact_refs(document):
        pinned = rebase(reference)
        previous = references.setdefault(pinned["path"], pinned)
        suite.require(previous == pinned, "conflicting artifact kinds or pins")
    evidence = [rebase(check["report"]) for check in document["checks"]]
    return {"handoff": suite.pin(workspace, handoff.relative_to(workspace).as_posix(), kind),
            "artifacts": list(references.values()), "readiness": document["status"],
            "unresolvedNeeds": document.get("unresolvedNeeds", []),
            "evidence": evidence}


def main():
    """CLI Facade: return only verified references or a nonzero sanitized validation failure."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("assembly-progress", "adapter-result", "graph-result"))
    parser.add_argument("handoff", type=Path)
    parser.add_argument("--workspace", type=Path, required=True)
    parser.add_argument("--project-id", required=True)
    parser.add_argument("--progress", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.kind, args.handoff, args.workspace, args.project_id, args.progress), indent=2))
    except (ValueError, OSError, KeyError, TypeError, AttributeError, ValidationError) as error:
        # Schema violations and malformed handoffs (wrong JSON types) are validation failures too: report the sanitized type,
        # never a traceback.
        parser.exit(1, f"Handoff verification failed: {type(error).__name__}\n")


if __name__ == "__main__":
    main()
