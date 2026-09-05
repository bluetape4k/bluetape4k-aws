# 취소 cleanup은 종료 대기부터 자원 해제까지 하나의 경계로 묶는다

## 배경

[Issue #619](https://github.com/bluetape4k/bluetape4k-aws/issues/619)의 Kinesis consumer는
shard마다 lease heartbeat를 실행하고, shard 처리가
끝나면 heartbeat를 종료한 뒤 lease를 해제한다. 기존 구현은 취소된 shard coroutine에서
`heartbeat.join()` 또는 `heartbeat.cancelAndJoin()`을 먼저 호출하고, 그 다음에야
`NonCancellable` lease release를 시작했다.

## 원인과 실패 증거

`Job.join()`과 `cancelAndJoin()`은 호출자의 취소를 전파한다. heartbeat가 아직 종료되지
않았다면 종료 대기에서 `CancellationException`이 발생해 뒤의 `release()`가 실행되지
않을 수 있다. fake lease store로 이 경로를 재현하자 `aws-kotlin`과 `aws-java` 테스트가
모두 release 신호를 받지 못하고 30초 후 `UncompletedCoroutinesError`로 실패했다.

또한 cleanup에서 새 오류가 발생했을 때 기존 작업 오류를 유지할지, cleanup 오류로
교체할지 두 모듈의 규칙이 일치하지 않았다.

## 결정

- heartbeat 취소, 종료 대기, bounded lease release를 하나의 `NonCancellable` 블록에서
  순서대로 실행한다.
- shard 처리 중 발생한 오류를 primary failure로 유지한다. heartbeat 종료 대기나 lease
  release가 실패하면 cleanup failure로 모으고 primary failure에 suppressed로 연결한다.
- primary failure가 없으면 cleanup failure를 호출자에게 전파한다.
- lease release는 기존 `leaseReleaseTimeout`과 `withTimeoutOrNull` 경계를 유지한다.
- 공개 API, `KinesisLeaseStore` SPI, 옵션 기본값은 변경하지 않는다.

## 결과

collector가 취소되어도 두 consumer 모두 5초 테스트 경계 안에서 lease release를 정확히
한 번 완료한다. cancellation의 타입과 메시지는 유지되며, shard primary failure와 lease
release failure가 함께 발생하면 shard failure가 결과를 결정하고 release failure는
suppressed chain에 남는다.

## 검증

- RED: 두 모듈의 cancellation 회귀 테스트가 release 신호를 기다리다 각각 30초에 실패
- targeted: Kotlin Kinesis 10개, Java SDK v2 Kinesis 6개 통과
- 전체 모듈: `aws-kotlin` 769개 통과, 13개 skipped, 실패 0개
- 전체 모듈: `aws-java` 532개 통과, 15개 skipped, 실패 0개
- 정적 분석: 두 모듈 `detekt` 성공
- 통합 검사: 두 모듈 `check` 성공
- 변경 경계: `git diff --check` 성공, 공개 선언 변경 없음

GitHub CI exact-head 검증은 PR 단계에서 별도로 수행한다.

## 놓친 점과 향후 지침

`finally` 일부만 `NonCancellable`로 감싸면 cleanup 전체를 보장하지 못한다. 자원 해제 전에
실행하는 child job 종료 대기도 동일한 취소 불가 경계 안에 있어야 한다.

- cleanup 테스트는 `release()` 호출 여부만 세지 말고 bounded 시간 안의 완료와 정확히
  한 번 실행됐는지를 함께 검증한다.
- 취소 테스트는 cleanup 오류가 cancellation을 대체하지 않는지 확인한다.
- 여러 cleanup 단계가 있으면 첫 cleanup 오류를 보존하고 이후 오류를 suppressed로
  연결한다.
- Java SDK v2와 AWS Kotlin SDK wrapper가 같은 lifecycle을 제공하면 두 모듈에 같은 실패
  시나리오를 유지한다.
