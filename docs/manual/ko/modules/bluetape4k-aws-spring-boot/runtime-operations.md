---
title: Runtime 운영
description: Spring AWS 통합을 제한된 수명 주기와 관측성으로 운영합니다.
manualId: bluetape4k-aws-spring-boot
chapterId: runtime-operations
---

# Runtime 운영

운영 안정성은 자동 설정 자체보다 수명 주기, 제한된 동시성, 관측성, 비밀 값 관리에서 나옵니다.

## Client 소유권

자동 설정이 만든 client는 Spring이 소유합니다. 애플리케이션이 제공한 client는 closeable bean으로 등록하지 않는 한 애플리케이션 소유입니다. listener container가 client보다 먼저 멈춰야 하며 요청 handler에서 공유 client를 닫으면 안 됩니다.

## S3 ResourceLoader

S3 자동 설정은 Spring resource protocol을 통해 exact object를 해석합니다.

```kotlin
val resource = applicationContext.getResource(
    "s3://order-config/config/application.yml",
)
```

exact 형식은 `@Value("s3://order-config/config/application.yml")`로도 주입할 수
있습니다. `ApplicationContext.getResources(...)`는 S3 pattern resolver가 자동으로
가로채지 않습니다. 고정 이름의 pattern bean을 직접 주입하세요.

```kotlin
class ConfigReader(
    @Qualifier("s3ResourcePatternResolver")
    private val resources: ResourcePatternResolver,
) {
    fun yamlFiles(): Array<Resource> =
        resources.getResources("s3://order-config/config/**/*.yml")
}
```

패턴은 의도적으로 좁게 제한됩니다. literal bucket 한 개, 비어 있지 않은 prefix,
`*`, `?`, `**`만 사용할 수 있습니다. cross-bucket 패턴과
`s3://order-config/*.json`, `s3://order-config/**` 같은 root listing은 AWS 호출 전에
실패합니다. write와 output stream은 지원하지 않습니다. 기본 bean 이름
`s3ResourcePatternResolver`는 기본 또는 custom S3 pattern 구현을 위한 예약 이름입니다.
custom replacement도 이 이름을 유지해야 하며 unrelated resolver가 이 이름을 차지하면
안 됩니다.

exact read에는 같은 bucket/key prefix에 대한 `s3:GetObject`가 필요하고, S3 HEAD
metadata 확인도 같은 권한을 사용합니다. pattern listing에는 같은 prefix를 조건으로
하는 `s3:ListBucket`과 `s3:prefix` 조건이 추가로 필요합니다. 따라서 최소 권한 정책은
한 bucket의 `config/` prefix만 가리키도록 작성합니다.

```json
{
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::order-config/config/*"
    },
    {
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::order-config",
      "Condition": { "StringLike": { "s3:prefix": ["config/*"] } }
    }
  ]
}
```

`s3:ListAllMyBuckets`나 다른 bucket에 대한 권한은 추가하지 마세요.
`bluetape4k.aws.s3.enabled=false`는 resolver만이 아니라 S3 backend 전체 자동 설정을
끄는 기존 switch입니다. custom replacement와 직접 만든 `S3Resource`의 입력 검증 및
IAM enforcement 책임은 caller에게 있습니다.

pattern 호출은 caller thread에서 paginator의 모든 page를 동기로 소비합니다. resolver는
retry, cache, coalescing, executor를 추가하지 않으며 주입된 client의 timeout과 transport
설정을 그대로 사용합니다. 짧은 prefix도 많은 object를 읽어 비용과 heap을 늘릴 수 있으므로
non-empty prefix와 IAM `s3:prefix` 조건을 함께 설계하세요. 반환 stream은 caller가 닫고
resource는 owning client와 ApplicationContext가 살아 있는 동안만 사용합니다. 오류 진단에는
bounded bucket/prefix만 포함하며 secret, credential, header, 제한 없는 cause text는 로그나
metric에 기록하지 마세요.

## SQS runtime

consumer 수, long-poll 시간, 한 번에 받을 메시지 수, visibility timeout, 실패 visibility, 종료 timeout을 함께 조정하세요. poller를 늘리면 처리량뿐 아니라 실행 중인 메시지와 downstream 압력도 커집니다. 끝없는 사용자 정의 재시도보다 SQS native redrive policy를 우선하세요.

## 관측성

