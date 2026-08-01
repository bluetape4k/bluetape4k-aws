# 이슈 #230 Micrometer 관측성 설계

날짜: 2026-06-07
이슈: #230

## 목표

기존 Spring Boot 및 Ktor 확장 계약을 보존하면서 SQS와 선택한 S3 operation에 Micrometer 기반 관측성 adapter를 추가한다.

## 현재 상태

- `aws-spring-boot`에는 이미 `api(libs.micrometer.core)`가 있고 CloudWatch meter 공개에 `MeterRegistry`를 사용한다.
- `aws-ktor`에는 Micrometer 의존성이 없다. `compileOnly(libs.micrometer.core)`와 test-only registry 지원으로 선택 사항을 유지해야 한다.
- Spring SQS는 receive, handle, acknowledgement phase에 `SqsListenerInterceptor`를 노출한다.
- Ktor SQS는 `SqsConsumerObserver` / `SqsConsumerObservation`을 노출한다. receive, invoke, ack, nack, 변환 실패, retry/failure observation에는 이미 operation, outcome, queue URL, duration, tag가 있다.
- Spring S3는 `S3Operations`를 노출하고 Ktor S3는 공통 interface 없이 `S3KtorClient`를 노출한다.
- Micrometer Observation API는 low-cardinality key value를 지원한다. Registry가 있는 coroutine decorator에는 직접 `Timer`를 기록하는 방식이 가장 가볍다.

## 설계

### Spring Boot SQS 계측

- producer/administrative operation용 Micrometer `SqsOperations` decorator를 추가한다.
- `MeterRegistry`가 있으면 `SqsAutoConfiguration`에서 decorator를 등록한다.
- listener receive, handle, acknowledgement phase용 Micrometer `SqsListenerInterceptor`를 추가한다.
- `MeterRegistry`가 있으면 listener interceptor를 자동 등록하여 일반 Spring Boot 사용자가 직접 연결하지 않게 한다.

### Spring Boot S3 계측

- 선택한 object operation인 upload, download, delete, list, resource, presign용 Micrometer `S3Operations` decorator를 추가한다.
- `MeterRegistry`가 있으면 `S3AutoConfiguration`에서 decorator를 등록한다.

### Ktor SQS 계측

- `SqsConsumerObservation`을 Micrometer timer/counter에 연결하는 opt-in bridge `MicrometerSqsConsumerObserver`를 추가한다.
- 사용자가 기존 `observer` hook을 통해 설치할 수 있도록 `SqsConsumerPluginConfig`에 DSL helper를 추가한다.
- producer 사용량을 같은 observer로 측정할 수 있도록 `SqsConsumerRuntime.send`가 `send` observation을 생성하게 확장한다.

### Ktor S3 계측

- `S3KtorClient` 소유권을 바꾸거나 전역 상태를 추가하지 않고 선택한 operation을 위한 가벼운 `MicrometerS3KtorClient` wrapper를 추가한다.
- opt-in 사용을 위한 `S3KtorClient.withMicrometer(...)`를 제공한다.

## Metric

기본 meter 이름:

- `bluetape4k.aws.sqs.operation`
- `bluetape4k.aws.sqs.listener`
- `bluetape4k.aws.s3.operation`
- `bluetape4k.aws.ktor.sqs.operation`
- `bluetape4k.aws.ktor.s3.operation`

기본 low-cardinality tag:

- `service`
- `operation`
- `outcome`
- `exception`
- 사용할 수 있을 때 `listener.id`
- 안전하게 도출하거나 설정한 경우에만 `queue.name`
- 설정에서 명시적으로 활성화한 경우에만 `bucket`

Queue URL, message ID, object key, receipt handle, raw exception message는 기본 tag로 사용하지 않아야 한다.

## 제외 범위

- OpenTelemetry 전용 의존성을 추가하지 않는다.
- 이 이슈에서 모든 S3 multipart helper를 instrument하지 않는다.
- 전역 registry를 도입하지 않는다.
- `aws-ktor` 사용자에게 Micrometer를 runtime 의존성으로 강제하지 않는다.

## 검증

- `aws-spring-boot`의 Micrometer 존재 여부와 `aws-ktor`의 선택적 compile/test 범위를 의존성 검사로 확인한다.
- SQS/S3 decorator와 조건부 등록을 집중적으로 검사하는 Spring Boot 테스트를 실행한다.
- SQS observer mapping, send observation, S3 wrapper를 집중적으로 검사하는 Ktor 테스트를 실행한다.
- `:bluetape4k-aws-spring-boot:test`
- `:bluetape4k-aws-ktor:test`
- `git diff --check`
