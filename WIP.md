# WIP - bluetape4k-aws

Snapshot: 2026-06-01 KST
Scope: release-train patch alignment for `0.3.1`.
Open count: 0 blocking issues for the patch lane.

## Current Direction

The `0.3.1` patch lane only aligns `aws-exposed` with the
`bluetape4k-exposed-bom` `1.10.0` release and keeps that BOM platform on
`implementation(platform(...))` instead of API scope.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | release-train dependency alignment | 0.3.1 | Align `aws-exposed` with `bluetape4k-exposed-bom` 1.10.0 and keep the platform import implementation-scoped. |

## Open PRs

| PR | Branch | Notes |
|---|---|---|
None for the `0.3.1` patch lane.

## Recently Completed

- `0.3.0` was published with the AWS integration line.
- [#186](https://github.com/bluetape4k/bluetape4k-aws/issues/186) / [PR #187](https://github.com/bluetape4k/bluetape4k-aws/pull/187) adopted the JetBrains Exposed Gradle plugin through `catalog/2026-05-26-00`.

## Refresh Notes

- Verified with `gh` on 2026-06-01 KST.
- Keep `bluetape4k-*` issue and resolving PR milestones aligned.
