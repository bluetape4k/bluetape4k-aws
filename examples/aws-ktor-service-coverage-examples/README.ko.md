# AWS Ktor Service Coverage Examples

[English](./README.md) | 한국어

이 모듈은 아직 example coverage가 부족했던 `bluetape4k-aws-ktor` service
plugin을 하나의 작은 Ktor application surface에서 보여줍니다. SES/v2, SNS,
CloudWatch, CloudWatch Logs, Kinesis, STS를 다룹니다.

## Routes

| Route | Service | Operation |
|---|---|---|
| `POST /coverage/email` | SES/v2 | simple email request 전송 |
| `POST /coverage/notifications` | SNS | topic notification publish |
| `POST /coverage/metrics` | CloudWatch | metric datum 1건 publish |
| `POST /coverage/logs` | CloudWatch Logs | log event 1건 기록 |
| `POST /coverage/stream-records` | Kinesis | stream record 1건 기록 |
| `GET /coverage/identity` | STS | caller identity 조회 |

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

`ServiceCoverageExampleOptions`는 route에서 사용하는 리소스 이름을 묶습니다.
CloudWatch namespace, CloudWatch Logs stream, Kinesis stream name, SNS topic
ARN이 여기에 들어갑니다. 실제 application에서는 host application이 소유한 AWS SDK
client, endpoint override, credentials로 만든 operations를 넘기면 됩니다.

## Emulator And Fallback Policy

이 모듈의 테스트는 MockK 기반 operation facade를 주입합니다. 이렇게 하면 여러
서비스의 emulator 지원 차이에 흔들리지 않고 Ktor plugin 설치, application accessor,
JSON request mapping, AWS request mapping, response mapping을 deterministic하게
검증할 수 있습니다.

실제 integration run에서는 필요한 service API를 대상 emulator가 지원할 때 repository의
Floci-first 정책을 따릅니다. Emulator coverage gap은 LocalStack을 명시적 fallback으로
사용하거나, application-owned client configuration에 실제 AWS endpoint와 credentials를
전달해 검증합니다.

## Test

```bash
./gradlew :aws-ktor-service-coverage-examples:test
```
