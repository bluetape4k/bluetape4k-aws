# Release Catalog Override Guard

## Context

The 0.3.0 tag-triggered release initially failed because the GitHub repository
variable `BLUETAPE4K_DEPENDENCIES_CATALOG_REF` still pointed to an older
`bluetape4k-dependencies` catalog. That variable overrode the checked-in
`settings.gradle.kts` catalog default before Gradle compiled the build scripts.

## Decision

Tag-triggered releases must use the checked-in catalog default from
`settings.gradle.kts`. `workflow_dispatch` can still override the catalog with
the explicit `catalogRef` input, then with the repository variable as an
operational fallback.

## Outcome

The release workflow now logs the selected catalog source and verifies required
catalog aliases before publishing to Maven Central Portal. A stale repository
variable fails fast or is ignored for tag releases instead of causing a late
Gradle script compilation failure.

## Verification

Validated the catalog selection shell logic for tag push and manual dispatch
paths, verified the required aliases against the current release catalog, ran
`actionlint`, and ran `git diff --check`.

## Future Guidance

Adopt normal catalog changes through downstream checked-in file updates. Treat
the GitHub repository variable as a manual release override only, not as the
release train source of truth.
