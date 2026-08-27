# 이슈 #572 SNS WebFlux bounded concurrency smoke review

## 검토 범위와 근거

이번 review는 PR #568에서 추가한 SNS WebFlux adapter의 동시 요청 경계를
생산 코드 변경 없이 테스트로 보강하는 이슈 #572를 대상으로 한다.

- 대상 테스트: `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsWebFluxHttpEndpointTest.kt`
- 대상 구현: `SnsHttpMessageWebFilter`, `SnsWebFluxHttpMessageArgumentResolver`,
  기존 controller fixture
- 기준 issue: [#572](https://github.com/bluetape4k/bluetape4k-aws/issues/572)
- 선행 구현: [PR #568](https://github.com/bluetape4k/bluetape4k-aws/pull/568)
- 동작 기준: 허용 topic의 bounded notification과 차단 topic의 failure boundary를
  같은 고정 동시성 행렬에서 실행한다.

## 검토 결과

판정은 **PASS**다. `MultithreadingTester`의 4 workers × 2 rounds, 총 8개
요청에서 각 요청이 고유한 `orderId`와 `messageId`를 유지했고, verifier 진입부의
2-way bounded barrier가 실제 overlap을 만들었다. 허용 topic 5건만 handler와
verifier에 도달했고, 차단 topic 3건은 403으로 끝났으며 handler와 confirmation
호출을 만들지 않았다.

## 계약별 근거

| 계약 | 근거 |
| --- | --- |
| 고정 bounded concurrency | `MultithreadingTester().workers(4).rounds(2)` |
| 실제 overlap 관찰 | verifier 진입부 `CyclicBarrier(2)`와 `maxVerifierInFlight > 1` |
| body/envelope cross-talk 방지 | handler가 수집한 `(orderId, messageId)` pair가 허용 요청 집합과 정확히 일치 |
| handler 호출 수 | 허용 요청 5건, `controller.invocations == 5` |
| verifier 호출 수 | 허용 요청 5건, `verify(exactly = 5)` |
| resolver 호출 수 | 허용 요청 5건 × notification parameter 4개 = `resolver.invocations == 20` |
| failure boundary | 차단 topic 요청 3건은 `HttpStatus.FORBIDDEN`, handler 미호출 |
| confirmation 경계 | `operations.confirmSubscription` 0회 |
| pooled buffer cleanup | WebTestClient controller binding은 pooled transport buffer를 노출하지 않으므로 N/A. 기존 `SnsHttpMessageWebFilterTest`의 joined/in-flight/chunked release 테스트가 해당 경계를 담당한다. |

## Findings와 범위 경계

- P0/P1: 없음.
- P2/P3: 없음. 생산 동시성 제한, latency/throughput/heap benchmark,
  무제한 부하, 실제 AWS signing/network는 이 테스트의 범위가 아니다.
- Floci는 signed SNS HTTP delivery를 생성하지 않으므로 이 adapter 경계의
  in-process Reactor 테스트를 대체하지 않는다. 실제 AWS credential smoke도
  실행하지 않았다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsWebFluxHttpEndpointTest' --rerun-tasks --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS (8 tests)
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.sns.SnsWebFluxHttpEndpointTest' --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageWebFilterTest' --rerun-tasks --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS (11 tests)
- `./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS (1,398 tests, 2 skipped)
- `./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon --console=plain --max-workers=1 -Dkotlin.compiler.execution.strategy=in-process`: PASS
- `git diff --check`: PASS

## 최종 verdict

이슈 #572의 테스트 보강은 승인된 범위에 맞고, 현재 WebFlux replay/security
경계를 고정된 bounded concurrency에서 재현한다. 추가 생산 코드 수정이나
Floci/AWS 네트워크 검증은 필요하지 않다.
