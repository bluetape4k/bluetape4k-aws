# 종료 경계의 외부 I/O는 원자적 admission으로 차단한다

## 배경

[Issue #638](https://github.com/bluetape4k/bluetape4k-aws/issues/638)은
`SqsMessageListenerContainer.stop()`과 handler permit 반환이 경쟁할 때 poller가
`receive()`를 한 번 더 호출하는 문제를 다룬다. `develop` CI run
[34017233571](https://github.com/bluetape4k/bluetape4k-aws/actions/runs/34017233571)에서는
수신 관측값이 예상한 1회가 아니라 2회로 기록됐다.

## 잘못된 가정

permit 대기가 끝난 직후 generation과 lifecycle을 다시 확인하면 종료 이후의 추가
수신을 막을 수 있다고 가정했다. 이 검사는 permit 대기 중에 발생한 stop은 감지하지만,
검사와 실제 `operations.receive()` 호출 사이의 check-then-act 경쟁은 제거하지 못한다.

## 결정

- 각 listener generation이 독립적인 receive admission gate를 소유한다.
- `stop()`과 fatal generation 종료는 generation 참조를 제거하기 전에 gate부터 닫는다.
- poller는 `beforeReceive`가 끝난 뒤 실제 SQS 호출 직전에 admission token을 원자적으로
  획득한다.
- gate가 닫힌 뒤에는 새 token을 발급하지 않는다. 닫히기 전에 발급한 token은 진행 중인
  수신으로 취급하고 `finally`에서 정확히 한 번 반환한다.

이 순서에서 gate closure가 stop과 새 receive의 선형화 지점이 된다. 단순한 상태 재확인은
이 경계를 대신하지 않는다.

## 결과

`beforeReceive`를 `NonCancellable` 구간에서 의도적으로 중단한 뒤 stop을 실행하는 테스트를
추가했다. 기존 구현에서는 interceptor가 재개된 뒤 `operations.receive()`가 1회 호출돼
테스트가 실패했다. admission gate를 적용한 뒤에는 gate 획득이 거부되어 SQS 호출이
시작되지 않는다.

## 검증

- 새 회귀 테스트 RED: `operations.receive(...) should not be called`, 실제 1회 호출
- 기존 CI 실패 테스트와 새 회귀 테스트 10회 반복: 10/10 통과
- `:bluetape4k-aws-spring-boot:detekt`: 통과
- `:bluetape4k-aws-spring-boot:cleanTest :bluetape4k-aws-spring-boot:test --no-build-cache`:
  1623개 테스트 통과, 6개 skipped

## 놓친 점

첫 수정은 실패한 CI 경로만 좁게 재현해 permit 대기 뒤의 상태 재확인을 추가했다. 독립
리뷰에서 interceptor와 SQS 호출 사이를 고정한 결정적 테스트가 없다는 점과 남은 P1
경쟁을 확인했다. 반복 성공만으로는 검증하지 않은 interleaving의 부재를 증명할 수 없다.

## 향후 보호 장치

종료, 취소, 재시작과 외부 side effect가 경쟁하는 코드는 상태 검사 위치만 검토하지 않는다.
상태 변경과 작업 admission이 공유하는 선형화 지점을 확인하고, 그 직전의 suspend hook을
고정한 테스트로 gate closure 이후 외부 호출이 시작되지 않는지 직접 검증한다.
