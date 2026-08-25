# Issue #474 SNS topic ARN resolver와 bounded cache

## 맥락

`aws-spring-boot`의 기존 `SnsCoroutinesTemplate.findTopicArn`은 호출마다
`ListTopics` 전체 pagination을 수행했다. 이름 조회를 반복하는 publisher와
Floci 테스트에서 네트워크 비용, negative lookup의 stale window, 같은 이름의
동시 중복 요청을 하나의 경계로 다룰 필요가 있었다.

## 결정

- `SnsTopicArnResolver`와 `SnsTopicArnCache`를 분리하고 기존
  `SnsOperations.findTopicArn`은 주입된 resolver에 위임한다.
- 기본 cache는 `Clock` 기반 TTL, access-order LRU, positive/negative entry를
  사용하며 최대 TTL을 24시간으로 제한한다. `NoopSnsTopicArnCache`를 사용해도
  영속 cache와 독립된 transient flight outcome으로 겹친 호출을 합친다.
- 동일 scope/name만 `Mutex` flight를 공유하고 다른 이름은 병렬로 진행한다.
  invalidate/clear는 pending flight를 분리하고 cache put과 같은 lock 순서로
  stale late-write를 막는다.
- explicit SNS ARN은 AWS 호출과 cache를 우회하지만 service, region, account,
  topic-name 형식을 검증한다. 교차 account는
  `allow-cross-account-topic-arn=true`일 때만 opt-in으로 허용한다.
- effective AWS service connection의 endpoint/region을 resolver scope에 전달하고,
  configured account가 있으면 explicit ARN과 `ListTopics` 반환 ARN을 같은 계정으로
  검증한다. account 또는 region을 모르는 explicit ARN은 fail-closed한다.
- 기존 `SnsProperties`의 5-인자 constructor, component, `copy`, Kotlin default-arg
  constructor descriptor를 보존해 새 설정을 additive로 도입한다. resolver를
  주입하는 template 3-인자 생성자는 기본 strategy를 사용하며, Java에서
  `null`을 세 번째 인자로 전달하면 strategy/resolver overload가 모호하므로
  명시적 cast 또는 4-인자 생성자를 사용해야 한다.
- cache key에는 endpoint/region/account와 resolver isolation namespace를
  포함한다. `SnsConnectionDetails`의 effective endpoint/region이 properties보다
  우선하며 endpoint의 user-info/query/fragment는 허용하지 않는다.
- `createTopic`과 `createFifoTopic`의 성공 직후 name을 invalidate한다. create
  직후 AWS 목록 반영 지연은 다음 조회가 null 또는 SDK 오류를 반환할 수 있고,
  SDK 오류는 cache하지 않는다는 경계를 유지한다.

## 예상 밖의 문제와 수정

1. 처음 구현한 per-key mutex는 `Noop` cache 또는 LRU eviction 뒤에 waiter가
   다시 AWS를 호출할 수 있었다. flight에 transient success/failure outcome을
   추가하고 Noop, max-size=1 overlap 테스트로 중복 억제를 고정했다.
2. pending 조회 중 invalidate/clear가 발생하면 owner가 늦게 결과를 cache에 쓸
   수 있었다. flight 무효화와 cache put을 공통 `ReentrantLock` 순서로 묶고,
   invalidate/clear race 테스트 및 Floci negative→create→find 테스트를 추가했다.
3. 새 public 생성자 때문에 기존 descriptor test가 실패했다. resolver 주입
   template resolver overload와 기존 strategy overload의 public descriptor를
   함께 유지하고 Java `null` 호출 모호성을 문서화했으며, `SnsProperties`에는
   기존 5-인자 JVM constructor/copy/copy$default/default-arg descriptor를
   명시적으로 보존하는 compatibility overload와 reflection test를 추가했다.
4. 첫 detekt 실행은 broad catch, throws count, magic number, test line-length를
   발견했다. 의도적인 `Throwable` outcome 보존은 경계별 suppression으로
   기록하고 ARN/topic 상수와 테스트 import/줄바꿈을 정리해 정적 검사를
   통과시켰다.
5. 초기 resolver는 configured global AWS region/endpoint를 scope에 전달하지
   않아 properties와 실제 client가 달라질 수 있었고, `ListTopics`가 반환한 ARN을
   형식·region·account 관점에서 재검증하지 않았다. auto-configuration의 effective
   defaults 전달과 반환 ARN validation, unknown account/region fail-closed 테스트로
   이 경계를 고정했다.
6. terminal AWS failure의 운영 신호가 없고 문서가 cache 비활성화와 전체 rollback을
   구분하지 않았다. 원문을 남기지 않는 hashed warning을 한 번만 기록하고 EN/KO
   README와 manual에 기본값·custom bean·rollback·eventual consistency를
   정리했으며, warning redaction test를 추가했다.
