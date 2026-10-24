"""Package-envelope and handoff scenarios use real canonical validators with synthetic project evidence."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import suite
import package_handoff
import test_suite
from jsonschema import ValidationError


class PackageTests(unittest.TestCase):
    """Verifier tests: stage output cannot claim readiness or membership without canonical evidence."""

    def setUp(self):
        """Fixture Factory: reuse the existing progress/artifact builder without duplicating its contract."""
        self.fixture = test_suite.SuiteTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root

    def test_canonical_progress_projection_and_drift(self):
        """A physically pinned canonical handoff is rebased; changed source bytes fail validation."""
        progress = self.fixture.progress()
        path = self.fixture.save("progress.json", progress)
        result = package_handoff.verify("assembly-progress", path, self.root, "fixture-project")
        self.assertEqual("validated", result["readiness"])
        self.assertTrue(result["artifacts"])
        self.assertEqual("assembly-progress", result["handoff"]["kind"])
        (self.root / "contract.json").write_text("changed")
        with self.assertRaisesRegex(ValueError, "artifact drift"):
            package_handoff.verify("assembly-progress", path, self.root, "fixture-project")

    def test_child_requires_requested_scope_and_existing_progress(self):
        """Adapter outputs must match project/request/capability coverage before downstream composition."""
        progress = self.fixture.save("progress.json", self.fixture.progress())
        child = self.fixture.save("adapter.json", self.fixture.adapter())
        with self.assertRaisesRegex(ValueError, "requires current canonical progress"):
            package_handoff.verify("adapter-result", child, self.root, "fixture-project")
        self.assertEqual("validated", package_handoff.verify("adapter-result", child, self.root, "fixture-project", progress)["readiness"])
        with self.assertRaisesRegex(ValueError, "projectId differs"):
            package_handoff.verify("adapter-result", child, self.root, "another-project", progress)

    def test_runnable_needs_startup_and_complete_governance(self):
        """A readiness word cannot substitute for startup checks or required bound services."""
        progress = self.fixture.progress(status="runnable")
        path = self.fixture.save("progress.json", progress)
        with self.assertRaisesRegex(ValueError, "startup"):
            package_handoff.verify("assembly-progress", path, self.root, "fixture-project")
        progress["checks"].append(self.fixture.check_record("startup"))
        self.fixture.save("progress.json", progress)
        self.assertEqual("runnable", package_handoff.verify("assembly-progress", path, self.root, "fixture-project")["readiness"])
        progress["governance"][0]["status"] = "unresolved"
        self.fixture.save("progress.json", progress)
        with self.assertRaises(ValueError):
            package_handoff.verify("assembly-progress", path, self.root, "fixture-project")

    def test_closed_compact_result_and_manifest_contracts(self):
        """Completed package records require archive/evidence, reject transcripts and preserve blocked readiness."""
        report = self.fixture.report
        archive = self.fixture.artifact(".package/package.zip", "synthetic package bytes", "package-archive")
        manifest_ref = self.fixture.artifact(".package/manifest.json", "synthetic manifest bytes", "package-manifest")
        result = {"schemaVersion": 1, "runId": "00000000-0000-4000-8000-000000000001", "state": "completed",
                  "readiness": "authored", "revision": 5, "completedStages": 4, "code": "NONE",
                  "unresolvedNeeds": [], "package": archive, "manifest": manifest_ref, "evidence": [report]}
        suite.validate("package-result", result, self.root, True)
        malformed = {**result, "transcript": "must not enter parent"}
        with self.assertRaises(ValidationError):
            suite.validate("package-run", malformed, self.root)
        malformed = {**result, "readiness": "runnable", "unresolvedNeeds": ["missing binding"]}
        with self.assertRaisesRegex(ValueError, "unresolved needs"):
            suite.validate("package-result", malformed, self.root)
        manifest = {"schemaVersion": 1, "runId": result["runId"], "packageName": "test", "planDigest": "a" * 64,
                    "readiness": "authored", "unresolvedNeeds": [], "artifacts": [report]}
        suite.validate("package-manifest", manifest, self.root, True)
        manifest["artifacts"].append({**report, "path": report["path"].upper()})
        with self.assertRaisesRegex(ValueError, "duplicate package artifact path"):
            suite.validate("package-manifest", manifest, self.root)

    def test_question_and_approval_envelopes_are_correlated_and_closed(self):
        """Interaction requests expose exact intent/expiry and bounded presentation, never raw provider fields."""
        request = {"requestId": "one", "kind": "approval", "purpose": "Approve pinned effect", "options": ["yes", "no"],
                   "intentDigest": "a" * 64, "expiresAt": "2026-10-24T00:00:00Z"}
        suite.validate("package-interaction", request, self.root)
        with self.assertRaises(ValidationError):
            suite.validate("package-interaction", {**request, "accessToken": "forbidden"}, self.root)
        with self.assertRaises(ValidationError):
            suite.validate("package-interaction", {**request, "intentDigest": ""}, self.root)


if __name__ == "__main__":
    unittest.main()
