# 이슈 #201 Ktor CloudWatch 및 CloudWatch Logs 설계

날짜: 2026-06-08
이슈: #201
마일스톤: 0.4.0

## 배경

`aws-spring-boot`는 이슈 #194를 통해 이미 CloudWatch와 CloudWatch Logs 연산을
제공한다. `aws-ktor`에는 SQS와 IMDS에서 확립한 Ktor 네이티브 생명주기 패턴이
있지만 CloudWatch용 플러그인은 없다. 이슈 #201은 Ktor 생명주기 소유권,
코루틴 연산, 명시적 사용 방식을 유지하면서 CloudWatch 메트릭과 CloudWatch
Logs 이벤트를 게시할 수 있는 선택적 Ktor 플러그인을 요구한다.

현재 재사용할 수 있는 자산은 다음과 같다.

- `bluetape4k-aws-java`는 다음 AWS SDK Java v2 코루틴 확장을 이미 제공한다.
  `CloudWatchAsyncClient.putMetricData`, `CloudWatchAsyncClient.listMetrics`,
  `CloudWatchLogsAsyncClient.createLogGroup`, `createLogStream`,
  `putLogEvents`, `describeLogGroups`, and `describeLogStreams`.
- `aws-ktor`는 `SqsConsumer`와 `ImdsKtorPlugin`에서 플러그인이 생성한 클라이언트와
  주입된 클라이언트의 소유권을 이미 구분한다.
- `AwsKtorCore`는 공통 리전, 엔드포인트 재정의, 자격 증명, 서비스 클라이언트
  커스터마이저를 애플리케이션 속성에 저장한다.
- `bluetape4k-projects`는 `bluetape4k-ktor-core`와 `bluetape4k-ktor-testing`을 제공한다.
  `aws-ktor`는 원시 Ktor 서버 설정만을 유일한 경로로 취급하지 않고, 공통 Ktor
  기준선 설치와 Ktor HTTP 테스트 검증에 이 도우미를 사용해야 한다.
- Ktor Micrometer 지원은 전역 Ktor 메트릭 교체가 아니라
  `compileOnly(micrometer-core)`와 명시적인 도우미 객체를 통해 선택적으로 제공한다.

## 목표

1. CloudWatch 메트릭 연산을 위한 `CloudWatchKtorPlugin`을 추가한다.
2. CloudWatch Logs 연산과 명시적인 로그 이벤트 일괄 게시를 위한
   `CloudWatchLogsKtorPlugin`을 추가한다.
3. 소비자에게 AWS 서비스 의존성을 선택 사항으로 유지한다. 이 모듈에서
   `software.amazon.awssdk:cloudwatch`와 `cloudwatchlogs`는 운영 코드의
   `compileOnly`, 테스트의 `testImplementation`으로 선언한다.
4. AWS 요청 연결 코드를 중복하지 않고 `bluetape4k-aws-java` 코루틴 확장을 재사용한다.
5. AWS Ktor 애플리케이션이 같은 설정 블록에서 공통 bluetape4k Ktor 기준선을
   선택할 수 있도록 `AwsKtorCore`를 통해 `bluetape4k-ktor-core`를 재사용한다.
6. 소유권 의미를 보존한다. 주입된 클라이언트와 연산은 플러그인이 닫지 않으며,
   플러그인이 생성한 클라이언트는 Ktor 중지 시 정확히 한 번 닫는다.
7. 게시를 명시적 사용 방식으로 유지한다. 어느 플러그인을 설치해도 기본적으로
   AWS를 호출하거나 데이터를 게시하지 않는다.
8. 버퍼링된 로그 이벤트를 종료할 때 제한 시간 안에서 취소에 안전하게 비운다.
9. 영문 및 한글 `aws-ktor` README 파일을 갱신한다.

## 제외 범위

