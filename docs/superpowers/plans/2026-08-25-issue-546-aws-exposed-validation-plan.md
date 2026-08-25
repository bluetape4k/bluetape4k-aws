# #546 aws-exposed validation 정렬 계획

## 목표

`aws-exposed`의 단항/range 설정 검증을 `io.bluetape4k.support` helper로
정렬하고, `bluetape4k-assertions`로 경계값과 실패 메시지 계약을 검증한다.

## 범위

- `AwsRdsIamAuthenticationProperties`의 port range와 positive duration 검증
- `AwsRdsIamAuthTokenRequest`의 port range 검증
- `tokenTtl <= MAX_TOKEN_TTL` 및 `refreshBeforeExpiry < tokenTtl` 관계 검증은
  의미가 분명한 raw `require`로 유지
- token 생성, refresh/close lifecycle, rollback 동작은 변경하지 않음
- 새 AWS 설정 DTO에 동일 규칙을 적용할 수 있도록 문서화

## 검증 순서

1. 변경 전 단항/range raw `require`와 기존 validation test 경계를 기록한다.
2. helper 전환과 경계/실패 메시지 테스트를 구현한다.
3. `AwsRdsIamAuthenticationTest` targeted test, `aws-exposed` 모듈 test,
   Detekt를 실행한다.
4. `git diff --check` 및 7-Tier review를 완료한다.

## 수용 기준

- port는 `requireInRange`, positive `Duration`은 `requireGt(Duration.ZERO, ...)`
  로 검증한다.
- helper는 `IllegalArgumentException`과 parameter 이름을 보존한다.
- 관계형 불변식과 RDS token/rollback 동작은 기존 의미와 테스트를 유지한다.
- 경계값과 helper 메시지를 `bluetape4k-assertions`로 검증한다.
