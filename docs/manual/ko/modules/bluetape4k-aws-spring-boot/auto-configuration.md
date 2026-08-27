---
title: 자동 설정
description: 조건부 AWS 서비스 bean, properties와 back-off 규칙을 설명합니다.
manualId: bluetape4k-aws-spring-boot
chapterId: auto-configuration
---

# 자동 설정

Spring 모듈은 조건부 자동 설정을 사용합니다. 서비스 SDK 클래스와 활성화 설정이 있을 때만 해당 통합이 나타납니다. 덕분에 라이브러리는 여러 서비스를 지원하면서도 애플리케이션 런타임 classpath에는 선택한 서비스만 남길 수 있습니다.

## 의존성 경계

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.aws:bluetape4k-aws-spring-boot")
    implementation("software.amazon.awssdk:s3")
}
```

애플리케이션은 중앙 BOM 버전과 서비스 SDK만 고릅니다. AWS 저장소 라이브러리 버전을 따로 선택하지 않습니다.

## 공통 기본값과 서비스별 override

`bluetape4k.aws` 아래의 `AwsProperties`가 enabled, region, endpoint override, 선택적인 web identity credentials를 제공합니다. 서비스별 properties가 공통 기본값보다 우선합니다. 서명 scope에는 region이 필요하므로 endpoint override를 사용할 때도 region을 설정해야 합니다.

SNS에서는 `bluetape4k.aws.sns.enabled`가 전체 자동 설정을 제어하며 기본값은
`true`입니다. 애플리케이션 bean이 없으면 모듈이 `SnsTopicArnCache`,
`SnsTopicArnResolver`, coroutine template을 등록합니다.
`topic-arn-cache.enabled`의 기본값은 `true`이고 bounded cache는 256 entry와
5분 TTL을 사용합니다. `false`로 설정해도 영속 entry만 끄고 topic별
single-flight는 유지합니다. 같은 계정 ARN 검증을 사용하려면 `account-id`를
설정하세요. account ID가 없으면 명시적 ARN은
`allow-cross-account-topic-arn=true`를 의도적으로 켜지 않는 한 거부됩니다.
명시적 ARN 검증에는 wildcard와 미확인 region을 막기 위한 유효한 `region`도
필요합니다.
사용자 정의 cache/resolver bean은 범위를 좁힌 구성 override일 뿐 동작을
보존하는 rollback 경로는 아닙니다. rollback에는 custom `SnsOperations` 구현을
제공하거나 last-known-good artifact를 재배포하세요. 전체 SNS bean을 끄려면
`enabled=false`를 사용합니다. 조회 실패 로그에는 hash 처리한 scope/topic 차원과
exception type만 기록됩니다.
AWS client customizer는 명시적으로 설정한 SNS endpoint와 region identity를
기본값 적용 후 바꾸면 안 됩니다. 설정한 region이 없으면 resolver가 AWS SDK
provider chain이 최종 선택한 region을 scope로 사용합니다. identity를 바꾸는
customizer가 의도된 경우 custom `SnsTopicArnResolver`를 함께 제공하세요.
endpoint 또는 region을 확인할 수 없는 custom client도 fail-fast하며 명시적인
resolver가 필요합니다. `SnsCoroutinesTemplate`을 직접 생성할 때는 client의
endpoint/region이 `SnsProperties`와 일치해야 하며, 다른 client나 검사할 수 없는
client에는 resolver 주입 생성자를 사용하세요.

## Modulith event 자동 설정 (미출시/develop)

`AwsModulithEventsAutoConfiguration`은 기존 기능에 영향을 주지 않는 추가 기능이며
기본값은 비활성입니다. Spring Modulith의 `EventExternalizationTransport`,
`EventSerializer`, `EventExternalizerModuleListener` 세 class가 모두 있고
`bluetape4k.aws.modulith.events.enabled=true`일 때만 로드됩니다.

producer와 consumer 방향은 서로 독립적으로 opt-in합니다.

| 조건 | 결과 |
| --- | --- |
| root 비활성 또는 Modulith class 없음 | adapter properties, transport, consumer bean을 만들지 않습니다. |
| `producer.enabled=true` | registry, serializer, 하나 이상의 논리 target, target service별 operations capability가 필요합니다. |
| SNS target만 사용 | SNS publisher만 만들며 SQS operations나 SDK class는 필요하지 않습니다. |
| SQS target만 사용 | FIFO key와 message attribute를 버리지 않도록 `SqsFullRequestOperations`가 필요합니다. |
| `consumer.enabled=true`, `source-mode=DIRECT` | SQS operations, registry, serializer, externalization configuration, queue name이 필요하고 명시적으로 끄지 않으면 redrive policy도 요구합니다. |
| `consumer.enabled=true`, `source-mode=SNS` | DIRECT 요구 사항에 `sns-message-manager`, `SnsHttpMessageVerifier`, 비어 있지 않은 정확한 TopicArn allowlist가 추가됩니다. |

애플리케이션이 `EventExternalizationTransport`를 제공하면 기본 producer가 back-off합니다.
`AwsModulithEventIdempotencyStore` bean은 기본 in-memory store를 대체합니다. custom
`SnsHttpMessageVerifier`는 SNS verification 자동 설정이 만든 verifier를 대체합니다.
각 경계는 독립적이므로 custom outbound transport를 제공해도 inbound consumer는
비활성화되지 않습니다.

기본 consumer 하나는 application context 하나에서
`bluetape4k.aws.modulith.events.consumer.queue` 하나와 `source-mode` 하나에 바인딩됩니다.
여러 queue나 DIRECT/SNS 혼합 소비가 필요하면 context를 분리하거나 애플리케이션 소유
listener를 사용하세요. 잘못된 교차 property 조합은 첫 message까지 미루지 않고
`BT4K-MOD-101`로 startup을 실패시킵니다.

## Back-off는 정상 동작이다

예상한 bean이 없다면 수동 bean부터 추가하지 말고 condition report를 확인하세요. 흔한 원인은 `compileOnly` 서비스 SDK 누락, disabled property, 또는 애플리케이션이 같은 타입의 bean을 제공해 자동 설정이 물러난 경우입니다.

## Testcontainers ServiceConnection

Floci와 LocalStack 테스트에서는 endpoint와 credentials를
`DynamicPropertySource`로 주입하던 코드를 named Spring Boot
`@ServiceConnection`으로 옮깁니다. Boot 4.1 annotation은 하나의 service name을
받으며 테스트 classpath에는 선택적 dependency alias를 추가합니다.

```kotlin
testImplementation(libs.spring.boot.testcontainers)
testImplementation(bt4k.bluetape4k.testcontainers)

