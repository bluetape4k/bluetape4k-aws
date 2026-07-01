# WIP - bluetape4k-aws

Snapshot: 2026-07-01 KST
Scope: post-0.4.0 service expansion closeout and backlog handoff.
Open count: 5 backlog issues.

## Current Direction

The `0.5.0` and `0.6.0` work queues are complete. Both milestones have zero
open issues, no open pull requests, and only backlog-level service ideas remain.

Keep this file focused on active repository management:

- update `CHANGELOG.md` when a merged feature changes the public surface,
- keep release and snapshot version notes aligned with `gradle.properties` and
  `gradle/libs.versions.toml`,
- move future service coverage candidates from `Backlog` into a concrete
  milestone only when they are ready to implement,
- avoid re-listing completed milestone work as active WIP.

AWS emulator policy remains Floci-first. New or migrated emulator-aware tests
should prefer `-Dbluetape4k.aws.emulator=floci`; LocalStack remains an explicit
fallback for Floci API coverage gaps, and MiniStack remains comparison-only.

## Active Queue

| Priority | Issue | Milestone | Notes |
|---|---|---|---|
| None | N/A | N/A | No active AWS milestone work remains after the 0.5.0 and 0.6.0 closeout. |

## Open PRs

| PR | Branch | Notes |
|---|---|---|
| None | N/A | Verified with `gh pr list` on 2026-07-01 KST. |

## Completed Since 0.4.0

- [#268](https://github.com/bluetape4k/bluetape4k-aws/issues/268) added core
  Secrets Manager and Parameter Store wrappers.
- [#269](https://github.com/bluetape4k/bluetape4k-aws/issues/269) and
  [#295](https://github.com/bluetape4k/bluetape4k-aws/issues/295) promoted RDS
  IAM helpers into the core AWS modules and delegated JDBC refresh to the
  shared helper boundary.
- [#270](https://github.com/bluetape4k/bluetape4k-aws/issues/270) added Spring
  Boot Kinesis auto-configuration and coroutine operations.
- [#271](https://github.com/bluetape4k/bluetape4k-aws/issues/271) added Ktor
  SES v2 and SNS integration support.
- [#180](https://github.com/bluetape4k/bluetape4k-aws/issues/180) and
  [#181](https://github.com/bluetape4k/bluetape4k-aws/issues/181) completed
  Spring Boot and Ktor `aws-exposed` settings integration.
- [#308](https://github.com/bluetape4k/bluetape4k-aws/issues/308) and
  [#309](https://github.com/bluetape4k/bluetape4k-aws/issues/309) added
  EventBridge core wrappers, coroutine DSLs, Spring Boot integration, Ktor
  integration, and README diagrams.
- [#272](https://github.com/bluetape4k/bluetape4k-aws/issues/272) added Ktor
  Kinesis and STS helpers.
- [#273](https://github.com/bluetape4k/bluetape4k-aws/issues/273) added Ktor
  examples for the remaining service coverage gaps.
- [#275](https://github.com/bluetape4k/bluetape4k-aws/issues/275) hardened
  gitleaks release asset lookup.
- [#284](https://github.com/bluetape4k/bluetape4k-aws/issues/284),
  [#285](https://github.com/bluetape4k/bluetape4k-aws/issues/285), and
  [#286](https://github.com/bluetape4k/bluetape4k-aws/issues/286) closed the
  remaining 0.5.0 hygiene review items.

## Backlog

| Issue | Notes |
|---|---|
| [#310](https://github.com/bluetape4k/bluetape4k-aws/issues/310) | EventBridge Scheduler support. |
| [#311](https://github.com/bluetape4k/bluetape4k-aws/issues/311) | S3 Tables support. |
| [#312](https://github.com/bluetape4k/bluetape4k-aws/issues/312) | Bedrock Runtime minimal facade. |
| [#313](https://github.com/bluetape4k/bluetape4k-aws/issues/313) | Step Functions execution helpers. |
| [#314](https://github.com/bluetape4k/bluetape4k-aws/issues/314) | Lambda invocation helpers. |

## Refresh Notes

- Verified with `gh` on 2026-07-01 KST: `0.5.0` has 24 closed items and zero
  open items.
- Verified with `gh` on 2026-07-01 KST: `0.6.0` has 4 closed items and zero
  open items.
- Latest GitHub release remains `0.4.0`; current unreleased development uses
  `baseVersion=0.5.0`.
- Local snapshot dependencies are
  `io.github.bluetape4k:bluetape4k-bom:1.11.1-SNAPSHOT` and
  `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.12.0-SNAPSHOT`.
