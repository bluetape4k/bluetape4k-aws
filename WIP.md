# WIP - bluetape4k-aws

Snapshot: 2026-06-02 KST
Scope: post-0.3.1 release train version alignment.
Open count: 14 issues.

## Current Direction

The `0.3.1` patch lane has been published and consumed by
`bluetape4k-dependencies` `1.2.0`. Development now moves to `0.4.0` with
`snapshotVersion=` kept empty for workflow-injected snapshot publication.

AWS emulator policy: Floci-first. New or migrated emulator-aware tests should
prefer `-Dbluetape4k.aws.emulator=floci`. LocalStack remains an explicit
fallback for Floci API coverage gaps.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | next minor development | 0.4.0 | Open the next minor line after the 0.3.1 release-train patch. |

## Open PRs

| PR | Branch | Notes |
|---|---|---|
None for the `0.4.0` development lane.

## Recently Completed

- `0.3.1` published and consumed by `bluetape4k-dependencies` `1.2.0`.
- [#186](https://github.com/bluetape4k/bluetape4k-aws/issues/186) / [PR #187](https://github.com/bluetape4k/bluetape4k-aws/pull/187) adopted the JetBrains Exposed Gradle plugin through `catalog/2026-05-26-00`.
- Adopted shared Ktor modules in AWS Ktor examples.
- Clarified Floci-first AWS emulator policy; preferred Floci for emulator-backed tests.
- Consumed `bluetape4k-projects` `1.10.0` BOM.

## Refresh Notes

- Verified with `gh` on 2026-06-02 KST.
- Keep `bluetape4k-*` issue and resolving PR milestones aligned.
