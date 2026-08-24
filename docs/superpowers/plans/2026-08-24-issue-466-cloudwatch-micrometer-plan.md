# Issue #466 CloudWatch Micrometer registry 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax for tracking.

**Goal:** 기존 수동 CloudWatch helper를 보존하면서 선택적 Micrometer CloudWatchMeterRegistry Spring Boot 4 자동 설정을 추가한다.

**Architecture:** 기존 CloudWatchAutoConfiguration은 shared CloudWatchAsyncClient와 수동 helper를 계속 소유한다. 새 CloudWatchMeterRegistryAutoConfiguration은 classpath/property/registry back-off와 Boot 4 ordering을 담당하고, CloudWatchMeterRegistryConfiguration은 CloudWatchConfig adapter와 설정 완료 전후 start gate를 담당한다.

**Tech Stack:** Kotlin, Spring Boot 4, Micrometer 1.17 CloudWatch registry, AWS SDK v2, Gradle version catalog, JUnit 5, MockK, ApplicationContextRunner, Awaitility.

---

## 파일 맵

| 경로 | 책임 |
| --- | --- |
| gradle/libs.versions.toml | micrometer-registry-cloudwatch2 alias |
| aws-spring-boot/build.gradle.kts | compileOnly/test dependency |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchProperties.kt | micrometer.registry 설정과 검증 |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchMeterRegistryAutoConfiguration.kt | 조건, ordering, bean, destroy close |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchMeterRegistryConfiguration.kt | adapter, filters/tags, start gate |
| aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports | import 등록 |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchMeterRegistryAutoConfigurationTest.kt | context/back-off/lifecycle 검증 |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/cloudwatch/CloudWatchMeterRegistryContractTest.kt | PutMetricData와 close 계약 검증 |
| README.md, README.ko.md, docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md, docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md | runtime dependency, opt-in, 운영 문서와 locale parity |

## Task 1: dependency와 import

- [ ] libs.versions.toml에 micrometer-registry-cloudwatch2 alias를 추가한다. 버전은 기존 micrometer-core와 같이 Spring Boot dependency management와 중앙 BOM에 맡긴다.
- [ ] aws-spring-boot/build.gradle.kts에 compileOnly(libs.micrometer.registry.cloudwatch2)와 testImplementation(libs.micrometer.registry.cloudwatch2)를 추가한다. native exporter가 transitive runtime으로 유입되지 않는지 dependency report를 확인한다.
- [ ] 다음 dependency 증거를 수집한다. ./gradlew :bluetape4k-aws-spring-boot:dependencies --configuration runtimeClasspath | rg 'micrometer-registry-cloudwatch2'와 ./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency micrometer-registry-cloudwatch2 --configuration runtimeClasspath는 library runtimeClasspath에 해당 artifact가 선택되지 않음을 보여야 하며, testRuntimeClasspath에는 테스트용 artifact가 선택되어야 한다.
- [ ] AutoConfiguration.imports의 CloudWatchAutoConfiguration 다음 줄에 io.bluetape4k.aws.spring.cloudwatch.CloudWatchMeterRegistryAutoConfiguration을 등록한다.
- [ ] Run: ./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon. Expected: BUILD SUCCESSFUL.

## Task 2: property contract TDD

- [ ] CloudWatchAutoConfigurationTest에 registry.enabled, namespace, step=59s, batch-size=20, read-timeout=15s, common-tags, filters includes/excludes binding RED 테스트를 먼저 추가한다.
- [ ] CloudWatchProperties.Micrometer 아래에 Registry와 Filters를 추가한다. 기본값은 enabled=false, namespace=null, step=1m, batch-size=20, read-timeout=10s, common-tags/includes/excludes empty이다. 기존 top-level batchSize와 Micrometer.enabled는 변경하지 않는다.
- [ ] Registry init에서 batch-size 1..1000, step 1s 이상, read-timeout 1s..5m을 검증한다. common tag key/value와 filter prefix blank를 거부하고 실제 property key를 오류에 포함한다.
- [ ] 59s/60s/1s/5m valid, 0s/6m/0/1001 invalid, namespace fallback 경계 테스트를 추가한다.
- [ ] Run: ./gradlew :bluetape4k-aws-spring-boot:test --tests '*CloudWatchAutoConfigurationTest' --no-daemon. RED 후 구현하고 GREEN을 확인한다.
- [ ] compileKotlin 후 generated spring-configuration-metadata.json에 bluetape4k.aws.cloudwatch.micrometer.registry.*가 있는지 확인한다.

