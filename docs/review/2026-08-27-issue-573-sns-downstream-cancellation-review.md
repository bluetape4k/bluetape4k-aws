# 이슈 #573 SNS WebFlux downstream cancellation 회귀 review

## 검토 범위와 근거

이번 review는 prepared message와 replayable request가 downstream에 전달된 뒤
구독이 취소되는 경계를 생산 코드 재설계 없이 회귀 테스트로 고정하는 이슈
#573을 대상으로 한다.

- 대상 테스트: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageWebFilterTest.kt`
- 대상 구현: `SnsHttpMessageWebFilter`
- 기준 issue: [#573](https://github.com/bluetape4k/bluetape4k-aws/issues/573)
- 선행 구현: [PR #568](https://github.com/bluetape4k/bluetape4k-aws/pull/568)
- 동작 기준: prepare와 replay body read를 완료한 뒤 handler gate 앞에서 실제
  Reactor subscription을 취소하고, 입력·replay buffer와 downstream 경계를
  함께 검증한다.

## 검토 결과

판정은 **PASS**다. 새 테스트는 별도 Netty response buffer factory를 사용해
입력과 replay 모두 `PooledDataBuffer`로 만들고, replay body read 완료를
동기화한 뒤 `StepVerifier.thenCancel()`을 실행한다. 취소 시 downstream chain과
body는 각각 한 번만 구독되고 활성 body 구독은 0으로 돌아가며, handler와
`confirmSubscription`은 호출되지 않는다. response status도 설정되지 않아
취소가 400 입력 오류로 정규화되지 않음을 확인한다.

## 계약별 근거

| 계약 | 근거 |
| --- | --- |
| prepared/replay 이후 취소 | `SnsHttpMessageWebFilter.filter`가 prepare를 완료하고 decorated request를 chain에 전달한 뒤 replay body read latch를 통과한다. |
| handler 미호출 | `Sinks.one` handler gate를 방출하지 않고 취소하며 `handlerInvocations == 0`을 확인한다. |
| confirmation 미호출 | relaxed `SnsOperations`에 대해 `confirmSubscription` 호출 수를 0으로 검증한다. |
| 입력 buffer cleanup | Netty 입력 `source`의 `PooledDataBuffer.isAllocated == false`를 확인한다. |
| replay buffer cleanup | Netty response factory에서 생성된 replay buffer 전부의 `isAllocated == false`를 확인한다. |
| 추가 구독 없음 | downstream chain/body 구독 수가 각각 1이고 replay body의 활성 구독 수가 0이다. |
| cancellation 의미 보존 | `StepVerifier.thenCancel().verify()`가 오류 응답 없이 종료되고 response status가 `null`이다. |

## Findings와 범위 경계

- P0/P1: 없음.
- P2/P3: 없음. 새로운 cancellation API, production semaphore, 실제 AWS
  서명·네트워크, bounded concurrency/latency benchmark는 범위에서 제외했다.
- 기존 filter의 cancellation 전파와 `doOnDiscard` 동작은 유지되며, 이번 변경은
  직접적인 downstream 회귀 증거를 추가하는 테스트 전용 변경이다.
- Floci는 signed SNS HTTP delivery를 생성하지 않으므로 이 in-process Reactor
  lifecycle 테스트를 대체하지 않는다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageWebFilterTest' --rerun-tasks --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS (4 tests)
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsWebFluxHttpEndpointTest' --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageWebFilterTest' --rerun-tasks --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS (12 tests)
- `./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS (1,399 tests, 2 skipped)
- `./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS
- `git diff --cached --check`: PASS
- Korean 용어 감사(`audit-korean-terms.mjs`): PASS (findings 0)

## 최종 verdict

이슈 #573의 downstream cancellation 회귀 증거는 승인된 범위에 맞게 고정됐다.
입력과 replay pooled buffer의 해제, 추가 구독 부재, handler·confirmation 0회,
취소 의미 보존을 하나의 재현 가능한 fixture에서 확인하며 production 변경은
필요하지 않다.

## DoD Status

- [x] RED fixture로 prepared/replay 이후 cancellation 경계를 고정한다.
- [x] 최소 production 변경으로 회귀 테스트를 통과시킨다. (production 변경 없음)
- [x] buffer/resource cleanup과 오류 분류를 read-back한다.
- [ ] exact-head 검증과 별도 PR로 전달한다.

Final status: IN PROGRESS — 로컬 targeted/module/quality 검증은 통과했으며 PR
exact-head 게이트가 남아 있다.
