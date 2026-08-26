# Issue #459 SNS HTTP Annotation Adapter Implementation Plan

> **For agentic workers:** 이 계획은 `bluetape-workflow`, `bluetape-kotlin-patterns`, `writing-plans`, `test-driven-development` 계약을 함께 적용한다. 각 단계는 체크박스로 추적하고, 생산 코드는 해당 단계의 RED 테스트가 실패한 뒤에만 작성한다.

**Goal:** 기존 SNS HTTP parser/verifier를 Spring MVC와 WebFlux의 annotation-driven endpoint, typed argument resolver, explicit confirmation status로 연결한다.

**Architecture:** 세 composed mapping annotation은 Spring `@RequestMapping`의 POST/header 조건과 `204 No Content`를 선언한다. Servlet `OncePerRequestFilter`와 WebFlux `WebFilter`가 SNS body를 bounded replayable 형태로 캐시하고 parser, expected-topic allowlist, required verifier를 handler 전에 실행한다. 플랫폼별 argument resolver는 같은 cache를 사용해 typed payload, subject, message attributes, raw envelope, confirmation status를 제공하며 Kotlin `suspend` 호출은 Spring Framework에 위임한다.

**Tech Stack:** Kotlin, Spring Boot 4, Spring Framework 7 `spring-web`/`spring-webmvc`/`spring-webflux`, `tools.jackson.databind.ObjectMapper`, Kotlin coroutines, JUnit 5, MockMvc, WebTestClient, MockK, Kluent.

**Fail-closed configuration:** `bluetape4k.aws.sns.http-endpoints.enabled=true` enables the adapter, but request processing requires a verifier bean and a non-empty `bluetape4k.aws.sns.http-endpoints.expected-topic-arns` allowlist by default. Missing verifier is a 503 and an unlisted topic is a 403 before handler invocation. Parser-only processing is an explicit unsafe mode requiring both `verification-required=false` and `allow-structural-only=true`; this mode is documented and tested as a rollback-only diagnostic choice.

`SnsHttpEndpointProperties` binds `enabled`, `verificationRequired`, `allowStructuralOnly`, and `expectedTopicArns` exactly to those kebab-case keys. It rejects the contradictory combination `verificationRequired=true` and `allowStructuralOnly=true`; an empty allowlist remains a request-time fail-closed rejection so context creation and optional-stack loading stay deterministic.

---

## 파일 경계

### 새 production 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/endpoint/NotificationMessageMapping.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/endpoint/NotificationSubscriptionMapping.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/endpoint/NotificationUnsubscribeConfirmationMapping.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/handlers/NotificationMessage.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/handlers/NotificationSubject.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/handlers/NotificationMessageAttributes.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/annotation/handlers/NotificationRawMessage.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/handlers/NotificationStatus.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessagePayloadConverter.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsNotificationStatus.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageResolverSupport.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsMvcHttpMessageArgumentResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsWebFluxHttpMessageArgumentResolver.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageServletFilter.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageWebFilter.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageLimits.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpEndpointProperties.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpEndpointRuntimeHints.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpEndpointWebMvcAutoConfiguration.kt`
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpEndpointWebFluxAutoConfiguration.kt`

### 수정 production/config 파일

- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessage.kt`: primary constructor는 유지하고 `messageAttributes` computed snapshot을 추가한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageParser.kt`: public semantics는 유지하고 shared `SnsHttpMessageLimits.MAX_BYTES`를 사용하도록 private size literal만 이동한다.
- `aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: MVC/WebFlux auto-configuration 두 줄을 추가한다.
- `aws-spring-boot/build.gradle.kts`: `spring-web`, `spring-webmvc`, `spring-webflux`, Servlet API를 `compileOnly`로 추가하고 테스트 stack을 `testImplementation`으로 추가한다.
- `README.md`, `README.ko.md`: 세 mapping과 resolver, explicit confirmation, MVC/WebFlux controller 예제를 추가한다.

### 새 테스트 파일

- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpEndpointAnnotationTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsMvcHttpEndpointTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsWebFluxHttpEndpointTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpEndpointAutoConfigurationTest.kt`
- `aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessagePayloadConverterTest.kt`

### 계획상 변경하지 않는 파일

- `SnsHttpMessageVerifier.kt`, `SnsOperations.kt`의 기존 public method semantics
- `aws-spring-boot` 외 모듈 및 examples module registration
- GitHub issue/PR metadata, workflows, release files

---

## Task 1: RED — public annotation/model 계약

**Files:** 위 새 annotation 7개, `handlers/NotificationStatus.kt`, `SnsNotificationStatus.kt`, `SnsHttpMessage.kt`, `SnsHttpEndpointAnnotationTest.kt`.

- [ ] **Step 1: mapping annotation 실패 테스트 작성**

`SnsHttpEndpointAnnotationTest`에 다음을 검증하는 테스트를 먼저 추가한다.

```kotlin
@Test
fun `mapping annotations expose post header path alias and no content`() {
    val message = NotificationMessageMapping::class.java.getAnnotation(RequestMapping::class.java)
    message.method shouldContain RequestMethod.POST
    message.headers shouldContain "x-amz-sns-message-type=Notification"
    NotificationMessageMapping::class.java.getAnnotation(ResponseStatus::class.java).value shouldBe HttpStatus.NO_CONTENT
}
```

같은 검증을 subscription/unsubscribe annotation에 적용하고, parameter annotation의 `@Target(VALUE_PARAMETER)`도 확인한다.

- [ ] **Step 2: mapping RED 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SnsHttpEndpointAnnotationTest' --no-daemon --console=plain
```

Expected: test compilation fails because the new annotation classes do not exist.

- [ ] **Step 3: annotation과 status 계약의 최소 구현**

각 mapping annotation은 `@Retention(RUNTIME)`, `@RequestMapping(method=[POST], headers=[...])`, `@ResponseStatus(NO_CONTENT)`, `@AliasFor(annotation=RequestMapping::class, attribute="path")`를 선언한다. `NotificationStatus`는 `io.bluetape4k.aws.spring.sns.handlers`에 두고 `topicArn`, `token`, `suspend fun confirmSubscription(authenticateOnUnsubscribe: Boolean = true): ConfirmSubscriptionResponse`를 선언한다. `SnsNotificationStatus`는 기존 `SnsOperations.confirmSubscription(message, flag)`에 위임한다. 모든 public annotation/interface에는 허용 parameter type을 KDoc으로 기록하고, 정적 parameter 오용은 handler 등록 시 fail-fast하며 client 400으로 분류하지 않는다.

- [ ] **Step 4: annotation GREEN 실행**

같은 명령을 실행해 annotation reflection과 status source compilation이 통과하는지 확인한다.

---

## Task 2: RED — envelope attributes와 payload 변환

**Files:** `SnsHttpMessage.kt`, `SnsHttpMessagePayloadConverter.kt`, `SnsHttpMessagePayloadConverterTest.kt`.

- [ ] **Step 1: payload/attributes 실패 테스트 작성**

테스트 fixture는 기존 `SnsHttpMessageParserTest`의 notification JSON을 재사용한다. 다음을 추가한다.

```kotlin
@Test
fun `message attributes expose typed snapshots`() {
    val message = SnsHttpMessageParser.parse(notificationJsonWithContentType)
    message.messageAttributes["contentType"] shouldBe SnsMessageAttribute("String", "application/json")
}

@Test
fun `json payload converts with configured object mapper`() {
    val converter = SnsHttpMessagePayloadConverter(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
    converter.convert("{\"id\":7}", OrderPayload::class.java, "application/json") shouldBe OrderPayload(7)
}

@Test
fun `typed payload rejects non json media type`() {
    assertFailsWith<ResponseStatusException> {
        converter.convert("not-json", OrderPayload::class.java, "text/plain")
    }.statusCode shouldBe HttpStatus.BAD_REQUEST
}
```

