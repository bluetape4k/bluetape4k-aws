# Issue #564 SNS topic ARN resolver 동시성·취소 stress hardening

## 맥락

Issue #474에서 `SnsTopicArnResolver`의 single-flight, bounded cache,
`invalidate`/`clear` 경계를 구현했다. 후속 검토에서는 owner/waiter 취소와
pending 결과의 늦은 도착을 반복 실행하고, 서로 다른 topic key가 계속 병렬로
진행하는지 확인해야 했다. 범위는
`aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsTopicArnResolverTest.kt`
의 테스트 보강으로 제한했다.

## 결정

- 전역 cross-key admission semaphore는 추가하지 않았다. 4개 topic key를
  `SuspendedJobTester`의 16 worker × 4 rounds로 겹쳐 실행해 AWS lookup이 정확히
  4회이고 모든 key가 완료되는지 확인했다. 현재 구현은 key별 flight를 유지하면서
  서로 다른 key의 병렬성을 보존한다.
- owner가 AWS 결과를 기다리는 동안 waiter를 취소하는 시나리오를 8 worker ×
  8 rounds로 반복한다. waiter의 `CancellationException`은
  `io.bluetape4k.assertions.assertFailsWith`로 검증하고 owner의 성공 결과와 단일
  AWS 호출을 함께 확인한다.
- `maxSize=1` cache에서 orders를 채운 뒤 payments로 eviction을 일으키고, 다시
  겹친 orders 조회가 AWS 호출 1회만 공유하는지 8 worker × 8 rounds로 반복한다.
- pending flight 중 `invalidate` 또는 `clear`를 번갈아 실행하고 늦게 도착한
  empty response가 cache에 기록되지 않는지 반복한다. 이후 조회가 새 AWS 호출로
  정상 ARN을 저장하는지 확인해 transient flight와 persistent cache의 수명을
  분리한다.
- 기존 테스트의 TTL, negative entry, LRU eviction, noop cache, 실패 후 재시도,
  pending flight overlap 검증을 유지한다. 이번 범위에서 production code, public
  API, 설정, 전역 동시성 제한은 변경하지 않았다.

## 7-Tier self-review

| Tier | 결과 | 근거 |
|---|---|---|
| 1. 구조/API | PASS | 테스트만 추가했고 `SnsTopicArnResolver` 공개 계약과 production source는 변경하지 않았다. |
| 2. 정확성 | PASS | 동일 key single-flight, owner/waiter 취소, `invalidate`/`clear` stale late-write 차단을 반복 검증했다. |
| 3. 보안/경계 | PASS | ARN·credential·scope 검증 기존 테스트를 유지했고 새 경로에 권한 우회나 민감한 로그를 추가하지 않았다. |
| 4. 성능/동시성 | PASS | 서로 다른 4개 key의 호출이 병렬로 진행되고 key당 lookup이 1회임을 `AtomicInteger`로 확인했다. 전역 semaphore를 도입할 근거가 없다. |
| 5. 안정성 | PASS | `SuspendedJobTester` 반복 실행과 bounded `withTimeout`으로 hang·orphan flight를 검출하고 eviction 뒤 overlapping flight를 확인한다. |
| 6. 운영성 | PASS | production logging/config/배포 동작은 변경하지 않았고 모듈 Detekt가 통과했다. |
| 7. 유지보수성 | PASS | stress suite를 별도 test class로 분리하고 bluetape assertion·coroutine test helper를 사용해 기존 테스트의 LargeClass 경고를 피했다. |

## 예상 밖의 문제와 수정

1. 첫 cache assertion은 `key("orders")`가 매번 새 `cacheNamespace`를 생성한다는
   사실을 놓쳐 false negative를 만들었다. 실제 resolver의 `scope`로
   `SnsTopicArnCacheKey`를 구성하도록 고쳐 cache entry의 수명을 정확히 검증했다.
2. stress test를 기존 class에 붙였을 때 Detekt `LargeClass`가 발생했다. 동작을
   바꾸지 않고 같은 파일의 `SnsTopicArnResolverStressTest`로 분리해 책임과
   정적 분석 경계를 함께 정리했다.

## 검증 증거

- targeted resolver suite: `31 passing`, `skipped=0`, `failures=0`, `errors=0`,
  `BUILD SUCCESSFUL`.
- affected `:bluetape4k-aws-spring-boot:test`: `720 passing`, `skipped=0`,
  `failures=0`, `errors=0`, `BUILD SUCCESSFUL`.
- `:bluetape4k-aws-spring-boot:detekt`: `BUILD SUCCESSFUL`.
- `git diff --check`: 통과.
- 변경 파일에서 `assertThrows` 사용은 0건이며 모든 예외 assertion은
  `io.bluetape4k.assertions.assertFailsWith`를 사용한다.

## 다음 작업을 위한 방어선

- 전역 동시성 제한은 cross-key 병렬성을 먼저 측정하고 실제 throughput/latency
  병목이 재현될 때만 별도 설계·이슈·PR로 제안한다.
- cache를 직접 확인하는 테스트는 무작위 `cacheNamespace`를 다시 만들지 말고
  실제 resolver scope를 사용한다.
- 취소 테스트는 JUnit `assertThrows`로 회귀하지 말고
  `bluetape4k-assertions.assertFailsWith`와 owner 결과 보존을 함께 검증한다.
- pending 결과, eviction, TTL, negative entry의 수명을 변경할 때 이 stress
  matrix와 기존 fake/Floci 검증을 같은 모듈의 순차 테스트로 재실행한다.

## Writer DoD

- [x] SPW-01: Issue #564, 대상 독자, 변경 파일, 테스트·source 근거와 미변경 경계를 고정했다.
- [x] SPW-02: lesson의 맥락·결정·7-Tier 결과·검증·예상 밖의 문제·방어선을 작성했다.
- [x] SPW-03: 한국어 기술 문체와 API/명령/수치 보존을 검토했다.
- [x] SPW-04: 테스트 파일, resolver 구현의 cache/flight 경계, 실제 Gradle 결과를 대조했다.
- [x] SPW-05: 최종 Markdown을 재검토했고 `git diff --check`를 통과했다.

## DoD Status

- [x] Issue #564의 동시성·취소·eviction stress matrix 추가
- [x] bluetape4k assertions 및 `SuspendedJobTester` 사용
- [x] affected module test·Detekt·diff 검증
- [ ] PR 생성·CI·merge·canonical `develop` sync — 현재 요청은 구현·검증 지속이며 새 PR 생성 권한/대상은 지정하지 않음

**상태: DONE (구현·검증 단계) — PR delivery와 merge는 별도 승인 게이트로 남긴다.**
