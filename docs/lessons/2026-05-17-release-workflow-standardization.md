# Release Workflow Standardization

Context: The Central Portal release campaign uses `bluetape4k-projects` as the
canonical release workflow shape.

Decision: Rename release-prep workflow files to `nightly-tests.yml` and
`publish-snapshot.yml` while keeping workflow display names unchanged.

Outcome: Release preparation scripts can rely on the same workflow file names
across bluetape4k repositories.

Verification: `actionlint .github/workflows/nightly-tests.yml .github/workflows/publish-snapshot.yml .github/workflows/release.yml`.

Future guard: Keep release workflow file names aligned with `bluetape4k-projects`
unless a repo-specific exception is documented in `AGENTS.md`.
