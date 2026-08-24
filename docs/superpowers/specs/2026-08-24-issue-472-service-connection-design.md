---
title: "Issue #472 AWS emulator Testcontainers ServiceConnection 지원 설계"
issue: 472
epic: 500
status: approved
date: 2026-08-24
---

# Issue #472 AWS emulator Testcontainers ServiceConnection 지원 설계

## 결정 요약

기존 `aws-spring-boot` 모듈에 선택적인 Spring Boot Testcontainers
`@ServiceConnection` 연동을 추가한다. `spring-boot-testcontainers`와
`bluetape4k-testcontainers`는 library runtime에 전이되지 않도록 선택적
compile classpath로만 참조하고, 실제 테스트 소비자가 test classpath에
추가한다.

`AwsEmulatorServer`를 구현한 Floci 또는 LocalStack 컨테이너만 인식하는
서비스별 `ContainerConnectionDetailsFactory`를 등록한다. 한 컨테이너에서
S3, SQS, SNS, DynamoDB, Kinesis용 endpoint, region, access key, secret key
details를 만들 수 있으며, `@ServiceConnection(name = ["s3"])`처럼 서비스
이름으로 범위를 제한할 수 있다.

기존 AWS auto-configuration은 ConnectionDetails를 endpoint/region의 우선
공급원으로 사용하고, 사용자 정의 `AwsCredentialsProvider`와 AWS client
bean은 계속 우선한다. ConnectionDetails가 없으면 현재 properties와
`DefaultCredentialsProvider` 경로를 그대로 사용한다.

ServiceConnection은 리소스 URL이나 리소스 자체를 생성하지 않는다. queue URL,
topic ARN, DynamoDB table name은 기존 테스트 fixture가 명시적으로 만들고
설정한다. S3 통합 테스트는 하나의 소유 bucket 안에서만 동작하며 소유한
객체만 정리한다. 따라서 컨테이너 자동 연결이 권한 범위나 다른 테스트의
리소스를 넓히지 않는다.

## 1. 문제와 현재 근거

Issue #472는 Floci-first AWS emulator를 Spring Boot Testcontainers의
`@ServiceConnection`으로 연결해, 테스트가 endpoint와 credentials를
`DynamicPropertySource`로 반복 선언하지 않도록 요구한다. 수용 기준은
다음과 같다.

- 하나의 `@ServiceConnection` 선언으로 AWS client auto-configuration이
  emulator endpoint를 사용한다.
- 사용하지 않는 service test가 불필요한 container 또는 property를
  시작·생성하지 않는다.
- Floci와 명시적 LocalStack fallback에서 context 및 대표 smoke 테스트가
  통과한다.
- `-Dbluetape4k.aws.emulator` 선택과 충돌하지 않는다.
- 운영 credential/profile 자동 설정이나 Spring Cloud AWS 의존성을
  도입하지 않는다.

현재 `aws-spring-boot`의 S3, SQS, SNS, DynamoDB, Kinesis client
auto-configuration은 각 서비스 properties와 공유 `AwsProperties`를 통해
region/endpoint를 결정하고, `AwsAutoConfiguration`이 기본
`DefaultCredentialsProvider`를 제공한다. AWS 서비스 SDK는 compileOnly
경계에 있으며, 테스트는 `AwsSpringBootTestEmulator`와
`AwsEmulatorServer` 구현체를 사용한다.

공유 testcontainers 모듈의 `AwsEmulatorServer`는 다음 정보를 제공한다.

| 값 | 의미 |
| --- | --- |
| `awsEndpoint` | emulator의 endpoint URI |
| `regionName` | AWS region 문자열 |
| `awsAccessKey` | 테스트용 access key |
| `awsSecretKey` | 테스트용 secret key |

이 인터페이스는 queue/topic/table URL을 알지 못한다. 해당 URL은 서비스 SDK로
리소스를 만든 뒤에만 알 수 있으므로 ServiceConnection의 책임으로 넣지
않는다.