- [ ] **Step 2: Task 2 RED 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SnsHttpMessagePayloadConverterTest' --no-daemon --console=plain
```

Expected: compilation fails because `messageAttributes` and converter do not exist.

- [ ] **Step 3: 최소 구현**

`SnsHttpMessage.messageAttributes`는 `raw["MessageAttributes"]`를 `Map<*, *>`로 검사하고 각 entry의 `Type`/`Value`를 기존 `io.bluetape4k.aws.spring.sqs.SnsMessageAttribute`로 변환한다. `SnsHttpMessagePayloadConverter`는 `String` target이면 원문을 반환하고, `nestedContentType`이 JSON인지와 non-null `ObjectMapper`를 확인한 뒤 `readValue(message, targetType)`를 호출한다. outer HTTP `Content-Type`은 전달하지 않는다. 모든 parser/media/conversion 실패는 `ResponseStatusException(BAD_REQUEST, ...)`으로 감싼다. malformed attributes의 object/key/Type/Value 음성 테스트를 추가한다.

- [ ] **Step 4: Task 2 GREEN 실행**

동일한 targeted test를 실행하고 `git diff --check`를 확인한다.

---

## Task 3: RED — 공통 resolver와 Servlet MVC adapter

**Files:** `SnsHttpMessageResolverSupport.kt`, `SnsMvcHttpMessageArgumentResolver.kt`, `SnsHttpMessageServletFilter.kt`, `SnsMvcHttpEndpointTest.kt`.

- [ ] **Step 1: MVC routing/resolver 실패 테스트 작성**

`MockMvc` standalone controller를 사용해 다음 handler를 선언한다.

```kotlin
@RestController
private class MvcController(private val operations: SnsOperations) {
    @NotificationMessageMapping("/notifications")
    fun notification(
        @NotificationMessage payload: OrderPayload,
        @NotificationSubject subject: String?,
        @NotificationMessageAttributes attributes: Map<String, SnsMessageAttribute>,
        @NotificationRawMessage raw: SnsHttpMessage,
    ) { /* capture arguments */ }

    @NotificationSubscriptionMapping("/notifications")
    fun subscription(status: NotificationStatus) { /* do not auto-confirm */ }
}
```

테스트는 outer `Content-Type: text/plain`, matching SNS header, typed payload, subject, attributes, raw type, 204 response를 검증한다. MVC regular와 `suspend fun` handler를 모두 포함한다. `SnsHttpMessageLimits.MAX_BYTES + 1` body를 MockMvc에서 전송해 400, handler 0회, operation 0회를 검증한다. 별도 테스트는 malformed JSON, header/body mismatch, invalid typed payload, malformed MessageAttributes, missing verifier, empty allowlist, valid signature + wrong TopicArn에서 4xx/503과 zero handler invocation을 검증한다. MockK `coVerify(exactly = 0)`로 invalid/암묵적 confirmation의 `SnsOperations` 호출을 확인하고, 명시적 `status.confirmSubscription()`만 `coVerify(exactly = 1)`로 확인한다.

- [ ] **Step 2: MVC RED 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SnsMvcHttpEndpointTest' --no-daemon --console=plain
```

Expected: test compilation fails because resolver/filter/configuration classes do not exist.

- [ ] **Step 3: common resolver support 구현**

request attribute key와 WebFlux exchange attribute key를 `SnsHttpMessageResolverSupport.REQUEST_ATTRIBUTE`/`WEBFLUX_MESSAGE_ATTRIBUTE`로 고정한다. Servlet support는 parsed message를 저장하고, WebFlux filter가 cached `Mono.just(parsed).cache()`의 유일한 생성·구독 소유자가 되어 chain 이전에 parse → expected-topic allowlist → verifier(`expectedTopicArn` 전달)를 완료한다. resolver는 완료된 attribute를 읽기만 하며 filter 없는 standalone fallback만 별도 bounded read를 수행한다. 기본 verifier 부재/빈 allowlist는 각각 503/403으로 차단한다. `NotificationMessage`, subject, attributes, raw, `io.bluetape4k.aws.spring.sns.handlers.NotificationStatus`, direct `SnsHttpMessage`만 지원하고 다른 parameter 또는 generic type은 handler 등록/해석 오류로 fail-fast한다. `SnsHttpMessage.messageAttributes`를 준비 단계에서 평가해 malformed attributes도 handler 전에 차단한다.