@Container
@ServiceConnection(name = "s3")
val floci: FlociServer = FlociServer.Launcher.floci
```

details에는 endpoint, region, 테스트 credentials만 들어갑니다. 선택적 의존성이나
annotation이 없으면 기존 properties-only fallback이 계속 동작합니다.
`bluetape4k.aws.emulator`는 backend launcher를 선택할 뿐 resource URL을
제공하지 않습니다. 이름 없는 `@ServiceConnection`은 명시적인 all-services
opt-in이며 named 선언과 함께 사용하지 않습니다.

factory는 애플리케이션 resource를 만들지 않습니다. fixture가 SQS queue URL,
SNS topic ARN, DynamoDB table name, Kinesis stream name을 만들고 소유한 literal만
정리합니다. S3 테스트는 하나의 bucket에서만 실행하고 bucket과 object key에
`owner-token`을 넣습니다. `wildcard` 또는 외부 literal은 AWS 호출 전에
거부합니다. lifecycle 순서는 fixture `cleanup`, application context close,
Testcontainers teardown입니다. cleanup 오류는 secret-free 형태로 바꾸어
suppressed 처리하고 cancellation은 다시 전파합니다. optional factory
dependency가 없으면 조용히 credentials를 바꾸지 말고 `FACTORY_LINKAGE` 오류로
실패시킨 뒤 테스트 classpath를 고치거나 annotation을 제거하세요.

## 사용자 정의

region과 endpoint 설정만으로 부족하면 제공된 client builder customization hook을 사용하세요. 서비스 bean마다 나중에 손대기보다 한 곳에서 builder를 조정하는 편이 안전합니다.

## 시작 단계 검증

잘못된 endpoint·region 조합, queue 설정, pool 크기, 함께 쓸 수 없는 credentials mode는 시작할 때 실패시켜야 합니다. 환경 post processor는 원격 설정을 시작 과정에서 한 번 읽고 요청 경로에서는 반복하지 않아야 합니다.

## 근거 자료

- [자동 설정 목록](../../../../../aws-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports)
- [공통 AWS properties](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsProperties.kt)
- [AWS 자동 설정](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/AwsAutoConfiguration.kt)
- [Modulith 자동 설정](../../../../../aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/modulith/AwsModulithEventsAutoConfiguration.kt)
