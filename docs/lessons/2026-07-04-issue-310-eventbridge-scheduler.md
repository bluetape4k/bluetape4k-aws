# Issue #310 EventBridge Scheduler 지원

## 배경

EventBridge 핵심 및 통합 작업을 반영한 뒤 마일스톤 `0.5.0`에는 EventBridge Scheduler 지원이 필요했다. Scheduler는 EventBridge event-bus 기능이 아니라 별도의 AWS 서비스 아티팩트(`scheduler`)이므로 구현에서 패키지와 의존성 경계를 명확히 유지해야 했다.

## 결정

별도의 Scheduler 패키지 아래에 얇은 Java SDK v2 및 AWS Kotlin SDK 헬퍼 API를 추가한다.

- `io.bluetape4k.aws.scheduler`
- `io.bluetape4k.aws.scheduler.model`
- `io.bluetape4k.aws.kotlin.scheduler`
- `io.bluetape4k.aws.kotlin.scheduler.model`

헬퍼는 schedule, schedule-group, target, flexible-window, retry-policy, dead-letter 요청을 만들고 동기, 비동기, coroutine, native suspend 클라이언트 호출의 원본 SDK 응답을 반환한다.

## 결과

- AWS Java SDK v2 및 AWS Kotlin SDK용 선택적 `scheduler` 의존성 별칭을 추가했다.
- Scheduler 목록 page 크기, flexible time window, 재시도 event age, 재시도 횟수의 요청 범위 검증을 추가했다.
- Target별 검증, 교차 계정 오케스트레이션, 서비스 측 의미는 bluetape4k 헬퍼 범위에서 제외했다.
- 영문 및 한글 README의 의존성 지침과 EventBridge 경계 설명을 갱신했다.

## 검증

- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-kotlin:compileKotlin` — PASS
- `./gradlew :bluetape4k-aws-java:test --tests '*scheduler*' :bluetape4k-aws-kotlin:test --tests '*scheduler*'` — PASS
- `git diff --check` — PASS

## 향후 지침

Scheduler 지원을 Spring Boot 또는 Ktor로 확장할 때는 해당 통합을 EventBridge event-bus 작업과 분리하고 런타임 `scheduler` SDK 아티팩트를 명시적으로 요구한다.