- 전역 Ktor 로깅 appender를 교체하지 않는다.
- 예약 실행되는 Micrometer CloudWatch 레지스트리나 exporter를 등록하지 않는다.
- 로컬 개발이나 일반 CI에서 CloudWatch, LocalStack 또는 Floci를 요구하지 않는다.
- 이 이슈에서 AWS Kotlin SDK CloudWatch API를 노출하지 않는다. 기존 CloudWatch
  도우미는 AWS SDK Java v2 기반이며 Spring 작업과 일치한다.
- 설치 시 기본으로 로그 그룹이나 스트림을 생성하지 않는다.

## 제안 API

### 공통 Ktor 기본값

`AwsKtorCore`에 서비스별 빌더 커스터마이저를 확장한다.

- `AwsKtorCoreConfig.ktorCore(...)`: 명시적으로 요청한 경우에만 공통
  `bluetape4k-ktor-core` 기준선을 설치한다.
- `AwsKtorCloudWatchAsyncClientCustomizer`
- `AwsKtorCloudWatchLogsAsyncClientCustomizer`
- `AwsKtorDefaults.cloudWatchAsyncClientCustomizers`
- `AwsKtorDefaults.cloudWatchLogsAsyncClientCustomizers`
- `AwsKtorCoreConfig.cloudWatchAsyncClient { ... }`
- `AwsKtorCoreConfig.cloudWatchLogsAsyncClient { ... }`

이 커스터마이저는 플러그인이 생성한 Java SDK v2 비동기 클라이언트에만 적용하며,
공통 기본값 다음이자 서비스 로컬 커스터마이저 전에 실행한다.

### CloudWatch 메트릭

패키지: `io.bluetape4k.aws.ktor.cloudwatch`

공개 타입:

- `CloudWatchKtorOperations`
- `CloudWatchKtorTemplate`
- `CloudWatchKtorPluginConfig`
- `CloudWatchKtorRuntime`
- `CloudWatchKtorPlugin`
- `CloudWatchKtorPluginConfig.cloudWatchAsyncClient { ... }`
- `Application.cloudWatch()`
- `Application.cloudWatchOrNull()`

연산 계약:

- `putMetricData(metricData)`는 설정된 기본 네임스페이스를 사용하며, 없거나 빈
  네임스페이스를 거부한다.
- `putMetricData(namespace, metricData)`는 하나 이상의 `MetricDatum` 값을
  `batchSize` 단위로 묶어 게시한다.
- `putMetricDatum(metricDatum)` and `putMetricDatum(namespace, metricDatum)`
  는 편의 래퍼다.
- `listMetrics(namespace?, metricName?, dimensions?)`는 기존 코루틴 확장에 위임한다.
- 빈 메트릭 목록은 `emptyList()`를 반환하고 AWS를 호출하지 않는다.

설정:

- `enabled: Boolean = true`
- `cloudWatchAsyncClient: CloudWatchAsyncClient? = null`
- `cloudWatchOperations: CloudWatchKtorOperations? = null`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `credentialsProvider: AwsCredentialsProvider? = null`
- `namespace: String? = null`
- `batchSize: Int = 1000`
- 서비스 로컬 클라이언트 커스터마이저

검증:

- `batchSize`는 `1..1000`이어야 한다.
- `endpointOverride`에는 유효 리전이 필요하다.
- 주입된 연산에는 클라이언트 전용 검증을 적용하지 않는다.

Micrometer 브리지:

- Spring 스냅샷 도우미와 같은 형태로 `CloudWatchKtorMeterPublishingOperations`와
  `CloudWatchKtorMeterPublishingTemplate`을 추가한다.
- 애플리케이션 코드가 `publishMeters` 또는 `publishMeter`를 호출할 때만 기존
  `MeterRegistry`를 읽는다.
- 전역 레지스트리, 스케줄러 또는 자동 게시를 등록하지 않는다.

### CloudWatch Logs 연산

패키지: `io.bluetape4k.aws.ktor.cloudwatch`

공개 타입:

