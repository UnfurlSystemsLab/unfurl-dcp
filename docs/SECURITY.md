# Security Notes: unfurl-dcp

DCP claims influence component trust, capability matching, and contract invocation boundaries.

## Guidance

- Treat external claims as untrusted until parsed, validated, and hash-pinned.
- Keep canonical hash inputs explicit and deterministic.
- Do not place secret values in claim metadata.
- Keep contract invocation payload validation strict at boundaries.
- Fail closed when a claim, schema version, or hash is malformed.

