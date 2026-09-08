# #646 암호화 컨텍스트 지문 충돌 방지

## 배경과 결과

`aws-spring-boot`의 KMS S3 `keyFingerprint`와 SQS extended policy
fingerprint가 encryption context를 `key=value;...` 문자열로 연결하고 있었다.
그래서 `{"a": "b;c=d"}`와 `{"a": "b", "c": "d"}`가 같은 fingerprint를
만들었다. RED 회귀 테스트에서 두 구현 모두 동일한 지문을 반환하는 것을
확인했고, 기존 SQS golden vector는 통과했다.

수정은 기존 delimiter-safe context의 fingerprint를 유지하는 방식으로
진행했다. `;` 또는 `=`가 포함되면 `v2` domain과 length-prefixed canonical
encoding을 사용한다. 빈 key는 `v2` validation 경계에서 거부한다. `v1`과 `v2` domain을 분리해서
두 표현이 같은 fingerprint namespace를 공유하지 않게 했으며, legacy
fingerprint와 새 fingerprint를 모호하게 비교하는 fallback은 추가하지 않았다.
S3 provider envelope의 metadata와 AAD wire encoding은 변경하지 않았다.

## 결정과 재발 방지 규칙

- 정렬된 map 순서는 유지해 동일한 context가 삽입 순서와 무관하게 같은
  fingerprint를 만든다.
- Unicode는 UTF-8 byte length를 기준으로 canonicalize한다. 빈 value는 유효한
  context 항목으로 유지하고, 빈 map과 구분한다.
- 빈 key는 S3 `ClientSideEncryption`과 SQS `Encryption` 생성자에서 계속
  거부한다. mutable map이 우회하더라도 `v2` 경계에서 안전하게 다루거나
  canonical validation에서 fail closed한다.
- safe `v1` 직렬화와 delimiter-bearing `v2` 직렬화를 domain으로 분리한다.
  delimiter-bearing 입력을 legacy digest와 비교해 복구시키는 호환성 경로는
  만들지 않는다.
- 이미 존재하는 `ProviderEnvelope.canonicalContextAad`의
  length-prefixed UTF-8 encoding을 KMS S3 fingerprint의 `v2` payload에
  재사용한다. provider envelope 자체의 version, metadata, AAD wire contract는
  그대로 둔다.

## TDD에서 확인한 내용

- KMS S3와 SQS 각각에서 map 순서, Unicode, 빈 value, 빈 map,
  `;`·`=`가 포함된 value를 검증한다.
- `{"a": "b;c=d"}`와 `{"a": "b", "c": "d"}`가 서로 다른 fingerprint를
  만들어야 한다.
- 빈 key는 입력 계약에 따라 `IllegalArgumentException`으로 거부한다.
- RED 실행에서는 4개 테스트 중 2개가 충돌 assertion에서 실패했고, 기존
  golden vector와 order/bound mutation 검사는 통과했다.

## 검증 근거

RED 재현 명령:

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionOperationsTest' \
  --tests 'io.bluetape4k.aws.spring.sqs.SqsExtendedPolicyFingerprintTest'
```

RED 결과는 `/tmp/aws-646-red.log`에 보존했다. 구현 후 같은 selector의 GREEN
실행, `detekt`, affected module validation, `git diff --check`는 부모
workflow가 직렬로 수행한다.

## 후속 guard

- fingerprint나 identity에 map을 추가할 때 delimiter 연결을 사용하지 않고,
  field/domain version과 length-prefixed canonical encoding을 먼저 고정한다.
- 기존 digest를 보존할 때는 safe 입력 범위를 명시하고, 변경 입력에는 별도
  domain을 사용한다. 해시가 우연히 같지 않다는 가정만으로 legacy fallback을
  허용하지 않는다.
- canonical encoding을 변경하면 기존 golden vector와 delimiter collision
  pair를 함께 실행해 wire/API compatibility와 충돌 방지를 분리해서 확인한다.

## Superpowers writer gate

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | Issue #646, 현재 KMS/SQS source, RED log, 대상 테스트와 구현 범위를 확인했다. |
| SPW-02 | PASS | 배경, 결정, compatibility 경계, RED 결과, 검증 명령과 후속 guard를 기록했다. |
| SPW-03 | PASS | 기술 설명은 한국어로 작성하고 API, domain, command, path, error type은 그대로 보존했다. |
| SPW-04 | PASS | KMS `keyFingerprint`, SQS `SqsExtendedPolicyFingerprint`, provider AAD 경계를 source와 대조했다. |
| SPW-05 | PASS | Markdown read-back과 `git diff --check`를 수행했다. GREEN 및 module-wide validation은 부모 workflow에서 갱신한다. |

**상태:** 구현과 RED 고정은 완료했다. GREEN 검증, commit, push, PR 생성은
부모 workflow의 직렬 게이트에서 진행한다.

## 독립 리뷰 반영과 이관

SQS는 기존 typed string 필드 구조 안에 UTF-8 길이 접두 문자열을 넣는다. S3 provider AAD의 validation과 바이너리 포맷을 SQS 정책에 결합하지 않기 위해 SQS 직렬화를 별도로 유지한다. 두 경로의 digest는 상호 교환하지 않으며 각각 충돌 회귀와 golden vector로 검증한다. 공통 helper 추출보다 기존 wire 경계 보존을 우선했다.

구분자를 포함한 context로 만든 기존 v1 SQS pointer는 새 v2 정책과 일치하지 않는다. 배포 전에 생산자를 중지하고 기존 버전 소비자로 해당 큐와 DLQ/redrive 대상 pointer를 처리한 다음 생산자·소비자를 함께 전환한다. 잔여 pointer를 새 digest와 모호하게 비교하는 fallback은 허용하지 않는다. 구분자가 없는 기존 context의 v1 digest는 유지한다.

수정 후 회귀 7개 통과. 전체 모듈 test/build/detekt는 격리 daemon에서 성공했다. 첫 daemon의 Dokka StringFormat 로딩 오류는 configuration 단계였으며 테스트 실패와 구분한다. 최종 PR 검증에서 최신 결과와 선택적 테스트 한계를 확인한다.
