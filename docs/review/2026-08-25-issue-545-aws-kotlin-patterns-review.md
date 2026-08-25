# #545 aws-kotlin 테스트 패턴 7-Tier code review

## 검토 범위

- 기준 base: `develop` at `d9fe5a6b` (#544 merge)
- 변경 모듈: `aws-kotlin/src/test`
- 변경 표면: S3 Tables normalization/cleanup smoke helper와 Step Functions
  lifecycle cleanup helper
- 이슈: #545
- 검토 방식: touched test diff, cancellation 경계, raw-pattern scan을 기준으로
  한 source-read-only 7-Tier 통합 검토

## 7-Tier 결과

| Tier | 관점 | 결과 | 증거 |
|---|---|---|---|
| 1 | 보안/데이터 경계 | PASS | AWS 자격 증명, endpoint, role ARN, 외부 UUID 상수와 smoke 입력은 변경하지 않았다. 생성되는 test-only 이름만 `Uuid.V7`로 정렬했다. |
| 2 | 운영/실행 | PASS | `runSuspendIO`, `withTimeout`, `NonCancellable`, credential-gated smoke tag 경계를 유지했다. cleanup cancellation은 즉시 재전파하고 정상 실패만 수집한다. |
| 3 | 구조/API | PASS | production API와 ABI는 변경하지 않았고, 테스트의 resource naming 및 failure handling만 수정했다. |
| 4 | Kotlin 패턴 | PASS | raw JUnit assertion을 `io.bluetape4k.assertions` intent matcher와 `assertFailsWith`로 교체했다. `runCatching` 대신 명시적 cancellation-preserving `try/catch`를 사용했다. |
| 5 | 테스트/회귀 | PASS | S3 Tables targeted 6/6, 전체 aws-kotlin 661개(13 skipped) 통과. cancellation propagation 회귀 테스트를 추가했다. |
| 6 | 성능/안정성 | PASS | UUIDv7은 test resource isolation만 제공하며 AWS 호출 수·timeout·polling 정책을 늘리지 않는다. cleanup은 기존 순서와 첫 실패/suppressed semantics를 유지한다. |
| 7 | 문서/유지보수 | PASS | 계획·review 문서와 RED/GREEN raw-pattern scan 명령을 고정했고, 다음 수정에서도 suspend cleanup 경계의 cancellation 계약을 확인할 수 있다. |

## 판정

- P0 = 0
- P1 = 0
- 결정: PASS. PR 생성과 exact-head CI 검증을 진행할 수 있다.

## 검증 증거

- RED scan: `/tmp/issue-545-red-scan.log`에 raw JUnit assertion, `UUID.randomUUID`,
  `java.util.UUID`, `runCatching` 잔여가 기록되었다.
- GREEN scan: `raw-assertion-uuid-runcatching-scan=clean`.
- targeted: `S3TablesSmokeTest` 6/6, exit 0.
- module: `aws-kotlin:test` 661/661 실행(13 skipped), exit 0.
- static: `:bluetape4k-aws-kotlin:detekt`, exit 0; `git diff --check`, exit 0.

## 알려진 경계

- S3 Tables와 Step Functions credential/emulator smoke lane은 기존 환경 입력과
  emulator 정책에 따라 기본 실행에서 제외된다. 이 PR은 그 실행 경계를 변경하지 않는다.
- raw scan은 변경된 두 test file의 회귀 방지 증거이며, 전체 저장소의 기존
  `runCatching` 사용을 정책 위반으로 판단하지 않는다.
- hosted CI와 credentialed live smoke는 PR 검증 단계에서 확인한다.

## DoD Status

- 상태: PR 생성 전 review 통과
- P0/P1: 0/0
- 완료: bluetape4k assertions, `Uuid.V7`, cancellation preservation,
  targeted/module/static evidence, known gaps 기록
- 미완료: Lore review commit, push, PR exact-head CI, merge
