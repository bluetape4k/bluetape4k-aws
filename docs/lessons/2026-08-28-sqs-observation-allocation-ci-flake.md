# SQS 관찰 disabled fast path allocation CI 변동 교훈

## 실패한 가정과 발견 증거

`SqsObservationAllocationTest`의 paired `ThreadMXBean` 측정이 같은 JVM의
direct baseline과 비교되므로 hosted 환경에서도 안정적으로 0.5 B/op 이하를
관찰할 것이라고 가정했다. 그러나 base `develop`와 SNS PR head의 hosted
`aws-spring-boot` job에서 같은 테스트가 반복 실패했고, exact error는 다음과
같았다.

`Expected <88.0> to be less than or equal to <0.5>, but was not.`

실패한 테스트는 비활성 경로의 `SqsObservationExecution` 생성 지점이었다.
로컬 GraalVM 25에서는 escape analysis가 임시 객체를 제거해 PASS했지만,
hosted Temurin 25에서는 호출마다 객체가 실제 할당될 수 있었다. 따라서
local PASS를 hosted allocation 계약의 증거로 취급하면 안 된다.

## 수정 결정

관찰 context가 없는 disabled 경로의 execution은 상태를 변경하지 않으므로
`Observation.NOOP`와 null context를 가진 singleton을 재사용한다. 먼저 호출마다
새 execution이 만들어지는지 정체성 회귀 테스트를 RED로 확인한 뒤 singleton을
도입해 GREEN으로 전환했다. supporting context factory는 계속 호출하지 않아야
하며, enabled 경로의 execution과 retry event 상태는 변경하지 않는다.

## 향후 예방 확인

- 성능 테스트는 paired allocation 수치와 invocation·factory sentinel을 함께
  검증한다.
- allocation 계약에는 escape analysis에 의존하지 않는 객체 정체성 회귀 테스트를
  둔다.
- 로컬 JVM vendor와 hosted CI vendor가 다르면 양쪽 결과를 별도 증거로 기록하고,
  hosted exact-head CI가 통과하기 전에는 merge-ready로 표시하지 않는다.
- 동일 실패가 base와 PR에서 반복되면 PR 범위와 분리된 bugfix worktree에서
  원인을 먼저 고정하고, 기존 기능 PR의 CI 상태를 임의로 재분류하지 않는다.

## 검증 경계

이번 수정은 `aws-spring-boot` SQS observation runtime과 allocation 회귀 테스트,
이 교훈 문서에만 적용한다. 실제 AWS 계정, 장시간 profiler, hosted exact-head
CI 재실행은 별도 검증 단계다.
