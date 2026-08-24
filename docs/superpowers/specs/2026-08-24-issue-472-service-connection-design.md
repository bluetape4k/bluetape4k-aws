---
title: "Issue #472 AWS emulator Testcontainers ServiceConnection 지원 설계"
issue: 472
epic: 500
status: draft-for-review
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
details를 만들 수 있으며, `@ServiceConnection(name = "s3")`처럼 서비스
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

구체적인 package와 Kotlin 파일명은 구현 계획에서 고정하되, 공개 계약은
다음과 같이 분리한다.

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

실제 구현 bean은 `AwsEmulatorServer`의 현재 endpoint, region, access key,
secret key를 immutable 값으로 복사한다. container가 멈추거나 재시작한 뒤
details가 동적으로 변하지 않도록 하며, container lifecycle은 Spring Boot와
Testcontainers가 소유한다.

### 4.2 Factory 등록과 이름 범위

서비스별 factory를 `ContainerConnectionDetailsFactory<Container<*>, D>`로
구현하고 다음 key로 `META-INF/spring.factories`에 등록한다.

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
2. container가 `AwsEmulatorServer`를 구현하지 않으면 `null`을 반환한다.
3. factory의 서비스 이름(`s3`, `sqs`, `sns`, `dynamodb`, `kinesis`)과
   `@ServiceConnection(name = ...)`가 불일치하면 `null`을 반환한다.
4. 이름이 없으면 해당 서비스 details를 만든다.
5. endpoint가 absolute URI가 아니거나 region/credential 값이 비어 있으면
   명확한 configuration 오류로 실패한다. 빈 값으로 AWS client를 만들지
   않는다.

따라서 다음 두 형태를 지원한다.

```kotlin
@Container
@ServiceConnection(name = "s3")
val floci = FlociServer.Launcher.floci
```

```kotlin
@Container
@ServiceConnection
val floci = FlociServer.Launcher.floci
```

이름 없는 선언은 지원 가능한 서비스 details를 모두 제공하지만 리소스를
생성하거나 AWS API를 호출하지 않는다. 서비스별 context 테스트는 named
connection과 서비스 `enabled` 설정을 함께 사용해 불필요한 client/property
경로를 만들지 않는다. 같은 서비스에 details bean이 둘 이상이면 자동으로
하나를 추측하지 않고 context startup을 실패시켜 endpoint 혼선을 막는다.

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

## 5. 의존성 및 classpath 경계

`aws-spring-boot/build.gradle.kts`에는 다음을 compileOnly로만 추가한다.

- `org.springframework.boot:spring-boot-testcontainers`
- `io.github.bluetape4k:bluetape4k-testcontainers` 또는 현재 repository의
  동일 project dependency

실제 consumer test는 central catalog/BOM을 통해 다음 test dependency를
선언한다.

```kotlin
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("io.github.bluetape4k:bluetape4k-testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
```

library production runtime에는 Testcontainers가 전이되지 않는다. AWS service
SDK compileOnly 정책도 유지한다. `spring-boot-testcontainers`가 없는
FilteredClassLoader context에서 기존 S3/SQS/SNS/DynamoDB/Kinesis auto-config가
동일하게 동작하는지 검증한다.

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
허용하지 않는다. cleanup은 test가 생성한 object와 bucket에 한정하고, 공유
emulator의 다른 리소스를 삭제하지 않는다.

### 6.2 Container lifecycle과 AOT

- Testcontainers lifecycle은 `@Container`와 Spring Boot ServiceConnection이
  관리한다. factory가 container를 start/stop하거나 별도 singleton을 만들지
  않는다.
- AOT와 test context 재생성에 안전하도록 static/companion field 선언을
  canonical example로 둔다.
