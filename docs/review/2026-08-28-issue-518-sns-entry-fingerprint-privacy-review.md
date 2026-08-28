# Issue #518 SNS entry fingerprint privacy hardening

## 결정

`SnsBatchTransportException`의 기본 `message`와 `toString()`에서
`fingerprint=` 출력을 제거한다. `completedEntryIds`는 #456의 partial-send
recovery 계약이므로 그대로 보존한다. public `entryFingerprint` getter는
호환성을 위해 유지하되 `@Deprecated`로 전환하며, 명시적으로 읽어 외부
observability에 전달하는 경우 privacy와 수명주기는 호출자가 책임진다.

공유 keyed HMAC, per-process salt, entry ID 정규화, 외부 observability
dependency는 현재 저장소에서 실제 소비자나 cross-process 상관관계 요구가
확인되지 않았으므로 별도 아키텍처 범위로 남긴다.

## 위협 모델과 현재 계약

- 공격자는 예외 문자열, 로그 또는 APM 이벤트만 읽으며 애플리케이션 메모리와
  별도 비밀키에는 접근하지 못한다고 가정한다.
- public `SnsBatchTransportException.from()` 경계의 ID에는 최소 entropy
  전제가 없고, `SnsPublishBatchEntry.id`는 현재 non-blank만 검사한다.
- 기존 unkeyed 계산은 `SHA-256(ids.joinToString("\u0000"))[0..5]`인
  48-bit lowercase hex 값이다. 낮은 후보 공간에서는 반복 hash로 원래 ID를
  추정할 수 있으며 프로세스 재시작과 replica 사이에도 같은 ordered input이
  같은 값을 만든다.
- 병렬 완료 순서가 `completedEntryIds` 순서를 결정하므로 같은 logical batch의
  값이 실행 순서에 따라 달라질 수 있다. NUL 구분자 preimage 모호성, 빈 목록의
  고정 값, 48-bit 충돌 여유도 현재 API가 정의하지 않는다.

`completedEntryIds`는 SDK cause, payload, ARN을 대체하는 문자열용 진단 정보가
아니라 호출자의 재처리 판단을 위한 programmatic recovery metadata다. 따라서
기본 logger·metric·telemetry가 이 목록을 직렬화하지 않는다는 운영 경계를
유지해야 한다.

## 대안과 판정

| 대안 | privacy·운영 영향 | 판정 |
|---|---|---|
| 현행 unkeyed 48-bit digest 유지 | 낮은 entropy 추정과 장기 linkability를 계속 허용 | 비권고 |
| 공유 keyed HMAC | 후보 추정은 줄지만 key 공급·rotation·미설정 정책과 ABI가 필요 | 운영 요구 확인 후 별도 설계 |
| per-process salt/HMAC | process 내부 상관관계는 남지만 restart·replica 해석과 전역 상태가 필요 | 차선책 |
| 자동 fingerprint 제거와 getter 단계 폐기 | 기본 로그의 추정·linkability를 제거하고 recovery 계약은 유지 | 이번 작업에서 채택 |

cross-process 상관관계가 실제로 필요하면 library 예외가 아니라 caller-owned
observability 계층에서 keyed HMAC을 생성한다. 키 ID, dual-key rotation,
restart·replica 범위, legacy fallback, fail-open/fail-closed 정책은 별도
승인된 아키텍처 이슈에서 결정한다.

## 호환성 및 migration

- 예외의 기본 문자열은 `failureType`과 `completedCount`만 포함하도록 바뀐다.
  `message`를 파싱하던 소비자는 `fingerprint=`를 더 이상 기대하지 않아야 한다.
- `entryFingerprint` source/JVM getter는 이번 release에서 유지하고 deprecation
  경고를 제공한다. 다음 명시적 호환성 경계에서 제거할 수 있도록 migration을
  준비한다.
- `completedEntryIds`, SDK cause 비보관, payload·ARN·CR/LF redaction,
  cancellation 재전파와 failure classification은 변경하지 않는다.

## 검증 계약

- 새 테스트를 추가한 뒤 현재 구현의 `message`·`toString()`에 `fingerprint=`가
  남는 RED를 확인했다.
- 수정 후 targeted `SnsBatchExceptionsTest` 5건은 raw ID·payload·ARN·cause와
  `fingerprint=`를 렌더링하지 않으면서 deprecated getter와 completed ID copy를
  유지하는지 검증한다.
- `:bluetape4k-aws-spring-boot:test` 전체는 1,603건 실행, 2건의 기존 skip,
  `BUILD SUCCESSFUL`로 통과했다. `detekt`도 `BUILD SUCCESSFUL`이다.
- 외부 consumer가 getter를 읽는지, 운영 APM에 실제 값이 유입되는지는 저장소
  증거만으로 확인할 수 없으므로 별도 운영 확인 항목으로 남긴다.

## 참고

- [Issue #518](https://github.com/bluetape4k/bluetape4k-aws/issues/518)
- [PR #517](https://github.com/bluetape4k/bluetape4k-aws/pull/517)
- [SNS `PublishBatchRequestEntry.Id` 제약](https://docs.aws.amazon.com/sns/latest/api/API_PublishBatchRequestEntry.html)
- 구현: `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExceptions.kt`
- 테스트: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsBatchExceptionsTest.kt`

## DoD Status

- [x] 위협 모델, entropy 전제와 기존 fingerprint 계약을 기록했다.
- [x] 유지, keyed HMAC, per-process salt, 제거 대안을 privacy·운영·호환성 기준으로 비교했다.
- [x] 선택한 제거 정책과 deprecated getter migration을 테스트와 KDoc으로 고정했다.
- [x] raw entry ID, payload, ARN, SDK cause redaction과 기존 분류 테스트를 유지했다.
- [ ] 외부 observability 소비자와 실제 운영 APM 유입 여부는 저장소 밖 확인이 필요하다.
- [ ] PR·exact-head CI·merge·issue close는 별도 권한과 게이트가 필요하다.

Final status: IMPLEMENTED LOCALLY — Type B 코드·테스트·문서 검증 완료; PR/merge는 수행하지 않았다.
