# #548 aws-spring-boot validation 7-Tier code review

## 검토 범위

- 기준 base: `develop` at `ae3093d3` (#547 merge)
- 변경 모듈: `aws-spring-boot`
- 변경 표면: `SqsProperties`의 단순 caller validation과 회귀 테스트
- 이슈: #548
- 검토 방식: touched diff, bluetape4k support/assertions 계약, Spring
  configuration-properties 의미와 heartbeat 관계형 불변식을 기준으로 한
  source-read-only 7-Tier 통합 검토

## 적용 규칙

- blank는 `requireNotBlank`, inclusive range는 `requireInRange`, 하한은
  `requireGe`를 사용한다.
- `Duration` non-negative 조건도 `requireGe(Duration.ZERO, name)`로
  공용 helper를 재사용한다.
- 예외 캡처와 메시지 검증은 `io.bluetape4k.assertions.assertFailsWith`,
  `shouldContain`, `shouldBeEqualTo`를 사용한다.
- interval/heartbeat 동시 설정과 interval<heartbeat 관계는 의미 있는
  관계형 불변식이므로 raw `require`로 유지한다.

## Kotlin/Spring checklist evidence

| Checklist | 결과 | 증거 |
|---|---|---|
| KT-FIN-01 | PASS | `SqsProperties`, `SqsPropertiesTest`, Spring `@ConfigurationProperties`와 기존 heartbeat 호출부를 확인했다. |
| KT-FIN-02 | PASS | 단순 caller validation은 `requireGe`/`requireInRange`/`requireNotBlank`로 바꾸고 `IllegalArgumentException`을 유지했다. |
| KT-FIN-03 | PASS | 새 `!!`, suspend `runCatching`, blocking lifecycle 변경이 없다. |
| KT-FIN-04 | PASS | SQS listener lifecycle/heartbeat 실행 코드는 변경하지 않고 설정 생성 시점 검증만 정렬했다. |
| KT-FIN-06 | PASS | Kotlin testing 및 Spring Boot references를 적용했고 context-runner/optional-class 표면은 변경하지 않았다. |
| KT-FIN-07 | PASS | 5개 테스트 모두 `bluetape4k-assertions`로 예외 타입·parameter 메시지·경계값을 검증한다. |
| KT-FIN-10 | PASS | targeted/module/detekt/raw scan/diff check가 fresh 결과로 통과했다. |

## 7-Tier 결과

| Tier | 관점 | 결과 | 증거 |
|---|---|---|---|
| 1 | 보안/데이터 경계 | PASS | redrive ARN은 blank만 검증하며 ARN/credential 값을 로그나 예외에 새로 노출하지 않는다. |
| 2 | 운영/실행 | PASS | listener start/stop, heartbeat scheduling과 retry 실행 의미는 변경하지 않고 DTO validation만 바꿨다. |
| 3 | 구조/API | PASS | immutable nested data class, Spring binding defaults, Serializable/serialVersionUID를 유지했다. |
| 4 | Kotlin 패턴 | PASS | `io.bluetape4k.support`의 `requireGe`, `requireInRange`, `requireNotBlank`를 사용하고 raw fallback을 관계형 불변식으로 제한했다. |
| 5 | 테스트/회귀 | PASS | `SqsPropertiesTest` 5/5, `aws-spring-boot` 전체 675/675 통과. |
| 6 | 성능/안정성 | PASS | helper는 즉시 동일한 입력 검증이며 런타임 AWS 호출·컨테이너 lifecycle에 새 비용을 추가하지 않는다. |
| 7 | 문서/유지보수 | PASS | 계획 문서에 helper 재사용과 raw 관계형 fallback 기준을 기록했다. |

## 판정

- P0 = 0
- P1 = 0
- 결정: PASS. PR 생성과 exact-head hosted CI 검증을 진행할 수 있다.

## 검증 증거

- RED scan: `/tmp/issue-548-red-scan.log`에 단순 validation raw `require`
  잔여를 기록했다.
- GREEN scan: 단순 validation scan clean; 허용된 raw `require`는 heartbeat의
  동시 설정/순서 관계 두 곳뿐이다.
- assertion scan: 대상 `SqsPropertiesTest`에 JUnit/kotlin.test exception
  assertion 0건.
- targeted: `SqsPropertiesTest` 5/5, exit 0.
- module: `:bluetape4k-aws-spring-boot:test` 675/675, exit 0.
- static: `:bluetape4k-aws-spring-boot:detekt`, exit 0; `git diff --check`,
  exit 0.

## 알려진 경계

- 한 번의 targeted 재실행에서 Dokka plugin classpath의 일시적
  `kotlinx/serialization/StringFormat` 오류가 발생했지만 동일 명령 retry가
  5/5로 통과했다. 이후 module/detekt도 새 프로세스로 통과했다.
- 이 PR은 configuration validation만 바꾸므로 credentialed AWS integration
  smoke는 추가하지 않았다.
- hosted CI는 PR 생성 후 exact head에서 확인한다.

## DoD Status

- 상태: PR 생성 전 review 통과
- P0/P1: 0/0
- 완료: support helper, `assertFailsWith` 경계/메시지 regression, raw scan,
  Kotlin/Spring checklist, 7-Tier review, module/static evidence
- 미완료: Lore review commit, push, PR exact-head CI, merge
