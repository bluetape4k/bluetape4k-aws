# Issue #635 SNS HTTP envelope 공통 정책 구현 검토

작성일: 2026-09-06

## 범위와 provenance

- 구현 commit: `7b1ace35b0f02d4dcf8645a6cab08f36541042f9`
- 기준 branch: `origin/develop` (`216c95203ff63e0d8c7f822c77ac4386c6c3b6c1`)
- 검토 범위: `aws-java`, `aws-ktor`, `aws-spring-boot`의 SNS HTTP envelope 구조 검증,
  공통 corpus, 공개 API 호환, 영어·한국어 README
- 검토 방식: 현재 세션의 native subagent 금지 정책에 따른 main-session six-lens fallback
- 독립 reviewer/model provenance: 없음. 관점별 근거를 분리했으며 독립 검토로 주장하지 않는다.
- 범위 제외: SNS signature·인증서 체인 검증, 예상 topic allowlist, replay 방지, 실제 AWS delivery

판정 기준은 P0/P1 0건이다. 검토 중 decoder cancellation identity가 사라지는 P1 후보를
회귀 테스트로 재현하고 수정했다. 짧은 signing host는 기존 short-circuit가 typed rejection으로
수렴함을 공통 corpus로 확인했다.

## 구현 근거

| 영역 | 근거 | 판단 |
| --- | --- | --- |
| 공통 정책 | `SnsHttpEnvelopePolicy`, `SnsHttpEnvelope`, typed rejection reason | framework-neutral decoded-envelope 정책을 `aws-java`가 소유한다. |
| Ktor adapter | `SnsHttpMessageParser`, `StrictObjectMapperSupport` | 주입 mapper 설정을 보존하며 duplicate detection을 강제하고 JSON을 한 번 decode한다. |
| Spring adapter | `SnsHttpMessageParser` | strict Jackson decoder 뒤에 같은 core policy를 적용한다. |
| 공통 corpus | `SnsHttpEnvelopeConformanceFixtures`와 양 adapter conformance test | valid/invalid case의 reason과 redacted message를 한 source로 고정한다. |
| 호환성 | 기존 parser test, `compatibilityCheck` | 기존 parser/model ABI와 raw mapping을 유지한다. |
| 사용자 경계 | KDoc과 Ktor/Spring EN/KO README | parsing 결과가 인증 결과가 아니며 signature/IAM/retry/replay가 caller-owned임을 명시한다. |

## 6개 관점 판정

| 관점 | 상태 | P0/P1/P2/P3 | 근거 |
| --- | --- | --- | --- |
| Performance | PASS | 0/0/0/0 | UTF-8 byte 상한을 decode 전에 검사하고 adapter별 `readValue` 호출은 1회다. core는 고정 field map을 순회하며 raw payload를 재직렬화하지 않는다. |
| Stability | PASS | 0/0/0/0 | decoder `CancellationException` 동일 인스턴스를 먼저 재전파한다. 그 외 decode 실패만 fixed `INVALID_JSON`으로 수렴한다. lifecycle·외부 자원은 없다. |
| Security | PASS | 0/0/0/0 | duplicate/non-object/malformed JSON, exact partition/region host, scheme/userinfo/query/fragment/port/path, body 상한을 양 adapter가 같은 corpus로 거부한다. 입력과 decoder 원인은 외부 message/cause에 노출하지 않는다. |
| Operator/Ops | PASS | 0/0/0/1 | stable low-cardinality reason을 제공한다. `java-test-fixtures` capability는 Maven POM에 표현되지 않지만 Gradle module metadata에는 보존되며 production 소비 계약에는 노출되지 않는다. |
| Developer/API | PASS | 0/0/0/0 | Ktor constructor/default/parse와 Spring object/parse를 유지한다. Kotlin에서 Jackson 3 self-referential builder generic을 안전하게 호출할 수 없어 package-private Java shim을 사용했다. |
| User/caller | PASS | 0/0/0/0 | 두 locale README가 structural parsing과 signature verification을 구분하며 credentials, IAM, retry, replay 책임을 호출자에게 유지한다. |

## 발견 사항 처분

1. decoder가 `CancellationException`을 던지면 초기 구현은 `INVALID_JSON`으로 바꿨다.
   동일 인스턴스 전파 테스트를 RED로 확인한 뒤 cancellation을 일반 decode failure보다 먼저
   재전파하도록 수정했다.
2. `SigningCertURL` host가 `sns`처럼 짧을 때 index 접근 위험을 검토했다. exact partition host
   검사에서 먼저 `false`가 되어 region index를 평가하지 않으며, core와 양 adapter corpus에서
   모두 `INVALID_SIGNING_CERT_URI`로 수렴함을 확인했다.
