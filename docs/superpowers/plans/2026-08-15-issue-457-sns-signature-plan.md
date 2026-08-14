# SNS HTTP 메시지 서명 검증 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven-development and verification-before-completion. Steps use checkbox syntax for tracking.

**Goal:** aws-spring-boot가 기존 parser 뒤에서 AWS SDK v2 sns-message-manager로 SNS HTTP 메시지 서명을 검증하고, 안전한 Spring Boot bean과 runtime dependency 문서를 제공하게 한다.

**Architecture:** SnsHttpMessageVerifier는 parser → expected TopicArn 비교 → SDK manager 검증 순서를 고정하고 기존 SnsHttpMessage를 반환한다. 별도 auto-configuration은 SDK manager classpath, 전역 AWS 활성화, SNS 활성화, verification property를 모두 만족할 때만 region-pinned verifier bean을 만들며 manager lifecycle은 close로 위임한다.

**Tech Stack:** Kotlin, Spring Boot 4 auto-configuration, AWS SDK v2 sns-message-manager, JUnit 5, MockK, Gradle version catalog, Markdown README.

---

## 파일 구조와 책임

| 경로 | 변경 책임 |
| --- | --- |
| gradle/libs.versions.toml | versionless aws2-sns-message-manager alias를 추가하고 AWS SDK BOM 해석을 사용한다. |
| aws-spring-boot/build.gradle.kts | production compileOnly와 test dependency를 alias로 추가한다. |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifier.kt | parser 선행, topic 조기 거부, SDK manager 위임, idempotent close, region factory를 구현한다. |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsProperties.kt | verification.enabled 기본값 true를 configuration properties에 추가한다. |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfiguration.kt | 조건부 verifier bean과 region pinning을 별도 auto-configuration으로 제공한다. |
| aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports | 새 auto-configuration을 등록한다. |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifierTest.kt | 성공·실패·header/topic 경계·lifecycle·region factory를 고정한다. |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfigurationTest.kt | classpath/property/SNS·전역 활성화 조건과 custom bean backoff를 고정한다. |
| aws-spring-boot/README.md | 영어 runtime dependency, 기본 활성화, opt-out 위험, 호출 순서를 문서화한다. |
| aws-spring-boot/README.ko.md | 같은 구조의 한국어 설명을 작성한다. |
| docs/reviews/2026-08-15-issue-457-sns-signature-plan-review.md | Step 3-R 관점별 검토와 보류 범위를 기록한다. |

## Task 0: baseline과 외부 근거를 고정한다

**Files:** AGENTS hierarchy, approved spec, current parser/properties/auto-config/tests (read-only).

- [ ] **Step 1: GNO와 live issue를 재확인한다**

~~~bash
/Users/debop/.bun/bin/gno search 'bluetape4k-aws SNS signature verification issue 457' -c bluetape4k-github -n 10 --line-numbers
gh issue view 457 --repo bluetape4k/bluetape4k-aws --json number,title,state,assignees,milestone,labels,body,url
~~~

Expected: GNO는 #457 SNS signature 요구사항을 반환하고 live issue는 OPEN, milestone 0.6.0, assignee debop, enhancement/aws-spring-boot/sns metadata를 유지한다.

- [ ] **Step 2: 변경 전 targeted baseline을 실행한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageParserTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest'
~~~

Expected: BUILD SUCCESSFUL; 기존 parser와 SNS auto-configuration 테스트가 통과한다.

- [ ] **Step 3: resolved manager version을 확인한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight \
  --dependency sns-message-manager \
  --configuration testRuntimeClasspath
~~~

Expected: software.amazon.awssdk:sns-message-manager가 중앙 bluetape4k-dependencies AWS SDK v2 BOM의 resolved version으로 표시된다. build file이나 catalog에 2.51.1 같은 hard-coded version을 쓰지 않는다.

## Task 1: dependency surface와 RED 테스트를 먼저 추가한다

**Files:**
- Modify: gradle/libs.versions.toml
- Modify: aws-spring-boot/build.gradle.kts
- Create: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifierTest.kt
- Create: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfigurationTest.kt

- [ ] **Step 1: catalog와 Gradle alias를 추가한다**

