# EventBridge 핵심 래퍼 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용해 이 계획을 작업별로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 구문을 사용한다.

**목표:** `bluetape4k-aws-java`와 `bluetape4k-aws-kotlin`에 집중된 Amazon EventBridge 래퍼를 추가한다.

**아키텍처:** 기존 서비스 래퍼 패턴을 따른다. Java SDK v2에는 클라이언트 팩토리, 요청 빌더, 동기 도우미, 비동기 `CompletableFuture` 도우미, 코루틴 어댑터를 제공한다. AWS Kotlin SDK에는 클라이언트 팩토리, 집중된 요청 빌더, 네이티브 suspend 도우미를 제공한다. 모든 도우미 호출은 원시 SDK 응답 객체를 보존하고 숨은 배치/재시도/정리를 피하며 부분 실패 처리를 호출자에게 맡긴다.

**기술 스택:** Kotlin 2.4, Java SDK v2 EventBridge, AWS Kotlin SDK EventBridge, JUnit 5, MockK, bluetape4k-assertions, Gradle.

---

## 파일 목록

- 수정: `gradle/libs.versions.toml`
- 수정: `aws-java/build.gradle.kts`
- 수정: `aws-kotlin/build.gradle.kts`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientCoroutinesExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/eventbridge/model/EventBridgeRequestSupport.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientSupportTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeClientExtensionsTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeAsyncClientCoroutinesExtensionsTest.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientSupport.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientExtensions.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/eventbridge/model/EventBridgeRequestSupport.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeRequestSupportTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientSupportTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeClientExtensionsTest.kt`
- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `aws-java/README.md`
- 수정: `aws-java/README.ko.md`
- 수정: `aws-kotlin/README.md`
- 수정: `aws-kotlin/README.ko.md`
- 생성 또는 갱신: `docs/review/2026-07-01-issue-308-code-review.md`
- 생성: `docs/lessons/2026-07-01-issue-308-eventbridge-core.md`

## 작업 1: 의존성 별칭과 RED 테스트

**복잡도:** 중간

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] **1단계: 실패하는 Java 요청 빌더 테스트 추가**

`aws-java/src/test/kotlin/io/bluetape4k/aws/eventbridge/EventBridgeRequestSupportTest.kt`를 생성한다.

검증 항목:

- `putEventsRequestOf(entries)`가 빈 항목과 10개 초과 항목을 거부한다.
- `putEventsRequestEntryOf(source, detailType, detail, ...)`가 빈 `source`, `detailType`, `detail`을 거부한다.
- 선택적 이벤트 `resources`는 없을 수 있지만 제공한 빈 리소스 값은 거부한다.
- `putRuleRequestOf(...)`가 누락되거나 빈 `eventPattern`, `scheduleExpression`을 거부한다.
- `putTargetsRequestOf(rule, targets)`가 빈 대상 또는 10개 초과 대상을 거부한다.
- `targetOf(id, arn, ...)`가 빈 ID와 ARN을 거부한다.
- `removeTargetsRequestOf(rule, ids)`가 빈 ID 또는 10개 초과 ID를 거부한다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeRequestSupportTest' --no-configuration-cache
```

예상 결과: EventBridge 별칭/클래스가 없으므로 실패한다.

- [ ] **2단계: 실패하는 AWS Kotlin 요청 빌더 테스트 추가**

`aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/eventbridge/EventBridgeRequestSupportTest.kt`를 생성한다.

