"""Authoring Facade: validate and synchronize DCP skill artifacts without runtime effects.

Inputs are local JSON/YAML files; outputs are diagnostics, index updates or explicit skill
installation. Recorded commands are never executed and no remote resources are fetched.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile

from jsonschema import Draft202012Validator, FormatChecker, ValidationError
from referencing import Registry, Resource
import yaml

SKILLS = ("add-dcp-capability", "dcp-component-adapter-creator",
          "dcp-software-assembly-generator", "dcp-runtime-graph-generator")
SKILL = Path(__file__).resolve().parents[1]
ASSETS = SKILL / "assets"
GOVERNANCE = {"authentication", "authorization", "secrets", "policy", "approvals",
              "audit", "monitoring", "quotas", "costs"}
COMPLETE = {"validated", "runnable"}


def load(path: Path):
    """Serializer: read JSON/YAML evidence; reject duplicate mapping keys in either format."""
    class UniqueLoader(yaml.SafeLoader):
        """Parser Strategy: preserve data integrity by rejecting duplicate YAML keys."""

    def mapping(loader, node, deep=False):
        """Builder: materialize a mapping once per key, rejecting overwritten evidence."""
        pairs = loader.construct_pairs(node, deep=deep)
        return unique_pairs(pairs)

    UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    content = path.read_text(encoding="utf-8-sig")
    return json.loads(content, object_pairs_hook=unique_pairs) if path.suffix == ".json" else yaml.load(content, Loader=UniqueLoader)


def unique_pairs(pairs):
    """Validator: reject duplicate keys instead of silently replacing earlier values."""
    result = {}
    for key, value in pairs:
        require(key not in result, f"duplicate mapping key: {key}")
        result[key] = value
    return result


def require(condition, message):
    """Guard: raise a user-facing validation error when an invariant is violated."""
    if not condition:
        raise ValueError(message)


def digest(path: Path):
    """Projector: hash exact artifact bytes so serialization changes invalidate pins."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def confined(root: Path, relative: str):
    """Path Strategy: resolve a portable local artifact without traversal or symlink escape."""
    require(relative and "\\" not in relative and ":" not in relative,
            f"artifact must use a relative POSIX path: {relative}")
    part = Path(relative)
    require(not part.is_absolute() and ".." not in part.parts, f"unsafe artifact path: {relative}")
    resolved = (root / part).resolve()
    require(resolved.is_relative_to(root.resolve()), f"artifact escapes root: {relative}")
    return resolved


def pin(root: Path, relative: str, kind: str):
    """Projector: return a digest reference for an existing file within the supplied root."""
    path = confined(root, relative)
    require(path.is_file(), f"missing artifact: {path}")
    return {"path": relative, "sha256": digest(path), "kind": kind}


def verify_artifact(root: Path, artifact):
    """Validator: ensure recorded bytes still match the pin; never silently update it."""
    path = confined(root, artifact["path"])
    require(path.is_file(), f"missing artifact: {path}")
    require(digest(path) == artifact["sha256"], f"artifact drift: {path}")
    return path


def canonical_root():
    """Locator Strategy: find the explicit installed source marker or repository checkout."""
    marker = SKILL / "suite-source.json"
    root = Path(load(marker)["repository"]) if marker.exists() else SKILL.parents[2]
    require((root / "adapters/adapter-registry.schema.json").is_file(),
            "canonical checkout unavailable; restore the suite source or reinstall")
    return root


def schema_environment():
    """Factory: load only local schemas into an offline reference registry."""
    paths = list(ASSETS.glob("*.schema.json")) + [canonical_root() / "adapters/adapter-registry.schema.json"]
    schemas = [load(path) for path in paths]
    registry = Registry().with_resources((schema["$id"], Resource.from_contents(schema)) for schema in schemas)
    return {schema["$id"].rsplit(":", 1)[-1]: schema for schema in schemas}, registry


def artifact_refs(value):
    """Visitor: yield typed artifact references throughout a handoff document."""
    if isinstance(value, dict):
        if set(value) == {"path", "sha256", "kind"}:
            yield value
        else:
            for child in value.values():
                yield from artifact_refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from artifact_refs(child)


def unique(values, label):
    """Validator: reject duplicate versioned identities or capability names."""
    require(len(values) == len(set(values)), f"duplicate {label}")


