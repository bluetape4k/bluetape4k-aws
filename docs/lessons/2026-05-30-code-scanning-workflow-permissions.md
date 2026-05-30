# Code scanning workflow permissions

## Context

GitHub CodeQL reported `actions/missing-workflow-permissions` alerts for the
snapshot publish and release workflows.

## Decision

Declare explicit workflow-level `contents: read` permissions for workflows that
use checkout, override token-free jobs with `permissions: {}`, and keep
`contents: write` only on the GitHub Release job that creates releases.

## Outcome

The workflow token defaults are now least-privilege for the alerted jobs without
changing publish or release behavior.

## Verification

- `actionlint .github/workflows/publish-snapshot.yml .github/workflows/release.yml`
- `yq` inspection of workflow and job permissions
- `git diff --check`

## Future guard

For future GitHub Actions edits, add an explicit workflow-level `permissions`
block first, then widen individual jobs only when a step needs write access.
