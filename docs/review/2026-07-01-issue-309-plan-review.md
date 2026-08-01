# Issue #309 계획 검토

## 판정

PASS - P0/P1 문제를 발견하지 못했다.

## 검토 내용

- 구현 전 테스트와 의존성 연결부터 시작한다.
- Spring/Ktor 구현 범위가 분리되어 독립적으로 검증할 수 있다.
- 검증표는 Bean 등록, 수명 주기, 원본 응답 전달, 의존성 범위, 문서, emulator 증거, 최종 빌드를 포함한다.
- 마지막 작업에 PR metadata parity와 `## DoD Status`가 포함된다.

## 잔여 위험

- `AwsKtorCore`는 모든 Ktor plugin이 공유하므로 EventBridge customizer 추가 후 값 객체 equality/hash를 컴파일과 단위 테스트로 검증해야 한다.
- Floci에서 EventBridge smoke를 지원하지 않으면 최종 증거에 그 공백을 직접 밝혀야 한다.
