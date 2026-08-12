# Issue #489 SQS runtime 소유 client 수명 주기

## 배경

`SqsConsumerRuntime`은 plugin 설정 시 생성한 `SqsAsyncClient`를 소유할 수
있다. 기존 상태 머신은 실행 전 상태를 `STOPPED`로 표현하면서 `stop()` 전에도
owned client를 닫았고, 이후 `start()`가 같은 닫힌 client를 다시 사용하도록
허용했다.

## 결정

- lifecycle을 `NEW -> RUNNING -> STOPPING -> STOPPED`로 분리한다.
- `RUNNING` 또는 `STOPPING` 중 중복 `start()`는 멱등하게 무시한다.
- 시작 전 `stop()`도 `NEW -> STOPPED`로 전환하고 owned client를 한 번 닫는다.
- `STOPPED` 이후 `start()`는 `IllegalStateException`으로 fail-fast 한다.
- injected client는 어떤 lifecycle 경로에서도 닫지 않는다.
- start/stop 상태 전환과 poller 생성은 짧은 lifecycle lock으로 직렬화해
  stop이 poller 생성 중간에 끼어드는 race를 막는다.

재시작 시 client factory를 보존하지 않는 현재 공개 API에서 닫힌 SDK client를
재생성하는 것보다, runtime을 명시적 one-shot으로 만드는 편이 소유권과 ABI를
보존하면서 안전하다.

## 검증 증거

- RED: `stop()` before `start()` 뒤 재시작을 허용하는 기존 구현에서
  `SqsConsumerRuntimeConfigTest`가 `IllegalStateException` 부재로 실패했다.
- GREEN: stop-before-start, start-stop-start, 중복 start/stop, owned/injected
  client ownership을 포함한 targeted SQS runtime 테스트를 통과했다.
- 영/한 `runtime-lifecycle.md`에 one-shot 계약과 테스트 항목을 동일한 구조로
  반영했다.

## 향후 보호 장치

소유 client를 생성하는 Ktor runtime은 초기 상태와 종료 상태를 구분하고,
재시작을 지원하려면 새 client factory와 재생성·실패 정리 계약을 먼저 설계해야
한다. 단순히 `STOPPED`를 `start()`의 재진입 상태로 사용하는 것은 닫힌 자원
재사용을 숨길 수 있으므로 회귀 테스트에서 금지한다.
