# PR #654 heartbeat 진단 테스트의 완료 시점

## 잘못된 가정과 근거

`handlerReturned.await()`가 끝나면 heartbeat의 관찰 종료 실패 로그도 이미 기록됐다고 가정했다.
그러나 handler 본문 반환 뒤 `withVisibilityHeartbeat`가 heartbeat 작업을 취소하고 기다리는 동안,
`Dispatchers.IO`에서 실행하는 `ObservationHandler.onStop`은 아직 끝나지 않을 수 있다.
`runCurrent()`는 이 IO 작업의 완료를 보장하지 않는다.

[PR #654](https://github.com/bluetape4k/bluetape4k-aws/pull/654)의
[첫 CI 실행](https://github.com/bluetape4k/bluetape4k-aws/actions/runs/34230440431/attempts/1)은
`SqsMessageListenerContainerTest`의 로그 단언 1개가 실패했다.
동일 head의 재실행 성공만으로 원인이 해결되지는 않았다. 사용자의 후속 수정 요청에 따라
종료 콜백을 latch로 지연해 같은 단언 실패를 재현했다.

## 결정과 회귀 검증

- `onStop` 진입을 확인한 뒤 handler를 반환시킨다. 종료 콜백이 대기하는 동안에는 진단 로그가 없어야 한다.
- latch를 해제하고 appender가 전달하는 `CompletableDeferred`를 기다린 뒤 로그를 검사한다.
  인접한 heartbeat 관찰 시작 실패 테스트의 이벤트 수신 패턴을 재사용한다.
- 동기 `onStop` 콜백의 순서를 제어하므로 `CountDownLatch`를 사용한다.
  임의 지연이나 확률적 경쟁을 만드는 stress helper 대신 실제 문제 순서를 고정한다.
- 원래 로그 단언은 이 조건에서 RED 1/1이었다. 수정된 테스트는 100회 반복 모두 통과했다.
- 로그의 코드·대상·원인을 확인하고 원본 큐 URL과 예외 메시지·stack trace가 노출되지 않는지 검사한다.
- 성공·실패 모두 `finally`에서 latch와 handler를 해제하고 컨테이너 종료를 기다린다.
  종료 대기가 실패해도 내부 `finally`에서 appender를 분리하고 logger 설정을 복원한다.

## 다음 변경의 확인 기준

비동기 테스트는 필요한 결과 자체의 완료 신호를 기다린다. 중간 단계의 완료를 최종 결과의 완료로
해석하지 않는다. 외부 dispatcher와 가상 시간이 섞이면 각 완료 신호가 어느 실행 경계를 증명하는지
검토한다. 단순 재시도 성공을 수정 증거로 쓰지 않고, 문제 순서를 강제한 RED/GREEN을 남긴다.

CI의 `completed`는 성공 여부를 뜻하지 않는다. 이번 작업에서 완료 상태를 성공으로 잘못 보고했다가
정정했으므로, 앞으로 작업과 전체 실행의 `conclusion=success`를 각각 확인한 뒤 보고한다.

이 변경은 테스트의 동기화와 정리만 수정하며 제품 코드와 공개 API는 변경하지 않는다.
전체 모듈 및 새 head의 CI 결과는 PR의 DoD에서 관리한다.
공유 GNO는 머지되지 않은 worktree를 제외하므로 이 문서는 머지 후 정규 색인에 포함한다.