AWS Kotlin SDK 모델 이름을 사용해 Java와 같은 동작 사례를 검증한다.

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridgeRequestSupportTest' --no-configuration-cache
```

예상 결과: EventBridge 별칭/클래스가 없으므로 실패한다.

- [ ] **3단계: 의존성 별칭과 선언 추가**

`gradle/libs.versions.toml`에 추가한다.

```toml
aws2-eventbridge = { module = "software.amazon.awssdk:eventbridge", version.ref = "aws2" }
aws-kotlin-eventbridge = { module = "aws.sdk.kotlin:eventbridge", version.ref = "aws-kotlin" }
```

`aws-java/build.gradle.kts`에 추가한다.

```kotlin
compileOnly(libs.aws2.eventbridge)
testImplementation(libs.aws2.eventbridge)
```

`aws-kotlin/build.gradle.kts`에 추가한다.

```kotlin
compileOnly(libs.aws.kotlin.eventbridge)
testImplementation(libs.aws.kotlin.eventbridge)
```

- [ ] **4단계: 최소 요청 빌더 구현**

요청 빌더 테스트를 통과하는 데 필요한 운영 코드만 구현한다. bluetape4k 검증 도우미와
영문 KDoc을 사용한다.

- [ ] **5단계: GREEN 검증**

1단계와 2단계의 두 명령을 실행한다. 예상 결과: 통과.

## 작업 2: Java SDK v2 EventBridge 도우미

**복잡도:** 높음

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] **1단계: 실패하는 Java 클라이언트/지원 테스트 추가**

`EventBridgeClientSupportTest.kt`와 `EventBridgeClientExtensionsTest.kt`를 생성한다.

검증 항목:

- `eventBridgeClientOf(endpoint, region, credentialsProvider, httpClient)`가 닫을 수 있는 클라이언트를 만든다.
- `eventBridgeAsyncClientOf(endpoint, region, credentialsProvider, httpClient)`가 닫을 수 있는 비동기 클라이언트를 만든다.
- 동기 도우미가 SDK 연산을 정확히 한 번 호출하고 원시 SDK 응답을 반환한다.
- `putEvents`, `putTargets`, `removeTargets` 도우미가 실패 항목 수/상세를 포함한 원시 SDK 응답을 보존한다.
- 어떤 도우미도 부분 성공을 Boolean 성공으로 축약하지 않는다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeClient*' --no-configuration-cache
```

예상 결과: 도우미가 없으므로 실패한다.

- [ ] **2단계: 실패하는 Java 코루틴 어댑터 테스트 추가**

`EventBridgeAsyncClientCoroutinesExtensionsTest.kt`를 생성한다.

검증 항목:

- 코루틴 도우미가 비동기 SDK 메서드를 호출하고 반환된 future에 `await()`를 호출한다.
- `CancellationException`으로 예외 완료된 `CompletableFuture`가 취소를 전파한다.
- 반복된 `putEvents` 도우미 호출이 호출마다 SDK를 한 번 실행하고 숨은 배치로 fan-out하지 않는다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeAsyncClientCoroutinesExtensionsTest' --no-configuration-cache
```

예상 결과: 코루틴 도우미가 없으므로 실패한다.

- [ ] **3단계: Java 클라이언트 팩토리 구현**

기존 `KinesisClientSupport.kt` / `KinesisAsyncClientSupport.kt`를 따른다. Kinesis
지원 파일과 같이 생성한 모든 Java `EventBridgeClient` 및 `EventBridgeAsyncClient`를
`build()` 직후 `ShutdownQueue.register(this)`에 등록한다.

- [ ] **4단계: Java 동기/비동기/코루틴 확장 구현**

다음 도우미를 추가한다.

- `createEventBus`
- `deleteEventBus`
- `putRule`
- `deleteRule`
- `putTargets`
- `removeTargets`
- `listRules`
- `listTargetsByRule`
- `putEvents`

도우미 호출당 SDK 요청 하나를 유지한다. 요약, 계약, 사용법, 부분 실패 응답 노출,
취소 전파, 삭제 순서 주의 사항을 다루는 영문 KDoc을 추가한다.

- [ ] **5단계: Java GREEN 검증**

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridge*' --no-configuration-cache
./gradlew :bluetape4k-aws-java:compileTestKotlin --warning-mode all
```

예상 결과: 통과.

## 작업 3: AWS Kotlin SDK EventBridge 도우미

**복잡도:** 높음

**적용:** `$bluetape4k-code-patterns`, `$test-driven-development`

- [ ] **1단계: 실패하는 AWS Kotlin 클라이언트/지원 테스트 추가**

`EventBridgeClientSupportTest.kt`와 `EventBridgeClientExtensionsTest.kt`를 생성한다.

검증 항목:

- `eventBridgeClientOf(...)`가 호출자 소유 클라이언트를 생성한다.
- `withEventBridgeClient { }`가 소유한 단기 클라이언트를 닫는다.
- 네이티브 suspend 도우미가 입력을 SDK 요청에 매핑한다.
- `putEvents`, `putTargets`, `removeTargets` 도우미가 실패 항목 수/상세를 포함한 원시 SDK 응답을 보존한다.
- 어떤 도우미도 숨은 재시도, 배치 또는 정리를 추가하지 않는다.

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache
```

예상 결과: 도우미가 없으므로 실패한다.

- [ ] **2단계: AWS Kotlin 클라이언트 팩토리와 요청 빌더 구현**

기존 `KinesisClientSupport.kt`와 SQS/Kinesis 모델 지원을 따른다. 빌더 도우미는
생성 빌더 전체를 복제하지 않고 검증과 부가 가치 생성자에 집중한다.

- [ ] **3단계: 네이티브 suspend 확장 구현**

다음 도우미를 추가한다.

- `createEventBus`
- `deleteEventBus`
- `putRule`
- `deleteRule`
- `putTargets`
- `removeTargets`
- `listRules`
- `listTargetsByRule`
- `putEvents`

suspend 호출을 `runCatching`으로 감싸지 않는다. SDK 예외와 취소가 전파되게 한다.

- [ ] **4단계: AWS Kotlin GREEN 검증**

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache
./gradlew :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all
```