## Task 3: auto-configuration 조건 RED

- [ ] CloudWatchMeterRegistryAutoConfigurationTest를 만들고 AwsAutoConfiguration, CloudWatchAutoConfiguration, 새 자동 설정과 relaxed MockK CloudWatchAsyncClient를 ApplicationContextRunner에 등록한다.
- [ ] opt-in positive, default disabled, user MeterRegistry back-off, user CompositeMeterRegistry back-off, FilteredClassLoader dependency back-off, shared CloudWatch disabled, custom client identity reuse 테스트를 먼저 작성한다.
- [ ] AutoConfiguration annotation metadata에서 afterName이 org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration, beforeName이 org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration인지 검증한다.
- [ ] 새 production type이 없어 RED인지 확인한다. Run: ./gradlew :bluetape4k-aws-spring-boot:test --tests '*CloudWatchMeterRegistryAutoConfigurationTest' --no-daemon.

## Task 4: mocked exporter contract RED

- [ ] CloudWatchMeterRegistryContractTest에 completed CompletableFuture와 failed CompletableFuture<PutMetricDataResponse>를 준비한다. failed future는 IllegalStateException("cloudwatch down")이며 scheduler 유지와 logger 오류를 검증한다.
- [ ] AccessDenied, timeout, malformed endpoint configuration에서는 fallback credential, privilege escalation, secret/payload logging, retry loop가 없고 provider/AWS 호출이 추가되지 않는 negative test를 추가한다. endpointOverride는 기존 trusted deployment setting이므로 request/tag로 endpoint를 바꾸는 경로가 없음을 확인하고, 새 production host allow-list는 호환성 범위 밖으로 추가하지 않는다.
- [ ] batch-size 분할, native registry에만 적용되는 common-tags/include/exclude, 59s high-resolution storageResolution=1, 60s standard resolution, 30/31 dimension warning, close final flush/in-flight wait, failed future 뒤 publish 가능 테스트를 작성한다.
- [ ] orders.processed meter와 jvm.memory.used sentinel로 request metric name/dimensions를 검증하고 PII/secret 값은 사용하지 않는다.
- [ ] Run: ./gradlew :bluetape4k-aws-spring-boot:test --tests '*CloudWatchMeterRegistry*' --no-daemon. production 구현 전 RED 출력을 기록한다.

## Task 5: adapter와 start gate 구현

- [ ] CloudWatchMeterRegistryConfiguration.kt에서 namespace는 registry namespace 우선, 없으면 top-level namespace, 둘 다 없으면 명확한 property 오류가 되게 한다. step, batchSize, readTimeout, highResolution은 properties를 반환한다.
- [ ] AtomicBoolean(false) enabled gate를 사용한다. constructor immediate start가 publisher를 실행하지 않도록 gate가 닫힌 상태에서 registry를 만든다.
- [ ] registry.config에 Tags와 MeterFilter를 적용한다. include는 denyUnless prefix allow-list, excludes는 뒤에 deny로 적용해 거부가 우선이며 기존 application registry에는 전파하지 않는다.
- [ ] step < 1m creation 시 한 번 WARN하고 namespace, step, storageResolution=1만 기록한다. NamedThreadFactory("bluetape4k-cloudwatch-metrics")로 공식 start(threadFactory)를 호출한다.
- [ ] 순서는 construction, common tags, filters, warning, gate open, start이다. retry loop, 별도 shutdown executor, shared client close는 추가하지 않는다.

## Task 6: auto-configuration과 GREEN

- [ ] 새 자동 설정에 after=[CloudWatchAutoConfiguration], afterName=MetricsAutoConfiguration, beforeName=CompositeMeterRegistryAutoConfiguration, ConditionalOnAwsEnabled, CloudWatchAsyncClient와 CloudWatchMeterRegistry classpath 조건, registry.enabled=true 조건, shared client 조건, ConditionalOnMissingBean(MeterRegistry)를 구현한다.
- [ ] bean은 destroyMethod=close, ObjectProvider<Clock>와 Clock.SYSTEM fallback을 사용한다. registry가 shared client를 닫지 않게 한다.
- [ ] MetricsAutoConfiguration/Actuator registry closer가 있는 context에서 registry close가 idempotent이고 registry publisher 종료 뒤 shared client가 닫히는지, 이중 close가 추가 PutMetricData를 만들지 않는지 검증한다.
- [ ] context tests GREEN: positive one, 모든 disabled/back-off zero, custom client same instance, ordering PASS.
- [ ] contract tests GREEN: batch, filters/tags, 59s/60s, dimension, error, flush/close PASS. real AWS write는 없다.

