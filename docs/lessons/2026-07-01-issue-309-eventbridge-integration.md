# Issue 309 EventBridge 프레임워크 통합

## 배경

Issue #309에서는 #308의 EventBridge 핵심 래퍼 위에 Spring Boot 및 Ktor 통합을 추가한다.

## 결정

프레임워크 계층은 얇게 유지한다. Spring Boot는 auto-configuration을 통해 `EventBridgeOperations` coroutine template을 제공하고, Ktor는 coroutine 및 future 작업을 포함한 `EventBridgeKtorPlugin`을 제공한다. 두 계층 모두 부분 실패 API의 원본 AWS SDK 응답을 보존하며 숨겨진 배치 처리, 재시도, 정리, Scheduler 지원, listener runtime, 글로벌 엔드포인트, 교차 계정 오케스트레이션을 추가하지 않는다.

## 결과

이제 Spring Boot는 region, endpoint, credentials, 공통 기본값, customizer를 지원하는 선택적 EventBridge 비동기 클라이언트와 operations template을 생성한다. Ktor는 명확한 소유권 의미와 함께 EventBridge 작업을 설치한다. 주입한 operations 또는 클라이언트는 애플리케이션이 소유하고, 플러그인이 소유한 클라이언트는 애플리케이션 수명 주기에서 한 번만 닫는다. 루트 및 모듈 README 언어 쌍에는 사용법과 지원하지 않는 범위를 문서화했다.

## 검증

- Spring Boot 및 Ktor EventBridge 컴파일 검증이 통과했다.
- Spring Boot 및 Ktor EventBridge 대상 테스트가 통과했다.
- `git diff --check`가 통과했다.
- 저장소에 `*EventBridge*Emulator*` scaffold가 없으므로 실제 emulator smoke 지원을 주장하지 않았다.

## 향후 지침

EventBridge 부분 실패를 Boolean 헬퍼 API 뒤에 숨기지 않는다. Floci 또는 LocalStack이 정확한 event bus, rule, target 및 `PutEvents` workflow를 지원함을 입증한 뒤에만 emulator smoke 검사를 추가한다. Scheduler, 글로벌 엔드포인트, 더 풍부한 target 검증은 별도 issue로 다룬다.

다이어그램 QA에서는 렌더링한 PNG를 기준 결과물로 취급해야 한다. SVG 스크립트가 통과해도 비좁은 클래스 다이어그램을 허용할 수는 없다. 관계선이 어색하게 우회하거나 반대로 꺾인 것처럼 보이는 둥근 모서리를 사용해야 한다면 먼저 캔버스와 카드 간격을 넓힌다. 그런 다음 검사를 다시 실행하고 원본 크기 PNG를 다시 확인한다.