def validate_capabilities(capabilities):
    """Policy Strategy: require exact-intent approval and reconciliation for writes."""
    unique([item["name"] for item in capabilities], "capability")
    for capability in capabilities:
        if capability["effect"] == "write":
            require(capability.get("approvalPolicy", "").strip(), "write missing approvalPolicy")
            require(capability.get("reconciliationPolicy", "").strip(), "write missing reconciliationPolicy")


def check_cycles(edges):
    """Graph Strategy: detect active dependency cycles without repeating adapter generation."""
    adjacency = {}
    for edge in edges:
        adjacency.setdefault(edge["from"], []).append(edge["to"])
    active, done = set(), set()

    def visit(node):
        """Depth-first Visitor: distinguish active recursion from already resolved dependencies."""
        require(node not in active, f"dependency cycle at {node}")
        if node in done:
            return
        active.add(node)
        for target in adjacency.get(node, []):
            visit(target)
        active.remove(node)
        done.add(node)

    for node in adjacency:
        visit(node)


def validate_governance(items, complete):
    """Policy Strategy: distinguish declared ports from evidenced bound governance."""
    unique([item["concern"] for item in items], "governance concern")
    if complete:
        require({item["concern"] for item in items} == GOVERNANCE, "incomplete governance review")
    for item in items:
        require(not (item["required"] and item["status"] == "not-applicable"), "required governance marked not-applicable")
        if item["status"] == "bound":
            require(item["bindings"] and item["evidence"], "bound governance needs binding and evidence")
        if complete:
            require(item["status"] != "unresolved", f"unresolved governance: {item['concern']}")
            require(not item["required"] or item["status"] == "bound", "required governance is not bound")


def check_completion(document):
    """Policy Strategy: separate reported structural, local, protocol and startup evidence."""
    if document["status"] == "authored":
        require(any(check["level"] == "structural" and check["outcome"] == "pass"
                    for check in document["checks"]), "authored result needs structural evidence")
        return
    if document["status"] not in COMPLETE:
        return
    checks = document["checks"]
    require(all(check["outcome"] == "pass" for check in checks), "completed result contains failed or unrun checks")
    levels = {check["level"] for check in checks}
    require({"local", "protocol"} <= levels, "completion needs local and protocol evidence")
    if document["status"] == "runnable":
        require("startup" in levels, "runnable requires startup evidence")
    require(not document.get("limitations", []), "completed result has unresolved limitations")


def validate_connections(connections, complete=False):
    """Connection Strategy: track host-owned credential references without granting live authority."""
    unique([item["id"] for item in connections], "connection identity")
    for item in connections:
        if item["status"] in {"configured", "verified"}:
            require(item["bindings"], "configured connection needs a runtime binding")
            require(all(binding["kind"] == "runtime-binding" for binding in item["bindings"]),
                    "connection needs runtime-binding artifacts")
        if item["status"] == "verified":
            require(item["evidence"], "verified connection needs evidence")
        if complete:
            require(item["status"] == "verified", "execution-ready result has an unverified connection")


def verify_entry(entry, registry_dir: Path):
    """Adapter Validator: cross-check indexed offers against pinned claim/catalog/schema bytes."""
    source = (registry_dir / entry["source"]["path"]).resolve()
    require(source.is_dir(), f"source checkout unavailable: {source}")
    for artifact in artifact_refs(entry):
        verify_artifact(source, artifact)
    claim_path = verify_artifact(source, entry["claim"])
    claim = load(claim_path)
    catalog = load(verify_artifact(source, entry["catalog"]))
    require(catalog.get("claim") == claim, "catalog claim differs from indexed claim")
    require(claim["identity"]["uri"] == entry["id"] and claim["identity"]["version"] == entry["version"],
            "registry identity/version differs from claim")
    offers = {offer["capability"]: offer for offer in claim["offers"]}
    require(len(offers) == len(claim["offers"]), "duplicate claim offer")
    require(set(offers) == {cap["name"] for cap in entry["capabilities"]}, "registry capabilities differ from claim")
    for capability in entry["capabilities"]:
        offer = offers[capability["name"]]
        require(offer["version"] == capability["version"], "capability version differs from claim")
        details = offer.get("offer_interface", {}).get("details", {})
        effect = {"READ_ONLY": "read", "WRITE": "write", "MUTATING": "write"}.get(details.get("effect"))
        require(effect == capability["effect"], "claim needs explicit matching operation effect")
        for name in ("input_schema", "output_schema"):
            schema_path = confined(claim_path.parent, details.get(name, ""))
            require(schema_path.is_file(), f"missing operation schema: {schema_path}")
            require(any(confined(source, item["path"]) == schema_path for item in entry["artifacts"]),
                    f"operation schema is not pinned: {schema_path}")
            Draft202012Validator.check_schema(load(schema_path))


