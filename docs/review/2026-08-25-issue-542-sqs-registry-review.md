# #542 SQS registry 7-Tier code review

## 검토 범위

- 기준 base: develop, 설계 commit 0bda4dba
- 구현: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistry.kt
- 테스트: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainerRegistryTest.kt
- 이슈: #542
- 검토 방식: 변경 diff와 touched module을 기준으로 한 source-read-only
  7-Tier 통합 검토

## 기준 P0/P1과 수렴

초기 P1은 전역 running 플래그가 개별 stop과 전역 stop을 같은 상태로
표현하여 남은 listener를 건너뛸 수 있다는 결함이었다. lifecycleStarted와
activeIds를 분리하고 다중 listener 회귀 테스트를 추가하여 수정했다.

최종 결과: P0=0, P1=0.

## 여섯 관점 결과

| 우선순위 | 관점 | 근거 | 결과 |
|---|---|---|---|
| P2 | Performance | activeIds는 O(1) 집합 갱신이며 start/stop 때 기존 container 순회 비용을 추가하지 않는다. 별도 stress benchmark는 범위 밖이다. | 후속 측정 없이 수용 |
| P0/P1 | Stability | registry lock 안에서 lifecycle state와 activeIds를 함께 갱신하고, global stop callback은 모든 snapshot callback 이후 한 번 실행된다. targeted 2/2 통과. | 발견 없음 |
| N/A | Security | 변경된 경로는 AWS credential, URL, payload, deserialization 경계를 다루지 않는다. | 해당 없음 |
| P2 | Operator/Ops | lifecycle 상태와 callback 계약을 바꾸지 않고 stop 중 start 거부를 보존한다. 실제 Floci 전체 검증은 별도 환경 gap이다. | 후속 환경 검증 |
| P0/P1 | Developer/API | public method signature와 SmartLifecycle interface는 유지된다. touched test는 assertFailsWith와 bluetape4k matcher를 사용한다. production !!와 raw JUnit exception assertion은 없다. | 발견 없음 |
| N/A | User/Caller | registry는 내부 Spring integration surface이며 public README나 migration 문구가 바뀌지 않는다. | 해당 없음 |

## 7-Tier 판정

1. Correctness: 개별 stop 후 activeIds에 남은 listener가 유지되고 global
   stop이 전체 snapshot을 drain하는 회귀를 검증했다.
2. API/ABI: method signature 변경이 없고 binary compatibility risk가 없다.
3. Concurrency/Lifecycle: ReentrantLock 경계에서 lifecycleStarted,
   activeIds, stoppingIds를 갱신하며 callback once 계약을 유지한다.
4. Ecosystem integration: test는 bluetape4k assertions의 assertFailsWith,
   shouldContain, shouldBeEqualTo, shouldBeTrue를 사용한다.
5. Tests: RED는 기존 구현에서 새 테스트가 실패했고, GREEN은 targeted
   registry 2/2 통과했다. non-emulator Spring module 608개도 통과했다.
6. Performance/Resource: 새로운 thread, coroutine, polling, fixed sleep,
   dependency가 없으며 set 연산만 추가했다.
7. Maintainability: lifecycleStarted와 activeIds 이름이 책임을 분리하고
   설계 spec과 실행 plan이 traceability를 제공한다.

## 검증 증거

- RED: 기존 구현에서 individualStopKeepsRegistryRunningAndGlobalStopDrainsRemainingListeners가
  false 대 true assertion으로 실패했다.
- GREEN: Gradle targeted registry test 결과 2/2, exit 0.
- module: skipAwsEmulatorTests=true Spring module test 결과 608/608,
  exit 0.
- full emulator attempt: 647개 중 31개가 Floci의
  Mapped port can only be obtained after the container is started 오류로
  실패했다. Docker/Colima는 running이며, 실패 테스트는 모두 emulator
  fixture 초기화 경계에 있어 #542 registry unit diff와 독립적이다.
- detekt: repository에 detekt task가 등록되어 있지 않아 실행할 task가
  없었다.
- static scan: touched test에 raw assertThrows·Thread.sleep·delay가 없고,
  production registry에 !!가 없으며, git diff --check가 통과했다.

## 후속 조치

- Floci 전체 module 실패는 #542에서 수정하지 않고 emulator/testcontainers
  안정성 backlog로 분리한다.
- detekt task 부재는 기존 repository 상태로 기록하며 이 PR에서 build
  topology를 확장하지 않는다.
- 다음 stacked child #543은 #542의 merge 후 exact head를 base로 사용한다.

## DoD Status

- 상태: PR 생성 전 review 통과
- P0/P1: 0/0
- 완료: 7-Tier six-perspective 통합, Kotlin pattern, assertions,
  targeted/full non-emulator evidence, known gaps와 후속 조치를 기록함
- 미완료: Lore implementation commit, push, PR exact-head CI, merge