~~~toml
aws2-sns-message-manager = { module = "software.amazon.awssdk:sns-message-manager" }
~~~

~~~kotlin
compileOnly(libs.aws2.sns.message.manager)
testImplementation(libs.aws2.sns.message.manager)
~~~

Expected: version은 alias에 적지 않고 root dependency management와 중앙 catalog가 제공한다.

- [ ] **Step 2: verifier RED 테스트를 작성한다**

~~~kotlin
class SnsHttpMessageVerifierTest {
    private val manager = mockk<SnsMessageManager>()
    private val verifier = SnsHttpMessageVerifier(manager)

    @Test
    fun verify_parses_supported_sns_types_before_sdk() {
        listOf(
            notificationJson to "Notification",
            subscriptionConfirmationJson to "SubscriptionConfirmation",
            unsubscribeConfirmationJson to "UnsubscribeConfirmation",
        ).forEach { (json, header) ->
            every { manager.parseMessage(json) } returns mockk(relaxed = true)
            verifier.verify(json, messageTypeHeader = header, expectedTopicArn = topicArn)
                .topicArn shouldBeEqualTo topicArn
        }
    }

    @Test
    fun manager_failure_is_the_same_fail_closed_cause() {
        val failure = IllegalArgumentException("invalid SNS signature")
        every { manager.parseMessage(notificationJson) } throws failure

        val actual = assertFailsWith<IllegalArgumentException> {
            verifier.verify(notificationJson, messageTypeHeader = "Notification")
        }

        actual shouldBeSameInstanceAs failure
    }

    @Test
    fun expected_topic_mismatch_rejects_before_manager() {
        every { manager.parseMessage(any<String>()) } returns mockk(relaxed = true)

        val actual = assertFailsWith<IllegalArgumentException> {
            verifier.verify(
                notificationJson,
                expectedTopicArn = "arn:aws:sns:us-west-2:123456789012:OtherTopic",
            )
        }

        actual.message.orEmpty() shouldBeEqualTo
            "SNS HTTP message TopicArn does not match expectedTopicArn."
        verify(exactly = 0) { manager.parseMessage(any<String>()) }
    }

    @Test
    fun parser_rejection_happens_before_manager() {
        val invalidJson = notificationJson.replace(
            "https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
            "http://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
        )

        assertFailsWith<IllegalArgumentException> {
            verifier.verify(invalidJson, messageTypeHeader = "Notification")
        }

        verify(exactly = 0) { manager.parseMessage(any<String>()) }
    }

    @Test
    fun close_delegates_exactly_once() {
        every { manager.close() } just runs

        verifier.close()
        verifier.close()

        verify(exactly = 1) { manager.close() }
    }

    @Test
    fun region_factory_rejects_blank_region() {
        SnsHttpMessageVerifier.forRegion("us-east-1").close()

        assertFailsWith<IllegalArgumentException> {
            SnsHttpMessageVerifier.forRegion(" ")
        }
    }

    private val topicArn = "arn:aws:sns:us-west-2:123456789012:MyTopic"
    private val notificationJson = """
        {
          "Type":"Notification","MessageId":"22b80b92-fdea-4c2c-8f9d-bdfb0c7bf324",
          "TopicArn":"arn:aws:sns:us-west-2:123456789012:MyTopic","Subject":"Order created",
          "Message":"{\"orderId\":\"order-1\"}","Timestamp":"2012-05-02T00:54:06.655Z",
          "SignatureVersion":"2","Signature":"signature-2",
          "SigningCertURL":"https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem",
          "UnsubscribeURL":"https://sns.us-west-2.amazonaws.com/?Action=Unsubscribe&SubscriptionArn=sub-1"
        }
    """.trimIndent()
    private val subscriptionConfirmationJson = """
        {
          "Type":"SubscriptionConfirmation","MessageId":"165545c9-2a5c-472c-8df2-7ff2be2b3b1b",
          "Token":"token-1","TopicArn":"arn:aws:sns:us-west-2:123456789012:MyTopic",
          "Message":"Confirm this subscription.","SubscribeURL":"https://sns.us-west-2.amazonaws.com/?Action=ConfirmSubscription&Token=token-1",
          "Timestamp":"2012-04-26T20:45:04.751Z","SignatureVersion":"2","Signature":"signature-1",
          "SigningCertURL":"https://sns.us-west-2.amazonaws.com/SimpleNotificationService.pem"
        }
    """.trimIndent()
    private val unsubscribeConfirmationJson = subscriptionConfirmationJson
        .replace("SubscriptionConfirmation", "UnsubscribeConfirmation")
        .replace("token-1", "token-2")
}
~~~

