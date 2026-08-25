# SNS topic ARN resolver·cache 설계

## 문제와 목표

`aws-spring-boot`의 `SnsCoroutinesTemplate.findTopicArn`은 topic name을
확인할 때마다 `ListTopics`의 모든 페이지를 순회한다. publish 호출을 위해
같은 topic을 반복 확인하면 네트워크 비용과 eventual consistency 경로가
호출자마다 달라진다. Issue #474의 목표는 이름/ARN 입력을 하나의 resolver로
정규화하고, bounded TTL/size cache와 중복 조회 억제를 제공하는 것이다.

이번 변경의 종료 조건은 다음과 같다.

- 같은 resolver scope에서 TTL 안에 확인한 topic name은 `ListTopics`를 다시
  호출하지 않는다.
- 미존재 결과도 bounded negative entry로 저장하며, AWS 오류는 cache에
  저장하지 않고 그대로 호출자에게 전파한다.
- 명시적 ARN은 trim 후 그대로 반환하고 cache와 AWS 조회를 모두 우회한다.
  `.fifo` suffix를 포함한 ARN은 변형하지 않는다.
- topic 생성이 성공하면 해당 name을 invalidate해 create 직후 재조회가
  stale negative entry를 사용하지 않도록 한다.
- endpoint, region, account 식별자가 다른 resolver scope는 동일한 cache를
  공유해도 서로 다른 key를 사용한다.
- 동일 name의 동시 coroutine은 한 번만 `ListTopics`를 실행한다. 한 호출이
  취소되거나 실패해도 in-flight 상태가 남지 않는다.

