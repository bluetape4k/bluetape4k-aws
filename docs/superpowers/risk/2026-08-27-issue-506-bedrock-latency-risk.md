# #506 Bedrock latency·heap/throughput harness 위험 기록

## 위험과 대응

| 위험 | 영향 | 대응/중단 조건 |
| --- | --- | --- |
| blocking cleanup이 테스트 dispatcher를 점유 | 테스트 hang 또는 executor leak | 별도 bounded worker, watchdog와 releasable latch를 사용하고 5초 초과 시 실패한다. |
| delayed cleanup의 scheduler race | publisher/coordinator latency 필드가 뒤섞임 | cancel request와 cleanup completion을 서로 다른 atomic 시각으로 기록하고 completion을 await한다. |
| cancellation이 삼켜짐 | 성공처럼 보이는 가짜 lifecycle proof | `CancellationException`은 재전파하고 cleanup 관찰만 `NonCancellable`에서 수행한다. |
| host/JVM noise | heap/throughput 수치 오해 | absolute target을 두지 않고 raw samples, JVM, dispatcher, warmup, measurement를 artifact에 남긴다. |
| ThreadMXBean 미지원 | allocation 증거 부재 | 지원 여부를 명시적으로 검사하고 실패시키며 heap snapshot은 보조 관찰값으로 남긴다. |
| late callback map 잔류 | terminal resource retention | terminal 뒤 late publisher를 주입해 cancel과 pending count 0을 hard assert한다. |
| failure marker가 원 Throwable을 참조 | 장기 heap retention | failure 20개에서 marker cause/suppressed identity를 검사하고 원 참조가 없음을 assert한다. |
| 실제 AWS/credential 경로 혼입 | 재현성·보안 경계 훼손 | MockK client와 test-owned publisher만 허용하고 endpoint/credential 설정을 사용하지 않는다. |
| #505 contract regression | 기존 controlled harness 파손 | #505 targeted 40건과 #506 targeted를 순차 재실행하고 production diff를 0으로 확인한다. |

## 위험 수용 기준

- lifecycle assertion, blocking release, executor 종료, pending 0, retention bound 중
  하나라도 실패하면 성능 수치를 해석하지 않고 구현을 멈춘다.
- p50/p95/p99·heap delta·throughput은 비교 snapshot일 뿐 서비스 보장으로 승격하지 않는다.
- production telemetry/public API/dependency 요구가 생기면 이 범위를 닫고 별도 이슈를 연다.

## Rerun / rollback

- watchdog timeout과 allocation API 부재는 해당 실행을 FAIL로 기록하고 원인을 고친다.
- source hash 변경 시 baseline/candidate를 함께 재생성한다.
- 실패 시 #506 새 test/adapter/docs만 되돌리며 #505 merged harness는 보존한다.

## Writer DoD

- **SPW-01:** 위험 대상·독자·범위와 근거를 기록했다.
- **SPW-02:** 위험별 대응, stop condition, rerun/rollback을 표로 고정했다.
- **SPW-03:** 한국어 기술 용어와 코드 token을 보존했다.
- **SPW-04:** #505 adapter, SQS allocation precedent와 #506 acceptance를 대조했다.
- **SPW-05:** 표·heading·숫자·경로를 최종 read-back한다.