- [ ] **Step 4: bounded replayable Servlet filter 구현**

`SnsHttpMessageServletFilter`는 SNS header가 없으면 그대로 통과한다. header가 있으면 `SnsHttpMessageLimits.MAX_BYTES + 1` 경계를 사용해 최대 256 KiB를 허용하고, parser → allowlist → verifier를 먼저 수행한 뒤 replayable `HttpServletRequestWrapper`와 parsed request attribute를 downstream에 전달한다. standalone resolver reader도 같은 경계를 적용한다. verifier 부재는 503, allowlist 불일치는 403, parse/verify/attribute 예외는 `sendError(400, ...)`로 종료해 handler를 호출하지 않는다. 로그에는 parser가 승인한 enum type, bounded size, normalized rejection category만 남기고 raw header text, raw JSON, Signature, Token, URL query를 남기지 않는다.

- [ ] **Step 5: MVC resolver와 configurer 구현**

`SnsMvcHttpMessageArgumentResolver`는 filter가 넣은 parsed message를 우선 사용하고, standalone path에서는 request input stream을 한 번만 읽어 attribute를 저장한다. typed payload는 `nestedContentType`만 사용한다. `WebMvcConfigurer.addArgumentResolvers`로 resolver를 등록하고 filter bean은 MVC auto-configuration에서 등록한다. regular와 `suspend fun` handler의 204와 호출 횟수를 모두 검증한다.

- [ ] **Step 6: MVC GREEN 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SnsMvcHttpEndpointTest' --no-daemon --console=plain
```

Expected: all MVC routing, resolver, malformed input, and 204 tests pass.

---

## Task 4: RED — WebFlux adapter와 suspend handler

**Files:** `SnsWebFluxHttpMessageArgumentResolver.kt`, `SnsHttpMessageWebFilter.kt`, `SnsHttpEndpointWebFluxAutoConfiguration.kt`, `SnsWebFluxHttpEndpointTest.kt`.

- [ ] **Step 1: WebFlux 실패 테스트 작성**

`WebTestClient`로 세 endpoint를 노출하고 `suspend fun` notification handler 및 일반 subscription handler를 함께 검증한다. resolver 없는 mapping을 포함해 filter가 chain 이전에 verifier를 완료하는지 확인한다. 같은 request body를 payload/subject/raw/attributes resolver가 소비하고, malformed/type mismatch/signature failure/allowlist failure에서는 handler counter가 0인지 확인한다. chunked oversized body, cancellation/disconnect, verifier 단일 실행을 검증하고, 두 confirmation type에서 `coVerify(exactly = 0/1)`로 자동/명시적 confirmation side effect를 구분한다. filter-level cancellation/release 테스트는 `TestPublisher<DataBuffer>` + `StepVerifier.thenCancel()` + pooled/ref-counted buffer로 재현한다.

- [ ] **Step 2: WebFlux RED 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SnsWebFluxHttpEndpointTest' --no-daemon --console=plain
```

Expected: test compilation fails because WebFlux adapter types do not exist.

- [ ] **Step 3: replayable WebFlux filter 구현**

`DataBufferUtils.join(body, SnsHttpMessageLimits.MAX_BYTES + 1)`으로 bounded read하고, verifier 호출은 `Schedulers.boundedElastic()`에서 실행한다. joined buffer를 byte array로 복사한 직후 `try/finally`로 release하며 overflow는 400, missing verifier는 503, unlisted topic은 403으로 종료한다. 검증 성공 시 exchange attribute에는 단일 실행 소유자인 cached `Mono<SnsHttpMessage>`만 저장하고 `ServerHttpRequestDecorator`가 매 subscription마다 새 buffer를 반환하도록 body를 replay한다. 실패 시 response status를 설정하고 `setComplete()`로 handler를 차단하며 cancellation/disconnect는 400으로 변환하지 않는다.

- [ ] **Step 4: WebFlux resolver 구현**

