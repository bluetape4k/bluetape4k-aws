# #476 DynamoDB coordination 구현 리스크

**범위:** `aws-kotlin`의 Floci 전용 local verification. 실제 AWS 계정·credential·운영
latency는 사용하지 않으며, 아래 N/A 항목을 production 증거로 주장하지 않는다.

## 리스크 ledger

| 신호/실패 모드 | 영향 | 완화·구현 guard | 재실행/판정 |
| --- | --- | --- | --- |
| release가 lock row를 삭제하거나 fencing counter를 0으로 되돌림 | 이전 owner의 stale side effect가 새 owner와 섞이고 token이 재사용됨 | release는 `UpdateItem`으로 owner 제거와 `expiresAt=now`만 수행하고 `fencingToken`을 보존한다. `DeleteItem`/lock TTL을 production source와 Floci test에서 금지한다. | `DynamoDbDistributedLockUnitTest`와 Floci expiry/reacquire에서 row 잔존·strictly increasing token 확인 |
| expired takeover가 observed owner/expiry/token을 조건으로 묶지 않음 | 두 contender가 같은 만료 row를 덮어쓰거나 이미 새 owner가 된 row를 stale writer가 탈취함 | 첫 conditional 실패의 `AllOld`를 읽고, valid expired item에 한해 equality condition의 두 번째 `UpdateItem`을 정확히 한 번만 보낸다. race failure는 `null`, loop 없음. | MockK call count 1/2 검증 후 Floci 2·8 coroutine barrier 재실행 |
| fencing token이 `Long.MAX_VALUE - 1`일 때 takeover가 `Long.MAX_VALUE`를 먼저 기록함 | caller가 lease를 받지 못한 채 새 owner row가 exhausted 상태로 남음 | takeover 전 usable upper bound를 확인하고 `Long.MAX_VALUE - 1` 발급을 거부한다. `AllNew` token은 fresh=1, takeover=observed+1, renew=기존 lease token과 정확히 일치해야 한다. | max-1 unit fixture와 AllNew mismatch fixture에서 second-call/lease 노출을 검증 |
| 연산별 `oldItem == null` 의미를 혼동함 | acquire/metadata의 진단 정보가 사라지거나 renew/release의 정상 missing 결과가 예외가 됨 | acquire·putIfAbsent·remove의 첫 실패에서 null old item은 unsupported/malformed로 fail-closed하고 second mutation을 금지한다. renew/release의 실패에서 null old item은 각각 `null`/`false`로 반환하며 old item이 있을 때만 parser를 호출한다. | operation별 MockK exception fixture와 call-count 테스트를 각각 재실행 |
| `ReturnValuesOnConditionCheckFailure.AllOld`가 없거나 old map이 불완전함 | malformed item을 정상 expired item으로 오인해 unsafe mutation | parser는 missing/wrong type/fraction/range를 fail-closed `IllegalStateException`으로 처리하고 second mutation을 보내지 않는다. 필수 `AllOld`/conditional capability가 없으면 pre-read/fallback 없이 run을 `PENDING/BLOCKED`로 멈추고 PR을 금지한다. | malformed unit tests와 Floci capability probe를 먼저 실행; 필수 capability 부재는 N/A가 아니라 blocker로 기록 |
| DynamoDB `N` 값이 `1.0`, fraction, 음수 또는 overflow | expiry/token 비교가 틀리고 lease 상태 판정이 비결정적이 됨 | canonical integer parsing, `expiresAt >= 0`, `fencingToken > 0`, `Long.MAX_VALUE` exhaustion을 별도 검증한다. | parser unit tests; 첫 실패 원인과 no-second-call evidence 기록 |
| custom resolver가 namespace/kind/key tuple을 충돌시킴 | 서로 다른 lock/metadata가 같은 physical item을 공유해 data loss/split-brain 발생 | 기본 resolver는 length-prefixed injective encoding을 사용한다. custom resolver 전체 tuple injectivity는 caller 책임·undefined behavior로 KDoc/manual에 명시하고 library는 output 형식만 검증한다. | delimiter/namespace/LOCK-METADATA 분리 단위 테스트; custom collision은 library가 탐지한다고 주장하지 않음 |
| scopeId/table/namespace/physical key가 서로 다른 lease를 renew/release | 다른 schema의 lease가 현재 table item을 변경 | renew/release 전에 table, partition attribute, namespace, physical key, scopeId를 비교하고 불일치 시 DynamoDB 호출 전 예외를 낸다. scopeId는 auth 경계가 아님을 문서화한다. | `LockLeaseTest`와 unit request call-count 0 검증 |
| clock skew 또는 expiry 경계가 `>`/`<=` 규칙과 다름 | 이미 만료된 lease가 갱신되거나 아직 유효한 lease가 takeover됨 | 모든 판단에 injected `Clock`의 epoch seconds를 사용한다. renew/release는 `expiresAt > now`, logical get은 `expiresAt <= now`를 적용하고 duration 덧셈 overflow를 거부한다. | fixed clock unit tests, Floci는 정수 초 경계만 검증; 실제 분산 clock skew는 N/A |
| SDK throttling/timeout을 adapter가 재시도하거나 cancellation을 삼킴 | 호출 상한 붕괴, duplicate mutation, structured concurrency 위반 | `ConditionalCheckFailedException`만 결과로 매핑하고 SDK retry/timeout은 caller 설정에 위임한다. `CancellationException`과 다른 예외는 그대로 전달한다. | MockK exceptional tests; retry count는 adapter call count로 판정 |
| indeterminate acquire timeout 뒤에 성공으로 가정 | 작업을 시작한 뒤 이미 다른 worker가 owner일 수 있음 | timeout은 성공 증거가 아니다. caller는 작업을 시작하지 않고 lease expiry 대기 또는 diagnostic read를 선택해야 한다는 manual 경고를 둔다. adapter에 background poll/retry를 추가하지 않는다. | KDoc/manual read-back; runtime AWS timeout 재현은 N/A |
| cancellation 중 cleanup이 취소됨 | Floci table/resource가 남고 다음 테스트가 오염됨 | test `finally`와 caller example은 `withContext(NonCancellable)` 안에서 `withTimeout(5.seconds)`를 사용하고 cleanup 실패를 원래 예외에 suppressed로 붙인다. | Floci test repeated run; cleanup timeout은 명시적 실패 |
| table delete 요청이 반환됐지만 raw table이 아직 남아 있음 | 다음 Floci run이 이전 table과 충돌하거나 cleanup 성공을 과장함 | `deleteTableIfExists` 호출과 `existsTable` 부재 확인을 각각 `NonCancellable` + `withTimeout(5.seconds)` 경계로 감싸고, 50ms bounded polling이 초과하면 실패로 기록한다. | order 5 idempotent cleanup과 bounded absence assertion |
| Floci shared Docker 자원과 병렬 test 실행 | table 경쟁, port/CPU 고갈, flaky result | `-Dbluetape4k.aws.emulator=floci --no-parallel --max-workers=1`, unique table/run, barrier의 독립 client를 사용하고 cleanup을 보장한다. | targeted Floci lane을 먼저 순차 재실행한 뒤 affected module test 실행 |
| metadata TTL을 lock item에도 기록하거나 `expiresAt`에 TTL 활성화 | lock row가 비동기 삭제되어 fencing counter가 사라짐 | `ttlEpochSeconds`는 metadata item에만 기록하며 caller가 해당 attribute에만 TTL을 활성화한다. `expiresAt`는 logical/correctness field다. | raw Floci `GetItem`에서 metadata만 TTL attr 보유 확인 |
| Floci TTL capability를 raw attribute만으로 오인함 | TTL 설정 API 미지원인데도 production 증거로 기록함 | table 생성 직후 `UpdateTimeToLiveRequest` 응답의 `timeToLiveSpecification.attributeName/enabled`와 `DescribeTable` key schema를 캡처한다. TTL 설정만 gap이면 raw attribute assertion으로 제한하고, `AllOld`/conditional/logical expiry 부재는 `PENDING/BLOCKED`다. | Floci capability probe를 first integration order에서 먼저 실행 |
| metadata `putIfAbsent`/remove가 value와 observed expiry를 equality로 묶지 않음 | 만료 item을 교체하는 동안 새 값이 삭제되거나 stale writer가 overwrite | AllOld를 parser로 검증한 valid expired item만 second Put/Delete를 하고, value/expiry(및 expected value)를 equality 조건에 넣는다. 최대 두 호출, retry loop 없음. | unit request capture와 2/8 metadata race 시나리오 |
| active metadata TTL 삭제에 expired-only 조건을 적용함 | 유효한 TTL metadata가 `remove`/`removeIfValue`에서 삭제되지 않음 | 삭제는 value/expiry/TTL equality만 사용하고, 이미 만료된 정리 경로에서만 `expiresAt <= now`를 추가한다. | active TTL unit/Floci 삭제와 expired cleanup의 조건 map을 각각 캡처 |
| 동일 metadata value를 재기록하는 ABA | `removeIfValue`가 다른 generation의 값을 삭제할 수 있음 | `removeIfValue`는 ownership/ABA 방어를 주장하지 않는다. caller가 unique version 또는 `LockLease.fencingToken`을 value/downstream condition에 포함해야 한다. | KDoc/manual과 lesson에 제한을 명시; 별도 version codec 추가 금지 |
| key/owner/value/duration 상한을 우회해 item/request를 팽창시킴 | 400KB item 경계, CPU/memory/비용 증가, request 거부 | identifier/attribute 256 UTF-8 bytes, resolver 2,048 bytes, metadata String 350,000 bytes, duration 1초~365일 finite integer를 호출 전 검사한다. | boundary ±1 byte/second unit tests |
| release 결과 `false` 또는 eventual read를 성공으로 오인 | 작업 종료 뒤 ownership을 잃거나 재처리 누락 | `release=false`는 stale/expired lease의 신호로 문서화한다. critical 후속 쓰기는 fencing token 조건으로 수행하고, `get`은 `consistentRead` 옵션을 사용한다. | manual example와 Floci stale release assertion |
| IAM namespace/owner를 authentication으로 사용하거나 metadata에 secret 저장 | tenant 간 오용, secret 노출 | namespace/owner는 naming·coordination 정보일 뿐 auth boundary가 아니다. least-privilege table/IAM은 caller가 분리하고 secret/PII는 Secrets Manager/KMS로 보낸다. 로그·telemetry에 value/token을 넣지 않는다. | security review read-back; 실제 IAM policy test는 N/A |
| public API가 client lifecycle을 닫거나 local mutex/background state를 생성 | caller-owned client 중단, bounded call 계약 위반 | coordinator는 주입 client를 닫지 않고 suspend SDK member만 호출한다. local mutex, polling, heartbeat scheduler, unbounded map을 추가하지 않는다. | source scan와 unit call-count; lifecycle test는 client가 caller-owned인지 확인 |

## 검증 순서와 stop 조건

1. schema/lease/unit RED→GREEN 후 `DynamoDbDistributedLockUnitTest`와
   `DynamoDbMetadataStoreUnitTest`를 실행한다.
2. Floci targeted test를 sequential로 실행해 실제 conditional behavior, cleanup, backend
   endpoint를 확인한다.
3. affected module test, `detekt`, manual contract, `git diff --check`를 실행한다.
4. malformed/capability/timeout failure가 해결되지 않으면 조건식을 완화하거나 실제 AWS로
   범위를 넓히지 않고 `PENDING` 또는 명시적 N/A gap으로 멈춘다.

## 롤백

구현이 승인된 contract를 만족하지 못하면 새 coordination production source, tests, manual
절, lesson을 한 단위로 되돌리고 기존 DynamoDB/Kinesis targeted test를 재실행한다. 기존
파일과 unrelated worktree/checkout 변경은 복원하지 않는다. 이미 published PR이 있으면
rollback commit을 별도 Lore decision record로 남기며 lock row를 운영 table에서 삭제하는
복구 절차는 제공하지 않는다.
