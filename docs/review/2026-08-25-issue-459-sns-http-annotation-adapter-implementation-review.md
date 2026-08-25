# 이슈 #459 SNS HTTP annotation adapter 구현 review

## 검토 범위와 기준

- 이슈: [#459](https://github.com/bluetape4k/bluetape4k-aws/issues/459)
- 저장소: `bluetape4k/bluetape4k-aws`
- 기준 base: `be5bb458`
- 설계/계획 기준선: `8051c837`
- 변경 모듈: `aws-spring-boot`의 SNS HTTP annotation, resolver, filter,
  auto-configuration, runtime hints, 테스트와 README EN/KO
- 검토 방식: Kotlin/Spring API, 보안·fail-closed, 성능·resource lifecycle,
  테스트·운영, AOT·optional classpath를 분리한 독립 관점 review
- human review: 1인 개발자 작업이므로 **N/A**. 대신 architecture/API,
  security/stability, performance/operations/tests 세 역할 review를 수행했다.

## 최종 판정

- P0: 0
- P1: 0
- P2: 1 (Spring reflection API deprecation 경고; 동작 blocker 아님)
- 결정: **PASS — PR/merge 단계로 진행하지 않고 local delivery에서 종료**

초기 독립 review에서 확인된 P1은 모두 수정했다. Servlet의
`ResponseStatusException`도 `classify → record → sendError`를 통과하며 로그는
허용된 message type과 `MAX_READ_BYTES`로 제한한 크기만 기록한다. MVC의 잘못된
signature는 `400`, handler 0회, confirmation operation 0회를 검증한다.

## 관점별 결과

| 관점 | 확인한 계약과 증거 | 판정 |
|---|---|---|
| 보안/fail-closed | parser와 MessageAttributes shape 검증이 verifier보다 먼저 실행된다. empty allowlist는 `403`, verifier 부재는 `503`, signature/topic/type/size 오류는 handler 전에 종료한다. `SnsHttpEndpointErrorPolicyTest`가 raw JSON, token, signature, ARN이 로그에 남지 않음을 확인한다. | PASS |
| 안정성/lifecycle | Servlet body는 256 KiB 경계로 한 번 읽고 replay wrapper로 제공한다. WebFlux는 `join(MAX_READ_BYTES + 1)`, `boundedElastic`, fresh replay buffer, `doOnDiscard`, cancellation release를 사용한다. pooled `DataBuffer` 정상·취소·chunk overflow 테스트가 통과했다. | PASS |
| 구조/API·ABI | 세 composed mapping과 세 SNS message type, typed payload/subject/attributes/raw/status, MVC/WebFlux regular·suspend handler를 검증한다. raw/status는 exact type, attributes map은 `Map<String, SnsMessageAttribute>`로 제한하고 handler 등록 시 validator가 fail-fast한다. | PASS |
| Kotlin/Spring/AOT | resolver는 `runBlocking`/`GlobalScope` 없이 Spring coroutine invocation에 위임한다. optional Jackson은 reflection bridge로 분리하고, `@Reflective` processor가 실제 controller method/payload hints를 등록한다. runtime hints 8개 타입과 실제 fixture를 확인했다. | PASS |
| 성능/운영 | body와 pooled buffer의 상한·release를 고정하고 verifier는 한 번만 실행한다. standalone WebFlux fallback도 exchange-level `Mono.cache()`로 재소비를 막는다. SDK timeout/retry는 `sns-message-manager`/애플리케이션 소유임을 EN/KO README에 기록했다. 별도 benchmark SLA는 없다. | PASS |
| 테스트/회귀 | 새 SNS endpoint 및 policy 테스트, 기존 parser/verifier/SNS suite, 전체 Spring module을 순차 실행했다. global AWS switch, service switch, optional web/Jackson classpath, runtime hints 조합을 확인했다. | PASS |
| 문서/유지보수 | README EN/KO의 `path = ["..."]`, fail-closed YAML, explicit confirmation, rollback, runtime dependency, timeout/retry ownership이 정렬됐다. | PASS |

## Fresh verification evidence

```text
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process
SUCCESS: Executed 760 tests; BUILD SUCCESSFUL

./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process
BUILD SUCCESSFUL

./gradlew detekt --no-daemon --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process
BUILD SUCCESSFUL

./gradlew build -x test --parallel --no-daemon --console=plain \
  -Dkotlin.compiler.execution.strategy=in-process
BUILD SUCCESSFUL

git diff --check
통과
```

추가 targeted evidence는 다음과 같다.

- `SnsHttpEndpointErrorPolicyTest`: 2/2
- `SnsMvcHttpEndpointTest`: 11/11 (invalid signature 포함)
- `SnsWebFluxHttpEndpointTest`: 7/7 (standalone cache 포함)
- `SnsHttpMessageWebFilterTest`: 3/3 (TestPublisher chunk overflow와 pooled release 포함)
- `SnsHttpEndpointAutoConfigurationTest`: 9/9 (global/service switch, optional classpath,
  runtime hints 포함)

초기 병렬/daemon 실행에서 기존 SQS cancellation/binary fixture 경계가 흔들린
시도가 있었으나, Kotlin compiler를 `in-process`로 고정해 모듈 전체 760/760을
재실행했고 최종 증거에는 성공 결과만 사용했다.

## 알려진 경계와 후속 조치

- Spring `MemberCategory`와 `ReflectionHintsPredicates.onMethod` 사용에서
  upstream deprecation warning이 남는다. 현재 Spring Boot 4 runtime hints 계약과
  실제 fixture 검증은 통과하므로 별도 유지보수 항목으로 둔다.
- 실제 AWS SNS signed delivery, certificate fetch timeout/retry telemetry,
  credential-gated live smoke는 실행하지 않았다. Floci는 signed SNS HTTP payload를
  생성하지 않으며, 이번 변경은 parser/verifier mock과 local Web stack 경계를
  보장한다.
- PR 생성, push, merge, issue close, release는 사용자 범위 밖이므로 수행하지
  않는다. human review도 사용자 지시에 따라 N/A다.

## DoD Status

- 상태: local implementation review 통과
- P0/P1: `0/0`
- 완료: design/plan traceability, TDD RED/GREEN, independent role review,
  Kotlin/Spring implementation, MVC/WebFlux lifecycle, optional classpath/AOT,
  EN/KO docs, full module/root validation, lesson artifact
- 미완료: hosted CI, PR review/merge, real AWS signed smoke (범위 밖/N/A)
