"""Isolated authoring scenarios: synthetic artifacts test guards, never native runtime certification."""

from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import suite
from jsonschema import ValidationError


class SuiteTests(unittest.TestCase):
    """Scenario Fixture: exercise file handoffs, coverage and installation in temporary projects."""

    def setUp(self):
        """Fixture Factory: create isolated synthetic evidence with no upstream effects or credentials."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.cap = {"name": "fixture.read", "version": "1.0.0", "effect": "read"}
        self.report = self.artifact("report.txt", "Synthetic authoring fixture; no runtime executed.", "evidence")
        self.claim = self.artifact("claim.json", json.dumps({"identity": {"uri": "urn:fixture", "version": "1.0.0"},
            "offers": [{"capability": "fixture.read", "version": "1.0.0", "offer_interface": {
                "details": {"effect": "READ_ONLY", "input_schema": "input.json", "output_schema": "output.json"}}}]}), "claim")
        self.catalog = self.artifact("catalog.yaml", json.dumps({"claim": suite.load(self.root / "claim.json")}), "catalog")
        self.schemas = [self.artifact(name, '{"type":"object"}', "operation-schema") for name in ("input.json", "output.json")]
        self.selected = {"id": "urn:fixture", "version": "1.0.0", "capabilities": [self.cap], "artifacts": [self.claim, self.catalog, *self.schemas]}
        self.binding = self.artifact("binding.json", '{"fixture":true}', "runtime-binding")
        self.entry = {"id": "urn:fixture", "version": "1.0.0", "domains": ["testing", "governance"],
            "source": {"repository": "urn:fixture:source", "path": "."}, "claim": self.claim, "catalog": self.catalog,
            "artifacts": self.schemas, "capabilities": [self.cap], "validation": [self.check_record("local")], "limitations": ["Synthetic only"]}
        self.registry = {"schemaVersion": 1, "revision": 1, "adapters": [self.entry]}

    def artifact(self, name, content, kind):
        """Builder: write a synthetic fixture file and pin its exact bytes."""
        (self.root / name).parent.mkdir(parents=True, exist_ok=True)
        (self.root / name).write_text(content, encoding="utf-8")
        return suite.pin(self.root, name, kind)

    def check_record(self, level):
        """Builder: label test-only evidence without claiming any real provider execution."""
        return {"level": level, "outcome": "pass", "command": "synthetic fixture scenario", "report": self.report}

    def progress(self, runtime="none", ui="backend-only", status="validated"):
        """Builder: create a complete synthetic project handoff for guard validation only."""
        artifacts = [self.artifact("contract.json", '{"fixture":true}', "contract"), self.binding,
                     self.artifact("start.txt", "Synthetic startup descriptor", "startup")]
        for kind in {"none": [], "flow": ["workflow"], "foundry": ["agent"], "both": ["workflow", "agent"]}[runtime]:
            artifacts.append(self.artifact(kind + ".json", '{"fixture":true}', kind))
        if ui != "backend-only":
            artifacts.append(self.artifact("ui.html", "<p>Synthetic fixture UI</p>", "ui"))
        governance = [{"concern": concern, "required": concern in {"authentication", "authorization", "secrets"},
            "owner": "fixture-host", "status": "bound" if concern in {"authentication", "authorization", "secrets"} else "not-applicable",
            "rationale": "Synthetic guard fixture, not a production decision", "bindings": [self.binding], "evidence": [self.report]}
            for concern in sorted(suite.GOVERNANCE)]
        return {"schemaVersion": 1, "projectId": "fixture-project", "component": "fixture", "flow": "read",
            "requiredOperations": ["fixture.read"], "requirements": ["Synthetic test"], "decisions": ["No live effects"],
            "stage": "verify", "completedStages": ["interview", "adapters", "governance", "compose"], "status": status,
            "selectedAdapters": [self.selected], "adapterRequests": [{"requestId": "read-request", "component": "fixture", "requiredOperations": ["fixture.read"]}],
            "dependencies": [], "unresolvedNeeds": [], "governance": governance, "runtime": runtime, "ui": ui,
            "artifacts": artifacts, "checks": [self.check_record("local"), self.check_record("protocol")]}

    def adapter(self):
        """Builder: simulate a child result containing the requested bounded capability."""
        return {"schemaVersion": 1, "projectId": "fixture-project", "requestId": "read-request", "status": "validated",
            "adapter": self.selected, "governance": [], "checks": [self.check_record("local"), self.check_record("protocol")], "limitations": []}

    def save(self, name, document):
        """Serializer: materialize a handoff so the real read-only CLI can validate it."""
        path = self.root / name
        suite.atomic_json(path, document)
        return path

    def test_complete_and_partial_operation_discovery(self):
        """Scenario: exact flow coverage distinguishes reusable reads from missing writes."""
        suite.validate("registry", self.registry, self.root, True)
        self.assertEqual([], suite.query(self.registry, ["fixture.read"], "testing")[0]["missing"])
        self.assertEqual(["fixture.write"], suite.query(self.registry, ["fixture.read", "fixture.write"])[0]["missing"])
        self.assertEqual([], suite.query(self.registry, domain="finance"))

    def connection(self, status="declared"):
        """Builder: describe a host-bound custom token provider using secret references only."""
        return {"id": "broker-connection", "provider": "fixture-broker", "lifecycleOwner": "host-connection-provider",
                "protocol": "provider-token", "authorizationModel": "Verified principal bound to broker account grant",
                "status": status, "credentialRefs": [{"role": "developer", "ref": "secret://fixture/developer"},
                    {"role": "client", "ref": "secret://fixture/client-login"}],
                "bindings": [] if status == "declared" else [self.binding],
                "evidence": [self.report] if status == "verified" else []}

    def authored_graph(self):
        """Builder: return actual synthetic definitions with explicit unresolved connection needs."""
        return {"schemaVersion": 1, "projectId": "fixture-project", "status": "authored", "runtime": "flow",
                "artifacts": [self.artifact("workflow.json", '{"fixture":true}', "workflow")], "bindings": [],
                "checks": [self.check_record("structural")], "limitations": ["Connection not provisioned"],
                "unresolvedNeeds": ["broker connection"], "connections": [self.connection()]}

    def test_authoring_continues_without_live_connection(self):
        """Scenario: native artifact authoring can return before credentials/bindings are available."""
        graph = self.authored_graph()
        suite.validate("graph-result", graph, self.root, True)
        progress = self.save("progress.json", self.progress("flow", status="draft"))
        child = self.save("graph.json", graph)
        suite.handoff(progress, graph_path=child)

    def test_authored_result_cannot_claim_execution_readiness(self):
        """Scenario: relabelling an authored graph cannot bypass unresolved connection gates."""
        graph = self.authored_graph()
        graph.update(status="validated", checks=[self.check_record("local"), self.check_record("protocol")], limitations=[])
        with self.assertRaisesRegex(ValueError, "unverified connection"):
            suite.validate("graph-result", graph, self.root)
        graph["connections"] = [self.connection("verified")]
        with self.assertRaisesRegex(ValueError, "needs runtime bindings"):
            suite.validate("graph-result", graph, self.root)
        graph["bindings"] = [self.binding]
        with self.assertRaisesRegex(ValueError, "unresolved needs"):
            suite.validate("graph-result", graph, self.root)
        graph["unresolvedNeeds"] = []
        suite.validate("graph-result", graph, self.root, True)

    def test_inline_credentials_and_wrong_lifecycle_owner_rejected(self):
        """Scenario: custom tokens do not justify storing login secrets in authoring records."""
        for mutate in ("inline", "owner", "ref"):
            graph = self.authored_graph()
            connection = graph["connections"][0]
            if mutate == "inline":
                connection["clientToken"] = "synthetic-forbidden-value"
            elif mutate == "owner":
                connection["lifecycleOwner"] = "strategy"
            else:
                connection["credentialRefs"][0]["ref"] = "synthetic-inline-secret"
            with self.subTest(case=mutate), self.assertRaises(ValidationError):
                suite.validate("graph-result", graph, self.root)

    def test_interview_can_record_undecided_runtime_and_ui(self):
        """Scenario: saving early decisions must not force invented runtime or UI choices."""
        document = self.progress(status="draft")
        document.update(runtime="undecided", ui="undecided")
        suite.validate("assembly-progress", document, self.root, True)
        document["status"] = "validated"
        with self.assertRaisesRegex(ValueError, "explicit runtime and UI"):
            suite.validate("assembly-progress", document, self.root)

    def test_separate_strategy_component_contributes_coverage(self):
        """Scenario: business logic has its own claim and is not forced into an API adapter."""
        document = self.progress()
        strategy = deepcopy(document["selectedAdapters"].pop())
        strategy["role"] = "strategy"
        document["strategyComponents"] = [strategy]
        suite.validate("assembly-progress", document, self.root, True)
        document["strategyComponents"][0]["role"] = "api-adapter"
        with self.assertRaises(ValidationError):
            suite.validate("assembly-progress", document, self.root)

    def test_authored_status_requires_structural_evidence(self):
        """Scenario: missing native syntax evidence remains draft, never reported as authored."""
        graph = self.authored_graph()
        graph["checks"] = []
        with self.assertRaisesRegex(ValueError, "structural evidence"):
            suite.validate("graph-result", graph, self.root)

    def test_call_manifest_handoff_is_pinned_and_operations_resolve(self):
        """Scenario: API/hook handoffs cannot silently select an unlisted or changed operation."""
        manifest = suite.load(suite.ASSETS / "api-calls.json")
        reference = self.artifact("api-calls.json", json.dumps(manifest), "call-manifest")
        document = self.progress(status="draft")
        document["callPlan"] = {"manifest": reference, "operations": ["fabric.catalog-query"]}
        suite.validate("assembly-progress", document, self.root, True)
        document["callPlan"]["operations"] = ["gtt.instructions.create"]
        with self.assertRaisesRegex(ValueError, "unknown operation"):
            suite.validate("assembly-progress", document, self.root, True)

    def test_registry_rejects_drift_and_schema_drift(self):
        """Scenario: artifact hashes reject changed claims and operation schemas."""
        for name in ("claim.json", "input.json"):
            with self.subTest(name=name):
                original = (self.root / name).read_bytes()
                (self.root / name).write_bytes(original + b" ")
                with self.assertRaisesRegex(ValueError, "drift"):
                    suite.validate("registry", self.registry, self.root, True)
                (self.root / name).write_bytes(original)

    def test_registry_rejects_false_capability_and_catalog(self):
        """Scenario: index summaries cannot add unsupported operations or change embedded claims."""
        changed = deepcopy(self.registry)
        changed["adapters"][0]["capabilities"][0]["name"] = "fixture.write"
        with self.assertRaisesRegex(ValueError, "capabilities differ"):
            suite.validate("registry", changed, self.root, True)
        changed = deepcopy(self.registry)
        changed["adapters"][0]["catalog"] = self.artifact("bad-catalog.yaml", '{"claim":{}}', "catalog")
        with self.assertRaisesRegex(ValueError, "catalog claim differs"):
            suite.validate("registry", changed, self.root, True)

    def test_atomic_registry_upsert_governance_adapter(self):
        """Scenario: verified governance adapter registration advances a revision without duplicates."""
        path = self.save("registry.json", self.registry)
        suite.upsert(path, self.entry)
        self.assertEqual(2, suite.load(path)["revision"])
        self.assertEqual(1, len(suite.load(path)["adapters"]))
        original = path.read_bytes()
        bad = deepcopy(self.entry)
        bad["version"] = "2.0.0"
        with self.assertRaisesRegex(ValueError, "identity/version"):
            suite.upsert(path, bad)
        self.assertEqual(original, path.read_bytes())

    def test_missing_adapter_registration_from_supplied_evidence(self):
        """Scenario: a missing component becomes discoverable only after pinned output is registered."""
        empty = {"schemaVersion": 1, "revision": 1, "adapters": []}
        path = self.save("new-registry.json", empty)
        self.assertEqual([], suite.query(empty, ["fixture.read"]))
        supplied = self.artifact("supplied-api.json", '{"description":"synthetic fixture read contract"}', "api-evidence")
        entry = deepcopy(self.entry)
        entry["artifacts"].append(supplied)
        suite.upsert(path, entry)
        self.assertEqual([], suite.query(suite.load(path), ["fixture.read"])[0]["missing"])

    def test_runtime_and_ui_choices(self):
        """Scenario: all runtime and UI branches preserve explicit artifact selection."""
        for runtime in ("none", "flow", "foundry", "both"):
            for ui in ("backend-only", "unfurl-ui", "custom"):
                with self.subTest(runtime=runtime, ui=ui):
                    suite.validate("assembly-progress", self.progress(runtime, ui), self.root, True)

    def test_runtime_ui_mismatch_denied(self):
        """Scenario: a graph or UI cannot be silently omitted from a completed selected project."""
        for field, value in (("runtime", "both"), ("ui", "custom")):
            document = self.progress()
            document[field] = value
            with self.assertRaisesRegex(ValueError, "artifacts differ"):
                suite.validate("assembly-progress", document, self.root, True)

    def test_governance_bindings_and_complete_review(self):
        """Scenario: customer governance is reusable only with actual evidence and bindings."""
        for action in ("missing", "unbound", "no-evidence", "required-na"):
            document = self.progress()
            if action == "missing":
                document["governance"].pop()
            elif action == "unbound":
                document["governance"][0]["status"] = "unresolved"
            elif action == "no-evidence":
                next(item for item in document["governance"] if item["concern"] == "authentication")["evidence"] = []
            else:
                document["governance"][0].update(required=True, status="not-applicable")
            with self.subTest(action=action), self.assertRaises(ValueError):
                suite.validate("assembly-progress", document, self.root)

    def test_cycles_and_unresolved_needs(self):
        """Scenario: unresolved dependencies and cycles prevent false assembly completion."""
        document = self.progress()
        document["dependencies"] = [{"from": "a", "to": "b"}, {"from": "b", "to": "a"}]
        with self.assertRaisesRegex(ValueError, "cycle"):
            suite.validate("assembly-progress", document, self.root)
        document["dependencies"] = []
        document["unresolvedNeeds"] = ["customer-idp"]
        with self.assertRaisesRegex(ValueError, "unresolved needs"):
            suite.validate("assembly-progress", document, self.root)
        document["status"] = "blocked"
        suite.validate("assembly-progress", document, self.root)

    def test_unsupported_operation_blocks_completion(self):
        """Scenario: a read adapter cannot complete an assembly requiring a write."""
        document = self.progress()
        document["requiredOperations"].append("fixture.write")
        with self.assertRaisesRegex(ValueError, "not covered"):
            suite.validate("assembly-progress", document, self.root)

    def test_mutation_policies_required(self):
        """Scenario: writes require both exact-intent approval and unknown-outcome policy."""
        document = self.adapter()
        document["adapter"]["capabilities"][0]["effect"] = "write"
        with self.assertRaisesRegex(ValueError, "approvalPolicy"):
            suite.validate("adapter-result", document, self.root)
        capability = document["adapter"]["capabilities"][0]
        capability["approvalPolicy"] = "Approve exact intent through host"
        with self.assertRaisesRegex(ValueError, "reconciliationPolicy"):
            suite.validate("adapter-result", document, self.root)
        capability["reconciliationPolicy"] = "Query status before any replay"
        suite.validate("adapter-result", document, self.root)
        with self.assertRaisesRegex(ValueError, "effect differs"):
            suite.validate("adapter-result", document, self.root, True)

    def test_completion_requires_scoped_checks_and_startup(self):
        """Scenario: structural reports alone cannot justify validated or runnable status."""
        document = self.progress(status="runnable")
        with self.assertRaisesRegex(ValueError, "startup evidence"):
            suite.validate("assembly-progress", document, self.root)
        document["checks"].append(self.check_record("startup"))
        suite.validate("assembly-progress", document, self.root, True)
        document["checks"][0]["outcome"] = "not-run"
        with self.assertRaisesRegex(ValueError, "failed or unrun"):
            suite.validate("assembly-progress", document, self.root)

    def test_resume_and_child_identity(self):
        """Scenario: resumption keeps answers and rejects wrong-project or unrequested children."""
        progress = self.save("progress.json", self.progress(status="draft"))
        child = self.save("adapter-result.json", self.adapter())
        before = progress.read_bytes()
        suite.handoff(progress, child)
        self.assertEqual(before, progress.read_bytes())
        for field in ("projectId", "requestId"):
            result = self.adapter()
            result[field] = "different"
            self.save("adapter-result.json", result)
            with self.subTest(field=field), self.assertRaises(ValueError):
                suite.handoff(progress, child)

    def test_adapter_result_missing_requested_operation_denied(self):
        """Scenario: the child cannot complete a requested flow by returning only a subset."""
        document = self.progress(status="draft")
        document["adapterRequests"][0]["requiredOperations"].append("fixture.write")
        progress = self.save("progress.json", document)
        child = self.save("adapter-result.json", self.adapter())
        with self.assertRaisesRegex(ValueError, "does not cover"):
            suite.handoff(progress, child)

    def test_resume_rejects_version_artifact_drift(self):
        """Scenario: modified packaged artifacts invalidate resume without overwriting saved pins."""
        progress = self.save("progress.json", self.progress(status="draft"))
        child = self.save("adapter-result.json", self.adapter())
        (self.root / "claim.json").write_text("changed version", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "drift"):
            suite.handoff(progress, child)

    def test_selected_version_must_match_packaged_claim(self):
        """Scenario: editing version metadata without changing a claim cannot spoof a new adapter."""
        document = self.progress()
        document["selectedAdapters"][0]["version"] = "2.0.0"
        with self.assertRaisesRegex(ValueError, "identity/version"):
            suite.validate("assembly-progress", document, self.root, True)

    def test_graph_handoff_selection_and_project(self):
        """Scenario: selected native graphs and bindings return only to the matching assembly."""
        progress = self.save("progress.json", self.progress("both", status="draft"))
        for runtime in ("flow", "foundry", "both"):
            document = self.progress(runtime)
            graph = {"schemaVersion": 1, "projectId": "fixture-project", "runtime": runtime, "status": "validated",
                "artifacts": [item for item in document["artifacts"] if item["kind"] in {"workflow", "agent"}],
                "bindings": [self.binding], "checks": document["checks"], "limitations": []}
            path = self.save("graph.json", graph)
            suite.validate("graph-result", graph, self.root, True)
            if runtime == "both":
                suite.handoff(progress, graph_path=path)
            else:
                with self.assertRaisesRegex(ValueError, "runtime differs"):
                    suite.handoff(progress, graph_path=path)

    def test_paths_and_duplicate_keys_rejected(self):
        """Scenario: portable pins cannot escape the project or silently overwrite JSON fields."""
        for path in ("../escape", "C:/secret", "/absolute", "a\\secret"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                suite.pin(self.root, path, "evidence")
        file = self.root / "duplicate.json"
        file.write_text('{"id":1,"id":2}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            suite.load(file)

    def test_unknown_fields_and_duplicate_registry_rejected(self):
        """Scenario: unknown credential fields and duplicate index identities fail validation."""
        document = deepcopy(self.registry)
        document["apiKey"] = "synthetic-forbidden-field"
        with self.assertRaises(ValidationError):
            suite.validate("registry", document, self.root)
        document = deepcopy(self.registry)
        document["adapters"].append(self.entry)
        with self.assertRaisesRegex(ValueError, "duplicate adapter"):
            suite.validate("registry", document, self.root)

    def test_install_conflict_preflight_and_unrelated_preservation(self):
        """Scenario: synchronize the suite without overwriting edits or unrelated local skills."""
        destination = self.root / "installed"
        suite.install(destination)
        marker = suite.load(destination / suite.SKILLS[0] / "suite-source.json")
        self.assertTrue(marker["files"])
        edited = destination / suite.SKILLS[0] / "SKILL.md"
        edited.write_text("local change", encoding="utf-8")
        unrelated = destination / suite.SKILLS[0] / "user-note.txt"
        unrelated.write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "local skill differs"):
            suite.install(destination)
        self.assertEqual("local change", edited.read_text())
        suite.install(destination, replace_local=True)
        self.assertEqual("keep", unrelated.read_text())
        self.assertTrue(edited.read_text().startswith("---"))

    def test_installed_cli_and_validation_failure_exit(self):
        """Scenario: installed tools resolve canonical schemas and return nonzero for invalid input."""
        destination = self.root / "installed"
        suite.install(destination)
        script = destination / "dcp-software-assembly-generator/scripts/suite.py"
        path = self.save("registry.json", self.registry)
        result = subprocess.run([sys.executable, str(script), "validate", "registry", str(path), "--artifacts"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.save("registry.json", {"schemaVersion": 1})
        result = subprocess.run([sys.executable, str(script), "validate", "registry", str(path)], capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("ERROR:", result.stdout)

    def test_install_removes_only_stale_managed_files(self):
        """Scenario: removed suite files are pruned while unrelated user files survive sync."""
        destination = self.root / "installed"
        suite.install(destination)
        folder = destination / suite.SKILLS[0]
        stale = folder / "old-managed.txt"
        stale.write_text("old", encoding="utf-8")
        marker = folder / "suite-source.json"
        data = suite.load(marker)
        data["files"]["old-managed.txt"] = suite.digest(stale)
        suite.atomic_json(marker, data)
        unrelated = folder / "unrelated.txt"
        unrelated.write_text("keep", encoding="utf-8")
        suite.install(destination)
        self.assertFalse(stale.exists())
        self.assertEqual("keep", unrelated.read_text())


if __name__ == "__main__":
    unittest.main()
