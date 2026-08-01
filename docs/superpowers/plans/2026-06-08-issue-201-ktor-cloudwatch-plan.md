# 이슈 #201 Ktor CloudWatch 및 CloudWatch Logs 구현 계획

작성일: 2026-06-08
이슈: #201
명세: `docs/superpowers/specs/2026-06-08-issue-201-ktor-cloudwatch-design.md`

## 목표

기존 `bluetape4k-aws-java` coroutine helper를 사용해 `aws-ktor`에 선택형 CloudWatch 및 CloudWatch Logs Ktor plugin을 추가하면서 opt-in publishing, AWS SDK dependency 선택성, lifecycle ownership, cancellation, README parity를 보존한다.

## 작업 계획

### 1. dependency 및 shared defaults 연결

파일:

- `aws-ktor/build.gradle.kts`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/AwsKtorCoreTest.kt`

작업:

- `compileOnly(libs.aws2.cloudwatch)`와 `compileOnly(libs.aws2.cloudwatchlogs)`를 추가한다.
- 대응하는 `testImplementation` dependency를 추가한다.
- `AwsKtorCloudWatchAsyncClientCustomizer`와 `AwsKtorCloudWatchLogsAsyncClientCustomizer`를 추가한다.
- 기존 `AwsKtorCore` setup block에서 shared `bluetape4k-ktor-core` baseline을 설치하는 opt-in `AwsKtorCoreConfig.ktorCore(...)` wiring을 추가한다.
- `AwsKtorDefaults` constructor, transient storage, accessor, equality/hash/toString, `AwsKtorCoreConfig`를 확장한다.
- default storage, `bluetape4k-ktor-core` baseline 설치, customizer ordering test를 추가한다.

DoD:

- shared 및 service-local customizer를 결정적인 순서로 실행한다.
- `AwsKtorCore { ktorCore() }`가 `bluetape4k-ktor-core` health/readiness route를 제공하며 `bluetape4k-ktor-testing` assertion으로 검증한다.
- endpoint override에는 여전히 effective region이 필요하다.
- public KDoc은 영문이다.

### 2. CloudWatch metrics operation과 plugin

파일:

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorPlugin.kt`

작업:

- `CloudWatchAsyncClient` coroutine extension 기반 operation facade를 구현한다.
- `putMetricData`를 `batchSize`로 batch 처리하고 빈 metric list는 건너뛰며 default-namespace method에서만 non-blank default namespace를 요구한다.
- injected operation, injected client, plugin-created client, client customizer, region/endpoint/credentials, namespace, `batchSize`를 위한 plugin config를 추가한다.
- 활성화했을 때만 operation/runtime attribute를 저장한다.
- `ApplicationStopping`에서 plugin-owned client만 한 번 닫는다.
- plugin configuration 변환 중 plugin-owned client를 생성하며 metrics용 background 작업은 시작하지 않는다. stop은 `ApplicationStopping`에서 owned client를 닫는다.

테스트:

- disabled plugin은 attribute를 저장하지 않고 `cloudWatchOrNull()`은 null을 반환한다.
- injected operation은 client-only validation을 우회한다.
- injected client는 runtime stop에서 닫지 않는다.
- plugin-owned client를 한 번 닫는다.
- 빈 metric list는 AWS를 호출하지 않는다.
- metric batch를 설정한 `batchSize`로 분리한다.
- default namespace 누락은 default-namespace method에서만 실패한다.
- 기반 AWS future의 cancellation을 `putMetricData`와 `listMetrics` suspend operation 모두에서 전파한다.

DoD:

- 설치 중 AWS 호출이 발생하지 않는다.
- ownership과 validation이 기존 SQS/IMDS Ktor pattern과 일치한다.
- public KDoc은 영문이다.

### 3. CloudWatch Micrometer snapshot 연결

파일:

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingOperations.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchKtorMeterPublishingTemplateTest.kt`

작업:

- Spring `CloudWatchMeterPublishingOperations` 동작을 Ktor package에 동일하게 구현한다.
- application code가 `publishMeters` 또는 `publishMeter`를 호출할 때만 기존 `MeterRegistry`를 읽는다.
- 실제 publishing에 `CloudWatchKtorOperations`를 재사용한다.

테스트:

- 빈 registry 또는 filtering된 meter는 `emptyList()`를 반환하고 AWS operation을 호출하지 않는다.
- 선택한 finite measurement를 Micrometer tag를 dimension으로 사용하는 `MetricDatum`에 mapping한다.
- `publishMeter`가 blank name을 거부한다.
- `CloudWatchKtorOperations.putMetricData`의 cancellation을 전파한다.

DoD:

- Micrometer를 `compileOnly` 및 opt-in으로 유지한다.
- global registry/exporter/scheduler를 도입하지 않는다.

### 4. CloudWatch Logs 작업, runtime, plugin

파일:

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/CloudWatchLogsKtorPlugin.kt`