The test file must import the existing assertion helpers and MockK APIs. Fixtures are local; no Testcontainers or external network is used.

- [ ] **Step 3: auto-configuration RED 테스트를 작성한다**

~~~kotlin
class SnsHttpMessageVerificationAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                AwsAutoConfiguration::class.java,
                SnsAutoConfiguration::class.java,
                SnsHttpMessageVerificationAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "bluetape4k.aws.sns.region=us-east-1",
            "bluetape4k.aws.sns.verification.enabled=true",
        )

    @Test
    fun registers_verifier_when_conditions_are_enabled() {
        contextRunner.run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun backs_off_when_verification_is_disabled() {
        contextRunner.withPropertyValues(
            "bluetape4k.aws.sns.verification.enabled=false",
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java).size shouldBeEqualTo 0
        }
    }

    @Test
    fun backs_off_when_sns_is_disabled() {
        contextRunner.withPropertyValues(
            "bluetape4k.aws.sns.enabled=false",
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java).size shouldBeEqualTo 0
        }
    }

    @Test
    fun backs_off_when_manager_class_is_absent() {
        contextRunner.withClassLoader(
            FilteredClassLoader("software.amazon.awssdk.messagemanager.sns"),
        ).run { context ->
            context.getBeansOfType(SnsHttpMessageVerifier::class.java).size shouldBeEqualTo 0
        }
    }
}
~~~

- [ ] **Step 4: RED 증거를 기록한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifierTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfigurationTest'
~~~

Expected: dependency resolution succeeds but compilation/test discovery fails because production verifier and auto-configuration types do not exist. Preserve this output before implementation.

- [ ] **Step 5: dependency와 RED 테스트를 한 단위로 커밋한다**

~~~bash
git add gradle/libs.versions.toml aws-spring-boot/build.gradle.kts \
  aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifierTest.kt \
  aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfigurationTest.kt
git commit -m "SNS 서명 검증의 RED 계약을 먼저 고정한다"
~~~

Constraint: RED 테스트는 production 구현보다 먼저 존재해야 한다.

## Task 2: verifier production contract를 GREEN으로 만든다

**Files:**
- Create: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifier.kt
- Modify: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsProperties.kt
- Test: SnsHttpMessageVerifierTest.kt

- [ ] **Step 1: parser와 SDK boundary를 구현한다**

~~~kotlin
class SnsHttpMessageVerifier(
    private val messageManager: SnsMessageManager = SnsMessageManager.builder().build(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun verify(
        json: String,
        messageTypeHeader: String? = null,
        expectedTopicArn: String? = null,
    ): SnsHttpMessage {
        val parsed = SnsHttpMessageParser.parse(json, messageTypeHeader)
        expectedTopicArn?.let { expected ->
            require(expected.isNotBlank()) { "expectedTopicArn must not be blank." }
            require(parsed.topicArn == expected) {
                "SNS HTTP message TopicArn does not match expectedTopicArn."
            }
        }
        messageManager.parseMessage(json)
        return parsed
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            messageManager.close()
        }
    }

    companion object {
        fun forRegion(region: String?): SnsHttpMessageVerifier {
            val builder = SnsMessageManager.builder()
            region?.let {
                require(it.isNotBlank()) { "region must not be blank." }
                builder.region(Region.of(it))
            }
            return SnsHttpMessageVerifier(builder.build())
        }
    }
}
~~~

Expected: parser exceptions and manager exceptions are not wrapped; topic mismatch never invokes manager; close is idempotent and manager close occurs once.

- [ ] **Step 2: verification property를 추가한다**

~~~kotlin
data class SnsProperties(
    val enabled: Boolean = true,
    val region: String? = null,
    val endpointOverride: URI? = null,
    val topics: Map<String, Topic> = emptyMap(),
    val verification: Verification = Verification(),
) : Serializable {
    data class Verification(
        val enabled: Boolean = true,
    ) : Serializable
}
~~~

