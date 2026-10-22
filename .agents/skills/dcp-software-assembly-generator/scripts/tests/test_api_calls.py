"""Controlled authoring API conformance: hook ordering, actual local HTTP and durable no-replay behavior."""

import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import suite
import api_calls
import runtime_bundle
from jsonschema import ValidationError


class ControlledTransport:
    """Transport Double: model response and connection loss without any live provider effects."""

    def __init__(self, result=None, failure=False):
        """Fixture: count physical attempts and optionally fail after dispatch."""
        self.count = 0
        self.failure = failure
        self.result = result or {"success": True, "output": {"status": "PASS"}}

    def send(self, origin, path, body, headers, timeout, limit):
        """Attempt recorder: retain safe test payloads and simulate one deterministic response."""
        self.count += 1
        self.headers = headers
        if self.failure:
            raise TimeoutError("synthetic response loss")
        return 200, json.dumps(self.result).encode()


class ApiCallTests(unittest.TestCase):
    """Integration fixtures: exercise real hooks and journals with synthetic authoring responses."""

    def setUp(self):
        """Builder: provision an isolated host profile and explicit fixture credential references."""
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.manifest = suite.ASSETS / "api-calls.json"
        self.profile = {"schemaVersion": 1, "manifestSha256": suite.digest(self.manifest), "tenantId": "fixture-tenant",
                        "principalId": "fixture-principal", "grants": ["fabric.catalog-query", "fabric.assembly-create"],
                        "services": {"fabric": {"origin": "http://127.0.0.1:1", "credentialHeaders": {
                            "Authorization": "secret://env/FIXTURE_UPSTREAM_AUTH"}}},
                        "gatewaySecretRef": "secret://env/FIXTURE_GATEWAY_AUTH", "timeoutSeconds": 2,
                        "maxResponseBytes": 4096, "maxRequestBytes": 4096, "concurrency": 2, "journalPath": "calls.sqlite"}
        self.path = self.root / "profile.json"
        self.environment = patch.dict(os.environ, {"FIXTURE_UPSTREAM_AUTH": "Bearer fixture-private-secret",
                                                  "FIXTURE_GATEWAY_AUTH": "fixture-gateway-secret"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def runner(self, transport=None):
        """Factory: re-pin a deliberately configured test profile and construct the real runner."""
        suite.atomic_json(self.path, self.profile)
        return api_calls.Runner(self.path, suite.digest(self.path), transport=transport)

    def test_preflight_has_no_dispatch_and_rechecks_on_call(self):
        """Scope scenario: preflight is not a transferable authorization token."""
        transport = ControlledTransport()
        runner = self.runner(transport)
        args = {"tenantId": "fixture-tenant"}
        self.assertTrue(runner.preflight("fabric.catalog-query", "read-1", args)["success"])
        self.assertEqual(0, transport.count)
        args["tenantId"] = "other-tenant"
        result = runner.invoke("fabric.catalog-query", "read-1", args)
        self.assertEqual("TENANT_MISMATCH", result["errorCode"])
        self.assertEqual(0, transport.count)

    def test_success_redacts_values_and_journal_contains_no_payload(self):
        """Post-hook scenario: recursively remove secret fields and scrub resolved credential values."""
        transport = ControlledTransport({"success": True, "output": {"status": "PASS", "nested": {
            "accessToken": "fixture-private-secret", "message": "fixture-private-secret"}}})
        runner = self.runner(transport)
        result = runner.invoke("fabric.catalog-query", "read-1", {"tenantId": "fixture-tenant", "marker": "private-input-marker"})
        self.assertTrue(result["success"])
        self.assertNotIn("fixture-private-secret", json.dumps(result))
        self.assertNotIn("accessToken", json.dumps(result))
        self.assertEqual("ACKNOWLEDGED", runner.status("read-1")["state"])
        contents = runner.journal.read_bytes()
        self.assertNotIn(b"private-input-marker", contents)
        self.assertNotIn(b"fixture-private-secret", contents)

    def test_denial_and_unknown_operation_prevent_dispatch(self):
        """Pre-hook scenario: missing grants, spoofed identity and inline credentials fail closed."""
        transport = ControlledTransport()
        runner = self.runner(transport)
        cases = [("gtt.instructions.create", {}, "OPERATION_DENIED"),
                 ("fabric.catalog-query", {"tenantId": "wrong"}, "TENANT_MISMATCH"),
                 ("fabric.catalog-query", {"tenantId": "fixture-tenant", "principalId": "wrong"}, "PRINCIPAL_MISMATCH"),
                 ("fabric.catalog-query", {"tenantId": "fixture-tenant", "password": "fixture"}, "SECRET_INPUT_FORBIDDEN")]
        for index, (name, args, code) in enumerate(cases):
            with self.subTest(code=code):
                self.assertEqual(code, runner.invoke(name, f"deny-{index}", args)["errorCode"])
        self.assertEqual(0, transport.count)
        with runner.database() as database:
            self.assertEqual(4, database.execute("SELECT COUNT(*) FROM events WHERE stage='DENIED'").fetchone()[0])
            self.assertEqual(4, database.execute("SELECT COUNT(*) FROM events WHERE stage='DENIED' AND tenant='fixture-tenant' AND principal='fixture-principal'").fetchone()[0])

    def test_mutation_timeout_is_unknown_and_never_replayed(self):
        """Uncertainty scenario: response loss remains unknown across process restart and identical call ids."""
        transport = ControlledTransport(failure=True)
        runner = self.runner(transport)
        args = {"tenantId": "fixture-tenant", "request": {"assemblyId": "fixture"}}
        result = runner.invoke("fabric.assembly-create", "write-1", args)
        self.assertEqual("OUTCOME_UNKNOWN", result["output"]["state"])
        restarted = api_calls.Runner(self.path, suite.digest(self.path), transport=transport)
        self.assertEqual("PRIOR_OUTCOME_UNKNOWN", restarted.invoke("fabric.assembly-create", "write-1", args)["errorCode"])
        self.assertEqual(1, transport.count)

    def test_duplicate_or_changed_intent_not_dispatched(self):
        """Idempotency scenario: call ids bind exact arguments and do not authorize a second attempt."""
        transport = ControlledTransport()
        runner = self.runner(transport)
        args = {"tenantId": "fixture-tenant"}
        runner.invoke("fabric.assembly-create", "write-1", args)
        self.assertEqual("CALL_ALREADY_RECORDED", runner.invoke("fabric.assembly-create", "write-1", args)["errorCode"])
        self.assertEqual("CALL_ID_CONFLICT", runner.invoke("fabric.assembly-create", "write-1", {**args, "changed": True})["errorCode"])
        self.assertEqual(1, transport.count)

    def test_http_success_business_gap_and_tool_failure(self):
        """Classification scenario: neither HTTP success nor a tool-level business GAP is runtime success."""
        runner = self.runner(ControlledTransport({"success": True, "output": {"status": "GAP"}}))
        result = runner.invoke("fabric.catalog-query", "gap-1", {"tenantId": "fixture-tenant"})
        self.assertEqual("GAP", result["output"]["status"])
        self.assertEqual("GAP", runner.status("gap-1")["state"])
        other = self.runner(ControlledTransport({"success": False, "output": {}}))
        result = other.invoke("fabric.catalog-query", "fail-1", {"tenantId": "fixture-tenant"})
        self.assertFalse(result["success"])
        self.assertEqual("UPSTREAM_TOOL_ERROR", result["errorCode"])

    def test_profile_manifest_pins_and_hook_order_fail_closed(self):
        """Configuration scenario: changed host profile or invented hooks cannot silently take effect."""
        runner = self.runner(ControlledTransport())
        with self.assertRaises(api_calls.CallFailure):
            api_calls.Runner(self.path, "0" * 64)
        manifest = suite.load(self.manifest)
        manifest["operations"]["fabric.catalog-query"]["beforeHooks"] = ["allow-all"]
        path = self.root / "manifest.json"
        suite.atomic_json(path, manifest)
        self.profile["manifestSha256"] = suite.digest(path)
        suite.atomic_json(self.path, self.profile)
        with self.assertRaises(ValidationError):
            api_calls.Runner(self.path, suite.digest(self.path), path)

    def test_missing_secret_and_intent_audit_failure_prevent_dispatch(self):
        """Failure scenario: missing credentials or durable intent persistence never becomes a no-op."""
        transport = ControlledTransport()
        runner = self.runner(transport)
        with patch.dict(os.environ, {"FIXTURE_UPSTREAM_AUTH": ""}):
            self.assertEqual("SECRET_UNAVAILABLE", runner.invoke("fabric.catalog-query", "missing-1", {"tenantId": "fixture-tenant"})["errorCode"])
        with patch.object(runner, "record", side_effect=sqlite3.OperationalError("fixture storage failure")):
            self.assertFalse(runner.invoke("fabric.assembly-create", "audit-1", {"tenantId": "fixture-tenant"})["success"])
        self.assertEqual(0, transport.count)

    def test_gateway_auth_and_actual_loopback_transport(self):
        """Wire scenario: native-style client calls the real authenticated gateway and bounded HTTP adapter."""
        runner = self.runner(ControlledTransport())
        server = api_calls.gateway(runner, 0)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/calls/fabric.catalog-query"
            payload = json.dumps({"callId": "wire-1", "arguments": {"tenantId": "fixture-tenant"}}).encode()
            with self.assertRaises(HTTPError):
                urlopen(Request(url, data=payload), timeout=2)
            status, body = api_calls.HttpTransport().send(f"http://127.0.0.1:{server.server_port}",
                "/calls/fabric.catalog-query", payload, {"Authorization": "Bearer fixture-gateway-secret"}, 2, 4096)
            self.assertEqual(200, status)
            self.assertTrue(json.loads(body)["success"])
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=2)

    def test_transport_refuses_nonlocal_cleartext_and_redirect(self):
        """Egress scenario: remote HTTP and redirects cannot leak host credentials."""
        with self.assertRaises(api_calls.CallFailure):
            api_calls.HttpTransport().send("http://example.com", "/calls/tool", b"{}", {}, 1, 128)

    def test_wire_redirect_size_and_deadline_guards(self):
        """Wire failures: redirects, oversized bodies and stalled responses are actually bounded."""
        class Handler(BaseHTTPRequestHandler):
            """Controlled upstream: provide deterministic adverse responses without any provider calls."""

            def log_message(self, format, *args):
                """Suppress fixture logging so tests cannot accidentally echo HTTP headers."""

            def do_POST(self):
                """Respond according to the test route and tolerate deliberate client cancellation."""
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                if self.path == "/redirect":
                    self.send_response(302); self.send_header("Location", "/other"); self.end_headers(); return
                body = b"x" * 256
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
                if self.path == "/slow":
                    time.sleep(0.6)
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            origin = f"http://127.0.0.1:{server.server_port}"
            for route, timeout, limit in (("/redirect", 1, 4096), ("/large", 1, 32), ("/slow", 0.15, 4096)):
                started = time.monotonic()
                with self.subTest(route=route), self.assertRaises((api_calls.CallFailure, OSError, ValueError)):
                    api_calls.HttpTransport().send(origin, route, b"{}", {}, timeout, limit)
                if route == "/slow":
                    self.assertLess(time.monotonic() - started, 0.55)
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=2)

    def test_post_call_journal_failure_never_returns_success(self):
        """Post-hook failure: a committed upstream result plus lost audit remains uncertain, not successful."""
        transport = ControlledTransport()
        runner = self.runner(transport)
        original = runner.record

        def fail_completion(call_id, state, code="", duration_ms=None):
            """Failure Strategy: preserve dispatch intent but simulate outcome storage being unavailable."""
            if state != "DISPATCHING":
                raise sqlite3.OperationalError("fixture outcome store unavailable")
            return original(call_id, state, code, duration_ms)

        with patch.object(runner, "record", side_effect=fail_completion):
            result = runner.invoke("fabric.assembly-create", "write-post-fail", {"tenantId": "fixture-tenant"})
        self.assertFalse(result["success"])
        self.assertEqual("OUTCOME_RECORD_FAILED", result["errorCode"])
        self.assertEqual("DISPATCHING", runner.status("write-post-fail")["state"])
        self.assertEqual(1, transport.count)

    def test_bundle_generation_preserves_hooks_and_native_roles(self):
        """Projection scenario: Flow pre/call/post nodes and scoped Foundry agents are actual resources."""
        root = self.root / "bundle"
        result = runtime_bundle.build(root, "fixture-model", tenant="fixture-tenant")
        self.assertEqual(4, result["agents"])
        flow = suite.load(root / "flow/fabric.catalog-query.workflow.json")
        self.assertEqual(["preflight", "preflight_gate", "call", "call_gate"], [node["id"] for node in flow["nodes"]])
        self.assertEqual(["preflight_gate"], flow["nodes"][2]["dependencies"])
        agent = suite.load(root / "agents/dcp-gap-analysis.agent.yaml")
        self.assertNotIn("fabric.authoring-converse", agent["toolRefs"])
        for reference in suite.load(root / "bundle-index.json")["artifacts"]:
            suite.verify_artifact(root, reference)
        with self.assertRaises(ValueError):
            runtime_bundle.build(root, "fixture-model", tenant="fixture-tenant")


if __name__ == "__main__":
    unittest.main()
