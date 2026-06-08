# WIP - bluetape4k-aws

Snapshot: 2026-06-08 KST
Scope: 0.4.0 release documentation and publish preflight.
Open count: 1 issue.

## Current Direction

The `0.4.0` feature lane is complete. Keep the milestone open until the publish
workflow finishes final release gates, then close it as part of release
administration.

The active work is release documentation only:

- refresh `README.md` / `README.ko.md` for the final 0.4.0 feature set,
- prepare the `CHANGELOG.md` 0.4.0 summary,
- confirm the root service coverage chart and architecture diagrams still match
  the current module scope,
- keep `WIP.md` focused on release preflight instead of completed backlog work.

AWS emulator policy remains Floci-first. New or migrated emulator-aware tests
should prefer `-Dbluetape4k.aws.emulator=floci`; LocalStack remains an explicit
fallback for Floci API coverage gaps, and MiniStack remains comparison-only.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| P1 | [#292](https://github.com/bluetape4k/bluetape4k-aws/issues/292) | 0.4.0 | Prepare README, WIP, CHANGELOG, and diagram evidence before publish. |

## Open PRs

| PR | Branch | Notes |
|---|---|---|
| None | N/A | Create one release-docs PR for #292. |

## Recently Completed

- [#179](https://github.com/bluetape4k/bluetape4k-aws/issues/179) added Ktor
  DynamoDB integration.
- [#191](https://github.com/bluetape4k/bluetape4k-aws/issues/191) added
  optional Spring Boot DynamoDB DAX integration.
- [#194](https://github.com/bluetape4k/bluetape4k-aws/issues/194) and
  [#201](https://github.com/bluetape4k/bluetape4k-aws/issues/201) added
  CloudWatch and CloudWatch Logs coverage for Spring Boot and Ktor.
- [#196](https://github.com/bluetape4k/bluetape4k-aws/issues/196) and
  [#200](https://github.com/bluetape4k/bluetape4k-aws/issues/200) added EC2
  IMDS helpers for Spring Boot and Ktor.
- [#227](https://github.com/bluetape4k/bluetape4k-aws/issues/227) and
  [#228](https://github.com/bluetape4k/bluetape4k-aws/issues/228) added
  optional S3 Access Grants support.
- [#229](https://github.com/bluetape4k/bluetape4k-aws/issues/229) added
  optional S3 Vectors support.
- [#230](https://github.com/bluetape4k/bluetape4k-aws/issues/230) added
  optional Micrometer observability adapters for SQS and S3.
- [#239](https://github.com/bluetape4k/bluetape4k-aws/issues/239) and
  [#241](https://github.com/bluetape4k/bluetape4k-aws/issues/241) completed
  the Floci-first AWS emulator policy and migration tracking.
- [#244](https://github.com/bluetape4k/bluetape4k-aws/issues/244) and
  [#245](https://github.com/bluetape4k/bluetape4k-aws/issues/245) adopted
  shared `bluetape4k-ktor-*` modules in AWS Ktor code and examples.
- [#251](https://github.com/bluetape4k/bluetape4k-aws/issues/251) through
  [#263](https://github.com/bluetape4k/bluetape4k-aws/issues/263) hardened CI
  and Nightly snapshot dependency refresh behavior.
- [#281](https://github.com/bluetape4k/bluetape4k-aws/issues/281) through
  [#283](https://github.com/bluetape4k/bluetape4k-aws/issues/283) closed final
  IMDS and DAX review gaps.

## Refresh Notes

- Verified with `gh` on 2026-06-08 KST: `0.4.0` had zero open issues before
  #292 was created.
- Keep `0.4.0` milestone open until the publish workflow completes.
- The release PR should not run heavyweight CI solely for documentation changes
  unless branch protection requires it.