Keep endpointOverride validation, Topic, and existing serialVersionUID intact; add KDoc and configuration metadata coverage.

- [ ] **Step 3: verifier GREEN 테스트를 실행한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifierTest'
~~~

Expected: all verifier tests PASS without an emulator.

- [ ] **Step 4: verifier implementation을 커밋한다**

~~~bash
git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifier.kt \
  aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsProperties.kt \
  aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifierTest.kt
git commit -m "SNS HTTP 메시지 서명 검증 경계를 구현한다"
~~~

## Task 3: 조건부 Spring Boot auto-configuration을 연결한다

**Files:**
- Create: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfiguration.kt
- Modify: aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
- Test: SnsHttpMessageVerificationAutoConfigurationTest.kt

- [ ] **Step 1: auto-configuration을 구현한다**

~~~kotlin
@AutoConfiguration(after = [AwsAutoConfiguration::class, SnsAutoConfiguration::class])
@ConditionalOnAwsEnabled
@ConditionalOnClass(name = ["software.amazon.awssdk.messagemanager.sns.SnsMessageManager"])
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.sns",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.sns.verification",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(SnsProperties::class)
class SnsHttpMessageVerificationAutoConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(SnsHttpMessageVerifier::class)
    fun snsHttpMessageVerifier(properties: SnsProperties): SnsHttpMessageVerifier =
        SnsHttpMessageVerifier.forRegion(properties.region)
}
~~~

- [ ] **Step 2: imports와 metadata를 연결한다**

Add exactly one line to AutoConfiguration.imports:

~~~text
io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfiguration
~~~

Expected: generated configuration metadata exposes bluetape4k.aws.sns.verification.enabled with default true.

- [ ] **Step 3: auto-configuration GREEN 테스트를 실행한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfigurationTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest'
~~~

Expected: verifier bean exists only when manager classpath and all properties are enabled; existing SNS client/template tests remain PASS.

- [ ] **Step 4: auto-configuration을 커밋한다**

~~~bash
git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfiguration.kt \
  aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerificationAutoConfigurationTest.kt
git commit -m "SNS 서명 검증 verifier auto-configuration을 등록한다"
~~~

## Task 4: 영어·한국어 README와 KDoc을 정렬한다

**Files:** aws-spring-boot/README.md, aws-spring-boot/README.ko.md, verifier/properties KDoc.

- [ ] **Step 1: 영어 설명을 추가한다**

~~~markdown
### SNS HTTP message signature verification

Add software.amazon.awssdk:sns-message-manager to the application runtime because this module keeps it compileOnly. Verification is enabled by default.

bluetape4k.aws.sns.verification.enabled=true

An HTTP adapter must call SnsHttpMessageParser first, then SnsHttpMessageVerifier, and only pass a successful result to the handler. Setting verification.enabled=false removes the auto-configured verifier and is an explicit security opt-out; parser output alone is not authenticated. Floci does not create signed SNS HTTP payloads, so fixture or manager-mock tests cover this boundary.
~~~

- [ ] **Step 2: 한국어 설명을 같은 구조로 추가한다**

~~~markdown
### SNS HTTP 메시지 서명 검증

이 모듈은 software.amazon.awssdk:sns-message-manager를 compileOnly로 유지하므로 애플리케이션 runtime에 해당 의존성을 추가해야 합니다. 검증은 기본적으로 활성화됩니다.

bluetape4k.aws.sns.verification.enabled=true

HTTP adapter는 SnsHttpMessageParser를 먼저 호출한 뒤 SnsHttpMessageVerifier를 호출하고, 검증에 성공한 결과만 handler에 전달해야 합니다. verification.enabled=false는 자동 구성 verifier를 제거하는 명시적 보안 opt-out이며 parser 결과만으로는 인증되지 않습니다. Floci는 서명된 SNS HTTP payload를 생성하지 않으므로 fixture 또는 manager mock으로 이 경계를 검증합니다.
~~~

- [ ] **Step 3: locale 구조와 Markdown을 검증한다**

