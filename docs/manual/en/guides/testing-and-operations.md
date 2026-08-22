---
manualId: "testing-and-operations"
title: "Testing and Operating AWS Integrations"
locale: "en"
releaseRef: "0.5.0"
---

# Testing and Operating AWS Integrations

Test the AWS boundary that can fail in production: request mapping, endpoint and credentials selection, retry and acknowledgement behavior, client ownership, and shutdown. A mocked client is useful for pure delegation logic, but it does not prove transport, serialization, emulator compatibility, or lifecycle.

![Floci-first emulator test flow](../../assets/testing/emulator-flow.png)

## Evidence ladder

| Level | What it proves | What it does not prove |
| --- | --- | --- |
| Unit or mock test | Request mapping, branching, conversion, retry classification | Real SDK transport, endpoint behavior, IAM, or lifecycle |
| Floci integration test | Fast default path for supported AWS APIs through Testcontainers | Operations not implemented by Floci or AWS production behavior |
| LocalStack fallback | An operation or cross-service path supported by LocalStack | AWS IAM, quota, latency, managed-service policy, or full semantic parity |
| AWS environment smoke test | Current credentials, region, endpoint, and selected service can work together | Repeatable failure recovery, scale, or broad policy coverage |
| Load or resilience test | One declared workload and failure model | A universal throughput or cost claim |

## Floci first, LocalStack explicitly

Repository tests default `bluetape4k.aws.emulator` to `floci`. Keep that default for fast supported paths. When Floci lacks an operation or a cross-service integration needs LocalStack, select it explicitly:

```bash
./gradlew :bluetape4k-aws-java:test \
  -Dbluetape4k.aws.emulator=localstack

./gradlew :aws-spring-boot-sqs-examples:test \
  -Dbluetape4k.aws.emulator=localstack
```

Do not hide emulator gaps by skipping assertions. Mark unsupported operations clearly and keep mock or fallback coverage tied to the gap. Passing against either emulator does not prove production IAM or service limits.

## Step Functions evidence {#step-functions-evidence}

The Issue #313 Step Functions helpers are an unreleased/develop surface. Run
their dedicated smoke checks through the Floci-first selector before trying the
LocalStack fallback:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests "*Sfn*SmokeTest" \
  -Dbluetape4k.aws.emulator=floci \
  --no-daemon --max-workers=1 --console=plain

./gradlew :bluetape4k-aws-kotlin:test \
  --tests "*Sfn*SmokeTest" \
  -Dbluetape4k.aws.emulator=floci \
  --no-daemon --max-workers=1 --console=plain
```

The current Floci guard records the exact message
`live integration unverified: Floci does not support Step Functions` and keeps
the skipped test result. This is `UNVERIFIED`, not a live-integration pass. For
a coverage gap, run the dedicated fixtures sequentially with LocalStack:

```bash
./gradlew :bluetape4k-aws-java:test \
  --tests "*Sfn*SmokeTest" \
  -Dbluetape4k.aws.emulator=localstack \
  --no-daemon --max-workers=1 --console=plain

./gradlew :bluetape4k-aws-kotlin:test \
  --tests "*Sfn*SmokeTest" \
  -Dbluetape4k.aws.emulator=localstack \
  --no-daemon --max-workers=1 --console=plain
```

If LocalStack does not support the operation or the smoke fails, preserve the
test result XML and record
`live integration unverified: LocalStack Step Functions smoke failed: <error>`.
Do not change the result to PASS. Emulator success proves endpoint and basic
serialization compatibility only; it does not prove AWS IAM resource policies,
KMS key policies, service quotas, latency, or managed-service behavior. Keep
IAM/KMS integration `UNVERIFIED` until a separate credential-gated AWS check
exists.

For production polling, enforce a caller-owned timeout or deadline, keep each
collector at or above the helper's one-second interval, and limit aggregate
account/Region polling below the current [Step Functions service
quotas](https://docs.aws.amazon.com/step-functions/latest/dg/service-quotas.html).
Use `shareIn` or `stateIn` for multiple observers of one execution, and add
caller-owned jitter or rate limiting when collectors start together. Record
service, operation, status, outcome, latency, retry/throttle counts, and the AWS
request ID; redact ARNs, names, input, output, errors, causes, trace headers,
and raw SDK payloads. Cancellation must stop collection without an implicit
`StopExecution`; close the service client at the owner boundary and leave an
injected HTTP client or engine under caller ownership.

## Run the smallest relevant example

```bash
./gradlew :aws-ktor-s3-examples:test
./gradlew :aws-ktor-dynamodb-examples:test
./gradlew :aws-ktor-sqs-examples:test
./gradlew :aws-ktor-exposed-examples:test

./gradlew :aws-spring-boot-s3-examples:test
./gradlew :aws-spring-boot-dynamodb-examples:test
./gradlew :aws-spring-boot-sqs-examples:test
./gradlew :aws-spring-boot-exposed-examples:test
```

For service code, add failure cases: duplicate SQS delivery, visibility timeout expiry, conditional DynamoDB write failure, partial S3 transfer, expired presigned URL, unavailable secret source, and shutdown while work is in flight.

## Operational checklist

- **Credentials and region** — use the provider chain appropriate to the deployment. Never log access keys, session tokens, secret payloads, or signed headers.
- **Retries and idempotency** — retry only operations whose business effect is safe. Carry idempotency keys into consumers and writes where duplicates are possible.
- **Timeouts** — configure connect, request, polling, handler, and shutdown timeouts against the service-level budget.
- **Readiness** — report ready only after required clients, queues, tables, buckets, or database pools can serve the path the application requires.
- **Observability** — record service, operation, outcome, retry count, latency, queue phase, and low-cardinality resource identifiers. Avoid bucket keys, message bodies, and secret names when they can expose user data.
- **Shutdown** — stop polling, drain or cancel in-flight work, flush bounded buffers, close clients and pools, then close any explicitly shared HTTP engine.

## Ownership tests

Add a lifecycle test whenever a component can either create or receive a client. Verify that owned clients close exactly once and injected clients remain open. For shared Kotlin SDK engines, verify the engine stays available while another client uses it and is closed by the application only after every client stops.

## Sources

- [Java module emulator selection](../../../../aws-java/src/test/kotlin/io/bluetape4k/aws/AbstractAwsTest.kt)
- [Kotlin module emulator selection](../../../../aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/AbstractAwsTest.kt)
- [Kotlin client lifecycle tests](../../../../aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/lifecycle/ClientLifecycleTest.kt)
- [Spring emulator selector](../../../../aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/test/AwsSpringBootTestEmulator.kt)
- [Ktor SQS shutdown contract](../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerRuntime.kt)
