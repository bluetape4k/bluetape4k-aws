# WIP - bluetape4k-aws

Snapshot: 2026-05-18 KST
Scope: open GitHub issues assigned to `debop`.
Open count: 8 issues.

## Refresh Notes

Verified with `gh` on 2026-05-18 KST.

- qmd was queried first for prior aws lessons, specs, plans, and follow-ups.
- Existing issue #145 was unassigned; it is now assigned to `debop`.
- New issue registered from this audit:
  - [#147](https://github.com/bluetape4k/bluetape4k-aws/issues/147) - `bug: forceDeleteBucket cannot empty versioned S3 buckets`
- PR #146 (`chore: refresh WIP snapshot - 2026-05-18`) is already merged, so this file reflects the current post-merge GitHub state.

## Current Direction

The 0.1.x release cleanup lane is effectively closed. Current open work is now
an 0.2.x API/foundation queue:

1. Exposed-first AWS database foundation (#74) before framework adapters.
2. S3 correctness and pagination (#147, #145) before more S3 examples.
3. Framework examples (#82) only after #74/#75/#76/#77 stabilize.
4. SES sender (#7) remains standalone lower-priority Spring Boot work.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P1 | [#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74) Exposed-first AWS database integration foundation | L | Broadest future contract; defines shared database properties and named database registry. |
| P1 | [#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75) Spring Boot Exposed auto-configuration | L | Depends on #74. |
| P1 | [#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76) Ktor AwsExposedPlugin | L | Depends on #74. |
| P1 | [#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77) RDS IAM auth token provider | M | After #74 contract is stable. |
| P1 | [#147](https://github.com/bluetape4k/bluetape4k-aws/issues/147) forceDeleteBucket cannot empty versioned S3 buckets | M | Current helper deletes only current keys; versioned buckets need object versions and delete markers removed. |
| P2 | [#145](https://github.com/bluetape4k/bluetape4k-aws/issues/145) S3 listObjectsV2 auto-pagination Flow extension | M | Add Flow/paginator API for current object listing; useful prerequisite for robust bucket cleanup. |
| P2 | [#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82) Spring Boot and Ktor Exposed AWS database examples | M | Depends on #74/#75/#76/#77. |
| P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES email sender | M | Standalone Spring Boot feature after foundation work. |

## Dependency Map

```text
#74 Exposed-first AWS database foundation
  -> #75 Spring Boot Exposed auto-configuration
  -> #76 Ktor AwsExposedPlugin
  -> #77 RDS IAM auth token provider
      -> #82 Spring Boot and Ktor Exposed AWS database examples

#145 S3 listObjectsV2 auto-pagination Flow extension
  -> #147 forceDeleteBucket support for versioned bucket cleanup
      -> list object versions and delete markers
      -> delete with ObjectIdentifier.versionId
      -> preserve coroutine cancellation propagation
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Database foundation | 1 | Start with `#74`; do not start #75/#76 before the shared contract is clear. |
| Framework adapters | 1 | `#75` or `#76` only after #74 has a stable shape. |
| S3 correctness/API | 1 | `#147` is the correctness fix; `#145` can supply reusable pagination support. |
| Examples | 1 | `#82` only after database adapters stabilize. |
| Standalone Spring Boot | 1 | `#7` when foundation/API work pauses. |