~~~bash
git diff --check
rg -n "sns-message-manager|verification.enabled|SnsHttpMessageParser|SnsHttpMessageVerifier|Floci" \
  aws-spring-boot/README.md aws-spring-boot/README.ko.md \
  aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns
~~~

Expected: 두 README가 같은 주제·예제·호출 순서를 가지며 property key와 API 이름은 동일하다.

- [ ] **Step 4: 문서를 커밋한다**

~~~bash
git add aws-spring-boot/README.md aws-spring-boot/README.ko.md \
  aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsProperties.kt \
  aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/SnsHttpMessageVerifier.kt
git commit -m "SNS 서명 검증 runtime 계약을 문서화한다"
~~~

## Task 5: module 검증과 dependency provenance를 수집한다

- [ ] **Step 1: targeted suite를 실행한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerifierTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageVerificationAutoConfigurationTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsHttpMessageParserTest' \
  --tests 'io.bluetape4k.aws.spring.sns.SnsAutoConfigurationTest'
~~~

Expected: BUILD SUCCESSFUL; all four suites pass without emulator.

- [ ] **Step 2: compile, detekt, full module test를 실행한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:compileKotlin
./gradlew :bluetape4k-aws-spring-boot:detekt
./gradlew :bluetape4k-aws-spring-boot:test
~~~

Expected: each returns BUILD SUCCESSFUL. A Colima/Testcontainers bind failure must be preserved separately; a retry with repository-documented TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE is only conditional evidence.

- [ ] **Step 3: resolved dependency와 metadata를 재검증한다**

~~~bash
./gradlew :bluetape4k-aws-spring-boot:dependencyInsight \
  --dependency sns-message-manager \
  --configuration testRuntimeClasspath
./gradlew :bluetape4k-aws-spring-boot:processResources
git diff --check
~~~

Expected: central AWS SDK v2 resolved version, verification property metadata, and clean diff check.

## Task 6: Step 3-R plan review와 rollback checkpoint를 기록한다

**Files:** docs/reviews/2026-08-15-issue-457-sns-signature-plan-review.md, approved spec, this plan.

- [ ] **Step 1: six perspectives를 재검토한다**

Record PASS/BLOCK for performance, stability, security, operations, developer/API, and user/caller. Confirm no task depends on a later artifact, RED precedes production code, and manager is never invoked for parser/topic rejection.

- [ ] **Step 2: P2 follow-up과 rollback을 명시한다**

Certificate-fetch timeout/cleanup telemetry and credential-gated real AWS smoke are deferred follow-up work, not this PR DoD. Rollback reverts dependency, production, resource, and docs commits together while retaining RED tests and review evidence; no partial manager dependency or auto-configuration removal is allowed.

- [ ] **Step 3: review와 plan을 커밋한다**

~~~bash
git add docs/superpowers/plans/2026-08-15-issue-457-sns-signature-plan.md \
  docs/reviews/2026-08-15-issue-457-sns-signature-plan-review.md
git commit -m "SNS 서명 검증 implementation plan을 승인 가능한 상태로 고정한다"
~~~

## Task 7: final review, lesson, and PR creation

- [ ] **Step 1: 범위와 issue metadata를 확인한다**

~~~bash
git status --short
git diff origin/develop...HEAD --stat
gh issue view 457 --repo bluetape4k/bluetape4k-aws --json number,title,state,assignees,milestone,labels,body,url
~~~

Expected: diff is limited to #457; issue remains open, assigned debop, milestone 0.6.0, with SNS/aws-spring-boot labels.

- [ ] **Step 2: Korean PR body와 DoD를 고정한다**

PR body must explain parser → verifier → handler, runtime dependency, default-enabled security contract, tests, Floci limitation, and deferred follow-ups. It must end with:

~~~markdown
## DoD Status

- [x] 구현·테스트·문서·정적 분석 완료
- [x] aws2-sns-message-manager resolved version을 dependencyInsight로 확인
- [x] CI 대기 및 1인 개발자 human review gate N/A
- [ ] CI 최종 결과는 GitHub 실행 후 확인
~~~

- [ ] **Step 3: PR을 생성한다**

Immediately before creation, re-read all AGENTS.md layers, selected workflow/Kotlin/writer skills, PR template, and live issue #457 metadata (CG-12A).

