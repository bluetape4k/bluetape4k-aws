# AGENTS.md - bluetape4k-aws

AWS SDK v2 and AWS Kotlin SDK wrappers for bluetape4k. Supports coroutines,
Spring Boot 4, and Ktor 3.

- Group: `io.github.bluetape4k.aws`
- Base version: `0.1.0-SNAPSHOT`
- Publishing: Maven Central through `nmcp` with `publishingType=AUTOMATIC`

## Modules

| Module | Status | Purpose |
|---|---|---|
| `aws/` | stable | AWS Java SDK v2 sync, async `CompletableFuture`, and coroutine extensions for DynamoDB, S3, SES, SNS, SQS, KMS, CloudWatch, Kinesis, STS |
| `aws-kotlin/` | stable | AWS Kotlin SDK native suspend APIs and DSL builders |
| `aws-spring-boot/` | WIP | Spring Boot 4 auto-configuration without awspring |
| `aws-ktor/` | WIP | Ktor 3 client/server integration |
| `bom/` | stable | `bluetape4k-aws-bom` consumer BOM |
| `examples/aws-ktor-s3-examples/` | example | LocalStack-oriented Ktor S3 examples; not published |
| `examples/aws-spring-boot-s3-examples/` | example | Spring Boot 4 S3 WebFlux examples with AOT tasks; not published |
| `examples/aws-spring-boot-sqs-examples/` | example | Spring Boot 4 SQS/SNS fanout examples with AOT tasks; not published |

Integration tests use LocalStack via Testcontainers. Use
`-Dbluetape4k.aws.emulator=localstack|floci`; default is `localstack`.

Root README visual assets live under `docs/assets/` and should be shared by
`README.md` and `README.ko.md` through the same relative path.

For non-trivial GitHub issue work, add or update a durable lesson under
`docs/lessons/YYYY-MM-DD-{slug}.md` before publishing the PR. Treat the lesson
as part of the completion checklist, not an optional follow-up.

## Commands

```bash
./gradlew build -x test --parallel
./gradlew :bluetape4k-aws-java:test
./gradlew :bluetape4k-aws-kotlin:test
./gradlew :bluetape4k-aws-spring-boot:test
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.s3.S3ClientSupportTest"
./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=floci
./gradlew :aws-spring-boot-s3-examples:processAot :aws-spring-boot-s3-examples:processTestAot
./gradlew :aws-spring-boot-sqs-examples:processAot :aws-spring-boot-sqs-examples:processTestAot
./gradlew build
./gradlew detekt
./gradlew publishBluetapeAwsPublicationToCentralPortal
./gradlew publishBluetapeAwsPublicationToCentralPortal -PsnapshotVersion=
```

## AWS-Specific Rules

- AWS service SDK dependencies are declared as `compileOnly` in both `bluetape4k-aws-java` and
  `bluetape4k-aws-kotlin`. Consumers must add the runtime service dependencies they use.
- In `bluetape4k-aws-java`, wrap `CompletableFuture` with `.await()`.
- In `bluetape4k-aws-kotlin`, use native AWS Kotlin SDK suspend APIs directly.
- Wrap blocking AWS calls in `withContext(Dispatchers.IO)`.
- AWS Kotlin SDK clients hold connection pools and threads; always close them.
  Use `withXxxClient { }` for short-lived clients and explicit `close()` for
  application-scoped clients.
