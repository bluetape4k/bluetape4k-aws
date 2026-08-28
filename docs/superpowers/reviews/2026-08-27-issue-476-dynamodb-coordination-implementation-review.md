# #476 DynamoDB coordination 구현 리뷰

**검토 대상:** 승인된 #476 설계·계획과 `aws-kotlin` coordination 구현

**범위:** `DistributedLock`, `MetadataStore`, `LockLease`, schema/options, DynamoDB
조건부 요청, parser·fencing·logical expiry, MockK 단위 테스트, FlociServer 계약 테스트,
문서·lesson·운영 경계

## 최종 판정

**PASS — P0=0, P1=0, P2=0, P3=0**

초기 구현 검토에서 확인된 active TTL metadata 삭제 조건, metadata value 256-byte 경계,
lock fencing request assertion, Floci 동시성·stale lease·cleanup 증거를 구현과 테스트에
반영했다. 후속 검토에서 확인된 fencing token `Long.MAX_VALUE - 1` 선행 mutation,
`AllNew` token 검증 누락, 음수 clock의 release 저장, 두 번째 conditional failure의
malformed 상태 은닉, generic exception detekt 위반, metadata `AllOld` logging 경계를
추가로 수선했다.

## 여섯 관점 검토

| 관점 | verdict | 확인 내용 |
| --- | --- | --- |
| 정확성·동시성 | PASS | acquire fast path는 1회, 만료 takeover는 최대 2회다. observed owner/expiry/token과 만료·token upper bound를 조건식에 넣고, takeover 2차 경쟁 실패는 valid item을 검증한 뒤 `null`로 끝낸다. renew/release는 owner/token/기존 expiry equality와 `expiresAt > now`를 사용한다. Floci 2-way·8-way barrier에서 owner 1개만 성공한다. |
| 성능·테스트 | PASS | pre-read·background retry·unbounded loop가 없고 MockK 호출 상한을 고정했다. malformed parser, AllOld/AllNew, token exhaustion, active/expired TTL, cancellation과 실제 Floci conditional behavior를 각각 검증한다. 실제 AWS latency/quota는 사용자 제약으로 주장하지 않는다. |
| 안정성·lifecycle | PASS | injected `Clock`, finite integer duration·overflow·non-negative epoch 검증, `CancellationException`과 SDK 예외 전파, caller-owned client, low-cardinality failure logging을 적용했다. Floci cleanup은 `NonCancellable`·5초 timeout·absence polling·idempotent delete를 사용한다. |
| 보안 | PASS | expression template와 alias/value map만 사용하고 caller 입력을 expression에 보간하지 않는다. 로그에 owner/key/value/token/credential을 넣지 않으며 namespace는 인증 경계가 아님을 문서화했다. |
| Kotlin 공개 API | PASS | immutable options, 명시적 suspend SPI, concrete default-duration overload, Serializable `LockLease`와 `readObject` 재검증을 제공한다. AWS Kotlin suspend client와 기존 Floci/Testcontainers helper를 재사용하고 새 dependency를 추가하지 않았다. |
| 운영·문서 | PASS | root/module README EN·KO, manual EN·KO, `[미출시]` CHANGELOG, Korean lesson, plan/risk/checklist를 갱신했다. PK-only table, metadata 전용 TTL, lock row 보존, Floci-only command와 실제 AWS·TTL 비동기·clock skew gap을 명시했다. |

## Finding ledger