~~~bash
gh pr create --repo bluetape4k/bluetape4k-aws \
  --base develop \
  --head feat/issue-457-sns-signature \
  --assignee debop \
  --milestone 0.6.0 \
  --title "feat(aws-spring-boot): SNS HTTP 메시지 서명 검증 추가" \
  --body-file /tmp/issue-457-pr-body.md
~~~

Expected: PR targets develop, is assigned debop, carries milestone 0.6.0, links Fixes #457, and ends with ## DoD Status.

## Task 8: CI gate, merge approval, sync, and Epic follow-up

- [ ] **Step 1: CI와 exact head를 확인한다**

~~~bash
gh pr view <PR_NUMBER> --repo bluetape4k/bluetape4k-aws --json number,state,headRefName,headRefOid,statusCheckRollup,reviews,reviewDecision,mergeable,body,url
gh pr checks <PR_NUMBER> --repo bluetape4k/bluetape4k-aws
~~~

Human review is N/A for this one-person repository, but required CI and exact-head checks remain mandatory. Skipped required matrix jobs are incomplete evidence.

- [ ] **Step 2: fresh merge approval 후 merge·sync한다**

Re-read exact head SHA, CI, threads, mergeability, and PR body; report merge-ready and wait for fresh explicit approval. Never enable auto-merge.

~~~bash
gh pr merge <PR_NUMBER> --repo bluetape4k/bluetape4k-aws --squash --delete-branch=false
git fetch origin --prune
git switch develop
git pull --ff-only origin develop
git status --short
~~~

- [ ] **Step 3: P2 후속 issue를 등록한다**

After #457 merges, search duplicates and create one Korean backlog issue for certificate-fetch timeout/cleanup telemetry plus credential-gated real AWS signature smoke evidence. Assign debop, use backlog milestone, add enhancement and aws-spring-boot labels, and link it from the merged PR/lesson. Do not claim measurements in #457 DoD.

- [ ] **Step 4: Epic #499 child completion을 확인하고 명시적으로 닫는다**

~~~bash
gh issue view 499 --repo bluetape4k/bluetape4k-aws --json number,state,body,subIssues,labels,milestone,assignees,url
~~~

Only after every child named in the live Epic body is CLOSED and its merged PR is verified, update stale child references if needed and close Epic #499 with a Korean closing comment. Native sub-issue completion does not auto-close the Epic.

## Rollback and hazard controls

- Dependency/catalog drift: preserve immutable bluetape4kDependenciesCatalogRef; do not hard-code a version or edit the shared catalog from this repository. If alias is unavailable at the pinned ref, stop before production code and update the owning catalog repository through a separate issue/PR.
- SDK absence: keep compileOnly in production and test missing-class backoff with FilteredClassLoader; never make existing SNS auto-config unconditionally load the manager.
- External network: manager network access occurs only during verify and SDK cache misses; no real certificate fetch is used in unit tests. Timeout/telemetry remains a separate follow-up issue.
- Floci limitation: Floci-backed tests are not evidence of valid SNS signatures; preserve fixture/mock results and report emulator signing absence.
- Locale drift: keep README headings/examples structurally aligned; use Korean prose in the Korean page and preserve API/property/code tokens.
- Rollback boundary: revert dependency, production, auto-config, resource, and docs commits as one behavior unit; retain RED tests, spec, plan, and review receipts.

## Plan self-review

- Spec coverage: SDK alias (Task 1), verifier ordering/fail-closed behavior (Task 2), auto-config conditions (Task 3), runtime docs/call ordering (Task 4), acceptance commands (Task 5), review/rollback (Task 6), PR/CI/Epic lifecycle (Tasks 7–8) are mapped.
- Placeholder scan: no TODO or TBD step is used; each code task contains target file and concrete Kotlin/Gradle shape.
- Type consistency: SnsProperties.Verification, SnsHttpMessageVerifier, SnsHttpMessageVerificationAutoConfiguration, alias aws2-sns-message-manager, and test class names are consistent.
- Deferred scope: timeout·cleanup telemetry and credential-gated AWS smoke are explicitly assigned to the post-merge follow-up issue, so they cannot be forgotten or silently claimed complete.