## Task 7: 문서와 locale parity

- [ ] README와 EN/KO manual에 다음 consumer runtime 좌표를 추가한다: implementation bluetape4k-aws-spring-boot, runtimeOnly io.micrometer:micrometer-registry-cloudwatch2, runtimeOnly software.amazon.awssdk:cloudwatch. library compileOnly와 중앙 BOM scope를 구분한다.
- [ ] 기존 micrometer.enabled는 수동 helper, registry.enabled는 scheduled native exporter opt-in임을 표로 설명하고 allow-list YAML, 빈 includes 전체 전송, PII/secret/high-cardinality 금지, common tag dimension 비용을 기록한다.
- [ ] step < 1m 비용, batch-size 20/1000, ceil(meter count / batch-size) × read-timeout shutdown, Spring/Kubernetes grace period, failed future/timeout logger-only/no retry, cloudwatch:PutMetricData 최소 IAM, production HTTPS endpoint, user registry/Boot composite 결과, --debug condition report를 EN/KO에 같은 의미로 작성한다.
- [ ] runtime rollback 절차를 추가한다. registry.enabled=false로 재배포한 뒤 publisher thread와 PutMetricData 호출이 0이고 기존 수동 helper가 계속 생성되는 context 증거를 기록한다. runtime dependency를 제거할 때는 opt-in bean이 사라지고 기존 client/helper는 유지되는 영향도 함께 적는다.

## Task 8: performance, stability, static verification

- [ ] metric datum 수 40/1000과 batch-size 20/1000을 parameterize해 호출 수가 ceil(metric datum / batch-size), 각 request datum 수가 batch-size 이하, estimated payload가 1MB 이하인지 assert하고 결과와 round-trip 시간을 기록한다. throttling/1MB 초과는 실패 신호로 남긴다.
- [ ] 2개가 아닌 다중 batch의 지연/never-completing/failed future와 겹치는 scheduler tick을 검증한다. close 상한, in-flight 호출 수, overrun skip, timeout logger, scheduler 지속, 이후 publish 가능성을 Awaitility deadline으로 assert한다.
- [ ] timed-out SDK future는 registry가 강제 취소하지 않고 AWS client 소유로 남기며, registry close 뒤 새 publish가 발생하지 않고 shared client close가 registry 종료 뒤에 일어나는지 검증한다. registry-level retry는 없고 SDK client retry 설정은 소비자 소유라는 구분과 batch별 호출 수 assertion을 기록한다.
- [ ] concurrent record/publish/close와 double-close를 bounded stress로 실행해 named publisher thread 종료, deadlock 없음, post-close call 0을 확인한다. allocation benchmark는 Micrometer publisher 소유 영역이므로 N/A로 명시하고, 동시 update/registration과 in-flight boundedness 명령/결과만 남긴다.
- [ ] 중복 validation/filter smell이 실제로 있을 때만 기존 utility를 재사용해 cleanup하고 regression tests를 다시 GREEN으로 만든다.
- [ ] Run: git diff --check; ./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon; ./gradlew :bluetape4k-aws-spring-boot:test --tests '*CloudWatch*' -PskipAwsEmulatorTests=true --no-daemon; ./gradlew detekt --no-daemon.
- [ ] Run: node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs README.md README.ko.md docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md; ruby scripts/manual/manual_contract_test.rb. Expected: all PASS.

## Task 9: review, commit, PR gate

