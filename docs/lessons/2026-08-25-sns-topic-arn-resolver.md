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
   생성자 2개를 명시적 compatibility descriptor에 반영해 기존 두 생성자를
   유지하면서 additive API임을 고정했다.
4. 첫 detekt 실행은 broad catch, throws count, magic number, test line-length를
   발견했다. 의도적인 `Throwable` outcome 보존은 경계별 suppression으로
   기록하고 ARN/topic 상수와 테스트 import/줄바꿈을 정리해 정적 검사를
   통과시켰다.

## 검증 증거

- RED: resolver 타입을 아직 만들지 않은 상태에서 targeted test compile이
  unresolved resolver/cache 타입으로 실패했다.
- targeted resolver/cache·auto-configuration·template: `42 passing`,
  `BUILD SUCCESSFUL`.
- Floci SNS emulator: `SnsCoroutinesTemplateAwsEmulatorTest` `9 passing`,
  `BUILD SUCCESSFUL`; negative cache 후 create invalidate 경계를 실제 emulator로
  통과했다.
- affected module: `:bluetape4k-aws-spring-boot:test` `700 passing`,
  `BUILD SUCCESSFUL`, `--max-workers=1 --no-parallel`.
- static: `:bluetape4k-aws-spring-boot:detekt` `BUILD SUCCESSFUL`.
- `git diff --check` 통과와 public KDoc/API read-back을 최종 커밋 전 최종 점검으로 남겼다.

## Step 6-R 통합 결과

세 독립 관점의 초기 결과는 performance P1=3/P2=3, stability P1=3/P2=2,
security P1=3/P2=2였고 P0는 없었다. transient outcome, late-write 차단,
effective connection scope, ARN trust boundary, bounded TTL와 관련 테스트를
보완한 뒤 현재 module slice 판정은 `P0=0, P1=0`이다.

남은 P2는 active-flight의 별도 global semaphore를 두지 않는 결정(현재 flight가
caller 수명 동안만 존재하고 완료 시 map에서 제거됨)과 owner/waiter cancellation
stress 강화다. 둘 다 현재 Issue #474의 correctness DoD를 막지 않으며, 전자는
global cross-key 병렬성을 훼손할 수 있어 이 범위에서 도입하지 않는다.

## 다음 작업을 위한 방어선

- resolver를 바꿀 때 persistent cache와 transient flight의 수명을 혼동하지
  않는다. cache hit이 없어도 겹친 호출의 outcome은 재사용되어야 한다.
- invalidate/clear와 결과 저장의 lock 순서를 바꾸지 말고, create 직후 negative
  cache를 우회하는 emulator 또는 fake test를 유지한다.
- accountId는 cache scope label이며 IAM authorization 증명이 아니다. 교차 계정
  ARN opt-in은 SDK credential 정책을 대체하지 않는다.
- batch publishing(#456), HTTP signature, topic 자동 생성은 이번 변경에 넣지
  않는다.

## DoD Status

- [x] spec/plan과 구현·테스트 traceability
- [x] resolver/cache/template/auto-configuration 구현
- [x] fake, Floci, affected-module test 및 detekt
- [x] Step 6-R 통합 `P0=0, P1=0`
- [x] PR·merge·remote push는 권한 범위 밖이라 실행하지 않음

**상태: DONE — Issue #474 구현과 로컬 검증을 완료했으며 PR delivery는 별도 승인 대기다.**