`MeterRegistry`가 있으면 S3·SQS 작업과 listener 단계에 낮은 cardinality timer를 붙일 수 있습니다. bucket key, message body, secret ID, 제한 없는 예외 문자열을 metric tag로 사용하지 마세요. 로그에는 AWS request ID를 남겨 상관관계를 추적합니다.

## Native CloudWatch registry

Micrometer 경로는 두 가지를 서로 분리합니다.

| 설정 | 책임 | 기본값 |
| --- | --- | --- |
| `bluetape4k.aws.cloudwatch.micrometer.enabled` | 명시적 기준 데이터 publish를 수행하는 기존 helper | `true` |
| `bluetape4k.aws.cloudwatch.micrometer.registry.enabled` | 주기적으로 동작하는 native `CloudWatchMeterRegistry` exporter | `false` |

CloudWatch namespace를 소유하는 애플리케이션에서만 선택적 runtime exporter를
추가하고 opt-in하세요.

```kotlin
implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot:${bluetape4kAwsVersion}")
runtimeOnly("io.micrometer:micrometer-registry-cloudwatch2")
implementation("software.amazon.awssdk:cloudwatch")
```

```yaml
bluetape4k:
  aws:
    cloudwatch:
      enabled: true
      region: ap-northeast-2
      namespace: OrderApi
      micrometer:
        registry:
          enabled: true
          namespace: OrderApiNative
          step: 1m
          batch-size: 20
          read-timeout: 10s
          common-tags:
            application: order-api
          filters:
            includes: ["orders.", "http.server.requests"]
            excludes: ["jvm."]
```

`registry.namespace`가 `cloudwatch.namespace`보다 우선하며 둘 다 없으면 AWS 요청 전에
startup이 실패합니다. 빈 `includes`는 모든 meter를 허용하는 명시적 선택이므로 낮은
cardinality prefix allow-list를 우선하고 secret, object key, request ID, raw exception
text를 tag에 넣지 마세요. 애플리케이션이 `MeterRegistry`(`CompositeMeterRegistry` 포함)를
이미 제공하면 native bean은 back-off합니다. native bean은 공유
`CloudWatchAsyncClient`를 재사용하지만 직접 닫지 않습니다.

`step < 1m`이면 Micrometer가 `storageResolution=1`을 전송하며 CloudWatch 비용이
높아질 수 있고 startup warning을 남깁니다. 공식 registry close lifecycle은 진행 중인
호출을 기다리며, batch가 `n`개이면 최악의 close 대기는 대략 `n * read-timeout`입니다.
registry 자체 retry나 강제 cancellation은 추가하지 않습니다. 실패·timeout future는
registry가 log로 남기고 AWS SDK retry 정책은 consumer가 소유합니다. production에서는
HTTPS endpoint, 표준 AWS credential provider chain, 최소 `cloudwatch:PutMetricData`
권한을 사용하세요. optional registry dependency가 없을 때는 `--debug` condition output에서
back-off 원인을 확인할 수 있습니다. rollback은
`bluetape4k.aws.cloudwatch.enabled=false` 또는 `bluetape4k.aws.enabled=false`로 수행하며,
기존 명시적 helper는 자체 설정을 따릅니다.

## 원격 설정

Secrets Manager, Parameter Store, S3 config loader는 환경 준비 단계에서 실행됩니다. 필수 source 조회가 실패하면 시작을 실패시키세요. 요청마다 AWS를 호출하지 말고 해석한 설정을 environment에 보관합니다.
`bluetape4k.aws.enabled=false`로 설정하면 이 startup loader와 AWS 자동 구성이 함께 비활성화되어 설정된 원격 source에 접근하지 않습니다.

## ConfigData import

원격 source를 startup 시점에만 읽어야 한다면 Spring Boot ConfigData import를
사용하세요.

```properties
spring.config.import=optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml,aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true,optional:aws-secretsmanager:application?prefix=app&format=json
```

같은 import를 YAML list로 선언할 수도 있습니다.

```yaml
spring:
  config:
    import:
      - optional:aws-s3:/config-bucket/application.yml?prefix=app&format=yaml
      - aws-parameterstore:/application?prefix=app&recursive=true&withDecryption=true
      - optional:aws-secretsmanager:application?prefix=app&format=json
```