범위 밖은 SNS batch/async publishing(#456), HTTP notification/signature,
무기한 전역 cache, 계정 간 credential 공유, topic 자동 생성이다.

## 현재 근거

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsCoroutinesTemplate.kt`의
  기존 `findTopicArn`은 `ListTopics` pagination을 직접 순회한다.
- `SnsPublishRequest`는 현재 `topicArn`을 필수로 받으므로 이번 변경은 기존
  publish request 모양을 바꾸지 않고 resolver bean과 `findTopicArn` 경로를
  제공한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/kms/DataKeyCache.kt`의
  `InMemoryDataKeyCache`가 `Clock` 주입, TTL, access-order LRU, 명시적
  invalidate/clear 패턴을 제공한다.
- Spring Cloud AWS SNS 문서는 topic name을 ARN으로 바꾸는 resolver를
  template 경계에 두고, name 조회에 `sns:ListTopics` 권한이 필요함을
  설명한다. 참고: <https://github.com/awspring/spring-cloud-aws/blob/main/docs/src/main/asciidoc/sns.adoc>

## 선택지와 결정

### A. template 내부에 단순 map cache를 추가

기존 `findTopicArn`에 `ConcurrentHashMap`과 expiry timestamp를 직접 넣는
방법이다. 변경량은 작지만 resolver가 공개 경계로 분리되지 않고, negative
entry·scope·single-flight·Spring bean 교체 지점을 한 클래스가 떠안는다.

### B. resolver와 cache 계약을 분리하고 template에 주입한다 — 선택

`SnsTopicArnResolver`가 입력 정규화와 pagination을 담당하고,
`SnsTopicArnCache`가 bounded TTL/LRU 저장을 담당한다. resolver는 scope가
포함된 key와 per-key `Mutex` flight table을 사용한다. 자동 구성은 기본
`InMemorySnsTopicArnCache`와 resolver를 bean으로 등록하며, 사용자는 두
계약을 교체할 수 있다.

이 선택은 기존 `SnsOperations.findTopicArn`과 생성자를 유지하면서도
cache 정책과 조회 정책을 독립적으로 테스트할 수 있다. flight table은
사용자 coroutine scope를 탈출하는 background job을 만들지 않으므로
취소·수명 관리가 명확하다.

### C. AWS SDK `CreateTopic` 기반 resolver

Spring Cloud AWS의 기본 동작처럼 name을 `CreateTopic`으로 확인한다. 그러나
이번 이슈의 기존 계약은 `ListTopics` pagination이며, 호출자에게 topic 생성
권한과 생성 side effect를 요구한다. 미존재 검출과 create/publish race의
정책도 달라지므로 선택하지 않는다.

## API와 데이터 흐름

### 공개 계약

```kotlin
data class SnsTopicArnResolverScope(
    val endpointOverride: URI? = null,
    val region: String? = null,
    val accountId: String? = null,
)

data class SnsTopicArnCacheKey(
    val scope: SnsTopicArnResolverScope,
    val topicName: String,
)

sealed interface SnsTopicArnCacheEntry {
    data class Resolved(val topicArn: String): SnsTopicArnCacheEntry
    data object NotFound: SnsTopicArnCacheEntry
}

interface SnsTopicArnCache {
    fun get(key: SnsTopicArnCacheKey): SnsTopicArnCacheEntry?
    fun put(key: SnsTopicArnCacheKey, entry: SnsTopicArnCacheEntry)
    fun invalidate(key: SnsTopicArnCacheKey)
    fun clear()
}

class SnsTopicArnResolver(
    client: SnsAsyncClient,
    cache: SnsTopicArnCache = InMemorySnsTopicArnCache(),
    scope: SnsTopicArnResolverScope = SnsTopicArnResolverScope(),
) {
    suspend fun resolve(topicReference: String): String?
    suspend fun findTopicArn(topicName: String): String?
    fun invalidate(topicName: String)
    fun clear()
}
```

실제 구현은 위 계약의 이름을 유지하되 필요 이상의 publish overload를
추가하지 않는다. `SnsOperations.findTopicArn`은 주입된 resolver로 위임한다.
기존 `SnsCoroutinesTemplate(SnsAsyncClient, SnsProperties)`와
`(..., SnsBatchExecutionStrategy)` 생성자는 그대로 남기고 resolver를 받는
생성자를 추가한다. 자동 구성은 `SnsTopicArnCache`와
`SnsTopicArnResolver`를 먼저 만들고 template에 주입한다.

입력 흐름은 다음과 같다.

1. 공백을 제거하고 blank 입력은 `IllegalArgumentException`으로 거부한다.
2. `arn:`으로 시작하면 정규화한 문자열을 즉시 반환한다. cache, flight,
   `ListTopics`를 호출하지 않는다.
3. 그 외에는 name을 scope와 합쳐 cache key를 만든다.
4. cache hit이면 `Resolved` ARN 또는 `NotFound`를 반환한다.
5. miss이면 해당 key의 flight mutex를 획득하고 double-check 후 모든
   `ListTopics` 페이지를 순회한다.
6. 성공한 ARN 또는 null만 cache에 저장하고, 예외·취소는 저장하지 않는다.
   flight 참조는 finally에서 제거한다.

## Cache와 scope 정책

`InMemorySnsTopicArnCache`는 KMS `InMemoryDataKeyCache` 패턴을 재사용한다.
`LinkedHashMap(accessOrder = true)`와 `ReentrantLock`으로 bounded LRU를
구현하고, `Clock`을 주입해 TTL을 결정한다. `maxSize > 0`, `ttl > 0`을
생성 시 검증한다. `NoopSnsTopicArnCache`는 cache를 끄되 resolver의
single-flight는 유지한다.

`SnsProperties`에는 다음 설정을 추가한다.

```yaml
bluetape4k:
  aws:
    sns:
      account-id: "000000000000"
      topic-arn-cache:
        enabled: true
        max-size: 256
        ttl: 5m
```

endpoint override, region, account-id가 모두 cache key에 들어간다. account
ID를 모르는 구성은 null을 사용하며, 다른 endpoint/region/account-id를
사용하는 resolver는 같은 cache bean을 공유해도 충돌하지 않는다.

## 실패·취소·경계 조건

1. **미존재 topic:** 모든 페이지를 읽은 뒤 `null`을 `NotFound`로 저장한다.
   TTL 동안 반복 조회를 막지만 `invalidate`로 즉시 제거할 수 있다.
2. **create 직후 eventual consistency:** `createTopic`과
   `createFifoTopic`이 ARN을 성공적으로 반환한 직후 resolver의 name key를
   invalidate한다. AWS가 아직 목록에 반영하지 않은 경우 다음 조회는
   문서화된 `null` 또는 AWS SDK 오류를 반환하고, 오류는 cache하지 않는다.
3. **pagination:** `nextToken`이 blank가 될 때까지 순회하며 ARN suffix
   `:$topicName`을 비교한다. `.fifo` name은 suffix를 그대로 비교한다.
4. **동시 호출:** 동일 key에 대해서만 flight mutex를 공유한다. 다른
   key는 서로 차단하지 않는다. caller cancellation은 AWS await와 mutex
   finally를 취소하지만 다른 key나 잔여 flight를 남기지 않는다.
5. **scope 변경:** scope가 다른 key는 cache hit를 공유하지 않는다.
6. **cache 경계:** TTL 만료 entry는 get 시 제거하고, max size 초과 시
   가장 오래 접근하지 않은 entry를 제거한다. 전역 무기한 보관은 없다.

## 호환성과 운영 영향

- 기존 `SnsOperations` 구현체와 `SnsPublishRequest`는 변경하지 않는다.
- 기존 template 생성자는 유지한다. 직접 생성한 template도 기본 resolver를
  사용하므로 기존 `findTopicArn` 동작을 유지하면서 cache를 얻는다.
- 자동 구성에서 사용자 정의 `SnsTopicArnCache` 또는
  `SnsTopicArnResolver` bean은 `@ConditionalOnMissingBean`으로 존중한다.
- 새 configuration property의 기본값은 cache 활성화, `max-size = 256`,
  `ttl = 5m`이다. cache를 끄면 `enabled=false`를 사용한다.
- SDK 권한·credential·account를 공유하거나 변경하지 않는다. list 조회가
  실패하면 AWS SDK 예외를 그대로 전파해 호출자가 재시도 정책을 선택한다.

## 검증과 수용 기준 추적

| Issue #474 기준 | 검증 위치 |
|---|---|
| TTL 내 반복 `ListTopics` 억제 | resolver cache-hit 테스트, template delegation 테스트 |
| miss/미존재/eventual consistency 오류 경계 | negative cache, invalidate, 예외 미저장 테스트 |
| 명시적 ARN 우회와 FIFO 보존 | resolver normalization/FIFO 테스트 |
| fake client pagination/invalidate/동시 호출 | `SnsTopicArnResolverTest` |
| Floci 실제 생성·조회·publish | 기존 `SnsCoroutinesTemplateAwsEmulatorTest` 확장 |
| endpoint/region/account scope 분리 | 공유 cache를 사용하는 scope 테스트 |

## DoD

- [ ] 설계의 공개 타입과 property 이름이 현재 Kotlin/Spring 패턴과 일치한다.
- [ ] resolver/cache 단위 테스트가 RED→GREEN 순서로 통과한다.
- [ ] 기존 SNS template/auto-configuration 테스트와 Floci SNS smoke가 통과한다.
- [ ] `git diff --check`, Kotlin 정적 검사, 변경 module test가 통과한다.
- [ ] PR/merge/remote side effect는 사용자 요청 범위 밖으로 유지한다.

## Writer gate 기록

- SPW-01: PASS — Issue #474, 현재 `SnsCoroutinesTemplate`, `SnsPublishRequest`,
  `DataKeyCache`, Spring Cloud AWS 문서를 근거로 범위와 unknown을 고정했다.
- SPW-02: PASS — 문제, 대안, API, 데이터 흐름, 실패 모드, 호환성, 수용
  기준, DoD를 포함했다.
- SPW-03: PASS — Korean technical register를 사용하고 API/명령/URL을 보존했다.
- SPW-04: PASS — acceptance 표가 테스트·소스 경로에 매핑되며 unsupported
  publish overload를 범위에서 제외했다.
- SPW-05: PASS — Markdown을 read-back했고 placeholder/TBD/모순이 없다.
