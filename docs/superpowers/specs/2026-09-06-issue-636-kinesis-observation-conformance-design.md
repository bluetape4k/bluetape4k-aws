# Issue #636 Kinesis 관측 conformance 계약 설계

## 문서 상태

- 대상 저장소: `bluetape4k/bluetape4k-aws`
- 대상 브랜치: `feat/issue-636-kinesis-observation-conformance`
- 기준: `origin/develop` (`216c95203ff63e0d8c7f822c77ac4386c6c3b6c1`)
- 관련 이슈: [#636](https://github.com/bluetape4k/bluetape4k-aws/issues/636)
- 승인된 방향: 공통 schema manifest + module-local adapter + 공통 conformance vectors
- 중지 지점: PR exact-head CI·review·mergeability 확인 후 병합 승인 대기

## 문제

`aws-java`와 `aws-kotlin`의 Kinesis consumer는 같은 lifecycle을 서로 다른 sealed event 모델,
outcome/reason 문자열, token 길이와 count bound로 노출한다. 기존 공개 sealed 타입을 한쪽으로
이동하거나 subtype/enum을 추가하면 exhaustive `when` 소비자와 ABI/source 호환을 깨뜨릴 수 있다.
반대로 문서만 맞추면 dashboard와 alert가 소비할 실제 canonical output을 만들 수 없다.

## 대안

### A. 기존 API 위의 module-local canonical adapter와 공통 manifest

각 모듈이 같은 wire-shaped `KinesisCanonicalObservation`과
`toCanonicalObservations()` adapter를 자기 package에 additive API로 제공한다. root의 한 properties
manifest를 두 module test resource로 연결하고 vocabulary, bounds, token vector와 lifecycle mapping을
각 adapter에 동일하게 적용한다.

- 장점: SDK 간 production dependency와 기존 API 변경이 없고, 실제 metric exporter가 안정된 값만 소비할 수 있다.
- 단점: 동일 shape의 adapter value 구현이 두 package에 존재한다.
- 결정: 공통 manifest와 conformance test가 두 구현의 drift를 fail-closed로 검출하므로 이 접근을 사용한다.

### B. `aws-kotlin`이 `aws-java` canonical 타입에 의존

한 타입을 재사용하지만 SDK 독립성과 선택 설치 계약을 깨뜨리므로 기각한다.

### C. 새 published 공통 모듈 도입

중복은 가장 적지만 settings/BOM/public artifact/release surface가 크게 늘어난다. 1.1.0 호환 train의
P2 conformance 보강 범위를 넘으므로 major train 후보로 남긴다.

## Canonical schema v1

두 모듈의 canonical observation은 다음 동일한 필드를 가진다.

```kotlin
data class KinesisCanonicalObservation(
    val eventKind: String,
    val outcome: String,
    val reason: String? = null,
    val retryClass: String? = null,
    val streamToken: String? = null,
    val shardToken: String? = null,
    val ownerToken: String? = null,
    val count: Int? = null,
    val retryCount: Int? = null,
)
```

- event kind: `discovery`, `shard`, `batch`, `record`, `lease`, `checkpoint`, `retry`
- outcome: `started`, `success`, `failed`, `skipped`, `lost`
- reason: `empty`, `shard_end`, `lease_busy`, `lease_lost`, `iterator_expired`, `throttled`, `error`, `cancelled`
- retry class: `discovery`, `iterator`, `throttle`
- token: lowercase SHA-256 prefix 24자
- `count`, `retryCount`: null 또는 `0..10_000`

Canonical value는 payload, raw stream/shard/owner ID, sequence number, credential, exception message를
포함하지 않는다. adapter가 legacy public token을 받으면 24자로 정규화하고 유효하지 않은 token,
vocabulary와 bound는 `IllegalArgumentException`으로 fail-closed한다.

## Lifecycle mapping

| 논리 event | aws-java legacy | aws-kotlin legacy | canonical |
| --- | --- | --- | --- |
| lease acquire | `Lease(acquired)` | `LEASE/STARTED` | `lease/started` |
| lease renew | `Lease(renewed)` | `LEASE/SUCCESS` | `lease/success` |
| lease loss | `Lease(lost, lease_lost)` | `LEASE/LOST/LEASE_LOST` | `lease/lost/lease_lost` |
| shard start | `Shard(started)` | `SHARD/STARTED` | `shard/started` |
| batch read | `Batch(read)` | `BATCH/SUCCESS` | `batch/success`, bounded count |
| record checkpoint | `Checkpoint(saved)` | `RECORD/SUCCESS` | `record/success`, count 1 |
| shard end | `Shard(completed)` | `CHECKPOINT/SUCCESS/SHARD_END` + `SHARD/SUCCESS` | 두 canonical event: `checkpoint/success/shard_end`, `shard/success` |
| retry | `Retry(retrying)` | `RETRY/SUCCESS` | `retry/success`, bounded retry count |
| discovery | `Discovery(page)` | `DISCOVERY/SUCCESS` | `discovery/success`, bounded shard count |

Java의 shard completion 한 건은 논리적으로 checkpoint 종료와 shard 성공 두 건을 포함하므로 adapter가
두 canonical observations를 반환한다. 나머지는 한 건을 반환한다. 이 expansion은 legacy callback
수를 바꾸지 않으며 canonical exporter가 받는 schema에만 적용된다.

## Migration과 성능

- 기존 `KinesisFlowMetrics`와 `KinesisFlowEvent`는 변경하지 않는다.
- 신규 exporter는 callback에서 `event.toCanonicalObservations()`를 호출한다.
- 기존 Java 24자와 Kotlin 64자 token을 직접 tag로 쓰는 dashboard는 canonical 24자 tag로 병행 전환한다.
- 24자 SHA-256 prefix는 기존 Java cardinality와 series continuity를 보존한다. cryptographic identity나
  authorization token으로 사용하지 않는다.
- 변환은 event당 최대 2개 작은 value와 token prefix만 생성하며 hashing을 다시 수행하지 않는다.
- 실제 consumer lifecycle, cancellation, lease/checkpoint I/O 순서는 변경하지 않는다.

## 공통 manifest와 검증

`conformance/kinesis-observation-v1/kinesis-observation-v1.properties`가 schema version, vocabulary,
bounds, token input/expected prefix와 대표 mapping vector를 소유한다. 두 module의 test resource가
같은 디렉터리를 참조한다. 각 conformance test는 manifest를 읽어 public adapter 결과와 정확히 비교한다.

검증은 다음을 포함한다.

- vocabulary 집합과 schema version
- 같은 raw input의 24자 token vector
- exact bound와 10,001 거부
- 모든 legacy event subtype/enum의 canonical mapping
- record checkpoint와 shard-end expansion 순서
- payload/secret/raw identifier 비노출
- 기존 public sealed type ABI와 기존 flow test

## 수용 조건

- [ ] 두 module이 같은 canonical vocabulary, bounds와 token vector를 통과한다.
- [ ] 기존 `KinesisFlowEvent` sealed API와 callback ABI/source 계약을 변경하지 않는다.
- [ ] checkpoint, lease, retry, discovery와 shard completion mapping이 같은 manifest로 검증된다.
- [ ] canonical output에 payload·raw identifier·sequence·exception message가 없다.
- [ ] migration, cardinality, 24자 token의 비보안 용도를 EN/KO README에 문서화한다.
- [ ] affected full tests, detekt, compatibility와 exact-head CI가 통과한다.

## DoD

- [ ] spec/plan review P0=0, P1=0
- [ ] TDD RED/GREEN과 shared manifest conformance
- [ ] existing API/ABI와 consumer lifecycle 회귀 없음
- [ ] six-lens implementation review P0=0, P1=0
- [ ] Lore lesson/commits와 Korean PR metadata
- [ ] fresh exact-head 병합 승인 전 merge 금지
