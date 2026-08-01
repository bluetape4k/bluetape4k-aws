# Issue 308 EventBridge 핵심 래퍼

## 배경

Issue #308에서는 상위 수준의 프레임워크 통합에 앞서 EventBridge 핵심 지원을 추가한다.

## 결정

첫 EventBridge API는 클라이언트 팩터리, 요청 빌더, 단일 요청 헬퍼, 원본 SDK 응답만 제공하는 얇은 계층으로 유지한다. 이 issue에서는 숨겨진 배치 처리, 재시도, 정리, Scheduler 지원, 글로벌 엔드포인트, 교차 계정 오케스트레이션, 프레임워크 통합을 추가하지 않는다.

## 결과

Java SDK v2에는 동기, 비동기, coroutine EventBridge 헬퍼를 추가했고 AWS Kotlin SDK에는 native suspend EventBridge 헬퍼를 추가했다. 두 모듈 모두 런타임 EventBridge SDK 의존성과 부분 실패 응답 계약을 문서화한다.

## 검증

- Java 및 AWS Kotlin 모듈의 EventBridge 대상 테스트가 통과했다.
- 두 모듈에서 `compileTestKotlin --warning-mode all`이 통과했다.
- 저장소에 `*EventBridgeEmulator*` smoke 검사가 없으므로 실제 emulator 지원을 주장하지 않고 미지원으로 기록했다.

## 향후 지침

#309 프레임워크 통합을 추가할 때는 이 핵심 헬퍼를 재사용하고 원본 부분 실패 응답을 보존한다. 테스트에서 사용하는 정확한 EventBridge event bus/rule/target workflow를 Floci 또는 LocalStack이 지원함을 입증한 뒤에만 emulator smoke 검사를 추가한다.
