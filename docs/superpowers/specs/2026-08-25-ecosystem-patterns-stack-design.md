# bluetape4k AWS ecosystem 정렬 stacked train 설계

## 목적

이 문서는 bluetape4k-aws의 모듈별 7-Tier 리뷰 결과를 실행 가능한
Epic과 선형 stacked PR train으로 전환하기 위한 설계 기준이다. 범위는
bluetape4k helper와 assertions의 일관된 사용, Kotlin 패턴 준수,
수명주기 안전성, ABI 검증, 예제 계약 정렬이다.

## 현재 근거

- 대상 저장소는 develop을 기본 브랜치로 사용하며 현재 구현 변경 없이
  깨끗한 상태다.
- 후속 이슈 #542부터 #551까지 모듈별 개선 항목이 등록되어 있다.
- aws-spring-boot의 SQS registry는 개별 listener 중지 때 전역
  running 플래그를 내리므로 다른 listener가 살아 있어도 전역 중지가
  완료된 것처럼 보일 수 있다.
- 관련 테스트는 bluetape4k assertions를 일부 사용하지만 예외 검증에는
  JUnit 원시 assertion이 남아 있다.
- Java, Kotlin, Spring Boot, Exposed, Ktor의 기존 targeted/full 검증은
  현재 기준으로 통과했으며 credentialed AWS smoke는 별도 범위다.

## 대안 검토

### A. 전역 running 플래그만 유지

변경량은 가장 작지만 개별 listener의 실제 상태를 표현할 수 없고,
다중 컨테이너 stop 순서에서 재현된 결함을 해결하지 못한다.

### B. 전역 lifecycle gate와 활성 listener 집합 분리

lifecycleStarted는 SmartLifecycle의 전역 진입 게이트로 유지하고,
activeIds는 실제 시작된 listener ID를 추적한다. 개별 stop은 해당 ID만
제거하며 전역 gate를 내리지 않는다. 전역 stop만 gate를 내리고 집합을
비운다. 기존 비동기 callback 계약과 auto-start 동작을 보존하면서
isRunning()을 실제 활성 집합에 연결할 수 있어 권장한다.

### C. 각 컨테이너 상태를 매번 순회하여 계산

호출 시점마다 모든 컨테이너를 순회하면 상태는 정확하지만 비동기 전환과
등록 경쟁에 취약하고 registry의 lock 경계를 확대한다.

## 권장 설계와 불변식

1. lifecycleStarted는 registry의 전역 start/stop 및 auto-registration
   gate다.
2. activeIds에는 start()가 성공한 listener만 포함한다.
3. 개별 stop(id)는 activeIds에서 해당 ID만 제거한다.
4. 개별 stop callback은 정확히 한 번 호출되고 stopping ID는 callback 후
   제거된다.
5. 전역 stop(callback)은 모든 등록 컨테이너에 대해 기존 비동기 drain
   계약을 유지하고 callback을 정확히 한 번 호출한다.
6. stop 중인 ID에 대한 start는 기존과 같이 거부한다.
7. isRunning()은 activeIds가 비어 있지 않을 때만 true다.
8. 테스트는 고정 sleep 대신 callback, await, 상태 assertion으로 동기화한다.

## #542 동작 계약

두 개 이상의 listener를 시작한 뒤 하나만 개별 중지하면:

- 중지된 listener의 callback은 한 번 호출된다.
- 다른 listener는 계속 활성 상태로 남는다.
- 이어지는 전역 stop은 남은 listener를 중지하고 전역 callback을 한 번
  호출한다.
- 개별 중지 완료 후 같은 ID를 다시 등록해도 전역 lifecycle gate가 살아
  있으면 auto-start 정책을 따른다.
- 비동기 stop 중 start 요청은 거부되고, drain 완료 후 재시작할 수 있다.

## 검증 전략

- TDD: 기존 registry 테스트에 다중 컨테이너 회귀 테스트를 먼저 추가하고
  targeted test에서 RED를 확인한다.
- GREEN: registry 구현을 최소 범위로 수정한 뒤 같은 targeted test와
  :bluetape4k-aws-spring-boot:test 전체를 실행한다.
- assertions: touched test의 예외 검증은
  io.bluetape4k.assertions.assertFailsWith로 정렬한다.
- 7-Tier: correctness, API/ABI, concurrency/lifecycle, ecosystem
  integration, tests, performance/resource, maintainability를 PR별로
  source-read-only review artifact에 기록한다.
- 각 PR은 이전 train head를 base로 하고 exact-head CI가 green인 경우에만
  다음 PR로 진행한다. merge는 별도 승인이 필요한 최종 게이트다.

## 선형 stacked PR train

~~~text
develop
└─ #542 fix/issue-542-sqs-registry
   └─ #543 chore/issue-543-abi-gate
      └─ #544 refactor/issue-544-aws-java-patterns
         └─ #545 refactor/issue-545-aws-kotlin-patterns
            └─ #546 refactor/issue-546-aws-exposed-validation
               └─ #547 refactor/issue-547-spring-assertions
                  └─ #548 refactor/issue-548-spring-validation
                     └─ #549 refactor/issue-549-ktor-validation
                        └─ #550 refactor/issue-550-ktor-examples
                           └─ #551 refactor/issue-551-spring-examples
~~~

각 child PR은 자기 이슈의 모듈과 테스트 범위만 변경한다. 하위 PR은
직전 PR의 실제 head를 base로 삼고, 상위 PR이 merge된 후에만 base를
갱신한다. 이 train은 사용자 요청에 따라 병렬 train을 만들지 않는 단일
순서로 운영한다.

## 완료 기준과 제외 범위

완료 기준은 Epic에 #542–#551 native sub-issue 관계가 연결되고, 각 child
PR이 이슈 metadata와 일치하며, exact-head CI와 7-Tier review가 통과하고,
각 PR body 마지막에 DoD Status 섹션이 있는 것이다. 이 실행에서는 merge,
release, tag, publication, credentialed AWS smoke를 수행하지 않는다.
해당 작업은 각 PR의 fresh merge approval 또는 별도 release 승인이 필요하다.

## DoD Status

- 상태: 설계 승인 및 #542 구현 진입 준비
- 근거: 7-Tier 결과, #542–#551 후속 이슈, 선형 train 순서, 회귀 계약과
  검증 명령을 명시함
- 미완료: Epic native sub-issue 등록, #542 TDD 구현, PR/CI, 후속 child
  PR들
