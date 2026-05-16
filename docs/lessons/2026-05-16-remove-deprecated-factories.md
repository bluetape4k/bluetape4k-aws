# Remove Deprecated XxxFactory Classes Before 0.1.0 Release

**Date**: 2026-05-16
**Issue**: #98
**PR**: (see PR link)
**Branch**: fix/remove-deprecated-factories

## Decision

Removed four deprecated factory objects before the first public release (0.1.0):

- `S3Factory` → replaced by `S3ClientFactory`
- `SesFactory` → replaced by `SesClientFactory`
- `SnsFactory` → replaced by `SnsClientFactory`
- `SqsFactory` → replaced by `SqsClientFactory`

**Rationale**: Publishing deprecated APIs in the first GA release creates unnecessary backwards-compatibility debt for consumers who have never seen these classes. Removal before 0.1.0 is a non-breaking change because no external consumer has taken a dependency on any published artifact yet.

## Root Cause

The deprecated objects were scaffolded early in the project and superseded by `XxxClientFactory` counterparts. The `@Deprecated` annotation was correct, but the removal was deferred without a tracking issue.

## Verification

- `grep` across all `.kt` files confirmed zero usages of `S3Factory`, `SesFactory`, `SnsFactory`, `SqsFactory` outside the factory files themselves.
- `SqsClientFactoryTest.kt` contained `SqsFactory` in *test display names* only (not in code); corrected as part of this PR.
- `./gradlew :aws:test`: **252 passing, 2 pending (pre-existing @Disabled), 0 failed**.
- No references found in README, build scripts, or YAML files.
- Binary compatibility: N/A — no 0.1.0 artifact published yet.

## Future Guidance

- When adding deprecated objects/classes, always open a tracking issue at the same time with a target removal version.
- In first-release projects, prefer removing deprecated stubs before GA rather than carrying a deprecation cycle.