Spring Boot는 `ContainerConnectionDetailsFactory`를 `spring.factories`에서
발견하고, 생성된 ConnectionDetails bean을 기존 connection property보다
우선하는 계약을 제공한다. 근거는 [Spring Boot Testcontainers
문서](https://docs.spring.io/spring-boot/4.0-SNAPSHOT/reference/testing/testcontainers.html),
[ContainerConnectionDetailsFactory API](https://docs.spring.io/spring-boot/4.0/api/java/org/springframework/boot/testcontainers/service/connection/ContainerConnectionDetailsFactory.html),
그리고 [ConnectionDetails 우선순위 release note](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.1-Release-Notes/841ac6d3467b41f1b0538bfdfcc864748818041f)다.

## 2. 목표와 범위 경계

### 목표

1. `@ServiceConnection`으로 Floci/LocalStack endpoint, region, 테스트
   credentials를 AWS auto-configuration에 연결한다.
2. S3, SQS, SNS, DynamoDB, Kinesis를 서비스별 ConnectionDetails 계약으로
   노출한다.
3. ConnectionDetails가 있으면 endpoint/region을 우선하고, custom provider와
   client bean은 교체하지 않는다.
4. `spring-boot-testcontainers`가 없는 애플리케이션의 기존 runtime 동작을
   바꾸지 않는다.
5. named connection, explicit resource fixture, single-bucket 규칙으로
   테스트 범위와 권한을 좁힌다.
6. Floci-first, LocalStack fallback, AOT-safe container 선언을 테스트와
   문서로 고정한다.

### 범위 밖

- 새로운 AWS emulator 이미지 또는 `AwsEmulatorServer` 구현
- queue/topic/table/bucket 자동 생성, URL 발견, 전체 리소스 정리
- 운영 AWS credential/profile/IAM 자동 구성
- Spring Cloud AWS 의존성 또는 구현 복사
- MiniStack을 acceptance backend로 승격
- AWS SDK service dependency의 runtime 전환
- 실제 AWS 계정에 쓰는 통합 테스트
- release, publish, tag, PR merge와 같은 외부 side effect

## 3. 대안과 선택

### A안 — 기존 `aws-spring-boot`에 선택적 통합 (선택)

같은 artifact가 ConnectionDetails 계약과 factory를 제공한다. Testcontainers
관련 dependency는 compileOnly로 제한하고, 사용자는 test scope에 필요한
Spring Boot Testcontainers 및 bluetape4k testcontainers 모듈을 추가한다.

장점은 현재 auto-configuration과 같은 version train에서 동작하고 새 모듈,
BOM, publish 규칙을 만들지 않아도 된다는 점이다. 단점인 선택적 classpath
linkage는 factory 등록·FilteredClassLoader 테스트로 검증한다. Testcontainers
클래스가 없는 일반 runtime에서 factory discovery를 강제로 실행하지 않으며,
기존 auto-configuration import에는 testcontainers factory 구현을 직접
추가하지 않는다.

### B안 — `DynamicPropertySource` 테스트 helper만 제공

테스트마다 endpoint와 credentials를 property로 주입하는 방식이다. 변경량은
작지만 Spring Boot ConnectionDetails 우선순위와 표준 `@ServiceConnection`
재사용성을 제공하지 못하고, Issue #472의 핵심 acceptance를 충족하지
못한다. 선택하지 않는다.

### C안 — 별도 `aws-spring-boot-testcontainers` 모듈

Testcontainers dependency 경계는 가장 깨끗하지만 새 module registration,
BOM alias, publish, CI와 문서 경로가 추가된다. 현재 Epic 하위 이슈에서
필요한 범위를 넘어가므로 선택하지 않는다. A안의 optional linkage가 실제
consumer classpath에서 실패한다는 검증 결과가 있을 때 후속 분리안으로
재검토한다.

## 4. 연결 계약과 데이터 흐름

### 4.1 ConnectionDetails 모델

ConnectionDetails 계약은
`io.bluetape4k.aws.spring.connection` package에 둔다. 서비스별 interface는
public non-null API로 고정하고, factory와 immutable 구현체의 파일명과
signature도 구현 계획과 compile contract에서 고정한다.

```kotlin
interface AwsServiceConnectionDetails : ConnectionDetails {
    val endpoint: URI
    val region: String
    val accessKey: String
    val secretKey: String
}

interface S3ConnectionDetails : AwsServiceConnectionDetails
interface SqsConnectionDetails : AwsServiceConnectionDetails
interface SnsConnectionDetails : AwsServiceConnectionDetails
interface DynamoDbConnectionDetails : AwsServiceConnectionDetails
interface KinesisConnectionDetails : AwsServiceConnectionDetails
```

`endpoint`는 absolute `URI`, `region`, `accessKey`, `secretKey`는 빈 문자열이
아닌 `String`이다. `accessKey`와 `secretKey`는 연결에 필요한 값이지만
diagnostic·예외·metric tag·serialization에는 절대 포함하지 않는다. 구현체는
data class의 기본 `toString()`을 사용하지 않고 secret을 `[REDACTED]`로
표시하는 `toString()`을 제공한다. public KDoc에도 이 비노출 계약을 적고,
toString/로그/직렬화 redaction 테스트로 고정한다.

실제 구현 bean은 `AwsEmulatorServer`의 현재 endpoint, region, access key,
secret key를 immutable 값으로 복사한다. container가 멈추거나 재시작한 뒤
details를 갱신하지 않는다. 활성 ApplicationContext에서 container를
재시작하는 것은 지원하지 않으며, 재시작이 필요하면 먼저 context를 닫고
새 container/context를 만들어야 한다. container lifecycle은 Spring Boot와
Testcontainers가 소유한다.

### 4.2 Factory 등록과 이름 범위

Spring Boot `4.0.x` API를 기준으로 서비스별 factory를 다음 정확한 형태로
구현하고 다음 key로 `META-INF/spring.factories`에 등록한다. `ConnectionDetails`,
`ContainerConnectionDetailsFactory`, `ContainerConnectionSource`의 import는
각각 `org.springframework.boot.autoconfigure.service.connection.ConnectionDetails`,
`org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory`,
`org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource`를
사용한다. 상세 구현체는
`ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>`를
상속하고, `S3ContainerConnectionDetails`, `SqsContainerConnectionDetails`,
`SnsContainerConnectionDetails`, `DynamoDbContainerConnectionDetails`,
`KinesisContainerConnectionDetails`라는 정확한 이름으로 둔다.

```kotlin
internal class S3ContainerConnectionDetailsFactory :
    ContainerConnectionDetailsFactory<Container<*>, S3ConnectionDetails>(
        "s3",
        "software.amazon.awssdk.services.s3.S3Client",
    ) {
    override fun getContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): S3ConnectionDetails? = S3ContainerConnectionDetails(source)
}
```

나머지 factory도 동일한 generic과 override를 사용하고, required class name만
다음처럼 고정한다.

| factory | service name | required class name | detail implementation |
| --- | --- | --- | --- |
| `S3ContainerConnectionDetailsFactory` | `s3` | `software.amazon.awssdk.services.s3.S3Client` | `S3ContainerConnectionDetails` |
| `SqsContainerConnectionDetailsFactory` | `sqs` | `software.amazon.awssdk.services.sqs.SqsClient` | `SqsContainerConnectionDetails` |
| `SnsContainerConnectionDetailsFactory` | `sns` | `software.amazon.awssdk.services.sns.SnsClient` | `SnsContainerConnectionDetails` |
| `DynamoDbContainerConnectionDetailsFactory` | `dynamodb` | `software.amazon.awssdk.services.dynamodb.DynamoDbClient` | `DynamoDbContainerConnectionDetails` |
| `KinesisContainerConnectionDetailsFactory` | `kinesis` | `software.amazon.awssdk.services.kinesis.KinesisClient` | `KinesisContainerConnectionDetails` |

각 detail 구현체의 constructor는
`(ContainerConnectionSource<Container<*>>)` 하나이며, Boot protected nested
`ContainerConnectionDetails<Container<*>>(source)` constructor를 호출한다.
`getContainer()`로 얻은 값은 먼저 `FlociServer` 또는 `LocalStackServer`인지
확인하고, `endpoint`, `region`, `accessKey`, `secretKey`를 불변 값으로
복사한다. unsupported container는 `null`을 반환하고, 지원 container의
malformed endpoint/region/credential은 `AwsServiceConnectionConfigurationException`으로
실패시키는 한 가지 규칙을 사용한다. `null`과 예외를 임의로 섞지 않는다.

`getContainerConnectionDetails`의 인자는 `ContainerConnectionSource<C>`이고
반환값은 nullable이다. 구현체는 Boot의 protected
`ContainerConnectionDetails<C>(source)`를 상속해 `getContainer()`으로 실제
container를 확인한 뒤 `AwsEmulatorServer`이면서 정확히 `FlociServer` 또는
`LocalStackServer`인 경우에만 details를 완성한다. MiniStack과 임의로
`AwsEmulatorServer`를 구현한 container는 fail closed로 거부한다. 각 factory는
서비스 SDK class name을 required class name으로 전달해 해당 SDK가 없으면
details를 만들지 않는다. 이 signature와 factory discovery는 compile 및
`spring.factories` contract test로 검증한다.

```properties
org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory=\
io.bluetape4k.aws.spring.connection.S3ContainerConnectionDetailsFactory,\
io.bluetape4k.aws.spring.connection.SqsContainerConnectionDetailsFactory,\
io.bluetape4k.aws.spring.connection.SnsContainerConnectionDetailsFactory,\
io.bluetape4k.aws.spring.connection.DynamoDbContainerConnectionDetailsFactory,\
io.bluetape4k.aws.spring.connection.KinesisContainerConnectionDetailsFactory
```

각 factory는 다음 순서로 판단한다.

1. source가 실제 `Container<?>`인지 확인한다.
2. container가 `FlociServer` 또는 `LocalStackServer`가 아니면 `null`로
   종료한다. `AwsEmulatorServer`만 구현한 임의 타입과 MiniStack은 신뢰하지
   않는다.
3. factory의 서비스 이름(`s3`, `sqs`, `sns`, `dynamodb`, `kinesis`)과
   `@ServiceConnection(name = ...)`가 불일치하면 `null`을 반환한다.
4. 이름이 없으면 해당 서비스 details를 만든다.
5. endpoint가 absolute URI가 아니거나 region/credential 값이 비어 있으면
   명확한 configuration 오류로 실패한다. 빈 값으로 AWS client를 만들지
   않는다.

따라서 다음 두 형태를 지원한다.

```kotlin
@Testcontainers
class S3ServiceConnectionTest {
    companion object {
        @JvmField
        @Container
        @ServiceConnection(name = ["s3"])
        val floci: FlociServer = FlociServer.Launcher.floci
    }
}
```

```kotlin
@Testcontainers
class AllAwsServicesConnectionTest {
    companion object {
        @JvmField
        @Container
        @ServiceConnection
        val floci: FlociServer = FlociServer.Launcher.floci
    }
}
```

이름 없는 선언은 명시적인 all-services opt-in으로만 사용하며 지원 가능한
서비스 details를 모두 제공한다. 리소스를 생성하거나 AWS API를 호출하지
않는다. 보안·성능 기본값은 named connection이고, 서비스별 context 테스트는
named connection과 서비스 `enabled` 설정을 함께 사용해 불필요한 details/client
경로를 만들지 않는다. 같은 서비스에 details bean이 둘 이상인 조합(unnamed와
named 동시 선언, 같은 이름의 다중 container 포함)은 client나 resource fixture를
만들기 전에 context startup을 실패시켜 endpoint 혼선을 막는다.

### 4.3 Auto-configuration 적용 순서

각 서비스 client auto-configuration의 기본값 해석을 다음 우선순위로
고정한다.

```text
service ConnectionDetails endpoint/region
    > service properties endpoint/region
    > shared AwsProperties endpoint/region
    > AWS SDK default
```

credentials는 다음 우선순위를 사용한다.

```text
user AwsCredentialsProvider bean
    > AwsServiceConnectionDetails static credentials
    > existing DefaultCredentialsProvider
```

`AwsAutoConfiguration`의 기본 provider bean은 optional details를 확인하되,
`@ConditionalOnMissingBean(AwsCredentialsProvider::class)` 계약을 유지한다.
따라서 사용자가 provider를 제공하면 emulator static credential로 대체하지
않는다. service details는 각 client builder의 endpoint/region resolver에
주입하고, 기존 `applyAwsDefaults`와 custom client back-off를 보존한다.

`-Dbluetape4k.aws.emulator`는 어떤 emulator launcher를 사용할지 결정하는
테스트 선택자다. ServiceConnection이 함께 있으면 실제로 선언한 Floci 또는
LocalStack container의 details가 endpoint/credentials source가 되며, 해당
system property가 지정되지 않은 기존 properties-only 테스트는 이전 경로를
그대로 사용한다.

각 service auto-configuration의 client bean method는 해당 details
`ObjectProvider`를 받고, 기존 `@ConditionalOnMissingBean`을 유지한다.
`AwsAutoConfiguration`의 provider method에는
`ObjectProvider<AwsServiceConnectionDetails>`를 전달하되 `getIfAvailable()`로
임의의 subtype을 선택하지 않는다. `AwsServiceConnectionCredentialsResolver`가
`orderedStream().toList()`를 materialize하고, 모든 details의
`(accessKey, secretKey)` tuple이 하나로 일치할 때만 하나의 불변 static
credential 값을 만든다. unnamed connection에서 5개 subtype bean이 생겨도
동일 tuple이면 한 번만 사용하고, named connection 여러 개가 서로 다른
credential을 노출하면 secret 없이 후보 수와 service name만 담은
`AwsServiceConnectionConfigurationException`으로 startup을 중단한다. 사용자
`AwsCredentialsProvider`가 있으면 조건에서 먼저 back-off한다. 서비스별
`@ConditionalOnClass`와 `@ConditionalOnProperty`는 기존 클래스에 유지하고,
details가 없을 때는 properties-only resolver와 `DefaultCredentialsProvider`로
돌아간다. 이 조건·bean creation 순서는 5개 서비스 각각의 context matrix로
검증한다.

ServiceConnection annotation이 있는 canonical test에서 factory linkage가
없어 details가 생성되지 않으면 properties/default provider로 조용히 전환하지
않고 contract guard가 명확한 startup 오류를 낸다. annotation을 사용하지 않는
properties-only context에서만 기존 fallback을 허용한다.

public `AwsServiceConnectionDetails` 구현·소비 계약은 다음과 같다. consumer는
details를 직접 구현할 수 있지만 `endpoint`는 absolute URI이고 네 문자열
accessor는 non-blank여야 한다. `secretKey` accessor는 client builder 경계에서만
사용하고 로그·예외·metric·serialization에 전달하지 않는다.

`io.bluetape4k.aws.spring.connection` package의 public
`AwsServiceConnectionConfigurationException`은 다음 고정된 오류 계약을
사용한다.

```kotlin
class AwsServiceConnectionConfigurationException(
    val reason: Reason,
    val serviceNames: Set<String>,
    val candidateCount: Int,
    cause: Throwable? = null,
) : IllegalStateException(/* secret 없는 message */, cause) {
    enum class Reason { FACTORY_LINKAGE, DUPLICATE_DETAILS, CREDENTIAL_CONFLICT, MALFORMED_DETAILS }
}
```

`reason`, `serviceNames`, `candidateCount`만 stable consumer contract이며
`message`는 secret 없는 진단용 문자열로만 취급하고 parsing 대상이 아니다.
message와 public fields에는 service name, reason, candidate count만 포함하며
endpoint credential 값은 포함하지 않는다. linkage 오류는 두 optional test
dependency를 추가하거나 annotation을 제거해 해결하고, duplicate 오류는
service당 하나의 container만 선언하며, credential conflict는 하나의 emulator
credential 값을 사용하고, malformed 오류는 Floci/LocalStack의 non-blank
details를 고쳐 해결한다. 이 예외는 annotation 없는 properties-only fallback과
혼동하지 않는다.

기존 consumer의 migration은 다음 표를 따른다.

| 기존 경로 | ServiceConnection 경로 | 유지되는 경계 |
| --- | --- | --- |
| `DynamicPropertySource`로 endpoint/region/credentials 주입 | named `@ServiceConnection(name = ["s3"])`를 기본 사용 | resource URL은 fixture가 생성·주입·정리 |
| `-Dbluetape4k.aws.emulator=floci` 또는 `localstack` selector | 동일 selector로 launcher backend만 선택하고 details가 실제 container에서 읽음 | annotation과 selector는 충돌하지 않으며, annotation 없는 경우에만 properties fallback |
| unnamed test container | unnamed `@ServiceConnection`은 명시적 all-services opt-in | 불필요한 service client/details와 resource 자동 생성 금지 |
| optional dependency 누락 | dependency를 test classpath에 보강하거나 annotation 제거 | factory linkage 누락은 guard startup failure, 조용한 default provider 전환 금지 |

## 5. 의존성 및 classpath 경계

`gradle/libs.versions.toml`에
`spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }`
alias를 추가하고, `aws-spring-boot/build.gradle.kts`에는 다음을 compileOnly로만
추가한다.

- `org.springframework.boot:spring-boot-testcontainers`
- `io.github.bluetape4k:bluetape4k-testcontainers` (`bt4k.bluetape4k.testcontainers`)

`bt4k.bluetape4k.testcontainers`는 이 저장소에서 이미 사용하는 중앙 catalog
alias이며 새 project dependency나 임의 버전 문자열을 만들지 않는다. 동일
alias를 `aws-spring-boot`와 세 Spring Boot example consumer의 test classpath에서
사용해 linkage를 하나로 고정한다.

consumer 적용 대상은 다음 세 파일로 고정한다.

- `examples/aws-spring-boot-s3-examples/build.gradle.kts`
- `examples/aws-spring-boot-sqs-examples/build.gradle.kts`
- `examples/aws-spring-boot-dynamodb-examples/build.gradle.kts`

세 consumer는 `testImplementation(libs.spring.boot.testcontainers)`와 기존
`testImplementation(bt4k.bluetape4k.testcontainers)`를 함께 사용한다. `aws-spring-boot`
본체는 `compileOnly(libs.spring.boot.testcontainers)`와
`compileOnly(bt4k.bluetape4k.testcontainers)`만 추가하고, 기존
`testImplementation` extendsFrom으로 test compile classpath를 구성한다.

실제 consumer test는 central catalog/BOM을 통해 다음 test dependency를
선언한다. 이 artifact들은 library runtime POM으로 전이되지 않는다.

```kotlin
testImplementation(libs.spring.boot.testcontainers)
testImplementation(bt4k.bluetape4k.testcontainers)
testImplementation(libs.testcontainers.junit.jupiter)
```

library production runtime에는 Testcontainers가 전이되지 않는다. AWS service
SDK compileOnly 정책도 유지한다. `spring-boot-testcontainers`가 없는
FilteredClassLoader context에서 기존 S3/SQS/SNS/DynamoDB/Kinesis auto-config가
동일하게 동작하는지 검증한다. `spring-boot-testcontainers`와
`bluetape4k-testcontainers`의 present/각각 absent/동시 absent 조합을 모두
검증한다. absent 조합에서 annotation을 사용하지 않은 properties-only
fallback만 허용하고, canonical ServiceConnection test는 두 dependency가 모두
있는 test classpath에서 details bean이 실제 생성되는지 먼저 assert한다. annotation
이 있는 상태에서 factory linkage가 빠진 조합은 contract guard의 명확한 startup
오류를 기대하며 default provider fallback을 성공으로 세지 않는다.

catalog preflight는 alias 해석, 네 모듈의 `testRuntimeClasspath`, duplicate
coordinate/version, 그리고 `:bluetape4k-aws-spring-boot:outgoingVariants` 또는
POM의 Testcontainers runtime 전이 부재를 확인한다. 이 이슈에는 settings/BOM
module registration, release, publish, tag, merge를 포함하지 않으며 release-train
소유자와 별도 runbook follow-up만 기록한다. 현재 개발 train에서는 버전
변경이나 release note를 만들지 않는다.

preflight는 다음 명령과 기대 결과로 고정한다.

```bash
./gradlew :bluetape4k-aws-spring-boot:dependencies \
  --configuration runtimeClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:outgoingVariants \
  --no-daemon --max-workers=1
./gradlew :aws-spring-boot-s3-examples:dependencies \
  --configuration testRuntimeClasspath --no-daemon --max-workers=1
./gradlew :aws-spring-boot-sqs-examples:dependencies \
  --configuration testRuntimeClasspath --no-daemon --max-workers=1
./gradlew :aws-spring-boot-dynamodb-examples:dependencies \
  --configuration testRuntimeClasspath --no-daemon --max-workers=1
./gradlew :bluetape4k-aws-spring-boot:generatePomFileForBluetapeAwsPublication \
  --no-daemon --max-workers=1
if rg -n 'testcontainers|spring-boot-testcontainers' \
  aws-spring-boot/build/publications/bluetapeAws/pom-default.xml; then
  echo 'Testcontainers leaked into runtime POM' >&2
  exit 1
fi
```

첫 명령과 `outgoingVariants`에는 Testcontainers runtime dependency가 없어야
하고, 세 example의 `testRuntimeClasspath`에는 두 optional test artifact와
`testcontainers-junit-jupiter`가 해석되어야 한다. POM 검색에 결과가 나오면
실패다.

## 6. 리소스·권한·lifecycle 안전 경계

### 6.1 리소스 URL은 명시적 fixture 소유

ConnectionDetails는 endpoint/region/credentials만 제공한다. 다음 값은 각
통합 테스트가 SDK 호출로 만든 뒤 기존 property fixture에 넣는다.

| 서비스 | 명시적으로 관리하는 값 |
| --- | --- |
| S3 | 하나의 test bucket 이름과 소유 object key |
| SQS | 생성한 queue URL |
| SNS | 생성한 topic ARN |
| DynamoDB | 생성한 table 이름 |
| Kinesis | 생성한 stream 이름 |

S3 테스트는 bucket wildcard, bucket enumeration, 다른 test bucket 접근을
허용하지 않는다. fixture는 테스트별 고유 owner token을 bucket/object 이름에
포함하고, request와 cleanup 직전에 owner token과 literal bucket/key를
검증한다. cleanup은 `try/finally`에서 test가 생성한 object와 bucket에
한정하며, foreign bucket/key 또는 wildcard 입력이면 AWS 호출 전에 실패한다.
SQS queue, SNS topic, DynamoDB table, Kinesis stream도 같은 소유권 규칙을
사용한다. 정상·startup failure·cancellation 모두에서 lifecycle 순서는
`fixture cleanup → application context close → Testcontainers teardown`으로
고정한다. fixture cleanup은 context client가 이미 닫힌 경우에도 사용할 수 있는
immutable details 기반의 독립 cleanup client를 사용하거나, fixture가 생성되지
않은 startup failure에서는 빈 cleanup 단계로 종료한다. cleanup 실패는 primary
cleanup client를 `use`/`finally`로 닫으며 close 실패도 primary failure에
suppressed exception으로 붙인다. 전체 cleanup 실패는 primary failure에
기존 primary failure가 없으면 close 또는 cleanup failure를 새 primary로
승격한다. 정상·startup failure·cancellation 모두에서 이 규칙을 테스트하고,
cancellation은 원래
`CancellationException`을 재전파한다. 이전 details를 재사용하지 않고 container를
중지한 뒤 새 application context를 만들 때만 새 기준값을 생성한다.

### 6.2 Container lifecycle과 AOT

- Testcontainers lifecycle은 `@Container`와 Spring Boot ServiceConnection이
  관리한다. factory가 container를 start/stop하거나 별도 singleton을 만들지
  않는다.
- AOT와 test context 재생성에 안전하도록 위의 `@JvmField` companion static
  declaration을 canonical example로 둔다. `@ServiceConnection`을 붙인
  `@Bean` method는 acceptance 경로에서 금지하고, 해당 선언이 AOT context에서
  details를 만들지 않는 negative test를 둔다.
- `@Bean` method에 `@ServiceConnection`을 붙이는 방식은 현재 AOT 위험이
  있으므로 acceptance path로 사용하지 않는다. 관련 주의점은 [Spring Boot
  이슈 #42851](https://github.com/spring-projects/spring-boot/issues/42851)을
  따른다.
- Floci와 LocalStack 테스트는 공유 Docker 자원 때문에 병렬 실행하지 않는다.
  acceptance 상태 기계는
  `RED → unit/context GREEN → properties-only baseline PASS → Floci PASS →
  LocalStack compatibility` 순서다. Floci가 FAIL, no-match 또는 전부 skipped이면
  즉시 feature FAIL로 판정하고, 다음 명령으로 properties-only rollback 증거를
  남긴 뒤 LocalStack 결과를 acceptance PASS로 승격하지 않는다.

  ```bash
  set -euo pipefail
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests '*AwsServiceConnectionAutoConfigurationTest' \
    -PskipAwsEmulatorTests=true --no-daemon --max-workers=1
  ```

  이 baseline 명령은 신규 factory/catalog 변경을 제거하지 않고도 기존
  properties-only 경로가 PASS인지 확인하는 rollback gate다. baseline PASS 뒤에
  다음 두 backend lane을 순서대로 실행한다.

  ```bash
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.connection.AwsServiceConnectionFlociTest' \
    -Dbluetape4k.aws.emulator=floci --no-daemon --max-workers=1
  ./gradlew :bluetape4k-aws-spring-boot:test \
    --tests 'io.bluetape4k.aws.spring.connection.AwsServiceConnectionLocalStackTest' \
    -Dbluetape4k.aws.emulator=localstack --no-daemon --max-workers=1
  ```

  Floci lane은 `FlociServer` static declaration, LocalStack lane은
  `LocalStackServer` static declaration과 실제 backend type assertion을 각각
  갖는다. Floci 실패 시 LocalStack은 compatibility 참고 결과로만 남긴다.
  MiniStack은 비교 평가 외 acceptance에서 제외한다. 각 lane은 해당 backend
  test의 실제 실행 test 수를 기록하고 canonical S3 round-trip 및 wiring
  assertions가 모두 실행됐는지 확인한다. no-match 또는 전부 skipped인 결과는
  acceptance PASS가 아니다.

## 7. 실패 모드와 관측성

| 실패/경계 | 기대 동작 | 검증 |
| --- | --- | --- |
| `spring-boot-testcontainers` 없음 | factory가 로드되지 않고 기존 auto-config만 동작 | `FilteredClassLoader` context test |
| 일반 `GenericContainer` | concrete Floci/LocalStack 검사에서 `null`, details 미생성 | factory unit test |
| Floci/LocalStack 이외의 `AwsEmulatorServer` | 임의 endpoint를 신뢰하지 않고 fail closed | endpoint trust-boundary test |
| 지원하지 않는 service name | 해당 factory만 back-off | named factory test |
| details endpoint/region/credential 누락 | 빈 값으로 client를 만들지 않고 명확한 startup 오류 | invalid detail test |
| 같은 service details 중복 | 임의 endpoint를 선택하지 않고 context 실패 | duplicate candidate test |
| 여러 service details의 credential 불일치 | 공통 static 기준값을 만들지 않고 context 실패 | credential resolver matrix |
| custom `AwsCredentialsProvider` 존재 | static emulator provider가 back-off | provider precedence test |
| custom AWS client bean 존재 | 기존 `@ConditionalOnMissingBean`으로 back-off | client back-off test |
| 기존 endpoint/region property만 존재 | 현재 properties/default 경로 유지 | regression context test |
| `-Dbluetape4k.aws.emulator=localstack` | LocalStack launcher details만 사용 | sequential LocalStack smoke |
| queue/topic/table URL 미설정 | ServiceConnection이 자동 생성하지 않으며 fixture가 명시적으로 실패 | resource fixture test |
| S3 bucket 범위 위반 시도 | 단일 bucket 밖 요청을 만들지 않음 | single-bucket integration test |
| container 종료/재시작 | details가 lifecycle을 직접 제어하지 않음; context가 종료를 관찰 | lifecycle test |
| context startup failure | 생성된 fixture가 없으면 cleanup을 건너뛰고 partial context close 후 container teardown | startup-failure lifecycle test |
| context close 또는 test cancellation | fixture cleanup을 먼저 수행하고 primary/cancellation exception을 재전파하며 cleanup 실패는 suppressed | cancellation/close lifecycle test |
| container 재시작 후 재사용 | stale details를 재사용하지 않고 새 context에서 새 불변 기준값만 생성 | two-context reinitialization test |
| details secret 노출 시도 | toString·로그·예외·metric tag·serialization에 secret을 포함하지 않음 | redaction contract test |

debug startup diagnostics가 필요한 경우에도 backend, service name, connection
name, endpoint host/port, source type, fallback 또는 guard 사유만 기록한다.
access key와 secret key는 어떤 log/metric/exception field에도 기록하지 않으며,
PENDING 검증은 Section 8의 `command`, `backend`, `timestamp`, `docker context/info`,
`owner`, `result` 증거 형식을 따른다.

Factory는 컨테이너의 service API를 추측하거나 리소스 생성 재시도 루프를
추가하지 않는다. endpoint와 credential 매핑 오류는 조용히 fallback하지 않고
context startup에서 드러나야 한다.

## 8. 테스트 설계

### 단위·계약 테스트

다음 테스트 파일과 식별자를 구현 전에 고정한다.

| 파일 | 필수 계약 |
| --- | --- |
| `AwsServiceConnectionDetailsFactoryTest.kt` | 5개 factory의 exact generic/constructor, Floci·LocalStack allow-list, unsupported source `null`, malformed supported source 예외, named/unnamed matching, `spring.factories` key |
| `AwsServiceConnectionDetailsRedactionTest.kt` | 불변 값 복사, `[REDACTED]` `toString()`, log/exception/metric/serialization secret 부재 |
| `AwsServiceConnectionAutoConfigurationTest.kt` | 5개 service endpoint/region precedence, 공통 credential resolver, duplicate/conflicting credential failure, custom provider/client back-off, `FilteredClassLoader` 및 properties-only matrix |
| `AwsServiceConnectionFlociTest.kt`, `AwsServiceConnectionLocalStackTest.kt` | backend별 canonical `@JvmField`/`@Container`/`@ServiceConnection(name = ["s3"])` compile, 실제 container type assertion, S3 smoke와 명시적 fixture 경계 |

단위/계약 테스트는 다음 순서로 TDD 실행한다.

```bash
# RED: 아직 구현 전이므로 target test class가 없거나 계약 assertion이 실패해야 한다.
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionDetailsFactoryTest' \
  --tests '*AwsServiceConnectionDetailsRedactionTest' \
  --tests '*AwsServiceConnectionAutoConfigurationTest' \
  --no-daemon --max-workers=1

# GREEN: 구현 후 동일 selector가 exact contract를 통과해야 한다.
./gradlew :bluetape4k-aws-spring-boot:test \
  --tests '*AwsServiceConnectionDetailsFactoryTest' \
  --tests '*AwsServiceConnectionDetailsRedactionTest' \
  --tests '*AwsServiceConnectionAutoConfigurationTest' \
  --no-daemon --max-workers=1
```

`aws-spring-boot`의 `Test` task configuration은
`filter.setFailOnNoMatchingTests(true)`를 명시해 selector가 0개를 실행하고
녹색이 되는 경로를 차단한다. 각 GREEN 실행은 Gradle 결과에서 실제 실행한
test class 수를 기록하고, factory/auto/redaction 각 selector의 기대 최소 수와
대조한다. 기대 수 미달이나 no-match는 PASS가 아니다.

`ApplicationContextRunner`는 S3/SQS/SNS/DynamoDB/Kinesis 각각에 대해
details 없음 + service/shared properties, details 우선, custom provider/client
back-off를 확인한다. `FilteredClassLoader`는
`spring-boot-testcontainers`만 없음, `bluetape4k-testcontainers`만 없음, 두
dependency 모두 없음, 두 dependency 모두 있음의 네 조합을 분리한다. 네 조합
중 ServiceConnection annotation을 사용한 테스트에서 factory linkage가 빠진
경우에는 guard startup 오류를 기대하고, annotation 없는 properties-only
조합에서만 fallback 성공을 인정한다.

### Emulator context/smoke

Floci를 기본 backend로 사용하고, 같은 시나리오를 LocalStack fallback에서
순차적으로 실행한다. 각 서비스 시나리오는 named ServiceConnection 하나와
서비스별 명시적 resource fixture를 사용한다.

`AwsServiceConnectionFlociTest.kt`와 `AwsServiceConnectionLocalStackTest.kt`는
각각 `@Testcontainers` companion의 `@JvmField @Container
@ServiceConnection(name = ["s3"])` 정적 선언을 갖고, 실제
`FlociServer`/`LocalStackServer` type을 assert한다. S3는 실제
endpoint/credential로 한 bucket의 read/write smoke를 수행하고,
SQS/SNS/DynamoDB/Kinesis는 각각 fake details context에서 client
endpoint/credential wiring을 확인한다. queue URL, topic ARN, table name, stream
name은 어떤 factory도 만들지 않으며 test fixture가 명시적으로 생성·주입한다.

- S3: 한 bucket 생성, object read/write smoke, 소유 object/bucket만 cleanup
- SQS: queue 생성 후 URL을 `SqsProperties` fixture에 전달
- SNS: topic 생성 후 ARN을 fixture에 전달
- DynamoDB: table 생성 후 table name을 fixture에 전달
- Kinesis: stream 생성 후 stream name을 fixture에 전달

테스트가 실패했을 때 skipped container test를 성공 증거로 보고하지 않는다.
Docker가 없거나 backend가 실행되지 않으면 해당 검증을 `PENDING`으로
기록하고, 실행 가능한 unit/context 검증 결과와 구분한다. evidence에는
`command`, `backend`, `timestamp`, `docker context/info`, `owner`, `result`
필드를 기록하며 `PENDING` lane은 acceptance PASS로 집계하지 않는다.

properties-only 회귀는 다음 5개 서비스 × 2개 조건을 모두 확인한다.

| 서비스 | 조건 A | 조건 B |
| --- | --- | --- |
| S3, SQS, SNS, DynamoDB, Kinesis | details 없음 + service/shared properties | Testcontainers 두 optional dependency filtered + properties/default provider |

각 조합에서 ConnectionDetails bean이 없고, 기존 endpoint/region/provider와
custom client back-off가 유지되며, resource fixture API가 호출되지 않는지
assert한다.

### AOT·문서 검증

다음 세 example에서 정확히 실행한다.

```bash
./gradlew :aws-spring-boot-s3-examples:processAot \
  :aws-spring-boot-s3-examples:processTestAot --no-daemon --max-workers=1
./gradlew :aws-spring-boot-sqs-examples:processAot \
  :aws-spring-boot-sqs-examples:processTestAot --no-daemon --max-workers=1
./gradlew :aws-spring-boot-dynamodb-examples:processAot \
  :aws-spring-boot-dynamodb-examples:processTestAot --no-daemon --max-workers=1
```

생성된 AOT output에서 `ContainerConnectionDetailsFactory` discovery와 optional
classpath linkage가 유지되는지 확인하고, `@Bean` declaration negative test는
details bean이 생성되지 않음을 assert한다. 문서 parity 대상은
`aws-spring-boot/README.md`, `aws-spring-boot/README.ko.md`,
`docs/manual/en/modules/bluetape4k-aws-spring-boot/auto-configuration.md`,
`docs/manual/ko/modules/bluetape4k-aws-spring-boot/auto-configuration.md`로
고정한다. 네 파일은 동일 heading/anchor 구조와 named/unnamed 예시,
dependency alias, properties migration, resource URL ownership,
single-bucket, Floci/LocalStack lifecycle 제약을 각각 담고 링크/API 이름만
언어별 문체에 맞게 번역한다. SQS queue URL, SNS topic ARN, DynamoDB table,
Kinesis stream은 fixture가 생성·주입·소유 cleanup한다는 최소 사용 예시도
각 reader-facing 문서에 연결한다.

문서 구조와 링크 parity는 다음 명령으로 확인한다.

```bash
ruby scripts/manual/manual_contract_test.rb
ruby scripts/manual/export_manifest.rb \
  docs/manual/manifest.yaml docs/manual/generated/manifest.json --check
git diff --check
```

## 9. Issue acceptance 매핑

| Issue #472 수용 기준 | 설계 증거 |
| --- | --- |
| 하나의 ServiceConnection으로 client endpoint 자동 연결 | 서비스별 factory와 details 우선순위 계약 |
| 불필요한 container/property 방지 | named connection, 서비스 enabled 조건, resource 자동 생성 제외 |
| Floci 및 LocalStack context/smoke | 순차 emulator matrix와 explicit fallback |
| emulator system property와 충돌 없음 | launcher 선택자와 details source 분리 |
| 운영 credential/profile auto-config 제외 | details가 있을 때만 static test credential, 없으면 기존 provider |
| Spring Cloud AWS 복사 금지 | Spring Boot factory API와 기존 auto-config 재사용 |
| 단일 bucket 권한 경계 | S3 fixture의 하나의 bucket·소유 객체 cleanup |
| 기존 consumer migration | before/after 표, emulator selector와 annotation 분리, annotation 없는 경우에만 properties fallback |
| optional dependency runtime 격리 | compileOnly 본체, 세 example test classpath, catalog/POM preflight |
| 문서와 AOT 사용성 | README EN/KO, manual EN/KO parity 및 세 example의 `processAot`/`processTestAot` |

## 10. 구현 전 자체 검토 기준

- [x] 설계 승인 후 작성했으며 구현 파일·dependency 파일은 아직 수정하지 않았다.
- [x] ConnectionDetails가 제공하는 값과 제공하지 않는 리소스 URL을 분리했다.
- [x] Floci-first와 LocalStack fallback을 유지하고 MiniStack을 acceptance에서 제외했다.
- [x] custom provider/client back-off와 기존 properties-only 회귀 경로를 명시했다.
- [x] optional classpath, AOT, duplicate details, lifecycle 실패 모드를 포함했다.
- [x] 단일 bucket 및 소유 리소스 cleanup 경계를 수용 기준과 테스트에 연결했다.
- [x] 구현 계획에서 파일·클래스·명령을 확정할 미결 사항만 남겼고, 제품 동작을 임의로 확장하지 않았다.

## 11. Step 2-R 통합 검토

여섯 관점의 독립 검토를 통합했으며 P0/P1 blocker는 모두 0건이다.

| 관점 | P0 | P1 | P2/P3 처분 |
| --- | ---: | ---: | --- |
| 성능 | 0 | 0 | selector no-match 차단, 실행 수 기록, `--max-workers=1`을 계획과 acceptance에 고정 |
| 안정성 | 0 | 0 | cleanup client close와 primary/suppressed 승격, two-context 재초기화, cancellation 테스트를 구현 계획에 포함 |
| 보안 | 0 | 0 | exact Floci/LocalStack allow-list, secret redaction, owner-token 경계를 그대로 유지 |
| 운영/Ops | 0 | 0 | baseline → Floci → LocalStack 상태 기계, fail-fast rollback, PENDING 증거, catalog/POM preflight를 구현 계획에 포함 |
| 개발자/API | 0 | 0 | Boot 4 exact signature/import, public exception fields, alias와 consumer classpath를 고정 |
| 사용자/호출자 | 0 | 0 | named migration, 세 consumer 경로, README/manual EN/KO parity와 서비스별 fixture 예시를 문서 작업으로 포함 |

P2 항목 중 네 서비스의 실제 URL/ARN/name fixture 예시와 문서 parity는
구현 계획의 테스트·문서 task로 승격했고, P3는 없다. writer gate
`audit-korean-terms.mjs`와 `git diff --check`를 통과했다.

이 문서는 사용자 승인과 Step 2-R 통합 검토를 통과한 설계 명세다.
`writing-plans` 단계에서 구현 파일·테스트·명령을 확정한 뒤, 별도 계획 승인
전에는 Kotlin 구현이나 Gradle 변경을 시작하지 않는다.
