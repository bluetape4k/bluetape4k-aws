## Context
AWS post-merge CI still failed after the initial retry guard because Central
Portal returned repeated HTTP 403 responses for exposed snapshot metadata.

## Decision
Use a longer bounded retry window and disable configuration cache for the
snapshot-dependent compile gate. This keeps the workflow resilient to transient
Central edge failures without changing source or dependency versions.

## Outcome
The workflow now retries compile-only builds five times with a 30-second backoff
and avoids configuration-cache serialization failures while classpaths are
unresolved.

## Verification
- `git diff --check`
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`

## Future Guidance
When Central snapshot metadata returns HTTP 403 repeatedly in one runner, extend
the bounded retry window before treating it as a code regression.