def verify_selected(entry, base: Path):
    """Adapter Guard: compare packaged identity/version/operations with the recorded selection."""
    claims = [item for item in entry["artifacts"] if item["kind"] == "claim"]
    catalogs = [item for item in entry["artifacts"] if item["kind"] == "catalog"]
    require(len(claims) == 1 and len(catalogs) == 1, "selected adapter needs one claim and catalog")
    claim = load(verify_artifact(base, claims[0]))
    require(claim["identity"]["uri"] == entry["id"] and claim["identity"]["version"] == entry["version"],
            "selected adapter identity/version differs from packaged claim")
    require(load(verify_artifact(base, catalogs[0])).get("claim") == claim, "packaged catalog claim differs")
    offers = {item["capability"]: item for item in claim["offers"]}
    for capability in entry["capabilities"]:
        offer = offers.get(capability["name"])
        require(offer and offer["version"] == capability["version"], "selected capability differs from packaged claim")
        effect = {"READ_ONLY": "read", "WRITE": "write", "MUTATING": "write"}.get(
            offer.get("offer_interface", {}).get("details", {}).get("effect"))
        require(effect == capability["effect"], "selected capability effect differs from packaged claim")


def validate(kind: str, document, base: Path, artifacts=False):
    """Validation Facade: apply closed schemas, semantic invariants and optional byte verification."""
    schemas, registry = schema_environment()
    Draft202012Validator(schemas[kind], registry=registry, format_checker=FormatChecker()).validate(document)
    if kind in {"call-manifest", "call-profile"}:
        if kind == "call-manifest":
            for operation in document["operations"].values():
                Draft202012Validator.check_schema(operation["argumentsSchema"])
                Draft202012Validator.check_schema(operation["resultSchema"])
        return
    if kind == "registry":
        unique([(entry["id"], entry["version"]) for entry in document["adapters"]], "adapter identity/version")
        for entry in document["adapters"]:
            validate_capabilities(entry["capabilities"])
            source_path = entry["source"]["path"]
            require(not Path(source_path).is_absolute() and "\\" not in source_path and ":" not in source_path,
                    "source path must be relative to the registry directory")
            for artifact in artifact_refs(entry):
                confined(base, artifact["path"])
            if artifacts:
                verify_entry(entry, base)
        return
    check_completion(document)
    validate_connections(document.get("connections", []),
                         kind != "adapter-result" and document["status"] in COMPLETE)
    if kind == "assembly-progress":
        check_cycles(document["dependencies"])
        unique([item["requestId"] for item in document["adapterRequests"]], "adapter request")
        selected = document["selectedAdapters"] + document.get("strategyComponents", [])
        unique([(entry["id"], entry["version"]) for entry in selected], "selected adapter/component")
        for entry in selected:
            validate_capabilities(entry["capabilities"])
        complete = document["status"] in COMPLETE
        validate_governance(document["governance"], complete)
        if complete:
            require(document["runtime"] != "undecided" and document["ui"] != "undecided",
                    "completion requires explicit runtime and UI selections")
            require(not document["unresolvedNeeds"], "assembly has unresolved needs")
            offered = {cap["name"] for entry in selected for cap in entry["capabilities"]}
            require(set(document["requiredOperations"]) <= offered, "required operations are not covered")
            kinds = {item["kind"] for item in document["artifacts"]}
            require({"contract", "runtime-binding", "startup"} <= kinds, "missing project contract/binding/startup artifacts")
            expected = {"flow": {"workflow"}, "foundry": {"agent"}, "both": {"workflow", "agent"}, "none": set()}[document["runtime"]]
            require((kinds & {"workflow", "agent"}) == expected, "runtime artifacts differ from selection")
            require(("ui" in kinds) == (document["ui"] != "backend-only"), "UI artifacts differ from selection")
    elif kind == "adapter-result":
        validate_capabilities(document["adapter"]["capabilities"])
        validate_governance(document["governance"], False)
        if document["status"] in COMPLETE:
            require({"claim", "catalog"} <= {item["kind"] for item in document["adapter"]["artifacts"]},
                    "completed adapter needs claim and catalog")
    elif kind == "graph-result":
        expected = {"flow": {"workflow"}, "foundry": {"agent"}, "both": {"workflow", "agent"}}[document["runtime"]]
        require({item["kind"] for item in document["artifacts"]} == expected, "graph artifacts differ from runtime selection")
        require(all(item["kind"] == "runtime-binding" for item in document["bindings"]), "graph needs runtime-binding artifacts")
        if document["status"] in COMPLETE:
            require(document["bindings"], "validated graph needs runtime bindings")
            require(not document.get("unresolvedNeeds", []), "validated graph has unresolved needs")
    for artifact in artifact_refs(document):
        confined(base, artifact["path"])
        if artifacts:
            verify_artifact(base, artifact)
    call_plan = document.get("callPlan")
    if call_plan and artifacts:
        manifest_path = verify_artifact(base, call_plan["manifest"])
        manifest = load(manifest_path)
        validate("call-manifest", manifest, manifest_path.parent)
        require(set(call_plan["operations"]) <= set(manifest["operations"]), "call plan references an unknown operation")
    if artifacts:
        for entry in document.get("selectedAdapters", []) + document.get("strategyComponents", []):
            if document["status"] in COMPLETE or any(item["kind"] == "claim" for item in entry["artifacts"]):
                verify_selected(entry, base)
        if kind == "adapter-result":
            if document["status"] in COMPLETE or any(item["kind"] == "claim" for item in document["adapter"]["artifacts"]):
                verify_selected(document["adapter"], base)


