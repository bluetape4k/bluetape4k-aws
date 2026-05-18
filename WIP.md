# WIP - bluetape4k-aws

📅 Snapshot: 2026-05-18 KST
🎯 Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
📬 Open count: 19 issues (18 pre-existing + 1 new from 2026-05-18 audit).

## ✅ Recently Completed

### 0.1.0 Release Preparation (Epic #97)
- ✅ #98 — Removed deprecated `S3Factory`, `SesFactory`, `SnsFactory`, `SqsFactory`
  before first public release (PR #113). Replaced by `XxxClientFactory` counterparts.
- ✅ #99, #100 — Added issue references and English rationale to all five `@Disabled`
  test annotations (PR #114). Categories: `unsupported-emulator`, `out-of-band-protocol`.
- ✅ #101 — Promoted `CHANGELOG.md [Unreleased]` to `[0.1.0] - 2026-05-16`
  (PR #115). 0.2.0 roadmap items separated.
- ✅ #102 — WIP.md snapshot refreshed to reflect 0.1.0 release state (this file).
- 🔄 #103 — README.md / README.ko.md structural alignment (in progress, PR pending).

### Pre-release Feature Work (now in 0.1.0)
- ✅ `aws-spring-boot`: S3 advanced transfer support merged (PR #94).
- ✅ `aws-spring-boot`: SQS listener/template parity and Spring Boot example AOT
  wiring merged (PR #93).
- ✅ `aws-spring-boot`: SNS SMS publishing and HTTP(S) endpoint message parsing
  merged (PR #95).
- ✅ `aws-ktor`: DynamoDB server plugin and repository facade merged (PR #87).
- ✅ `aws-spring-boot`: Secrets Manager / Parameter Store refresh support merged
  (PR #84 and PR #86).
- ✅ Dependencies refreshed: AWS SDK, Ktor 3.5, Gradle 9.5.1, SLF4J 2.0.18 (PR #89–#92).
- ✅ CI/GitHub Actions refreshed (PR #88).

## 🧭 Current Direction

**0.1.0 release preparation is the active lane.** All pre-release blockers
(#98–#103) have PRs open or merged. Once CI passes on all PRs, the release
is ready for Maven Central publish via:

```bash
./gradlew publishBluetapeAwsPublicationToCentralPortal
```

After 0.1.0 ships, the highest-impact 0.2.0 work is Exposed-first database
integration (#74 → #75/#76 → #77 → #82), then streaming (#81) and Ktor migration (#85).

- 🔴 **Active**: 0.1.0 release — PRs #113, #114, #115 open; #102, #103 in progress.
- 🟢 `aws-spring-boot`: All runtime foundations merged (S3, SQS, SNS, KMS, Secrets, DynamoDB).
- 🟢 `aws-ktor`: S3, SQS, SigV4, and DynamoDB server support merged.
- 🟡 Examples must compile and test in Nightly; Spring Boot examples must keep AOT tasks green.
- 🟡 #71 (README refresh) treated as residual polish; core gaps narrowed by recent work.

## 🔥 Priority Queue

### 🚀 Active: 0.1.0 Release Gate

| Priority | Issue | Status | Notes |
|---|---|---|---|
| 🔴 NOW | [#103](https://github.com/bluetape4k/bluetape4k-aws/issues/103) README.md / README.ko.md alignment | 🔄 In progress | PR pending |
| 🔴 NOW | [#113](https://github.com/bluetape4k/bluetape4k-aws/pull/113) PR: remove deprecated factories | ⏳ CI | Merge after CI pass |
| 🔴 NOW | [#114](https://github.com/bluetape4k/bluetape4k-aws/pull/114) PR: @Disabled annotations | ⏳ CI | Merge after CI pass |
| 🔴 NOW | [#115](https://github.com/bluetape4k/bluetape4k-aws/pull/115) PR: CHANGELOG [0.1.0] | ⏳ CI | Merge after CI pass |

### 📅 0.2.0 Roadmap (after 0.1.0 ships)

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| 🔴 P1 | [#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74) Exposed-first AWS database foundation | L | Broadest future contract; defines shared database properties and named database registry. |
| 🔴 P1 | [#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75) Spring Boot Exposed auto-configuration | L | Depends on #74. |
| 🔴 P1 | [#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76) Ktor AwsExposedPlugin | L | Depends on #74. |
| 🔴 P1 | [#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77) RDS IAM auth token provider | M | After #74 contract is stable. |
| 🟠 P2 | [#81](https://github.com/bluetape4k/bluetape4k-aws/issues/81) Kinesis and DynamoDB Streams Flow support | L | Independent; high leverage. |
| 🟠 P2 | [#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82) Spring Boot and Ktor Exposed AWS database examples | M | Depends on #74/#75/#76/#77. |
| 🟠 P2 | [#85](https://github.com/bluetape4k/bluetape4k-aws/issues/85) Migrate existing Ktor integrations toward `:aws-kotlin` | M | Audit after DynamoDB Kotlin-first path stable. |
| 🟠 P2 | [#104](https://github.com/bluetape4k/bluetape4k-aws/issues/104) Remove deprecated XxxFactory classes | S | Finish deprecation cycle started in #98. |
| 🟠 P2 | [#105](https://github.com/bluetape4k/bluetape4k-aws/issues/105) LocalStack-compatible test strategy for SES V2 / SNS token | M | Mock-based coverage for #99/#100 disabled tests. |
| 🟠 P2 | [#145](https://github.com/bluetape4k/bluetape4k-aws/issues/145) S3 listObjectsV2 auto-pagination Flow extension | M | SDK v2 `listObjectsV2` caps at 1000 objects; add `Flow<S3Object>` extension using SDK paginator; after #59 KMS work. |
| 🟠 P2 | [#106](https://github.com/bluetape4k/bluetape4k-aws/issues/106) Disabled-test registry and CI release gate | M | Enforce @Disabled issue-link rule in CI. |
| 🟡 P3 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Must satisfy Spring Boot AOT. |
| 🟡 P3 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Exercise consumer and publish paths. |
| 🟡 P3 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Follow Kotlin-first DynamoDB plugin. |
| 🟢 P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone Spring Boot feature. |
| ⚪ P5 | [#71](https://github.com/bluetape4k/bluetape4k-aws/issues/71) aws-spring-boot README feature refresh | S | Residual doc polish. |

## 🔗 Dependency Map

```text
#74 Exposed-first AWS database foundation
  -> #75 Spring Boot Exposed auto-configuration
  -> #76 Ktor AwsExposedPlugin
  -> #77 RDS IAM auth token provider
      -> #82 Spring Boot and Ktor Exposed AWS database examples

#31 Spring Boot DynamoDB
  -> #14 Spring Boot DynamoDB example

#60 Ktor SQS runtime
  -> #16 Ktor SQS example

#87 Ktor DynamoDB
  -> #17 Ktor DynamoDB example

#87 Ktor DynamoDB Kotlin-first path
  -> #85 Existing Ktor aws-kotlin migration audit

#59 @KmsEncrypted field-level encryption
  -> #145 S3 listObjectsV2 auto-pagination Flow extension (P2)
       -> implement listAllObjects() using SDK v2 paginator
       -> after #59 KMS work; both are aws-coroutine API additions
```

## 🚧 WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| 🔴 Database foundation | 1 | `#74`; do not start #75/#76 before the shared contract is clear. |
| 🟠 Framework adapters | 1 | `#75` or `#76` only after #74 has a stable shape. |
| 🟠 Streaming | 1 | `#81` can proceed independently if database work pauses. |
| 🟡 Examples | 1 | Choose one of `#14/#16/#17/#82`; Spring Boot examples must keep AOT tasks green. |
| ⚪ Docs/KDoc polish | 1 | `#71` only as a small focused PR or close/narrow after README verification. |