- [ ] 계획을 Performance, Stability, Security, Operator/Ops, Developer/API, User/caller 6개 관점으로 리뷰한다. 통합자는 P0=0/P1=0을 확인하고 P2/P3를 구현·문서·수용 위험으로 disposition한다. material design change면 영향 관점을 재실행한다.
- [ ] 통합 disposition을 기록한다. 성능 P1은 metric datum 호출/payload와 다중 batch close 증거로 해소한다. 안정성 P1은 timeout·late future·retry ownership·close ordering 증거로 해소한다. 보안 리뷰의 endpoint/credential/IAM/PII P1은 기존 trusted deployment endpoint와 표준 AWS provider chain을 유지하고 registry 기본 disabled, HTTPS/IAM/비밀 비기록, AccessDenied·endpoint·log-redaction negative tests를 추가하는 P2 범위 경계로 재분류한다. S3Resource 등 이 이슈와 무관한 reviewer finding은 근거 불일치로 채택하지 않는다.
- [ ] 계획 review와 user 승인 후에만 source/test 변경을 시작하고 설계 commit 63c59a6은 보존한다.
- [ ] 구현 commit은 Lore protocol을 사용한다. Intent line은 Issue #466 native exporter opt-in 이유를 쓰고 Constraint, Rejected, Confidence, Scope-risk, Directive, Tested, Not-tested trailer를 포함한다.
- [ ] PR 전 git status --short, git diff --check, 변경 파일, 테스트, known gap, exact base/head, Issue #466 metadata를 live-read한다.

## Rollback과 stop condition

- source/test rollback은 구현 commit 단위로 revert하고 설계 commit 63c59a6은 보존한다.
- runtime rollback은 registry.enabled=false 재배포 후 publisher/API 호출 0, 기존 수동 helper 유지, shared client 정상 종료를 확인하는 단계로 수행한다.
- managed Micrometer API가 호환되지 않으면 다른 registry/adapter로 우회하지 않고 dependency와 설계를 재검토한다.
- compile/test/detekt/manual contract 실패 또는 P0/P1 잔존 시 PR로 진행하지 않는다.
- 실제 AWS write, credential, branch/remote deletion, merge는 자동 범위가 아니다. merge는 exact-head, CI/review/DoD 후 별도 승인에서만 수행한다.

## 완료 증거

1. positive/negative/back-off context tests PASS
2. mocked API error, batch, filter/tag, resolution, dimension, flush/close PASS
3. compileKotlin, Spring Boot test subset, detekt PASS
4. catalog와 configuration metadata 확인
5. README/manual EN·KO parity와 terminology audit PASS
6. git diff --check와 working tree/변경 파일 증거
7. Issue #466 acceptance/DoD와 real AWS/IAM/cost 미검증 범위 기록
8. skipAwsEmulatorTests는 coverage 증거가 아니며 CloudWatch exporter emulator/Docker 검증은 N/A라는 stability boundary 기록

## Step 3-R 통합 판정

| 관점 | 최신 판정 | 근거와 disposition |
| --- | --- | --- |
| Performance | P0=0, P1=0 | Task 8에 metric datum/batch parameterization, 호출 수·payload·round-trip 증거, 다중 batch close와 bounded in-flight assertion을 추가했다. allocation benchmark는 Micrometer 소유 영역 N/A로 명시한다. |
| Stability | P0=0, P1=0 | timeout/never-completing/late future, scheduler 지속, retry ownership, cancellation 금지, double-close/thread cleanup, shared client close ordering을 Task 4/6/8에 추가했다. |
| Security | P0=0, P1=0 | endpoint/credential/IAM/PII 위험은 기존 trusted deployment endpoint와 표준 provider chain이라는 범위 경계로 P2 재분류하고, default disabled·HTTPS·최소 IAM·비밀 비기록·AccessDenied/endpoint/log-redaction negative tests를 추가했다. request-supplied endpoint나 broad host policy는 이 이슈에 추가하지 않는다. |
| Operator/Ops | P0=0, P1=0 | shutdown grace-period, logger/alert boundary, runtime rollback, Actuator closer, dependency evidence와 quota/throttling tuning을 Task 6-8에 고정했다. |
| Developer/API | P0=0, P1=0 | current Kotlin/Spring Boot 4 patterns, exact auto-configuration names, classpath/property guards, shared client ownership, compile/test commands를 직접 대조했고 추가 P1은 없다. |
| User/caller | P0=0, P1=0 | runtime dependency 좌표, 기존 helper/native migration 표, allow-list 기본 예제, EN/KO parity와 condition report를 Task 7에 고정했다. S3Resource 관련 reviewer 출력은 Issue #466 범위와 근거가 불일치해 채택하지 않는다. |

통합 결과는 P0=0, P1=0이다. P2는 security caller-owned data, logs-only/no-health contract,
emulator 미검증과 같은 명시적 범위 경계로 문서화했고, P3는 dependency/quota와
운영 튜닝 증거로 후속 게이트에 연결했다. 이 판정은 최신 계획을 다시 읽고
step-3r-plan-review.md의 spec-to-task, ordering, verification, README/locale,
rollback 조건을 확인한 뒤 내려졌다.