지원하는 prefix는 `aws-s3:`, `aws-parameterstore:`,
`aws-secretsmanager:`, `aws-app-config:`입니다. `optional:`은 해당 backend의
not-found 결과만 억제합니다. 인증, network, parsing 및 그 밖의 service 오류는
startup 실패로 남습니다. `bluetape4k.aws.enabled=false`는 SDK classpath 확인보다
먼저 판정되므로, 비활성화하면 ConfigData가 AWS client를 만들지 않고 원격에도
접근하지 않습니다. 로컬 emulator는 Floci를 우선 사용하고 LocalStack은 명시적인
fallback으로 사용하세요. S3, Parameter Store, Secrets Manager ConfigData는
startup 전용입니다. 기존 `EnvironmentPostProcessor` source는 기존 refresh와
precedence 동작을 계속 제공합니다.

### AppConfig Data runtime reload

AppConfig Data SDK를 runtime 의존성으로 추가하고 `application`, `profile`,
`environment` 순서로 세 identifier를 import합니다.

```kotlin
implementation("software.amazon.awssdk:appconfigdata")
```

```properties
spring.config.import=aws-app-config:orders-api#production#ap-northeast-2?format=yaml&prefix=app
```

기본 separator는 `#`이며 `bluetape4k.aws.app-config.separator`로 안전한 한 글자
separator를 지정할 수 있습니다. 각 component는 AWS name 또는 identifier를
사용할 수 있습니다. `auto`, `properties`, `yaml`, `json` 형식을 지원하고,
`prefix`는 decode 이후 적용합니다. JSON/YAML 값은 Spring property key로
flatten합니다.

```yaml
bluetape4k:
  aws:
    app-config:
      enabled: true
      region: ap-northeast-2
      endpoint-override: http://localhost:2772
      fail-fast: true
      refresh-interval: 30s       # 생략/null이면 startup 로딩만 수행
      required-minimum-poll-interval: 15s
```

loader는 `StartConfigurationSession`을 한 번 호출한 뒤 각 응답의 최신 token으로
`GetLatestConfiguration`을 호출합니다. 빈 응답은 마지막 map을 유지하면서 token을
전진시킵니다. decode 또는 transport 오류가 나도 마지막 정상 map을 유지하며,
transport/session 오류는 session을 버리고 제한된 full jitter로 재시도합니다.
payload는 1 MiB, flatten 깊이 32, property 10,000개로 제한합니다. token,
응답 body, 원격 identifier는 로그에 기록하지 않습니다.

`refresh-interval`을 명시하지 않으면 reload는 비활성입니다. context 하나가 제한된
scheduler 하나를 소유하고 AppConfig resource마다 fixed-delay self-rescheduling
task 하나만 실행합니다. 초기 ConfigData 로딩이 끝나면 bootstrap client를 닫고,
runtime client는 application context가 소유합니다. 종료할 때 새 예약을 막고
task를 취소·drain한 다음 runtime client보다 먼저 scheduler를 멈춥니다. `Environment`는 최신 map을 읽지만 `@Value` field와
`@ConfigurationProperties` instance는 자동 rebind하지 않습니다. 이 모듈은
`Spring Cloud Context`, `RefreshScope`, event bus를 추가하지 않습니다.

AppConfig Data 권한에는 다음 service action이 필요합니다. 이 API에는 resource ARN
형식이 없으므로 IAM statement는 `Resource: "*"`를 사용해야 하며, role boundary,
organization policy, network control로 account/region과 workload 범위를 제한하세요.

```json
{
  "Action": [
    "appconfig:StartConfigurationSession",
    "appconfig:GetLatestConfiguration"
  ],
  "Resource": "*"
}
```

Long poll은 AppConfig Data request를 소비하므로 비용과 poll interval을 함께
검토하세요. local sidecar가 더 적합하면 AWS AppConfig Agent를 선택할 수 있지만,
이 모듈이 Agent를 설치하거나 관리하지는 않습니다. Floci/LocalStack이 이 API를
제공한다고 가정하지 말고 fake session contract로 결정론적 테스트를 수행하세요.
`BLUETAPE4K_APPCONFIG_REAL_SMOKE=true`와 명시적인 AWS identifier·credential이
모두 있을 때만 real smoke를 실행합니다.

### Import precedence

| 상황 | 결과 |
| --- | --- |
| 하나의 comma-separated 또는 YAML list에서 뒤에 선언한 항목 | 뒤 import가 앞 값을 override합니다. |
| profile-specific document | Spring Boot가 profile document를 선택하며 resolver는 원격 profile suffix를 붙이지 않습니다. |
| import한 데이터와 import를 선언한 document | import한 데이터가 선언 document보다 우선합니다. |
| legacy `EnvironmentPostProcessor` | refresh나 기존 property-source 순서가 필요하면 계속 사용합니다. |

### Failure policy

