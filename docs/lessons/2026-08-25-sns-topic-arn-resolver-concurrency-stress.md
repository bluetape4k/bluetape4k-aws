# SNS topic ARN resolver 동시성·취소 stress hardening

- 관련 이슈: [#564](https://github.com/bluetape4k/bluetape4k-aws/issues/564)
- 대상 코드: `aws-spring-boot`의 `SnsTopicArnResolver`
- 대상 테스트: `SnsTopicArnResolverConcurrencyStressTest`

## 배경

Issue #474에서 resolver의 key별 single-flight, caller cancellation cleanup,
`invalidate`·`clear`의 late-write 차단을 구현했다. 기존 테스트는 각 경계를
대표 사례로 확인했지만, 반복 실행 중 서로 다른 key의 병렬성이나 owner/waiter
취소 조합을 충분히 고정하지 못했다.

## 결정

이번 범위에서는 전역 semaphore나 동시성 상한을 추가하지 않는다. 12개의 서로
다른 topic key를 동시에 요청하는 반복 stress에서 각 key의 AWS lookup이
동시에 시작되고, 기존 key별 병렬성을 유지하는 현재 구조가 acceptance 기준을
충족했다. 전역 admission이 필요한지는 별도의 실제 throughput·latency 측정
근거가 생길 때 다시 결정한다.

테스트는 `runTest`와 제어 가능한 `CompletableFuture`를 사용해 실제 `delay`나
공유 emulator에 의존하지 않는다. 따라서 같은 key의 중복 억제와 취소 시점의
수명 경계를 반복해도 재현 가능한 결과를 얻는다.

## 결과

다음 여섯 경계를 20회 반복하는 stress 테스트를 추가했다.

- 서로 다른 12개 key의 병렬 lookup과 key별 결과 일치
- waiter 일부 취소 후에도 owner flight 하나만 유지
- owner 취소 후 waiter가 새 lookup을 이어받고 orphan flight를 남기지 않음
- `TimeoutCancellationException` identity 보존과 다음 lookup 재시도
- `invalidate`·`clear` 중 완료된 stale 결과의 cache write 차단
- `maxSize=1` LRU eviction 뒤 겹친 동일 key flight의 중복 억제

생산 코드는 변경하지 않았다. 테스트 클래스가 커져 Detekt `LargeClass`가
발생했기 때문에 stress 테스트를 같은 소스 파일의 별도 클래스로 분리했다.

## 검증

- `:bluetape4k-aws-spring-boot:test`에서 기존 resolver 테스트 27개와 stress
  테스트 6개가 모두 통과했다(`skipped=0`, `failures=0`, `errors=0`).
- `:bluetape4k-aws-spring-boot:detekt`가 통과했다.
- `git diff --check`가 통과했다.

## 다음 guard

resolver 동시성 변경 시에는 cross-key 병렬성, owner/waiter cancellation,
late-write, eviction을 함께 반복 검증한다. 전역 동시성 상한을 도입할 때는
먼저 재현 가능한 throughput·latency 측정과 설정 경계를 추가하고, 정상적인
cross-key 병렬성을 직렬화하지 않는지 별도 검증한다.