- `CloudWatchLogStream`
- `CloudWatchLogsKtorOperations`
- `CloudWatchLogsKtorTemplate`
- `CloudWatchLogsKtorPluginConfig`
- `CloudWatchLogsKtorRuntime`
- `CloudWatchLogsKtorPlugin`
- `CloudWatchLogsKtorPluginConfig.cloudWatchLogsAsyncClient { ... }`
- `Application.cloudWatchLogs()`
- `Application.cloudWatchLogsOrNull()`

연산 계약:

- `createLogGroup(logGroupName)`
- `createLogStream(logStream)`: `logStream`은 `logGroupName`과 `logStreamName`을
  포함하는 `CloudWatchLogStream` 값 객체다.
- `putLogEvents(logEvents)`: 설정된 기본 로그 그룹과 스트림을 사용한다.
- `putLogEvents(logStream, logEvents)`
- `describeLogGroups(logGroupNamePrefix?)`
- `describeLogStreams(logGroupName, logStreamNamePrefix?)`
- 빈 로그 이벤트 목록은 `emptyList()`를 반환하고 AWS를 호출하지 않는다.

런타임 버퍼 게시:

- `CloudWatchLogsKtorRuntime.append(message, timestamp = Instant.now())`는 명시적인
  이벤트 하나를 메모리 내 버퍼에 추가한다.
- `append(InputLogEvent)`는 이미 생성된 이벤트를 추가한다.
- `flush()`는 버퍼링된 이벤트를 `batchSize` 단위로 전송한다.
- 버퍼가 비어 있으면 `flush()`는 AWS를 호출하지 않고 반환한다.
- `start()`는 플러그인이 활성화된 경우에만 선택적 주기 flush 작업 하나를
  시작하며, 이벤트가 추가되기 전까지 유휴 상태를 유지한다.
- `stop()`은 주기 작업을 취소하고, `shutdownFlushTimeout` 안에 남은 버퍼 이벤트를
  비운 뒤 플러그인 소유 클라이언트만 닫고 반환한다.
- 종료 flush가 시간 초과되면 런타임은 flush를 취소하고 플러그인 소유 클라이언트는
  여전히 한 번 닫는다.

설정:

- `enabled: Boolean = true`
- `cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient? = null`
- `cloudWatchLogsOperations: CloudWatchLogsKtorOperations? = null`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `credentialsProvider: AwsCredentialsProvider? = null`
- `logGroupName: String? = null`
- `logStreamName: String? = null`
- `batchSize: Int = 10000`
- `flushInterval: Duration = Duration.ofSeconds(5)`
- `shutdownFlushTimeout: Duration = Duration.ofSeconds(5)`
- `createLogGroupOnStart: Boolean = false`
- `createLogStreamOnStart: Boolean = false`
- 서비스 로컬 클라이언트 커스터마이저

검증:

- `batchSize`는 `1..10000`이어야 한다.
- `flushInterval`과 `shutdownFlushTimeout`은 양수여야 한다.
- `endpointOverride`에는 유효 리전이 필요하다.
- 기본 `putLogEvents`, `append`, 시작 설정에는 비어 있지 않은 `logGroupName`과
  `logStreamName`이 필요하다.
- 주입된 연산에는 클라이언트 전용 검증을 적용하지 않지만, 기본 또는 버퍼 게시를
  사용할 때는 기본 로그 식별자가 필요하다.
- 로그 그룹과 스트림이 모두 필요한 공개 메서드는 같은 타입의 위치 기반 문자열을
  잘못 전달하는 일을 막기 위해 `CloudWatchLogStream`을 사용한다.

## 생명주기와 실패 모드

- 플러그인을 설치하면 연산과 런타임을 애플리케이션 속성에만 저장한다. 로그에서
  `createLogGroupOnStart` 또는 `createLogStreamOnStart`를 명시적으로 활성화하지
  않는 한 AWS를 호출하지 않는다.
- `CloudWatchKtorRuntime.stop()`과 `CloudWatchLogsKtorRuntime.stop()`은 멱등이다.
- 기존 Ktor 플러그인 패턴을 따라 `ApplicationStopping`에서 `Dispatchers.IO`로
  SDK 클라이언트를 닫는다.