filter가 저장한 완료된 `Mono<SnsHttpMessage>`를 exchange attribute에서 읽고, resolver는 저장·`.cache()`·재구독하지 않는다. `resolveArgument`는 parameter annotation/type별로 common support를 호출하고 `Mono<Object>`를 반환한다. filter 없는 standalone fallback만 별도 bounded read/cache를 사용한다. coroutine handler invocation은 Spring Framework에 맡기며 resolver에서 `runBlocking`을 사용하지 않는다. cancellation/disconnect 시 verifier가 재구독되지 않고 handler/confirmation operation이 호출되지 않으며 buffer가 release되는 테스트를 추가한다.

- [ ] **Step 5: WebFlux auto-configuration 구현**

`WebFluxConfigurer.configureArgumentResolvers`에 resolver를 추가하고 `WebFilter` bean을 노출한다. `@ConditionalOnAwsEnabled`, `@ConditionalOnProperty(prefix="bluetape4k.aws.sns", name=["enabled"], matchIfMissing=true)`, `@ConditionalOnClass(name=["org.springframework.web.reactive.config.WebFluxConfigurer", "org.springframework.web.server.WebFilter"])`, `@ConditionalOnWebApplication(type=REACTIVE)`, `@ConditionalOnProperty(prefix="bluetape4k.aws.sns.http-endpoints", name=["enabled"], matchIfMissing=true)`를 적용한다. MVC auto-configuration에도 같은 AWS/SNS guard와 Servlet class guard를 적용한다.

- [ ] **Step 6: WebFlux GREEN 실행**

동일한 targeted test를 실행해 일반/suspend handler, body replay, error gate, 204를 검증한다.

---

## Task 5: RED — auto-configuration과 optional classpath

**Files:** `SnsHttpEndpointWebMvcAutoConfiguration.kt`, `SnsHttpEndpointWebFluxAutoConfiguration.kt`, `SnsHttpEndpointAutoConfigurationTest.kt`, `build.gradle.kts`, `AutoConfiguration.imports`.

- [ ] **Step 1: 조건부 context 실패 테스트 작성**

`WebApplicationContextRunner`와 `ReactiveWebApplicationContextRunner`를 분리해 MVC/Reactive web context를 만들고, `FilteredClassLoader`로 MVC/WebFlux/Servlet/양쪽 stack 부재를 각각 검증한다. `@ConditionalOnAwsEnabled`, `bluetape4k.aws.sns.enabled=false`, `bluetape4k.aws.sns.http-endpoints.enabled=false`가 configurer/filter/resolver 모든 phase를 비활성화하는지 확인한다. missing verifier, empty allowlist, four Boolean combinations of `verification-required`/`allow-structural-only`(오직 false+true만 structural-only 허용), valid signature + wrong TopicArn도 검증한다. Runtime hints registrar와 `RuntimeHintsPredicates` annotation reflection test를 추가하며 소비자 AOT에서 composed mapping이 보존되는 전략을 고정한다.

- [ ] **Step 2: auto-configuration RED 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --tests '*SnsHttpEndpointAutoConfigurationTest' --no-daemon --console=plain
```

Expected: missing auto-configuration imports/beans or missing compileOnly stack dependencies cause failure.

- [ ] **Step 3: dependency/import/configuration 구현**

`build.gradle.kts`에 `compileOnly("org.springframework:spring-web")`, `compileOnly("org.springframework:spring-webmvc")`, `compileOnly("org.springframework:spring-webflux")`, `compileOnly("jakarta.servlet:jakarta.servlet-api")`를 추가하고 테스트에는 `libs.spring.boot.starter.web`, `libs.spring.boot.starter.webflux`를 추가한다. `AutoConfiguration.imports`에는 두 auto-configuration class를 추가한다. class-level string conditions로 optional stack class loading을 차단한다. `SnsHttpEndpointProperties`를 `@EnableConfigurationProperties`로 등록하고 두 auto-configuration에 `@ImportRuntimeHints(SnsHttpEndpointRuntimeHints::class)`를 연결한다.

- [ ] **Step 4: auto-configuration GREEN 실행**

같은 targeted test와 compile task를 실행한다.

---

## Task 6: 문서 예제와 회귀 테스트

**Files:** `README.md`, `README.ko.md`, existing parser/verifier tests, all new SNS endpoint tests.

- [ ] **Step 1: 문서 예제 fixture 검증**

README의 English/Korean controller snippets가 동일한 mapping names, parameter annotations, explicit `status.confirmSubscription()` call, `text/plain` note, `verification-required`, `expected-topic-arns`, rollback용 `allow-structural-only` property를 사용하도록 작성한다. `enabled=false` rollback, raw JSON/Signature/Token/URL query 비기록, verifier가 소유하지 않는 SDK timeout/retry 정책도 기록한다.

- [ ] **Step 2: 회귀 검증 실행**

Run:

```bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageParserTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifierTest' \
  --tests 'io.bluetape4k.aws.spring.sns.*SnsHttp*' \
  --no-daemon --console=plain
