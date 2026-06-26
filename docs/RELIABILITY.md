# Reliability Notes: unfurl-dcp

DCP reliability means stable interpretation of claims and contracts across repos.

## Expectations

- Canonical hashes are stable across platforms.
- Schema changes are additive where possible.
- Codec round trips preserve required fields.
- Consumers reject unsupported versions cleanly.
- Test fixtures cover representative claims and edge cases.