def handoff(progress_path: Path, adapter_path=None, graph_path=None):
    """Coordinator Guard: validate child identity, requested coverage and runtime before merging.

    This read-only check never alters saved answers or accepts an incomplete result as complete.
    The calling skill merges validated data and reruns assembly validation afterwards.
    """
    progress = load(progress_path)
    validate("assembly-progress", progress, progress_path.resolve().parent, True)
    require(adapter_path or graph_path, "supply at least one child result")
    if adapter_path:
        child = load(adapter_path)
        validate("adapter-result", child, adapter_path.resolve().parent, True)
        require(child["projectId"] == progress["projectId"], "adapter projectId differs")
        requests = {item["requestId"]: item for item in progress["adapterRequests"]}
        require(child["requestId"] in requests, "unrequested adapter result")
        if child["status"] in COMPLETE:
            names = {item["name"] for item in child["adapter"]["capabilities"]}
            require(set(requests[child["requestId"]]["requiredOperations"]) <= names,
                    "adapter result does not cover requested operations")
    if graph_path:
        child = load(graph_path)
        validate("graph-result", child, graph_path.resolve().parent, True)
        require(child["projectId"] == progress["projectId"], "graph projectId differs")
        require(child["runtime"] == progress["runtime"], "graph runtime differs from assembly")


def query(document, capabilities=(), domain=None):
    """Projector: report exact operation coverage without pretending to resolve SemVer or authority."""
    results = []
    for entry in document["adapters"]:
        if domain and domain not in entry["domains"]:
            continue
        names = {cap["name"] for cap in entry["capabilities"]}
        results.append({"id": entry["id"], "version": entry["version"], "domains": entry["domains"],
                        "covered": sorted(set(capabilities) & names), "missing": sorted(set(capabilities) - names)})
    return results


def atomic_json(path: Path, document):
    """Serializer: atomically replace an explicit authoring file after successful validation."""
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(document, stream, indent=2)
        stream.write("\n")
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def upsert(path: Path, entry):
    """Repository Strategy: verify a versioned entry and atomically advance the index revision."""
    document = load(path)
    validate("registry", document, path.parent)
    key = (entry["id"], entry["version"])
    candidate = {**document, "revision": document["revision"] + 1,
                 "adapters": [item for item in document["adapters"] if (item["id"], item["version"]) != key] + [entry]}
    validate("registry", candidate, path.parent)
    verify_entry(entry, path.parent)
    atomic_json(path, candidate)


