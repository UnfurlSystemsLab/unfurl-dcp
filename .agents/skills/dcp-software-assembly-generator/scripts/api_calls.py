"""Ports & Adapters: execute pinned authoring API calls with mandatory hooks and durable evidence.

The optional HTTP facade is loopback-only development tooling. It neither authenticates broker
users nor bypasses upstream DCP/host policy. No captured credentials, redirects or retries are used.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import ssl
import threading
import time
from urllib.parse import urlsplit

from jsonschema import Draft202012Validator, ValidationError

import suite

ASSETS = suite.ASSETS
BEFORE = ["scope", "input", "intent"]
AFTER = ["classify", "schema", "redact", "outcome"]
PRIVATE = re.compile(r"^(password|passwd|otp|mpin|secret|token|apikey|clientsecret|clientcredentials|developercredentials|accesstoken|refreshtoken|authorization|auth|sid|cookie|setcookie)$", re.I)


class CallFailure(Exception):
    """Typed failure: expose safe machine codes without echoing payloads, URLs or credentials."""

    def __init__(self, code):
        """Value constructor: carry only a fixed diagnostic code across the tool boundary."""
        super().__init__(code)
        self.code = code


def reject(condition, code):
    """Guard: reject invalid state with a sanitized failure code."""
    if not condition:
        raise CallFailure(code)


def timestamp():
    """Clock Adapter: record unambiguous UTC event instants without interpreting business dates."""
    return datetime.now(timezone.utc).isoformat()


def clean(value, secrets=()):
    """Redaction Visitor: remove sensitive keys recursively and scrub resolved secret values."""
    if isinstance(value, dict):
        return {key: clean(child, secrets) for key, child in value.items()
                if not PRIVATE.fullmatch(re.sub(r"[-_]", "", key))}
    if isinstance(value, list):
        return [clean(child, secrets) for child in value]
    if isinstance(value, str):
        for secret in secrets:
            if secret:
                value = value.replace(secret, "[REDACTED]")
    return value


def check_input(value, tenant, principal):
    """Scope Visitor: reject secret-bearing arguments and forged tenant/principal fields."""
    if isinstance(value, dict):
        for key, child in value.items():
            reject(not PRIVATE.fullmatch(re.sub(r"[-_]", "", key)), "SECRET_INPUT_FORBIDDEN")
            if key == "tenantId":
                reject(child == tenant, "TENANT_MISMATCH")
            if key == "principalId":
                reject(child == principal, "PRINCIPAL_MISMATCH")
            check_input(child, tenant, principal)
    elif isinstance(value, list):
        for child in value:
            check_input(child, tenant, principal)


def secret(reference):
    """Secret Adapter: resolve an explicitly configured environment reference, never caller input."""
    match = re.fullmatch(r"secret://env/([A-Za-z_][A-Za-z0-9_]*)", reference)
    reject(match is not None, "SECRET_REFERENCE_UNSUPPORTED")
    result = os.environ.get(match.group(1), "")
    reject(bool(result) and "\r" not in result and "\n" not in result, "SECRET_UNAVAILABLE")
    return result


class HttpTransport:
    """Transport Adapter: one bounded HTTP exchange, verified TLS and no redirect/retry machinery."""

    def send(self, origin, path, body, headers, timeout, limit):
        """Dispatch exactly once and return status/bytes within one total monotonic deadline."""
        uri = validate_origin(origin)
        reject(path.startswith("/") and not path.startswith("//") and "?" not in path
               and "#" not in path and ".." not in path and "\\" not in path, "INVALID_CALL_PATH")
        connection = (http.client.HTTPSConnection(uri.hostname, uri.port, timeout=timeout, context=ssl.create_default_context())
                      if uri.scheme == "https" else http.client.HTTPConnection(uri.hostname, uri.port, timeout=timeout))
        started = time.monotonic()
        watchdog = None
        shutdown_socket = None
        expired = threading.Event()
        try:
            connection.connect()
            remaining = timeout - (time.monotonic() - started)
            reject(remaining > 0, "DEADLINE_EXCEEDED")
            # A duplicate descriptor can interrupt a slow body even after HTTPConnection detaches
            # its original socket for a Connection: close response. It carries no additional request.
            if os.name == "nt":
                shutdown_socket = socket.fromshare(connection.sock.share(os.getpid()))
            else:
                shutdown_socket = socket.fromfd(connection.sock.fileno(), connection.sock.family,
                                                connection.sock.type, connection.sock.proto)

            def abort_exchange():
                """Deadline Adapter: abort the shared TCP stream without replay or unbounded body reads."""
                expired.set()
                try:
                    shutdown_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            watchdog = threading.Timer(remaining, abort_exchange)
            watchdog.daemon = True
            watchdog.start()
            connection.request("POST", path, body=body, headers=headers)
            remaining = timeout - (time.monotonic() - started)
            reject(remaining > 0, "DEADLINE_EXCEEDED")
            if connection.sock is not None:
                connection.sock.settimeout(remaining)
            response = connection.getresponse()
            reject(not 300 <= response.status < 400, "REDIRECT_REFUSED")
            chunks, size = [], 0
            while True:
                remaining = timeout - (time.monotonic() - started)
                reject(remaining > 0, "DEADLINE_EXCEEDED")
                if connection.sock is not None:
                    connection.sock.settimeout(remaining)
                chunk = response.read1(min(65536, limit + 1 - size))
                if not chunk:
                    break
                size += len(chunk)
                reject(size <= limit, "RESPONSE_LIMIT")
                chunks.append(chunk)
            reject(not expired.is_set() and time.monotonic() - started <= timeout, "DEADLINE_EXCEEDED")
            return response.status, b"".join(chunks)
        finally:
            if watchdog is not None:
                watchdog.cancel()
            if shutdown_socket is not None:
                shutdown_socket.close()
            connection.close()


def validate_origin(origin):
    """Endpoint Strategy: allow verified HTTPS or explicit local development HTTP without URL credentials."""
    uri = urlsplit(origin)
    reject(uri.scheme in {"http", "https"} and uri.hostname and not uri.username and not uri.password
           and not uri.query and not uri.fragment and uri.path in {"", "/"}, "INVALID_SERVICE_ORIGIN")
    if uri.scheme == "http":
        try:
            loopback = ipaddress.ip_address(uri.hostname).is_loopback
        except ValueError:
            loopback = uri.hostname == "localhost"
        reject(loopback, "TLS_REQUIRED")
    return uri


class Runner:
    """Facade: apply immutable profile grants, hooks and journal around one authoring tool call."""

    def __init__(self, profile_path, profile_sha256, manifest_path=None, transport=None):
        """Pin host-controlled configuration and bootstrap a metadata-only durable call journal."""
        self.profile_path = Path(profile_path).resolve()
        reject(suite.digest(self.profile_path) == profile_sha256, "PROFILE_DRIFT")
        self.profile_hash = profile_sha256
        self.profile = suite.load(self.profile_path)
        self.manifest_path = Path(manifest_path or ASSETS / "api-calls.json").resolve()
        for name, document in (("call-profile", self.profile), ("call-manifest", suite.load(self.manifest_path))):
            Draft202012Validator(suite.load(ASSETS / (name + ".schema.json"))).validate(document)
        reject(suite.digest(self.manifest_path) == self.profile["manifestSha256"], "MANIFEST_DRIFT")
        self.manifest = suite.load(self.manifest_path)
        for operation in self.manifest["operations"].values():
            reject(operation["beforeHooks"] == BEFORE and operation["afterHooks"] == AFTER, "UNKNOWN_OR_UNSAFE_HOOK_ORDER")
            Draft202012Validator.check_schema(operation["argumentsSchema"])
            Draft202012Validator.check_schema(operation["resultSchema"])
        reject(set(self.profile["grants"]) <= set(self.manifest["operations"]), "UNKNOWN_GRANT")
        for service in self.profile["services"].values():
            validate_origin(service["origin"])
        self.journal = suite.confined(self.profile_path.parent, self.profile["journalPath"])
        self.journal.parent.mkdir(parents=True, exist_ok=True)
        self.transport = transport or HttpTransport()
        self.capacity = threading.BoundedSemaphore(self.profile["concurrency"])
        with self.database() as database:
            database.execute("CREATE TABLE IF NOT EXISTS calls (call_id TEXT PRIMARY KEY, digest TEXT NOT NULL, operation TEXT NOT NULL, state TEXT NOT NULL)")
            database.execute("CREATE TABLE IF NOT EXISTS events (sequence INTEGER PRIMARY KEY, call_id TEXT NOT NULL, stage TEXT NOT NULL, code TEXT NOT NULL)")
            # Additive migration keeps previous metadata-only journals readable without rewriting outcomes.
            for table, fields in (("calls", {"tenant": "TEXT", "principal": "TEXT", "profile_sha": "TEXT", "started_at": "TEXT"}),
                                  ("events", {"occurred_at": "TEXT", "duration_ms": "INTEGER", "tenant": "TEXT",
                                              "principal": "TEXT", "profile_sha": "TEXT"})):
                existing = {row[1] for row in database.execute("PRAGMA table_info(" + table + ")")}
                for name, sql_type in fields.items():
                    if name not in existing:
                        database.execute("ALTER TABLE " + table + " ADD COLUMN " + name + " " + sql_type)

    @contextmanager
    def database(self):
        """Journal Adapter: SQLite full-synchronous transactions persist state and safe audit together."""
        database = sqlite3.connect(self.journal, timeout=10)
        try:
            database.execute("PRAGMA synchronous=FULL")
            with database:
                yield database
        finally:
            database.close()

    def record(self, call_id, state, code="", duration_ms=None):
        """Journal Strategy: commit state and outcome attribution without arguments or result bodies."""
        with self.database() as database:
            database.execute("UPDATE calls SET state=? WHERE call_id=?", (state, call_id))
            self.event(database, call_id, state, code, duration_ms)

    def event(self, database, call_id, stage, code="", duration_ms=None):
        """Audit Adapter: attribute every event, including denials, to the immutable host profile."""
        database.execute("INSERT INTO events(call_id,stage,code,occurred_at,duration_ms,tenant,principal,profile_sha) VALUES(?,?,?,?,?,?,?,?)",
                         (call_id, stage, code, timestamp(), duration_ms, self.profile["tenantId"],
                          self.profile["principalId"], self.profile_hash))

    def status(self, call_id):
        """Read model: report persisted call state without dispatching or exposing payloads."""
        with self.database() as database:
            row = database.execute("SELECT operation,state FROM calls WHERE call_id=? AND profile_sha=?",
                                   (call_id, self.profile_hash)).fetchone()
        return None if row is None else {"operation": row[0], "state": row[1]}

    def invoke(self, operation_id, call_id, arguments):
        """Hook Facade: attribute denials without altering a previous attempt or leaking arguments."""
        try:
            return self._invoke(operation_id, call_id, arguments)
        except (CallFailure, ValidationError, ValueError) as error:
            code = error.code if isinstance(error, CallFailure) else "INPUT_INVALID"
            if isinstance(call_id, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", call_id):
                with self.database() as database:
                    self.event(database, call_id, "DENIED", code)
            return {"success": False, "output": {"status": "GAP"}, "errorCode": code, "errorMessage": code}

    def prepare(self, operation_id, call_id, arguments):
        """Pre-hook Strategy: validate scope/schema/credentials without dispatching or granting authority."""
        reject(bool(re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", call_id)), "INVALID_CALL_ID")
        operation = self.manifest["operations"].get(operation_id)
        reject(operation is not None and operation_id in self.profile["grants"], "OPERATION_DENIED")
        reject(isinstance(arguments, dict), "ARGUMENTS_INVALID")
        check_input(arguments, self.profile["tenantId"], self.profile["principalId"])
        Draft202012Validator(operation["argumentsSchema"]).validate(arguments)
        service = self.profile["services"].get(operation["service"])
        reject(service is not None, "SERVICE_UNBOUND")
        # Freeze the request once: later mutation by a caller cannot change approved scope or intent.
        frozen = json.dumps(arguments, sort_keys=True, separators=(",", ":"), allow_nan=False)
        identity = hashlib.sha256((self.profile_hash + operation_id + frozen).encode()).hexdigest()
        headers = {"Content-Type": "application/json", "X-Unfurl-Tenant": self.profile["tenantId"]}
        secrets = []
        for name, reference in service["credentialHeaders"].items():
            reject(name.lower() not in {"host", "content-length", "transfer-encoding", "x-unfurl-tenant"}, "RESERVED_HEADER")
            headers[name] = secret(reference)
            secrets.append(headers[name])
            if headers[name].lower().startswith(("bearer ", "basic ")):
                secrets.append(headers[name].split(" ", 1)[1])
        payload = {"callId": call_id, "toolName": operation_id, "arguments": json.loads(frozen), "metadata": {}}
        body = json.dumps(payload, allow_nan=False).encode()
        reject(len(body) <= self.profile["maxRequestBytes"], "REQUEST_LIMIT")
        return operation, service, frozen, identity, headers, secrets, body

    def preflight(self, operation_id, call_id, arguments):
        """Read-only gate: expose current pre-hook diagnostics; actual dispatch revalidates everything."""
        self.prepare(operation_id, call_id, arguments)
        return {"success": True, "output": {"status": "PASS", "tool": operation_id,
                "callId": call_id, "profileSha256": self.profile_hash}, "errorCode": "", "errorMessage": ""}

    def _invoke(self, operation_id, call_id, arguments):
        """Template Method: enforce pre/call/post and exception paths; never automatically replay."""
        operation, service, frozen, identity, headers, secrets, body = self.prepare(operation_id, call_id, arguments)
        started = time.monotonic()
        reject(self.capacity.acquire(blocking=False), "CONCURRENCY_LIMIT")
        recorded = False
        dispatched = False
        try:
            with self.database() as database:
                database.execute("BEGIN IMMEDIATE")
                previous = database.execute("SELECT digest,state FROM calls WHERE call_id=?", (call_id,)).fetchone()
                if previous:
                    reject(previous[0] == identity, "CALL_ID_CONFLICT")
                    raise CallFailure("PRIOR_OUTCOME_UNKNOWN" if previous[1] in {"DISPATCHING", "OUTCOME_UNKNOWN"} else "CALL_ALREADY_RECORDED")
                database.execute("INSERT INTO calls(call_id,digest,operation,state,tenant,principal,profile_sha,started_at) VALUES(?,?,?,?,?,?,?,?)",
                                 (call_id, identity, operation_id, "PREPARED", self.profile["tenantId"], self.profile["principalId"], self.profile_hash, timestamp()))
                self.event(database, call_id, "PREPARED")
            recorded = True
            self.record(call_id, "DISPATCHING")
            dispatched = True
            status, response = self.transport.send(service["origin"], operation["path"], body, headers,
                                                   self.profile["timeoutSeconds"], self.profile["maxResponseBytes"])
            reject(200 <= status < 300, "UPSTREAM_HTTP_ERROR")
            result = json.loads(response.decode("utf-8"), object_pairs_hook=suite.unique_pairs,
                                parse_constant=invalid_constant)
            Draft202012Validator(operation["resultSchema"]).validate(result)
            reject(result["success"] is True, "UPSTREAM_TOOL_ERROR")
            output = result["output"]
            business = output.get("status", "PASS")
            reject(business in {"PASS", "GAP", "CLARIFY"}, "UPSTREAM_STATUS_INVALID")
            sanitized = clean(result, secrets)
            self.record(call_id, "GAP" if business != "PASS" else "ACKNOWLEDGED", duration_ms=int((time.monotonic() - started) * 1000))
            return {"success": True, "output": {"status": business, "tool": operation_id,
                    "callId": call_id, "result": sanitized}, "errorCode": "", "errorMessage": ""}
        except Exception as error:
            code = error.code if isinstance(error, CallFailure) else "CALL_FAILED"
            if recorded:
                state = "OUTCOME_UNKNOWN" if dispatched and operation["effect"] == "write" else "FAILED"
                try:
                    self.record(call_id, state, code, int((time.monotonic() - started) * 1000))
                except (sqlite3.Error, OSError):
                    # DISPATCHING remains durable when outcome persistence fails; never return success.
                    code = "OUTCOME_RECORD_FAILED"
                return {"success": False, "output": {"status": "GAP", "callId": call_id, "state": state},
                        "errorCode": code, "errorMessage": code}
            raise
        finally:
            self.capacity.release()


def invalid_constant(value):
    """JSON Guard: reject NaN/Infinity rather than validate fabricated numeric values."""
    raise CallFailure("NONFINITE_JSON")


def gateway(runner, port):
    """HTTP Adapter Factory: expose guarded calls locally using a host-owned bearer credential."""
    credential = secret(runner.profile["gatewaySecretRef"])

    class Handler(BaseHTTPRequestHandler):
        """Local tool facade: never log credentials or raw request paths/payloads."""

        def log_message(self, format, *args):
            """Logging policy: suppress default request logging, using the safe journal instead."""

        def do_POST(self):
            """Route Adapter: authenticate, bound/decode input, then use the same hook runner."""
            try:
                reject(hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + credential), "GATEWAY_DENIED")
                reject(self.headers.get("Transfer-Encoding") is None
                       and len(self.headers.get_all("Content-Length", [])) == 1
                       and len(self.headers.get_all("Authorization", [])) == 1, "REQUEST_FRAMING_INVALID")
                reject(re.fullmatch(r"/(calls|preflight)/[a-z][a-z0-9.-]+", self.path) is not None, "ROUTE_DENIED")
                size = int(self.headers.get("Content-Length", "0"))
                reject(0 < size <= runner.profile["maxRequestBytes"], "REQUEST_LIMIT")
                self.connection.settimeout(runner.profile["timeoutSeconds"])
                value = json.loads(self.rfile.read(size), object_pairs_hook=suite.unique_pairs, parse_constant=invalid_constant)
                reject(isinstance(value, dict) and set(value) == {"callId", "arguments"}, "REQUEST_INVALID")
                kind, operation = self.path.strip('/').split('/')
                method = runner.invoke if kind == "calls" else runner.preflight
                result = method(operation, value["callId"], value["arguments"])
                status = 200
            except Exception:
                result = {"success": False, "output": {"status": "GAP"}, "errorCode": "GATEWAY_REJECTED", "errorMessage": "Request rejected"}
                status = 400
            payload = json.dumps(result).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main(argv=None):
    """CLI Facade: run explicit authoring calls or start the local guarded gateway."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path)
    parser.add_argument("--profile-sha256")
    parser.add_argument("--manifest", type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    call = sub.add_parser("call")
    call.add_argument("operation")
    call.add_argument("--call-id", required=True)
    call.add_argument("--arguments", type=Path, required=True)
    serve = sub.add_parser("serve")
    serve.add_argument("--port", type=int, default=8788)
    state = sub.add_parser("status")
    state.add_argument("call_id")
    initializer = sub.add_parser("init-profile")
    initializer.add_argument("destination", type=Path)
    initializer.add_argument("--tenant", required=True)
    initializer.add_argument("--principal", required=True)
    initializer.add_argument("--fabric-origin", required=True)
    initializer.add_argument("--flow-origin", required=True)
    initializer.add_argument("--grant", action="append", required=True)
    initializer.add_argument("--gateway-secret-ref", required=True)
    initializer.add_argument("--fabric-auth-ref")
    initializer.add_argument("--flow-auth-ref")
    args = parser.parse_args(argv)
    try:
        if args.command == "init-profile":
            profile = {"schemaVersion": 1, "manifestSha256": suite.digest(args.manifest or ASSETS / 'api-calls.json'),
                       "tenantId": args.tenant, "principalId": args.principal, "grants": args.grant,
                       "services": {"fabric": {"origin": args.fabric_origin, "credentialHeaders": {}},
                                    "flow": {"origin": args.flow_origin, "credentialHeaders": {}}},
                       "gatewaySecretRef": args.gateway_secret_ref, "timeoutSeconds": 30,
                       "maxResponseBytes": 1048576, "maxRequestBytes": 262144,
                       "concurrency": 4, "journalPath": "authoring-calls.sqlite"}
            for service, reference in (("fabric", args.fabric_auth_ref), ("flow", args.flow_auth_ref)):
                if reference:
                    profile["services"][service]["credentialHeaders"]["Authorization"] = reference
            Draft202012Validator(suite.load(ASSETS / "call-profile.schema.json")).validate(profile)
            args.destination.parent.mkdir(parents=True, exist_ok=True)
            with args.destination.open("x", encoding="utf-8") as stream:
                json.dump(profile, stream, indent=2); stream.write("\n")
            print(json.dumps({"profile": str(args.destination), "sha256": suite.digest(args.destination)}))
            return 0
        reject(args.profile is not None and args.profile_sha256 is not None, "PINNED_PROFILE_REQUIRED")
        runner = Runner(args.profile, args.profile_sha256, args.manifest)
        if args.command == "serve":
            with gateway(runner, args.port) as server:
                server.serve_forever()
        elif args.command == "status":
            print(json.dumps(runner.status(args.call_id)))
        else:
            result = runner.invoke(args.operation, args.call_id, suite.load(args.arguments))
            print(json.dumps(result, indent=2))
            return 0 if result["success"] and result["output"]["status"] == "PASS" else 2
        return 0
    except (CallFailure, ValidationError, OSError, ValueError, KeyError):
        print(json.dumps({"success": False, "errorCode": "CALL_CONFIGURATION_OR_POLICY_REJECTED"}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
