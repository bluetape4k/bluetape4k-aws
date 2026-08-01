# Issue #201 구현 검토

Date: 2026-06-08
범위: `aws-ktor`용 CloudWatch 및 CloudWatch Logs Ktor plugin

## 검토 범위

다음 기준으로 구현 diff를 검토했다.

- #201 인수 조건
- 승인된 명세 및 계획 산출물
- `bluetape4k-code-patterns` Kotlin, coroutine, 테스트, 공개 API 규칙
- Ktor plugin lifecycle 및 AWS SDK 비동기 client 소유권 패턴
- `bluetape4k-projects` Ktor 모듈: `bluetape4k-ktor-core`, `bluetape4k-ktor-testing`
- CloudWatch metric batching과 CloudWatch Logs buffered flushing 동작
- README 언어판 동기화 및 의존성 문서

## 검토 결과

| 심각도 | 개수 | 비고 |
|---|---:|---|
| P0 | 0 | 정확성, 보안, 빌드 또는 릴리스 차단 요인이 없다. |
| P1 | 0 | 초기 runtime batching 누락을 PR 전에 해결했다. |
| P2 | 0 | 초기 periodic flush/startup lifecycle 누락을 PR 전에 해결했다. |
| P3 | 0 | 이 PR에 낮은 심각도의 후속 조치가 필요하지 않다. |

## 검토 중 해결한 항목

- P1: `CloudWatchLogsKtorRuntime.flush()`가 비운 buffer 전체를 한 번에 주입된 작업에
  위임해 테스트나 애플리케이션이 사용자 작업 구현을 제공하면 runtime `batchSize` 계약이
  적용되지 않았다. 이제 runtime은 `operations.putLogEvents(...)` 호출 전에 비운 event를
  `batchSize` 단위로 나누며 회귀 테스트는 `listOf(2, 1)` batch를 검증한다.
- P2: 주기적 CloudWatch Logs flush 작업은 일시적 publish 실패 한 번으로 영구 종료될 수
  있었고 startup setup 실패는 plugin 소유 client를 누수하면서 runtime을 started 상태로
  남겼다. 이제 runtime은 buffered event를 보존하면서 cancellation이 아닌 주기적 실패를
  기록하고 startup 실패 시 `started`를 초기화하며 소유 client를 닫는다. 회귀 테스트는
  startup setup 실패 정리와 일시적 실패 후 periodic retry를 다룬다.

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| API/호환성 | PASS | 새 API는 `io.bluetape4k.aws.ktor.cloudwatch` 아래에 추가되며 기존 Ktor SQS, IMDS, core plugin 계약은 변경되지 않는다. |
| Ktor lifecycle | PASS | Plugin은 활성화할 때만 attribute를 설치하고 기본 publish를 하지 않으며 `ApplicationStopping`에서 plugin 소유 client만 닫고 `AwsKtorCore { ktorCore() }` 위에서 실행할 수 있다. |
| Coroutine/cancellation | PASS | Runtime은 `CancellationException`을 다시 던지고 실패 시 비운 log event를 복원하며 buffered 상태에 `Mutex`를 사용하고 `withTimeoutOrNull`로 shutdown flush 시간을 제한한다. |
| 의존성 관리 | PASS | CloudWatch 및 CloudWatch Logs AWS SDK 모듈은 선택적 `compileOnly` 서비스 의존성이며 기존 `bluetape4k-ktor-core`/`testing` 의존성을 직접 사용한다. |
| 테스트 범위 | PASS | lifecycle, ownership, disabled-state, batching, cancellation restoration, startup setup, periodic retry, Micrometer-selection, `bluetape4k-ktor-core` baseline 테스트를 추가했다. |
| 문서 | PASS | `aws-ktor/README.md`와 `aws-ktor/README.ko.md`는 선택적 의존성, metric publishing, log buffering, lifecycle ownership, 구성 knob, `ktorCore()` 사용법을 설명한다. |
| 운영/보안 | PASS | 전역 logging appender, background metric exporter, 로컬 CloudWatch 요구 사항, credential logging, 비밀 자료 처리를 추가하지 않았다. |

## 정적 스캔

- `rg -n "!!|TODO|FIXME|println\\(|runCatching\\s*\\{" ...`
  - 일치 항목 없음
- `rg -n "GlobalScope|runBlocking\\(|Thread\\.sleep|delay\\(|synchronized\\(|@Synchronized|runCatching\\s*\\{" ...`
  - `delay(...)` 일치 항목은 비동기 테스트 simulation과 runtime periodic flush loop로 제한된다.
  - `runBlocking(Dispatchers.IO)` 일치 항목은 기존 Ktor plugin 패턴과 일치하는 Ktor 동기 lifecycle hook bridge로 제한된다.
  - `GlobalScope`, `Thread.sleep`, `synchronized`, `@Synchronized`, `runCatching` 일치 항목이 없다.

## 검증 증거

- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency cloudwatch --configuration compileClasspath`
  - `software.amazon.awssdk:cloudwatch:2.46.0` 확인
- `./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency cloudwatchlogs --configuration compileClasspath`
  - `software.amazon.awssdk:cloudwatchlogs:2.46.0` 확인
- `./gradlew :bluetape4k-aws-ktor:compileKotlin`
  - 통과
- `./gradlew :bluetape4k-aws-ktor:compileTestKotlin`
  - 통과
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest' --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'`
  - AwsKtorCore 및 CloudWatch 집중 테스트 41개 통과
- `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'`
  - `ktorCore()` bridge 후속 조치 전 CloudWatch 집중 테스트 38개 통과
- `./gradlew :bluetape4k-aws-ktor:test`
  - 모듈 테스트 126개 통과
- `git diff --check`
  - 통과

## 게이트 판정

PASS.

구현 검토 게이트 상태:

- `P0=0`
- `P1=0`
