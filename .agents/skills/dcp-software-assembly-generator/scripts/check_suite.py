"""Validation Facade: check skill discovery, local links and offline suite schemas in CI."""

from pathlib import Path
import re

from jsonschema import Draft202012Validator
import yaml

import suite


def check():
    """Validator: verify all four canonical skills and the index without requiring sibling checkouts."""
    root = suite.canonical_root()
    for name in suite.SKILLS:
        folder = root / ".agents/skills" / name
        text = (folder / "SKILL.md").read_text(encoding="utf-8")
        match = re.match(r"^---\s*\n(.*?)\n---", text, re.S)
        suite.require(match is not None, f"missing frontmatter: {name}")
        metadata = yaml.safe_load(match.group(1))
        suite.require(metadata["name"] == name, f"skill name mismatch: {name}")
        suite.require(0 < len(metadata["description"]) <= 1024, "invalid skill description")
        suite.require("[TODO:" not in text, f"unfinished skill: {name}")
        interface = suite.load(folder / "agents/openai.yaml")["interface"]
        suite.require(25 <= len(interface["short_description"]) <= 64, f"invalid UI description: {name}")
        suite.require(f"${name}" in interface["default_prompt"], f"missing invocation: {name}")
        for document in folder.rglob("*.md"):
            for target in re.findall(r"\]\(([^)]+)\)", document.read_text(encoding="utf-8")):
                if not target.startswith(("https://", "http://", "#")):
                    suite.require((document.parent / target.split("#")[0]).exists(), f"broken link: {document}: {target}")
    schemas, _ = suite.schema_environment()
    for schema in schemas.values():
        Draft202012Validator.check_schema(schema)
    path = root / "adapters/adapter-registry.json"
    suite.validate("registry", suite.load(path), path.parent)
    Draft202012Validator(schemas["call-manifest"]).validate(suite.load(suite.ASSETS / "api-calls.json"))
    print(f"Four skills, metadata, local links, {len(schemas)} schemas, call manifest and registry structure valid")


if __name__ == "__main__":
    check()