def install(destination: Path, replace_local=False):
    """Installer Adapter: synchronize only suite files; refuse divergent files unless requested.

    All paths are checked before mutation. Unrelated destination files are preserved and
    symlinked destinations are refused. No global environment configuration is changed.
    """
    root = canonical_root()
    source = root / ".agents/skills"
    destination = destination.expanduser().absolute()
    require(not destination.is_symlink(), "installation destination cannot be a symlink")
    require(not destination.resolve().is_relative_to(source.resolve()), "cannot install over canonical skills")
    plans = []
    removals = []

    def target_path(folder, relative):
        """Path Guard: refuse symlinked managed paths rather than overwrite unrelated linked files."""
        target = confined(folder, relative)
        current = folder
        for part in Path(relative).parts:
            current = current / part
            require(not current.is_symlink(), f"symlinked managed path: {current}")
        return target

    for name in SKILLS:
        folder = destination / name
        require(not folder.is_symlink(), f"symlinked skill destination: {folder}")
        files = {}
        for path in (source / name).rglob("*"):
            if not path.is_file() or "__pycache__" in path.parts:
                continue
            relative = path.relative_to(source / name).as_posix()
            target = target_path(folder, relative)
            require(not target.exists() or target.is_file(), f"destination is not a file: {target}")
            require(replace_local or not target.exists() or digest(target) == digest(path),
                    f"local skill differs: {target}; inspect then use --replace-local")
            plans.append((target, path.read_bytes()))
            files[relative] = digest(path)
        marker = target_path(folder, "suite-source.json")
        if marker.exists():
            require(marker.is_file(), "source marker is not a file")
            previous = load(marker).get("files", {})
            for relative, previous_hash in previous.items():
                if relative not in files:
                    target = target_path(folder, relative)
                    require(not target.exists() or target.is_file(), "stale managed path is not a file")
                    require(replace_local or not target.exists() or digest(target) == previous_hash,
                            f"stale managed file was modified: {target}")
                    removals.append(target)
        plans.append((marker, (json.dumps({"repository": str(root), "registry": str(root / 'adapters/adapter-registry.json'),
                                         "files": files}, indent=2) + "\n").encode()))
    for target in removals:
        target.unlink(missing_ok=True)
    for target, content in plans:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    return list(SKILLS)


def main(argv=None):
    """CLI Facade: dispatch explicit offline operations and return nonzero on any validation error."""
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validator = commands.add_parser("validate")
    validator.add_argument("kind", choices=("registry", "assembly-progress", "adapter-result", "graph-result", "call-manifest", "call-profile"))
    validator.add_argument("file", type=Path)
    validator.add_argument("--artifacts", action="store_true")
    search = commands.add_parser("query")
    search.add_argument("file", type=Path)
    search.add_argument("--domain")
    search.add_argument("--capability", action="append", default=[])
    search.add_argument("--verify", action="store_true")
    pinner = commands.add_parser("pin")
    pinner.add_argument("root", type=Path)
    pinner.add_argument("path")
    pinner.add_argument("kind")
    update = commands.add_parser("upsert")
    update.add_argument("registry", type=Path)
    update.add_argument("entry", type=Path)
    installer = commands.add_parser("install")
    installer.add_argument("--destination", type=Path, default=Path(os.environ.get("CODEX_HOME", str(Path.home() / '.codex'))) / "skills")
    installer.add_argument("--replace-local", action="store_true")
    bridge = commands.add_parser("handoff")
    bridge.add_argument("progress", type=Path)
    bridge.add_argument("--adapter-result", type=Path)
    bridge.add_argument("--graph-result", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "validate":
            validate(args.kind, load(args.file), args.file.resolve().parent, args.artifacts)
            print(f"Valid {args.kind}" + ("; artifact pins verified" if args.artifacts else "; structural/semantic checks only"))
        elif args.command == "query":
            document = load(args.file)
            validate("registry", document, args.file.resolve().parent, args.verify)
            print(json.dumps(query(document, args.capability, args.domain), indent=2))
        elif args.command == "pin":
            print(json.dumps(pin(args.root, args.path, args.kind), indent=2))
        elif args.command == "upsert":
            upsert(args.registry.resolve(), load(args.entry))
            print("Registry entry verified and updated")
        elif args.command == "handoff":
            handoff(args.progress, args.adapter_result, args.graph_result)
            print("Handoff identities, coverage and artifact pins verified; merge in the calling skill")
        else:
            print(json.dumps({"installed": install(args.destination, args.replace_local)}, indent=2))
        return 0
    except (ValueError, KeyError, OSError, yaml.YAMLError, ValidationError) as error:
        print(f"ERROR: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