예상 결과: 통과.

## 작업 4: 문서, 에뮬레이터 probe 및 최종 검증

**복잡도:** 중간

**적용:** `$bluetape4k-code-patterns`

- [ ] **1단계: README 로케일 세트 갱신**

루트 및 모듈 README 쌍을 갱신한다.

- `README.md`
- `README.ko.md`
- `aws-java/README.md`
- `aws-java/README.ko.md`
- `aws-kotlin/README.md`
- `aws-kotlin/README.ko.md`

문서화 항목:

- EventBridge 서비스 지원 범위
- 런타임 의존성: `software.amazon.awssdk:eventbridge` 및 `aws.sdk.kotlin:eventbridge`
- `PutEvents`/`PutTargets` 부분 실패 응답 확인
- 미지원 경계 기능: Scheduler, 프레임워크 통합, 전역 엔드포인트, 계정 간 대상
  오케스트레이션, SDK 타입을 넘어서는 대상별 검증

- [ ] **2단계: Floci 우선 에뮬레이터 probe 실행**

구성된 에뮬레이터 지원으로 최소 EventBridge 워크플로를 실행할 수 있는지 확인한다.
지원하면 좁은 smoke 테스트를 실행한다. 지원하지 않으면 Floci와 LocalStack 대체
경로의 정확한 근거를 PR 검증 기록에 남긴다.

먼저 로컬 에뮬레이터 지원과 테스트 기반을 확인한다.

```bash
rg -n "EventBridge|events|floci|localstack|LocalStack|unsupportedForFloci" aws-java/src/test aws-kotlin/src/test README.md README.ko.md
```

EventBridge 에뮬레이터 smoke 테스트를 추가했고 Floci가 워크플로를 지원하면 다음을 실행한다.

```bash
./gradlew :bluetape4k-aws-java:test --tests '*EventBridgeEmulator*' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
./gradlew :bluetape4k-aws-kotlin:test --tests '*EventBridgeEmulator*' -Dbluetape4k.aws.emulator=floci --no-configuration-cache
```

Floci는 워크플로를 지원하지 않지만 LocalStack은 지원하면 같은 테스트를
`-Dbluetape4k.aws.emulator=localstack`으로 실행한다. 이 저장소에서 어느 에뮬레이터
경로도 EventBridge를 지원하지 않으면 실제 에뮬레이터 smoke를 주장하지 말고 정확한
`rg` 출력과 `*EventBridgeEmulator*` 테스트 부재를 PR `## DoD Status`에 기록한다.

예상 결과: 지원하는 에뮬레이터 경로가 통과하거나 명시적인 에뮬레이터 미지원 근거가 남는다.

- [ ] **3단계: 전체 대상 검증 실행**

실행:

```bash
./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all
./gradlew :bluetape4k-aws-java:test --tests '*EventBridge*' :bluetape4k-aws-kotlin:test --tests '*EventBridge*' --no-configuration-cache
git diff --check
```

예상 결과: 통과.

- [ ] **4단계: 명세, 계획, 구현, 리뷰, 교훈 커밋**

Lore 커밋 트레일러를 사용한다. 최종 코드 PR 생성 전에 계획 산출물을 커밋한 상태로 유지한다.

## 검증 매트릭스

| 요구 사항 | 근거 |
|---|---|
| Java 팩토리/빌더/확장/코루틴 | Java EventBridge 테스트 + `compileTestKotlin` |
| AWS Kotlin 팩토리/빌더/suspend 도우미 | AWS Kotlin EventBridge 테스트 + `compileTestKotlin` |
| PutEvents/PutTargets 제한 | 요청 지원 테스트 |
| PutRule 일치 조건 요구 사항 | 요청 지원 테스트 |
| 부분 실패 노출 | 확장 테스트 및 README/KDoc |
| 숨은 배치/재시도/정리 없음 | mock 호출 횟수 테스트 |
| 생명주기 계약 | 클라이언트 지원 테스트 또는 명시적인 최근접 패턴 근거 |
| 에뮬레이터 준비 상태 | Floci 우선 probe 통과 또는 미지원 근거 |
| 공개 문서 | README 로케일 세트 + 영문 KDoc 검색 |