| 조건 | 필수 import | `optional:` import |
| --- | --- | --- |
| backend별 not-found | startup 실패 | import를 건너뜁니다. |
| 인증, credential, network, parse 또는 `SecretString` 없음 | startup 실패 | startup 실패입니다. |
| `bluetape4k.aws.enabled=false` 또는 backend 비활성 | client·network 호출 없는 빈 no-op source | 같은 동작입니다. |

서비스별 region과 endpoint override가 공유 AWS 기본값보다 우선합니다. Web
Identity를 활성화하면 STS와 설정한 role ARN, session name, 읽을 수 있는 token
file이 모두 필요합니다. 설정이 잘못되면 default credential chain으로 우회하지 않고
fail-closed합니다. ConfigData startup client는 application bean customizer를 자동으로
찾지 않습니다. 필요하면 `BootstrapRegistryInitializer`에서
`AwsSyncClientCustomizer`를 명시 등록하세요.

### Legacy source에서 migration

| 요구 사항 | 권장 경로 |
| --- | --- |
| startup 때 원격 값을 한 번 읽기 | `spring.config.import` ConfigData |
| startup 이후 값 갱신 | 기존 `EnvironmentPostProcessor` 속성 |
| 기존 legacy property-source winner 유지 | 기존 `EnvironmentPostProcessor` 속성 |
| 없는 optional backend source만 건너뛰기 | 해당 location 앞에 `optional:` 추가 |

## Graceful shutdown

새 요청을 막고 listener polling을 멈춘 다음 설정한 timeout까지 handler를 기다립니다. 그 뒤 소유한 서비스 client와 database pool 순서로 닫으세요. 같은 순서를 테스트에서도 검증해야 합니다.

## Extended Client 종료와 rollback

`SqsExtendedClientLifecycle`은 관리되는 AWS client보다 먼저 실행됩니다.
drain timeout은 `shutdown-drain-timeout-seconds`와 Spring shutdown phase 예산의
범위 안에 있어야 합니다. timeout이 나면 client를 닫지 않고 실행 중 상태와
제한된 진단을 보존하므로 명시적인 stop 재시도를 수행할 수 있습니다.

rollback coordinator는 producer 비활성화, legacy consumer 중지, extended
작업 drain, 최대 visibility/retry window 동안 두 번의 empty raw probe 순서를
지킵니다(`max=1`, `visibility=0`, `wait=0`). malformed 또는 소진된
`RedrivePolicy`/DLQ budget은 거부하고, quarantine pointer를 inline message로
rehydrate한 뒤 count와 idempotency를 검증해야 legacy consumer를 시작합니다.
pointer가 다시 나타나도 전체 rollback deadline은 늘어나지 않습니다.
`DEADLINE_EXCEEDED` 또는 `REDRIVE_BUDGET_EXHAUSTED`는
`ROLLBACK_BLOCKED`로 남기며 extended pointer queue에서 `@SqsListener`를
시작하지 않습니다.

최소 권한 정책은 대상 queue와 payload prefix에만 한정합니다.

```json
{
  "Action": ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage"],
  "Resource": "arn:aws:sqs:ap-northeast-2:123456789012:orders"
}
```

`s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`는
`arn:aws:s3:::orders-extended-payloads/bluetape4k/sqs/orders/*`에만 추가하세요.
암호화 정책은 정확한 CMK ARN과 encryption-context 조건의
`kms:GenerateDataKey`, `kms:Decrypt`가 추가로 필요합니다. wildcard 또는
다른 bucket/key/CMK identity는 설정 검증에서 거부합니다.

## 운영 점검표

- 배포 환경과 region·endpoint가 맞는가
- credentials가 최소 권한이며 교체되는가
- 활성화한 통합에 필요한 서비스 SDK JAR이 있는가
- 재시도 예산이 제한되어 있는가
- metric·로그에 비밀 값과 높은 cardinality 식별자가 없는가
- 로컬 기본 emulator는 Floci이고 LocalStack은 명시적 fallback인가
- 외부 publisher latency·cleanup telemetry와 heap/throughput 측정은 후속 이슈
  #515가 담당하며 Extended Client 완료 근거로 포함하지 않았는가

## 근거 자료

- [SQS listener container](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/SqsMessageListenerContainer.kt)
- [Micrometer SQS interceptor](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sqs/MicrometerSqsListenerInterceptor.kt)
- [Secrets environment processor](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/secretsmanager/SecretsManagerEnvironmentPostProcessor.kt)
