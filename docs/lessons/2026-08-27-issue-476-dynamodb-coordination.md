# Issue #476 DynamoDB coordination lesson

## 결정

- AWS Kotlin SDK의 native suspend `DynamoDbClient`를 그대로 주입하고, 새 dependency나
  별도 client lifecycle을 만들지 않았다.
- `DynamoDbDistributedLock`은 PK-only table에서 `UpdateItem` 조건부 쓰기만 사용한다.
  acquire의 빠른 경로는 한 번, 만료 takeover는 관찰한 owner·expiry·fencing token의
  equality를 포함해 최대 두 번 호출한다.
- release는 row를 삭제하지 않고 `owner`를 제거하며 `expiresAt=now`로 표시한다. 이 방식이
  fencing counter를 보존해 다음 owner가 더 큰 `LockLease.fencingToken`을 받도록 한다.
- `DynamoDbMetadataStore`는 String value와 선택적 logical expiry를 같은 epoch second로
  기록하고, 만료 item의 교체·정리에는 관찰 값과 expiry를 포함한 bounded CAS를 사용한다.

## 처음 발견한 위험과 실패

### fencing token reset

처음 단순한 delete/recreate 흐름을 고려했을 때 lock row가 삭제되면 다음 acquire가 token
`1`부터 다시 시작할 수 있었다. 오래 실행 중인 worker의 side effect가 새 worker의 write를
덮는 ABA가 된다. 따라서 lock row 물리 삭제와 lock TTL을 금지하고, `if_not_exists` counter와
`Long.MAX_VALUE` exhaustion guard를 조건부 update에 고정했다.

### AllOld capability 경계

조건 실패에서 `ReturnValuesOnConditionCheckFailure.AllOld`가 없으면 active인지 expired인지
판별할 관찰 상태가 없다. 이 경우를 단순 miss로 처리하면 indeterminate acquire를 성공처럼
오인하거나 두 번째 mutation을 잘못 보낼 수 있다. acquire, `putIfAbsent`, `remove`의 첫
조건 실패에서 old item이 없으면 `IllegalStateException`으로 fail closed하고, renew/release
의 old item 부재만 정상적인 stale 결과로 매핑했다.

### Floci expression 제약

Floci 검증 중 request에 사용하지 않는 `ExpressionAttributeNames` alias를 모두 넣었을 때
`Value provided in Expr ... ExpressionAttributeNames unused` 오류가 발생했다. 고정 template은
유지하되 각 요청에서 실제 expression에 쓰는 alias만 선택하도록 `aliases` helper를 분리했다.
이 guard는 custom attribute name schema에서도 그대로 적용된다.

## resolver 책임

기본 resolver는 namespace·kind·logical key를 UTF-8 length-prefixed tuple로 인코딩해 delimiter가
포함된 key도 충돌하지 않게 한다. custom `DynamoDbCoordinationNameResolver`의 injectivity와
결정성은 임의의 함수에 대해 사후 검증할 수 없으므로 caller 계약으로 남겼다. library는
blank/control 문자와 2,048-byte 물리 key 상한만 fail closed로 확인한다.

## 검증 증거

- `DynamoDbCoordinationSchemaTest`, `LockLeaseTest`, `DynamoDbCoordinationSupportTest`,
  `DynamoDbDistributedLockUnitTest`, `DynamoDbMetadataStoreUnitTest`: 총 37개 테스트 통과.
- `DynamoDbCoordinationFlociTest`: PK-only table, conditional `AllOld`, 2/8-way lock
  contention, renewal/heartbeat/release, fencing takeover, metadata overwrite/logical
  expiry/CAS/cleanup을 FlociServer에서 4개 테스트로 통과.
- `:bluetape4k-aws-kotlin:detekt`와 Kotlin compilation 통과. 실제 AWS credential·endpoint는
  사용하지 않았다.

## 재발 방지 guard

1. downstream write는 `LockLease.fencingToken`을 다시 조건으로 검사한다.
2. schema의 metadata TTL attribute만 DynamoDB TTL 대상으로 활성화하고 lock row에는 TTL을
   적용하지 않는다.
3. malformed `AttributeValue`·fractional number·missing `AllOld`는 null/false로 숨기지 않고
   두 번째 mutation 전에 예외를 낸다.
4. adapter 내부 retry, pre-read, background coroutine을 만들지 않는다. 취소 가능한 cleanup은
   `NonCancellable` 안에서 bounded `withTimeout`으로만 수행한다.
5. Floci는 emulator 조건부 쓰기와 logical expiry를 증명하지만 AWS throttling, asynchronous
   TTL deletion, clock skew, 운영 quota와 IAM을 증명하지 않는다는 한계를 매뉴얼과 테스트에
   함께 기록한다.