- 재시도 동작은 AWS SDK 클라이언트 설정에 위임한다. Ktor 플러그인은 별도 재시도
  루프를 추가하지 않으며, 서비스별 재시도 정책이 필요한 애플리케이션은 플러그인이
  생성하는 클라이언트 빌더를 커스터마이즈할 수 있다.
- suspend AWS 호출은 `CancellationException`을 다시 던져야 하며 suspend 호출
  주변에서 `runCatching`을 사용하지 않아야 한다.
- 동시 append/flush 호출에서 중복 flush나 이벤트 유실을 막도록 버퍼링된
  CloudWatch Logs 접근을 `Mutex`로 보호한다.
- CloudWatch Logs는 배치 안에서 시간순 정렬을 요구하므로 게시 전에 로그 이벤트를
  타임스탬프순으로 정렬한다.

## 문서 요구 사항

`aws-ktor/README.md`와 `aws-ktor/README.ko.md`를 함께 갱신한다.

- CloudWatch 및 CloudWatch Logs 플러그인 기능 목록 항목
- `bluetape4k-ktor-core`의 JSON/상태/상태 확인 기준선을 원하는 애플리케이션을 위해
  `ktorCore()`를 보여 주는 공통 `AwsKtorCore` 예제
- 간결하게 유지할 수 있을 때만 CloudWatch 커스터마이저를 포함하는 공통
  `AwsKtorCore` 기본값 예제
- 명시적 `putMetricDatum`을 보여 주는 CloudWatch 메트릭 코드 조각
- 명시적 `append`와 종료 flush 동작을 보여 주는 CloudWatch Logs 코드 조각
- 네임스페이스, 로그 그룹/스트림, 배치 크기, flush 간격, 종료 flush 제한 시간 옵션 표
- 기본적으로 AWS 게시가 발생하지 않고 전역 로깅 appender나 Micrometer 레지스트리를
  교체하지 않는다는 설명
- 새 공개 API KDoc은 영어로 작성하고 소유권, 명시적 게시, 종료 동작을 명시한다.

## 인수 검사

- 비활성화된 플러그인은 애플리케이션 속성을 저장하지 않고 `*OrNull()`을 null로 노출한다.
- 주입된 연산에는 클라이언트 전용 검증을 적용하지 않는다.
- 주입된 SDK 클라이언트는 애플리케이션 소유로 남으며 닫지 않는다.
- 플러그인이 생성한 SDK 클라이언트는 정확히 한 번 닫는다.
- CloudWatch 메트릭 배치는 `batchSize`를 사용하고 빈 배치를 건너뛴다.
- CloudWatch Logs 배치는 `batchSize`를 사용하고 중지 시 flush하며
  `shutdownFlushTimeout`을 준수한다.
- 빈 CloudWatch Logs flush는 AWS를 호출하지 않는다.
- 로그 그룹/스트림의 시작 설정은 명시적으로 선택하며 기본값은 비활성이다.
- Micrometer 스냅샷 브리지는 명시적으로 호출할 때만 게시한다.
- `aws-ktor` 영문/한글 README 파일을 함께 갱신한다.

## 위험

- 최신 `PutLogEvents`에는 CloudWatch Logs 시퀀싱 토큰이 더 이상 필요하지 않지만
  순서는 여전히 중요하다. 구현은 전송 전에 이벤트를 정렬해야 한다.
- 주기적 flush는 숨어 있는 AWS 호출을 만들 수 있다. 이 설계는 애플리케이션 코드가
  이벤트를 추가하기 전까지 유휴 상태를 유지하고, append가 명시적 게시 요청임을 문서화한다.
- 공통 커스터마이저를 추가하면 `AwsKtorDefaults`의 동등성/hash 동작이 바뀐다.
  테스트는 새 커스터마이저 순서와 기본값 저장을 검증해야 한다.
