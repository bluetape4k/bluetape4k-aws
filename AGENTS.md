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

Integration tests use LocalStack via Testcontainers. Use
`-Dbluetape4k.aws.emulator=localstack|floci`; default is `localstack`.

## Commands

```bash
./gradlew build -x test --parallel
./gradlew :aws:test
./gradlew :aws-kotlin:test
./gradlew :aws:test --tests "io.bluetape4k.aws.s3.S3ClientSupportTest"
./gradlew :aws:test -Dbluetape4k.aws.emulator=floci
./gradlew build
./gradlew detekt
./gradlew publishBluetapeAwsPublicationToCentralPortal
./gradlew publishBluetapeAwsPublicationToCentralPortal -PsnapshotVersion=
```

## AWS-Specific Rules

- AWS service SDK dependencies are declared as `compileOnly` in both `aws` and
  `aws-kotlin`. Consumers must add the runtime service dependencies they use.
- In `aws`, wrap `CompletableFuture` with `.await()`.
- In `aws-kotlin`, use native AWS Kotlin SDK suspend APIs directly.
- Wrap blocking AWS calls in `withContext(Dispatchers.IO)`.
- AWS Kotlin SDK clients hold connection pools and threads; always close them.
  Use `withXxxClient { }` for short-lived clients and explicit `close()` for
  application-scoped clients.
