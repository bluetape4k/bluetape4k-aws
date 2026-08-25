# #546 aws-exposed validation 7-Tier code review

## 검토 범위

- 기준 base: `develop` at `2055fd32` (#545 merge)
- 변경 모듈: `aws-exposed`
- 변경 표면: RDS IAM authentication properties와 token request의 단항/range
  validation 및 회귀 테스트
- 이슈: #546
- 검토 방식: touched diff, helper 계약, 경계/메시지 테스트를 기준으로 한
  source-read-only 7-Tier 통합 검토

## 적용 규칙

- 숫자/Comparable 범위는 `requireInRange`, 상한은 `requireLe`, 양수 조건은
  `requireGt(Duration.ZERO, parameterName)`을 사용한다.
- 두 설정값의 관계처럼 도메인 문장이 더 중요한 불변식은 raw `require`로
  유지하고, helper를 기계적으로 적용하지 않는다.
- 새 AWS 설정 DTO 검토에서도 이 규칙을 먼저 확인하며, 검증 실패는
  `IllegalArgumentException`과 parameter 이름을 보존해야 한다.

## 7-Tier 결과

| Tier | 관점 | 결과 | 증거 |
|---|---|---|---|
| 1 | 보안/데이터 경계 | PASS | RDS hostname, username, token 생성/비밀 redaction 경계는 변경하지 않았다. validation 메시지에도 token 값이 추가되지 않는다. |
| 2 | 운영/실행 | PASS | token refresh, close, rollback, datasource lifecycle은 변경하지 않고 설정 validation만 정렬했다. |
| 3 | 구조/API | PASS | 공개 data class와 예외 타입은 유지하며 port/Duration 입력 검증의 helper 구현만 바꿨다. |
| 4 | Kotlin 패턴 | PASS | `io.bluetape4k.support.requireInRange`, `requireGt`, `requireLe`와 `bluetape4k.assertions`를 사용했다. 관계형 불변식은 명시적 raw `require`로 남겼다. |
| 5 | 테스트/회귀 | PASS | targeted 9/9, 전체 aws-exposed 26/26, 경계값·실패 parameter 메시지 테스트 통과. |
| 6 | 성능/안정성 | PASS | helper는 동일한 즉시 `IllegalArgumentException` 검증이며 token 발급·refresh 횟수와 동시성 동작에 영향을 주지 않는다. |
| 7 | 문서/유지보수 | PASS | 계획과 본 review에 새 AWS 설정 DTO의 helper 적용 규칙 및 관계형 예외를 기록했다. |

## 판정

- P0 = 0
- P1 = 0
- 결정: PASS. PR 생성과 exact-head CI 검증을 진행할 수 있다.

## 검증 증거

- RED scan: `/tmp/issue-546-red-scan.log`에 port/positive Duration raw `require`
  잔여가 기록되었다.
- GREEN scan: `simple-validation-require-scan=clean`.
- targeted: `AwsRdsIamAuthenticationTest` 9/9, exit 0.
- module: `aws-exposed:test` 26/26, exit 0.
- static: `:bluetape4k-aws-exposed:detekt`, exit 0; `git diff --check`, exit 0.

## 알려진 경계

- 이 PR은 RDS IAM token 생성, refresh, close, rollback 실행 경계를 변경하지
  않으므로 credentialed AWS integration smoke는 추가하지 않았다.
- raw validation scan은 변경된 RDS 설정 파일의 단항/range 패턴을 대상으로 하며,
  관계형 `refreshBeforeExpiry < tokenTtl` 검증은 의도적으로 허용한다.
- hosted CI는 PR 생성 후 exact head에서 확인한다.

## DoD Status

- 상태: PR 생성 전 review 통과
- P0/P1: 0/0
- 완료: support helper, bluetape4k assertions, boundary/message regression,
  module/static evidence, helper 적용 규칙 기록
- 미완료: Lore review commit, push, PR exact-head CI, merge
