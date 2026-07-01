# AWS Ktor Service Coverage Examples

English | [한국어](./README.ko.md)

This module demonstrates the remaining `bluetape4k-aws-ktor` service plugins in
one small Ktor application surface. It covers SES/v2, SNS, CloudWatch,
CloudWatch Logs, Kinesis, and STS.

## Routes

| Route | Service | Operation |
|---|---|---|
| `POST /coverage/email` | SES/v2 | Sends a simple email request |
| `POST /coverage/notifications` | SNS | Publishes a topic notification |
| `POST /coverage/metrics` | CloudWatch | Publishes one metric datum |
| `POST /coverage/logs` | CloudWatch Logs | Writes one log event |
| `POST /coverage/stream-records` | Kinesis | Writes one stream record |
| `GET /coverage/identity` | STS | Reads caller identity |

## Plugin Setup

```kotlin
application {
    serviceCoverageExampleModule(
        sesOperations = sesOperations,
        snsOperations = snsOperations,
        cloudWatchOperations = cloudWatchOperations,
        cloudWatchLogsOperations = cloudWatchLogsOperations,
        kinesisOperations = kinesisOperations,
        stsOperations = stsOperations,
    )
}
```

`ServiceCoverageExampleOptions` groups the resource names used by the routes:
CloudWatch namespace, CloudWatch Logs stream, Kinesis stream name, and SNS topic
ARN. Production examples can pass operations backed by AWS SDK clients,
endpoint overrides, and credentials owned by the host application.

## Emulator And Fallback Policy

The tests in this module inject operation facades with MockK. That keeps route
coverage deterministic and verifies Ktor plugin installation, application
accessors, JSON request mapping, AWS request mapping, and response mapping
without depending on uneven emulator support across these services.

For real integration runs, use the repository's Floci-first policy when the
target emulator supports the required service API. Use LocalStack as the
explicit fallback for emulator coverage gaps, or pass real AWS endpoints and
credentials in an application-owned client configuration.

## Test

```bash
./gradlew :aws-ktor-service-coverage-examples:test
```
