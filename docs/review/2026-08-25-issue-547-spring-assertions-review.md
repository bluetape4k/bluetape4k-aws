# #547 aws-spring-boot assertions 7-Tier code review

## 검토 범위

- 기준 base: `develop` at `c5a0a29f` (#546 merge)
- 변경 모듈: `aws-spring-boot`
- 변경 표면: ConfigData parser/classpath guard/loader, S3 metadata, SQS
  listener container 테스트의 예외 assertion
- 이슈: #547
- 검토 방식: touched diff, 기존 bluetape4k assertion 사용, 예외 계약과
  sanitized message 회귀를 기준으로 한 source-read-only 7-Tier 통합 검토

## 적용 규칙

- 예외 캡처는 `io.bluetape4k.assertions.assertFailsWith`를 사용한다.
- 결과와 메시지 검증은 기존 `shouldBeEqualTo`, `shouldContain`,
  `shouldNotContain`, `shouldBeTrue`, `shouldBeInstanceOf` matcher를
  우선 재사용한다.
- JUnit는 테스트 lifecycle(`@Test`)에만 사용하며
  `org.junit.jupiter.api.assertThrows`는 대상 파일에서 허용하지 않는다.
- 예외 타입, resource identity, secret redaction, coroutine cancellation과
  listener lifecycle 동작은 변경하지 않는다.

## 7-Tier 결과

| Tier | 관점 | 결과 | 증거 |
|---|---|---|---|
| 1 | 보안/데이터 경계 | PASS | ConfigData loader의 secret/resource sanitized message 검증과 기존 redaction assertion을 유지했다. |
| 2 | 운영/실행 | PASS | SQS listener start/stop, cancellation hook, heartbeat와 retry 실행 로직은 변경하지 않았다. |
| 3 | 구조/API | PASS | 테스트 assertion import/call만 교체했으며 production API와 예외 타입은 유지했다. |
| 4 | Kotlin 패턴 | PASS | `bluetape4k-assertions.assertFailsWith`와 기존 matcher를 재사용했고 raw JUnit exception assertion을 제거했다. |
| 5 | 테스트/회귀 | PASS | 대상 33/33, `aws-spring-boot` 전체 673/673 테스트 통과. |
| 6 | 성능/안정성 | PASS | assertion adapter 변경만으로 production allocation, coroutine dispatcher, MockK interaction을 추가하지 않았다. |
| 7 | 문서/유지보수 | PASS | 계획 문서에 재사용 규칙과 raw assertion scan 수용 기준을 기록했다. |

## 판정

- P0 = 0
- P1 = 0
- 결정: PASS. PR 생성과 exact-head hosted CI 검증을 진행할 수 있다.

## 검증 증거

- RED scan: `/tmp/issue-547-red-scan.log`에 대상 파일의 raw JUnit
  `assertThrows` import/call을 기록했다.
- GREEN scan: 대상 6개 파일에서
  `org.junit.jupiter.api.assertThrows`와 `assertThrows<...>`가 0건이다.
- targeted: 대상 테스트 33/33, exit 0.
- module: `:bluetape4k-aws-spring-boot:test` 673/673, exit 0.
- static: `:bluetape4k-aws-spring-boot:detekt`, exit 0; `git diff --check`,
  exit 0.

## 알려진 경계

- 이 PR은 테스트 assertion adapter만 바꾸므로 credentialed AWS integration
  smoke를 추가하지 않았다.
- 모듈 전체 테스트에는 기존 emulator 경로가 포함되어 있으며 해당 결과는
  이 PR에서 새로 추가된 assertion 검증과 분리해 해석한다.
- hosted CI는 PR 생성 후 exact head에서 다시 확인한다.

## DoD Status

- 상태: PR 생성 전 review 통과
- P0/P1: 0/0
- 완료: `assertFailsWith` 전환, 기존 bluetape4k matcher 재사용, raw scan,
  targeted/module/detekt evidence, 7-Tier review
- 미완료: Lore implementation/review commit, push, PR exact-head CI, merge