| 초기 심각도 | finding | 처리 |
| --- | --- | --- |
| P1 | active TTL metadata 삭제가 만료 조건을 요구함 | 삭제 경로는 value/expiry/TTL equality만 사용하고, expired cleanup일 때만 `expiresAt <= now`를 추가했다. unit·Floci에서 두 경계를 검증했다. |
| P1 | metadata value parser가 256 bytes로 제한됨 | metadata 전용 350,000-byte validator를 사용하고 빈 값·최대 경계를 테스트했다. |
| P1 | takeover가 `Long.MAX_VALUE`를 기록한 뒤 lease 발급에 실패할 수 있음 | `Long.MAX_VALUE - 1` 이상 token은 2차 mutation 전에 exhaustion으로 거부하고, usable token 경계를 회귀 테스트했다. |
| P1 | `AllNew` token 응답을 연산 기대값과 비교하지 않음 | fresh=1, takeover=observed+1, renew=기존 lease token을 검증한다. mismatch는 lease로 노출하지 않는다. |
| P1 | 음수 injected clock이 release에 durable malformed expiry를 기록함 | 공통 `coordinationNowEpochSeconds`가 음수 epoch를 호출 전 거부한다. |
| P1 | lock fencing 조건·Floci stale lease·경쟁 증거가 부분적임 | request equality/expiry/token/max 조건, stale renew/release, raw owner/token/row 보존, 독립 coordinator 2·8-way barrier를 추가했다. |
| P2 | 두 번째 conditional failure의 malformed row를 정상 race로 숨김 | error item이 있으면 같은 schema parser로 검증하고 malformed는 예외로 전파한다. item 없음만 정상 race/miss다. |
| P2 | SDK logging이 generic catch로 detekt를 위반함 | `SdkBaseException`만 terminal SDK logging 대상으로 좁히고 cancellation/conditional 예외는 그대로 전파한다. |
| P2 | metadata AllOld 부재가 logging 없이 예외화됨 | lock과 같은 low-cardinality `requireOldItem` logging helper를 사용한다. |
| P2 | Floci capability probe가 필수 AllOld 부족을 skip할 수 있음 | 필수 AllOld는 hard failure로 처리한다. TTL은 성공 응답을 확인하고 알려진 unsupported SDK 오류만 gap으로 기록한다. |
| P3 | 실제 AWS throttling·async TTL deletion·clock skew·LSP는 local lane에서 직접 검증할 수 없음 | 문서와 risk ledger에 명시적 N/A/gap으로 남겼으며 production 성공 증거로 주장하지 않는다. |

## Fresh evidence

2026-08-27 최신 소스에서 아래 명령을 순서대로 재실행했다. 모든 AWS 서비스 검증은
사용자가 지정한 FlociServer 또는 MockK만 사용했으며 실제 AWS endpoint·credential은 사용하지
않았다.

| 단계 | 명령 요약 | 결과 |
| --- | --- | --- |
| 단위 계약 | `:bluetape4k-aws-kotlin:test` + schema/support/lease/lock/metadata 5개 selector, `--no-build-cache --rerun-tasks` | `48 passing`, `gradle_rc=0`, `BUILD SUCCESSFUL` (25초) |
| Floci 계약 | `:bluetape4k-aws-kotlin:test --tests '*DynamoDbCoordinationFlociTest' -Dbluetape4k.aws.emulator=floci --no-build-cache --rerun-tasks` | `4 passing`, `gradle_rc=0`, `BUILD SUCCESSFUL` (28초) |
| 영향 모듈 전체 | `:bluetape4k-aws-kotlin:test -Dbluetape4k.aws.emulator=floci --no-build-cache --rerun-tasks` | `735 passing`, `13 pending`, `gradle_rc=0`, `BUILD SUCCESSFUL` (42초) |
| 정적 분석 | `./gradlew detekt --no-daemon --rerun-tasks` | `gradle_rc=0`, `BUILD SUCCESSFUL` |
| 매뉴얼 계약 | `ruby scripts/manual/manual_contract_test.rb` | `9 runs, 44 assertions, 0 failures, 0 errors, 0 skips` |
| 매니페스트 | `ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check` | `Manual manifest snapshot is current.` |
| diff/구조 | `git diff --check`, placeholder·README·구조·EN/KO anchor/fence 검사 | 모두 통과; anchor `22`, code fence `28` |

## Gate disposition

- **구현·Kotlin gate:** PASS. P0/P1=0이며 detekt와 targeted test가 green이다.
- **Floci gate:** PASS. emulator guard, PK-only schema, conditional AllOld, fencing,
  logical expiry, active/expired metadata deletion, 2·8-way contention과 cancellation-safe
  cleanup을 확인한다.
- **문서 gate:** PASS. EN/KO manual anchor·code fence·API token parity와 manifest contract를
  확인한다.
- **PR/merge gate:** 구현 review는 통과했지만 PR publication과 merge는 별도 exact-head
  evidence 및 fresh 사용자 승인 전에는 수행하지 않는다.
