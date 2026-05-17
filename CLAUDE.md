# CLAUDE.md - bluetape4k-aws

AWS SDK v2 and AWS Kotlin SDK wrappers for bluetape4k. This repository provides
coroutine-first AWS access, Spring Boot 4 auto-configuration, and Ktor 3
integration.

- **Group**: `io.github.bluetape4k.aws`
- **Base version**: `0.1.0-SNAPSHOT`
- **Publishing**: Maven Central through `nmcp` with `publishingType=AUTOMATIC`

## Repository Layout

| Module | Status | Description |
|---|---|---|
| `aws/` | stable | AWS Java SDK v2 sync, async `CompletableFuture`, and coroutine extensions for DynamoDB, S3, SES/SESv2, SNS, SQS, KMS, CloudWatch, CloudWatch Logs, Kinesis, and STS |
| `aws-kotlin/` | stable | AWS Kotlin SDK native `suspend` functions and DSL builders |
| `aws-spring-boot/` | active | Spring Boot 4 auto-configuration without awspring |
| `aws-ktor/` | active | Ktor 3 SigV4, S3, SQS, and upcoming DynamoDB integration |
| `bom/` | stable | `bluetape4k-aws-bom` consumer BOM |
| `examples/aws-ktor-s3-examples/` | example | LocalStack-oriented Ktor S3 examples; not published |

Integration tests use LocalStack via Testcontainers. Use
`-Dbluetape4k.aws.emulator=localstack|floci`; default is `localstack`.

## Build Commands

```bash
./gradlew build -x test --parallel
./gradlew :bluetape4k-aws-java:test
./gradlew :bluetape4k-aws-kotlin:test
./gradlew :bluetape4k-aws-java:test --tests "io.bluetape4k.aws.s3.S3ClientSupportTest"
./gradlew :bluetape4k-aws-java:test -Dbluetape4k.aws.emulator=floci
./gradlew build
./gradlew detekt
./gradlew publishBluetapeAwsPublicationToCentralPortal
./gradlew publishBluetapeAwsPublicationToCentralPortal -PsnapshotVersion=
```

## AWS Rules

- AWS service SDK dependencies are `compileOnly` in `bluetape4k-aws-java` and `bluetape4k-aws-kotlin`.
  Consumers must add the runtime service dependencies they use.
- In `bluetape4k-aws-java`, wrap `CompletableFuture` with `.await()` for coroutine APIs.
- In `bluetape4k-aws-kotlin`, call native AWS Kotlin SDK suspend APIs directly.
- Wrap blocking AWS calls in `withContext(Dispatchers.IO)`.
- AWS Kotlin SDK clients own connection pools and threads. Use `withXxxClient`
  for short-lived clients and explicit `close()` for application-scoped clients.

## Documentation Rules

- Keep `README.md` and `README.ko.md` structurally aligned.
- Store shared README images under `docs/assets/` and reference them with the
  same relative path from both locales.
- Keep this file and other agent-facing guidance in English.