- `@Bean` method에 `@ServiceConnection`을 붙이는 방식은 현재 AOT 위험이
  있으므로 acceptance path로 사용하지 않는다. 관련 주의점은 [Spring Boot
  이슈 #42851](https://github.com/spring-projects/spring-boot/issues/42851)을
  따른다.
- Floci와 LocalStack 테스트는 공유 Docker 자원 때문에 병렬 실행하지
  않는다. MiniStack은 비교 평가 외 acceptance에서 제외한다.

## 7. 실패 모드와 관측성

| 실패/경계 | 기대 동작 | 검증 |
| --- | --- | --- |
| `spring-boot-testcontainers` 없음 | factory가 로드되지 않고 기존 auto-config만 동작 | `FilteredClassLoader` context test |
| 일반 `GenericContainer` | `AwsEmulatorServer` 검사에서 `null`, details 미생성 | factory unit test |
| 지원하지 않는 service name | 해당 factory만 back-off | named factory test |
| details endpoint/region/credential 누락 | 빈 값으로 client를 만들지 않고 명확한 startup 오류 | invalid detail test |
| 같은 service details 중복 | 임의 endpoint를 선택하지 않고 context 실패 | duplicate candidate test |
| custom `AwsCredentialsProvider` 존재 | static emulator provider가 back-off | provider precedence test |
| custom AWS client bean 존재 | 기존 `@ConditionalOnMissingBean`으로 back-off | client back-off test |
| 기존 endpoint/region property만 존재 | 현재 properties/default 경로 유지 | regression context test |
| `-Dbluetape4k.aws.emulator=localstack` | LocalStack launcher details만 사용 | sequential LocalStack smoke |
| queue/topic/table URL 미설정 | ServiceConnection이 자동 생성하지 않으며 fixture가 명시적으로 실패 | resource fixture test |
| S3 bucket 범위 위반 시도 | 단일 bucket 밖 요청을 만들지 않음 | single-bucket integration test |
| container 종료/재시작 | details가 lifecycle을 직접 제어하지 않음; context가 종료를 관찰 | lifecycle test |

Factory는 컨테이너의 service API를 추측하거나 리소스 생성 재시도 루프를
추가하지 않는다. endpoint와 credential 매핑 오류는 조용히 fallback하지 않고
context startup에서 드러나야 한다.

## 8. 테스트 설계

### 단위·계약 테스트

- 각 factory가 `AwsEmulatorServer`에서 정확히 endpoint/region/credentials를
  복사하는지 검증한다.
- 일반 container, 이름 불일치, 빈 값, malformed endpoint를 거부한다.
- unnamed 및 named `@ServiceConnection` 매칭을 각각 검증한다.
- details가 기존 properties보다 우선하고, custom provider/client가 back-off하는
  `ApplicationContextRunner` 계약을 검증한다.
- optional Testcontainers classpath가 없을 때 기존 auto-config가 회귀하지
  않는지 `FilteredClassLoader`로 검증한다.

### Emulator context/smoke

Floci를 기본 backend로 사용하고, 같은 시나리오를 LocalStack fallback에서
순차적으로 실행한다. 각 서비스 시나리오는 named ServiceConnection 하나와
서비스별 명시적 resource fixture를 사용한다.

- S3: 한 bucket 생성, object read/write smoke, 소유 object/bucket만 cleanup
- SQS: queue 생성 후 URL을 `SqsProperties` fixture에 전달
- SNS: topic 생성 후 ARN을 fixture에 전달
- DynamoDB: table 생성 후 table name을 fixture에 전달
- Kinesis: stream 생성 후 stream name을 fixture에 전달

테스트가 실패했을 때 skipped container test를 성공 증거로 보고하지 않는다.
Docker가 없거나 backend가 실행되지 않으면 해당 검증을 `PENDING`으로
기록하고, 실행 가능한 unit/context 검증 결과와 구분한다.

### AOT·문서 검증

기존 Spring Boot 예제의 `processAot`와 `processTestAot` task를 실행해 static
container declaration과 factory discovery가 AOT output에서 깨지지 않는지
검증한다. README와 manual English/Korean 페이지에서 dependency, property,
named connection, resource URL, single-bucket, Floci/LocalStack lifecycle
설명이 서로 일치하는지 확인한다.

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

## 10. 구현 전 자체 검토 기준

- [x] 설계 승인 후 작성했으며 구현 파일·dependency 파일은 아직 수정하지 않았다.
- [x] ConnectionDetails가 제공하는 값과 제공하지 않는 리소스 URL을 분리했다.
- [x] Floci-first와 LocalStack fallback을 유지하고 MiniStack을 acceptance에서 제외했다.
- [x] custom provider/client back-off와 기존 properties-only 회귀 경로를 명시했다.
- [x] optional classpath, AOT, duplicate details, lifecycle 실패 모드를 포함했다.
- [x] 단일 bucket 및 소유 리소스 cleanup 경계를 수용 기준과 테스트에 연결했다.
- [x] 구현 계획에서 파일·클래스·명령을 확정할 미결 사항만 남겼고, 제품 동작을 임의로 확장하지 않았다.

이 문서는 사용자 명세 검토 전의 draft다. 명세 승인 후에만
`writing-plans` 단계로 이동하며, 그 전에는 Kotlin 구현이나 Gradle 변경을
시작하지 않는다.