7. AWS SDK의 기본 Region provider chain을 사용하는 애플리케이션에서는 설정값이
   `null`이어도 최종 client가 유효한 Region을 가질 수 있었다. 초기 strict equality
   검사는 이 정상 경로를 기동 실패로 만들었으므로, 명시적으로 설정한 Region만
   일치 여부를 검사하고 미설정 시 SDK가 선택한 최종 Region을 resolver scope로
   사용하도록 수정했다. `aws.region` system property startup 회귀 테스트와 함께
   직접 생성 template의 client identity 일치·명시적 resolver 주입 계약을 EN/KO
   KDoc·README·manual에 고정했다.
8. `allow-cross-account-topic-arn`을 explicit ARN 경로에만 적용해야 하는데,
   초기 account 검증은 `ListTopics` 반환 ARN에도 같은 opt-in을 적용할 수 있었다.
   configured account가 있는 name 조회는 항상 같은 account를 요구하도록 경계를
   분리하고, opt-in이 켜져도 다른 account 응답을 거부하는 negative 회귀 테스트를
   추가했다.

## 검증 증거

- RED: resolver 타입을 아직 만들지 않은 상태에서 targeted test compile이
  unresolved resolver/cache 타입으로 실패했다.
- targeted resolver/cache·auto-configuration·template·ABI/redaction: `62 passing`,
  `BUILD SUCCESSFUL`.
- Floci `SnsCoroutinesTemplateAwsEmulatorTest`: `9 passing`, `BUILD SUCCESSFUL`.
- affected `:bluetape4k-aws-spring-boot:test`: `716 passing`, `BUILD SUCCESSFUL`.
- `:bluetape4k-aws-spring-boot:detekt`: `BUILD SUCCESSFUL`.
- manual contract: `9 runs, 44 assertions, 0 failures`; manifest snapshot과
  `git diff --check`도 통과했다.
- `javap`/reflection에서 기존 constructor/copy/default-arg descriptor를
  확인했다. 다음으로 follow-up review와 live develop rebase 후 exact-head
  receipt를 확정한다.

## Step 6-R 통합 결과

여섯 관점(performance, stability, security, operations, developer/API,
user/caller)의 초기 결과는 performance P1=3/P2=3, stability P1=3/P2=2,
security P1=3/P2=2, operations P1=3/P2=1, developer/API P1=1/P2=2,
user/caller P1=0/P2=0이었다. transient outcome, late-write 차단, effective
connection scope, ARN trust boundary, bounded TTL, ABI overload와 운영 문서·로그를
보완했고, 각 reviewer의 follow-up과 main integration은 exact current head에서
재확인한다. delivery-ready 판정은 이 re-review와 PR CI receipt 이후에만 확정한다.

남은 P2는 active-flight의 별도 global semaphore를 두지 않는 결정(현재 flight가
caller 수명 동안만 존재하고 완료 시 map에서 제거됨)과 owner/waiter cancellation
stress 강화다. 둘 다 현재 Issue #474의 correctness DoD를 막지 않으며, 전자는
global cross-key 병렬성을 훼손할 수 있어 이 범위에서 도입하지 않는다. 측정과
stress matrix는 후속 Issue #564로 연결했다.

## 다음 작업을 위한 방어선

- resolver를 바꿀 때 persistent cache와 transient flight의 수명을 혼동하지
  않는다. cache hit이 없어도 겹친 호출의 outcome은 재사용되어야 한다.
- invalidate/clear와 결과 저장의 lock 순서를 바꾸지 말고, create 직후 negative
  cache를 우회하는 emulator 또는 fake test를 유지한다.
- accountId는 cache scope label이며 IAM authorization 증명이 아니다. 교차 계정
  ARN opt-in은 SDK credential 정책을 대체하지 않는다.
- Java 호출자는 resolver 3-인자와 strategy 3-인자의 `null` overload 모호성을
  피하고 명시적 타입 cast 또는 resolver+strategy 4-인자 생성자를 사용한다.
- batch publishing(#456), HTTP signature, topic 자동 생성은 이번 변경에 넣지
  않는다.
- global flight semaphore, owner/waiter cancellation stress matrix, lazy TTL
  sweep와 추가 convenience overload는 후속 hardening 범위로 남긴다. 동시성·취소
  hardening은 [Issue #564](https://github.com/bluetape4k/bluetape4k-aws/issues/564)에서
  추적한다.

## DoD Status

- [x] spec/plan과 구현·테스트 traceability
- [x] resolver/cache/template/auto-configuration 구현
- [x] fake 기반 targeted test 62건, ABI reflection, warning redaction 및 detekt
- [x] rebase된 exact head에서 Floci 9건·affected-module full 716건과
  `git diff --check`
- [ ] Step 6-R follow-up re-review와 exact-head CI receipt
- [ ] PR 생성·merge·canonical `develop` sync·안전한 local cleanup

**상태: PENDING — Issue #474 구현과 주요 로컬 검증은 완료했으며, 최종 exact-head
재검증 후 PR delivery, merge, canonical sync/cleanup 게이트가 남아 있다.**