작업:

- log group/stream identity를 위한 `Serializable` value object로 `CloudWatchLogStream`을 추가한다.
- `CloudWatchLogsAsyncClient` coroutine extension 기반 operation facade를 구현한다.
- `putLogEvents`를 batch 처리하고 빈 log-event list는 건너뛰며 default method에서만 default log group/stream을 요구한다.
- `Mutex`, 명시적 `append`, 명시적 `flush`, 선택형 periodic flush, bounded `stop`을 사용하는 buffered runtime을 구현한다.
- publishing 전에 buffered event를 timestamp로 정렬한다.
- log group과 log stream을 위한 opt-in startup setup을 추가한다.
- 활성화했을 때만 operation/runtime attribute를 저장한다.
- plugin-owned client만 한 번 닫는다.
- plugin configuration 변환 중 plugin-owned client를 생성하고, `ApplicationStarted`에서 opt-in group/stream setup과 periodic flush를 실행하며 `ApplicationStopping`에서 stop/flush/close한다.

테스트:

- disabled plugin은 attribute를 저장하지 않고 `cloudWatchLogsOrNull()`은 null을 반환한다.
- injected operation은 client-only validation을 우회한다.
- injected client는 닫지 않고 plugin-owned client는 한 번 닫는다.
- 빈 `putLogEvents`와 빈 `flush`는 AWS를 호출하지 않는다.
- 기본 `append`에는 non-blank log group과 stream이 필요하다.
- `CloudWatchLogStream`은 blank log group 또는 stream name을 거부한다.
- batch를 설정한 `batchSize`로 분리하고 event를 timestamp로 정렬한다.
- `flush()`는 concurrent 호출에서 안전하며 event를 중복하지 않는다.
- `stop()`은 buffered event를 flush하고 `shutdownFlushTimeout`을 지키며 flush timeout이 발생해도 plugin-owned client를 닫는다.
- opt-in startup setup은 설정했을 때만 group/stream을 생성한다.
- 기반 suspend AWS 호출의 cancellation을 `createLogGroup`, `createLogStream`, `putLogEvents`, `describeLogGroups`, `describeLogStreams`, buffered `flush`에서 전파한다.

DoD:

- buffered publishing은 명시적이다. plugin 설치만으로 publish하지 않는다.
- shutdown 동작은 bounded하고 idempotent하다.

### 5. README와 lesson

파일:

- `aws-ktor/README.md`
- `aws-ktor/README.ko.md`
- `docs/lessons/2026-06-08-issue-201-ktor-cloudwatch.md`

작업:

- 두 README locale의 feature list, quick-start snippet, CloudWatch/Logs 사용 section, option table을 갱신한다.
- 사용자가 raw Ktor-only setup보다 먼저 bluetape4k Ktor ecosystem 경로를 볼 수 있도록 shared defaults example에 `AwsKtorCore { ktorCore() }`를 표시한다.
- publishing/setup은 opt-in이고 global logging appender 또는 Micrometer registry를 대체하지 않는다고 명시한다.
- context, decision, outcome, verification, future guard를 포함한 간결한 lesson을 추가한다.

DoD:

- README locale parity를 보존한다.
- lesson에 ownership, opt-in publishing, cancellation/shutdown guard를 기록한다.

### 6. 검증

명령:

```bash
./gradlew :bluetape4k-aws-ktor:compileKotlin
./gradlew :bluetape4k-aws-ktor:compileTestKotlin
./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest' --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'
./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*'
./gradlew :bluetape4k-aws-ktor:test
./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency cloudwatch --configuration compileClasspath
./gradlew :bluetape4k-aws-ktor:dependencyInsight --dependency cloudwatchlogs --configuration compileClasspath
git diff --check
```

예상 evidence:

- focused CloudWatch test가 통과한다.
- `AwsKtorCoreTest`가 `bluetape4k-ktor-core` baseline 설치를 입증하고 `bluetape4k-ktor-testing` assertion을 사용한다.
- 전체 `aws-ktor` test가 통과한다.
- dependency insight에서 service SDK jar를 `compileOnly`로 사용할 수 있고 unconditional `api`로 승격하지 않았음을 확인한다.
- `git diff --check`가 통과한다.

## rollback과 compatibility

- 변경은 additive하다. 새 plugin type, 새 optional dependency, 새 `AwsKtorCore` customizer list, opt-in `ktorCore()` bridge를 추가한다.
- 기존 S3, SQS, DynamoDB, IMDS, Exposed API는 source compatible해야 한다.
- `AwsKtorDefaults` equality 동작이 regression되면 customizer extension을 rollback하고 service customizer를 각 plugin config 내부에 유지한다.

## 범위 제외

- CloudWatch emulator integration 테스트.
- global Ktor logging appender 추가.
- scheduled Micrometer CloudWatch registry exporter 추가.
- AWS Kotlin SDK CloudWatch plugin 추가.
