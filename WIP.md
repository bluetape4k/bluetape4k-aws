# WIP - bluetape4k-aws

📅 Snapshot: 2026-05-16 KST
🎯 Scope: open GitHub issues assigned to `debop`, created on or after 2026-01-01.
📬 Open count: 12 issues.

## ✅ Recently Completed

- ✅ `aws-spring-boot`: S3 advanced transfer support is merged by PR #94. Basic
  S3 operations stay CRT-free; `S3TransferOperations` is conditional on
  `software.amazon.awssdk:s3-transfer-manager`.
- ✅ `aws-spring-boot`: SQS listener/template parity and Spring Boot example AOT
  wiring are merged by PR #93.
- ✅ `aws-spring-boot`: SNS SMS publishing and HTTP(S) endpoint message parsing are
  merged by PR #95. Signature verification remains a documented application
  responsibility after parser-level `SigningCertURL` scheme/host guardrails.
- ✅ `aws-ktor`: DynamoDB server plugin and repository facade are merged by
  PR #87, using `:aws-kotlin` and the official AWS SDK for Kotlin.
- ✅ `aws-spring-boot`: Secrets Manager / Parameter Store refresh support and the
  refresh snapshot race fix are merged by PR #84 and PR #86.
- ✅ Dependencies were refreshed through PR #89 through PR #92: AWS SDK, Ktor 3.5,
  Gradle 9.5.1, and SLF4J 2.0.18.
- ✅ CI/GitHub Actions were refreshed by PR #88, and the CI secret scan installer
  was stabilized after the dependency update wave.

## 🧭 Current Direction

`aws` and `aws-kotlin` remain stable base modules. The highest-impact queue has
shifted from Spring Boot AWSpring parity to Exposed-first database integration,
then streaming support and examples.

- 🟢 `aws-spring-boot`: S3, SQS, SNS, KMS, Secrets Manager, Parameter Store, and
  DynamoDB runtime foundations are merged. Remaining Spring Boot work is SES
  sender support, Exposed database auto-configuration, DynamoDB examples, and
  documentation polish.
- 🟢 `aws-ktor`: S3, SQS, SigV4, and DynamoDB server support are merged. Remaining
  Ktor work is the existing integration migration toward `:aws-kotlin`, Exposed
  database wiring, and SQS/DynamoDB examples.
- 🟡 Examples must compile and test in Nightly. Spring Boot examples must also keep
  `processAot` and `processTestAot` working.
- 🟡 Documentation issue #71 remains open, but the root and module README files now
  cover KMS, remote config, SNS publish/SMS/HTTP endpoint usage, and S3 transfer
  operations. Treat #71 as residual doc polish unless it is narrowed or closed.

## 🔥 Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| 🔴 P1 | [#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74) Exposed-first AWS database foundation | L | Broadest future contract; should define shared database properties, secret/config loading, and named database registry. |
| 🔴 P1 | [#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75) Spring Boot Exposed auto-configuration | L | Depends on #74; Spring Boot adapter over the shared database foundation. |
| 🔴 P1 | [#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76) Ktor AwsExposedPlugin | L | Depends on #74; Ktor server adapter and suspend transaction helper. |
| 🔴 P1 | [#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77) RDS IAM auth token provider | M | Supports Exposed database creation paths; likely after #74 contract is stable. |
| 🟠 P2 | [#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82) Spring Boot and Ktor Exposed AWS database examples | M | Depends on #74/#75/#76 and likely #77 for IAM examples. |
| 🟠 P2 | [#81](https://github.com/bluetape4k/bluetape4k-aws/issues/81) Kinesis and DynamoDB Streams Flow support | L | Independent streaming foundation; higher leverage than docs-only work. |
| 🟠 P2 | [#85](https://github.com/bluetape4k/bluetape4k-aws/issues/85) Migrate existing Ktor integrations toward `:aws-kotlin` | M | Audit S3/SQS/SigV4 Java SDK exposure after the DynamoDB Kotlin-first path. |
| 🟡 P3 | [#14](https://github.com/bluetape4k/bluetape4k-aws/issues/14) Spring Boot DynamoDB example | M | Unblocked by PR #31; must compile/test and satisfy Spring Boot AOT. |
| 🟡 P3 | [#16](https://github.com/bluetape4k/bluetape4k-aws/issues/16) Ktor SQS example | M | Unblocked by PR #60; should exercise consumer and publish paths. |
| 🟡 P3 | [#17](https://github.com/bluetape4k/bluetape4k-aws/issues/17) Ktor DynamoDB example | M | Unblocked by PR #87; should follow the Kotlin-first DynamoDB plugin. |
| 🟢 P4 | [#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7) SES sender | M | Standalone Spring Boot feature; lower ecosystem leverage than Exposed and streaming. |
| ⚪ P5 | [#71](https://github.com/bluetape4k/bluetape4k-aws/issues/71) aws-spring-boot README feature refresh | S | Documentation-only residual; core README gaps were narrowed by recent docs work. |

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
```

## 🚧 WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| 🔴 Database foundation | 1 | `#74`; do not start #75/#76 before the shared contract is clear. |
| 🟠 Framework adapters | 1 | `#75` or `#76` only after #74 has a stable shape. |
| 🟠 Streaming | 1 | `#81` can proceed independently if database work pauses. |
| 🟡 Examples | 1 | Choose one of `#14/#16/#17/#82`; Spring Boot examples must keep AOT tasks green. |
| ⚪ Docs/KDoc polish | 1 | `#71` only as a small focused PR or close/narrow after README verification. |