```

Expected: baseline parser/verifier tests and all new adapter tests pass with no skips.

- [ ] **Step 3: documentation and terminology checks**

Run `git diff --check` and `node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs README.ko.md`. Repair every finding contextually; do not apply global replacements.

---

## Task 7: validation, review, lesson, and local delivery

**Files:** `docs/review/2026-08-25-issue-459-sns-http-annotation-adapter-implementation-review.md`, `docs/lessons/2026-08-25-issue-459-sns-http-annotation-adapter.md`, `.bluetape` evidence files.

- [ ] **Step 1: targeted and module validation**

Run sequentially:

```bash
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --console=plain
./gradlew :bluetape4k-aws-spring-boot:detekt --no-daemon --console=plain
./gradlew :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --console=plain
git diff --check
```

Record unrelated baseline findings separately; a new adapter finding is blocking until fixed. AOT task absence and real AWS/Testcontainers delivery smoke are explicit N/A evidence; no benchmark is claimed because this adapter has no latency SLA, while bounded oversize/replay/concurrent smoke remains required.

- [ ] **Step 2: independent Kotlin review artifact**

Review public annotations, `NotificationStatus` package, nullable subject semantics, concrete/generic payload type policy, converter nested media type, request body lifecycle, max-byte/release behavior, `Schedulers.boundedElastic` boundary, cancellation behavior, expected-topic fail-closed guard, optional classpath guards, AOT N/A evidence, and absence of `runBlocking`/`GlobalScope`. Record P0/P1/P2/P3 findings with path and evidence; human review remains `N/A`.

- [ ] **Step 3: Korean lesson artifact**

Record the decision to reuse parser/verifier, the replayable body cache boundary, optional web stack dependency policy, confirmation opt-in behavior, tests that proved the contract, and the known absence of real AWS delivery smoke.

- [ ] **Step 4: final read-back and Lore commit**

Read all changed files and evidence, verify branch/diff scope, then create one Korean Lore commit whose intent is the handler safety boundary. The commit message must include `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, and `Not-tested` trailers.

- [ ] **Step 5: no-PR closeout**

Do not create PR, push, merge, close issue, or release. Complete the flow receipt with implementation, verification, main verification, review, lesson, and no-PR N/A evidence. Final state is `DONE` only if all local checks pass and the worktree is clean after commit; otherwise report `PENDING` with exact failing evidence.

---

## Plan self-review

- Spec coverage: mapping, all three SNS types, typed payload, subject, attributes, raw envelope, status, MVC, WebFlux, suspend, parser/verifier reuse, expected-topic fail-closed policy, bounded body/release/cancellation, 4xx failures, examples, regression, and no-PR boundary each map to Tasks 1–7.
- Placeholder scan: no `TBD`, `TODO`, or unspecified “handle edge cases” step is used; every task names files, tests, commands, and expected result.
- Type consistency: `NotificationStatus`, `SnsNotificationStatus`, `SnsHttpMessagePayloadConverter`, resolver names, request attribute cache, and auto-configuration names are used consistently across the plan.

## Writer DoD

- [x] SPW-01 audience, purpose, source paths/URLs, identifiers, unknowns
- [x] SPW-02 plan structure, file paths, dependency order, tests, rollback/rerun points, approval gates
- [x] SPW-03 Korean technical register and terminology pass
- [x] SPW-04 spec-to-plan traceability and exact validation commands
- [x] SPW-05 plan read-back and self-review recorded