3. 설계 초안은 decoder 원인을 exception cause로 보존하는 예시를 포함했다. payload나 secret이
   cause chain을 통해 노출되지 않도록 cause를 보존하지 않는 더 엄격한 redaction 계약으로 설계와
   구현을 함께 정렬했다.
4. publication metadata 생성은 성공했으나 Maven POM이 test-fixture capability를 표현하지 못한다는
   Gradle 경고가 남는다. fixture는 저장소 내부 test dependency이고 Gradle module metadata는
   정상 생성되므로 production artifact 차단 사유는 아니다.

## 수용 조건 traceability

| 조건 | 증거 | 상태 |
| --- | --- | --- |
| A-VER-01 공통 framework-neutral 정책 | core production import에 Ktor/Spring/Jackson 없음 | PASS |
| A-VER-02 기존 adapter API/ABI | `compatibilityCheck` 64/64, 기존 parser tests | PASS |
| A-VER-03 동일 corpus·reason/message | Ktor 3/3, Spring 2/2 conformance tests | PASS |
| A-VER-04 hostile·경계 입력 거부 | duplicate, host/partition/region, URI, 256 KiB + 1, field corpus | PASS |
| A-VER-05 bounded single decode | core decode-count test와 adapter `readValue` source scan | PASS |
| A-VER-06 redacted failure | exact reason/message와 secret/URL negative assertions | PASS |
| A-VER-07 문서 책임 경계 | Ktor/Spring EN/KO README와 public KDoc | PASS |

## 검증 증거

| 검증 | 결과 |
| --- | --- |
| TDD 보강 | cancellation identity RED 1건 재현 후 core 11/11, Ktor conformance 3/3, Spring conformance 2/2 PASS |
| `aws-java` clean test | 530 passing, 15 pending, failure/error 0 |
| `aws-ktor` clean test | 256 passing, failure/error/skip 0 |
| `aws-spring-boot` clean test | 1619 passing, 6 pending, failure/error 0 |
| detekt | affected 3 modules PASS |
| ABI | `compatibilityCheck --no-configuration-cache`, 64 passing |
| publication metadata | Gradle module metadata와 POM 생성 PASS; test-fixture POM capability 경고 기록 |
| source scan | unsafe coroutine/blocking construct 0, core framework import 0, adapter decode 각 1회 |
| 문서 | EN/KO 구조와 책임 경계 대조, 한국어 terminology audit 0 |
| diff | `git diff --check` PASS |

pending 21건은 기존 환경 조건부 테스트다. 새 core·adapter conformance suite에는 pending이 없다.
실제 AWS SNS delivery와 hosted exact-head CI는 로컬 검증으로 대체하지 않는다.

## Kotlin 최종 체크리스트

| Gate | 상태 | 근거 |
| --- | --- | --- |
| KT-FIN-01 current surface | PASS | issue, base, 기존 parser/model/test와 exact implementation commit을 대조했다. |
| KT-FIN-02 validation contracts | PASS | typed reason과 redacted message를 추가하고 기존 `IllegalArgumentException` 계층을 유지했다. |
| KT-FIN-03 unsafe constructs | PASS | 새 production에 `!!`, blocking, monitor lock, suspend `runCatching`이 없다. cancellation은 먼저 재전파한다. |
| KT-FIN-04 lifecycle ownership | N/A | 순수 동기 parser이며 coroutine/resource lifecycle이 없다. |
| KT-FIN-05 Exposed boundaries | N/A | Exposed를 변경하지 않았다. |
| KT-FIN-06 triggered references | PASS | Kotlin test, Jackson adapter, Gradle module 경계를 적용했다. |
| KT-FIN-07 named behavior | PASS | body limit, decode count, cancellation, hostile URI, raw mapping을 직접 검증한다. |
| KT-FIN-08 public docs | PASS | public KDoc과 두 module EN/KO README를 함께 갱신했다. |
| KT-FIN-09 diagnostics | PASS | compile은 전체 test에 포함되고 detekt 3 modules가 통과했다. |
| KT-FIN-10 fresh validation | PASS | exact implementation commit 직전 clean tests, detekt, ABI, metadata를 실행했다. |
| KT-FIN-11 final scope | PASS | Issue #635 범위만 포함하고 P0=0, P1=0이다. |

## DoD Status

- [x] 공통 validator와 typed rejection reason 구현
- [x] 단일 test fixture와 양 adapter conformance GREEN
- [x] 기존 adapter API/ABI와 raw mapping 회귀 없음
- [x] main-session six-lens review P0=0, P1=0
- [x] affected tests, detekt, compatibility, metadata, 문서 audit 통과
- [x] 실제 AWS와 독립 reviewer provenance의 부재를 명시
- [ ] PR exact-head hosted CI와 review/thread read-back
- [ ] 별도 fresh exact-head 병합 승인

최종 판정: **PASS (local implementation/review)**. PR 생성과 hosted exact-head CI는 delivery
단계에서 이어서 확인한다.
