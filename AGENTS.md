# AGENTS.md - bluetape4k-aws

This repository inherits the workspace guidance from `../AGENTS.md`.
Read and follow the workspace root guide first. This file only adds
AWS-specific layout, commands, domain rules, and local exceptions.

AWS SDK v2 and AWS Kotlin SDK wrappers for bluetape4k. Supports coroutines,
Spring Boot 4, and Ktor 3.

- Group: `io.github.bluetape4k.aws`
- Base version: `0.1.0-SNAPSHOT`
- Publishing: Maven Central through `nmcp` with `publishingType=AUTOMATIC`

## Modules

| Module | Status | Purpose |
|---|---|---|
| `aws/` | stable | AWS Java SDK v2 sync, async `CompletableFuture`, and coroutine extensions for DynamoDB, S3, SES, SNS, SQS, KMS, CloudWatch, Kinesis, EventBridge Scheduler, STS |
| `aws-kotlin/` | stable | AWS Kotlin SDK native suspend APIs and DSL builders, including EventBridge Scheduler helpers |
| `aws-spring-boot/` | WIP | Spring Boot 4 auto-configuration without awspring |
| `aws-ktor/` | WIP | Ktor 3 client/server integration |
| `bom/` | stable | `bluetape4k-aws-bom` consumer BOM |
| `examples/aws-ktor-s3-examples/` | example | Emulator-oriented Ktor S3 examples; not published |
| `examples/aws-ktor-service-coverage-examples/` | example | Ktor SES/v2, SNS, CloudWatch, CloudWatch Logs, Kinesis, and STS service coverage routes; not published |
| `examples/aws-spring-boot-s3-examples/` | example | Spring Boot 4 S3 WebFlux examples with AOT tasks; not published |
| `examples/aws-spring-boot-sqs-examples/` | example | Spring Boot 4 SQS/SNS fanout examples with AOT tasks; not published |

AWS emulator migration policy is Floci-first. New or migrated emulator-aware
tests should prefer `-Dbluetape4k.aws.emulator=floci`, keep `localstack` as an
explicit fallback, and use `ministack` only as an evaluation/comparison backend
until the target SDK smoke matrix passes repeatedly. Java/Kotlin SDK wrapper
tests default to Floci through their shared AWS test bases; LocalStack remains
the explicit fallback for Floci API coverage gaps.

Store reusable repository guidance, release rules, checklists, and other
durable operating documents under `docs/`, not `.omx/`. Treat `.omx/` as
transient runtime state and local artifacts only.

## Manual Ownership

- `docs/manual/` is the source of truth for detailed user guidance. README files
  summarize the repository and point readers to the manual; do not duplicate a
  full manual chapter in README.
- Keep English and Korean pages structurally aligned. Korean prose must read as
  natural Korean rather than a literal translation while preserving API names,
  links, anchors, and technical meaning.
- Bind a stable manual to an actual release tag and peeled commit. A page may be
  authored on `develop`, but every release source link must resolve in the
  declared `releaseRef` tree.
- Consumers select the `bluetape4k-dependencies` version. Internal AWS BOM and
  service SDK versions are implementation details unless a runtime
  `compileOnly` dependency requires the application to add that service SDK.
- Keep workshops and runnable examples as first-class learning paths.
- Store each manual diagram as an editable SVG plus a 2x authoritative PNG.
  Follow the `bluetape-diagram` checklist, the established dark palette, and
  full-size visual inspection before publication.

For release work, check the workspace governance docs first:
`../.github/docs/release/central-portal-release-runbook.md`,
`../.github/docs/release/pre-release-checklist.md`, and
`../.github/docs/governance/version-and-release-train.md`.

## Commands

```bash
./gradlew build -x test --parallel
./gradlew :bluetape4k-aws-java:test
./gradlew :bluetape4k-aws-kotlin:test
./gradlew :bluetape4k-aws-spring-boot:test
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.s3.S3ClientSupportTest"
./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=floci
./gradlew :bluetape4k-aws-spring-boot:test -Dbluetape4k.aws.emulator=ministack
./gradlew :aws-spring-boot-s3-examples:processAot :aws-spring-boot-s3-examples:processTestAot
./gradlew :aws-spring-boot-sqs-examples:processAot :aws-spring-boot-sqs-examples:processTestAot
./gradlew build
./gradlew detekt
./gradlew exportManualModuleInventory --no-daemon
TAG=0.4.0; SHA=$(git rev-parse "$TAG^{}"); ruby scripts/manual/validate_release_manuals.rb "$TAG" "$SHA"
ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
ruby scripts/manual/manual_contract_test.rb
./gradlew publishBluetapeAwsPublicationToCentralPortal
./gradlew publishBluetapeAwsPublicationToCentralPortal -PsnapshotVersion=
```

## AWS-Specific Rules

- AWS service SDK dependencies are declared as `compileOnly` in both
  `bluetape4k-aws-java` and `bluetape4k-aws-kotlin`. Consumers must add the
  runtime service dependencies they use.
- In `bluetape4k-aws-java`, wrap `CompletableFuture` with `.await()`.
- In `bluetape4k-aws-kotlin`, use native AWS Kotlin SDK suspend APIs directly.
- Wrap blocking AWS calls in `withContext(Dispatchers.IO)`.
- AWS Kotlin SDK clients hold connection pools and threads; always close them.
  Use `withXxxClient { }` for short-lived clients and explicit `close()` for
  application-scoped clients.

## Repo-Specific Guards

- Prefer local emulator/Testcontainers verification for S3, DynamoDB, SQS, SNS,
  or other service integrations when available.
- Run emulator-backed checks sequentially when Docker resources are shared.
