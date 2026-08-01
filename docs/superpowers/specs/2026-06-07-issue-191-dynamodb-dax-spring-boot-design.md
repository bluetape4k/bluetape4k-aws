# 이슈 #191 DynamoDB DAX Spring Boot 설계

작성일: 2026-06-07
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/191

## 배경

`bluetape4k-aws-spring-boot`는 이미 `DynamoDbAsyncClient`,
`DynamoDbEnhancedAsyncClient`, `DynamoDbTableNameResolver`로 DynamoDB를 자동
구성한다. 저장소 사용자는 `DynamoDbEnhancedAsyncClient`에 의존하므로 DAX 지원은
저장소 클래스를 변경하지 않고 클라이언트 계층에 연결해야 한다.

외부 참고 사항은 다음과 같다.

- Spring Cloud AWS 4.0.2는 endpoint URL, timeout, retry, concurrency 설정을 포함한
  DynamoDB DAX 프로퍼티를 DynamoDB 통합 아래에 노출한다.
- AWS DynamoDB DAX Java 2.x 문서는 `software.amazon.dax:amazon-dax-client`와
  AWS SDK v2 `DynamoDbAsyncClient` 계약을 구현하는 `ClusterDaxAsyncClient`를 사용한다.
- 2026-06-07 KST에 확인한 Maven 메타데이터에서
  `software.amazon.dax:amazon-dax-client`의 latest/release는 `2.0.9`였다.
- `amazon-dax-client-2.0.9.jar`를 로컬 `javap`으로 검사한 결과,
  `ClusterDaxAsyncClient.Builder`는
  `overrideConfiguration(software.amazon.dax.Configuration)`만 받는다.
  `Configuration.Builder`는 URL, region, credentials, timeout, retry, concurrency,
  hostname verification, metrics 옵션을 직접 지원한다.
- 로컬 `ApplicationContextRunner` 피드백에서 `Configuration.Builder`가 빈 생성 중
  credentials를 확인했다. 따라서 DAX가 활성화된 테스트와 애플리케이션에는 확인 가능한
  `AwsCredentialsProvider`가 필요하다.

## 문제

현재 bluetape4k Spring Boot 사용자에게 DAX는 노출되지 않는다.

- `bluetape4k.aws.dynamodb.dax.*` 프로퍼티가 없다.
- 선택적 DAX 클라이언트 빈이 없다.
- `DynamoDbEnhancedAsyncClient`가 DAX를 사용하도록 자동 설정할 방법이 없다.
- DAX 주의 사항이나 LocalStack/DynamoDB Local 테스트를 DAX와 분리하는 이유를 설명하는
  사용자 문서가 없다.

## 목표

1. DAX가 비활성화되거나 없을 때 기본 DynamoDB 동작을 그대로 유지한다.
2. DAX SDK 클래스 경로로 보호되는 opt-in DAX 클라이언트 연결을 추가한다.
3. 선택된 `DynamoDbAsyncClient` 위에 `DynamoDbEnhancedAsyncClient`를 만들어 기존
   저장소 코드가 API 변경 없이 DAX를 사용하도록 한다.
4. 클래스 경로 누락, 활성 endpoint 바인딩, enhanced client 선택을
   `ApplicationContextRunner` 테스트로 검증한다.
5. `README.md`와 `README.ko.md`에 구성 및 운영상 주의 사항을 문서화한다.

## 제외 범위

- 로컬 또는 CI 테스트에서 실제 AWS DAX 클러스터를 사용하지 않는다.
- DAX 일관성이나 cache 동작을 숨기는 저장소 API를 만들지 않는다.
- awspring 템플릿/Spring Integration 어댑터를 복제하지 않는다.
- 광범위한 DynamoDB 저장소 refactor를 하지 않는다.

## 제안 사용자 API

프로퍼티는 기존 bluetape4k 네임스페이스 아래에 둔다.

```yaml
bluetape4k:
  aws:
    dynamodb:
      region: us-east-1
      dax:
        enabled: true
        url: dax://orders-cache.abc123.dax-clusters.us-east-1.amazonaws.com
        connect-timeout: 1s
        request-timeout: 1s
        idle-timeout: 30s
        connection-ttl: 0s
        read-retries: 2
        write-retries: 2
        cluster-update-interval: 4s
        endpoint-refresh-timeout: 6s
        max-concurrency: 1000
        max-pending-connection-acquires: 10000
        skip-host-name-verification: false
```

`dax.enabled=true`일 때만 `dax.url`이 필요하다. Region과 credentials는 기존
DynamoDB/전역 AWS 구성을 사용한다.

## 설계

### 프로퍼티

`DynamoDbProperties`에 중첩된 `DynamoDbDaxProperties` 값을 추가한다.

검증 규칙은 다음과 같다.

- DAX가 활성화되면 `dax.url`이 있어야 한다.
- retry와 concurrency 값은 음수가 아니어야 한다.
- timeout duration은 음수가 아니어야 한다.
- 기존 `endpointOverride` + `region` 검증을 유지한다.
- DAX SDK가 없는 기본 사용자가 프로퍼티 바인딩에 실패하지 않도록 DAX 자동 구성이
  활성화된 경우에만 DAX 검증을 실행한다.

### 자동 구성

DAX 연결을 별도 자동 구성 클래스로 분리한다.

- `DynamoDbAutoConfiguration`
  - 기본 `DynamoDbAsyncClient` 빈 동작을 유지한다.
  - 사용 가능한 `DynamoDbAsyncClient` 위에 `DynamoDbEnhancedAsyncClient`를 유지한다.
- `DynamoDbDaxAutoConfiguration`
  - `@ConditionalOnClass(name = ["software.amazon.dax.ClusterDaxAsyncClient"])`로 보호한다.
  - `@ConditionalOnProperty(prefix = "bluetape4k.aws.dynamodb.dax", name = ["enabled"], havingValue = "true")`로 보호한다.
  - 사용자 `DynamoDbAsyncClient` 빈이 없을 때 `ClusterDaxAsyncClient`로
    `DynamoDbAsyncClient`를 정의한다.
  - 기존 credentials resolution을 적용한다.
  - DAX builder가 AWS SDK `AwsAsyncClientBuilder`가 아니므로
    `AwsAsyncClientCustomizer`나 `AwsClientCustomizer<DynamoDbAsyncClientBuilder>`를
    적용하지 않는다. DAX 전용 조정은 타입 지정 프로퍼티로 표현한다.

순서는 다음과 같다.

- DAX `DynamoDbAsyncClient`가 enhanced client 빈을 충족하도록
  `DynamoDbDaxAutoConfiguration`을 `DynamoDbAutoConfiguration`보다 먼저 등록한다.
- 두 클래스 모두 `bluetape4k.aws.dynamodb.enabled`로 독립적으로 보호한다.

### 의존성 경계

`software.amazon.dax:amazon-dax-client:2.0.9`를 `aws-spring-boot`의
`compileOnly`와 `testImplementation`으로 추가한다.

소비자는 같은 의존성을 런타임에 추가해 opt-in한다. 이 의존성이 없으면 DAX 자동 구성
클래스가 로드되지 않아야 하며 기본 DynamoDB 동작은 바뀌지 않아야 한다.

`amazon-dax-client:2.0.9`는 `software.amazon.awssdk:dynamodb:2.38.5`에
전이 의존한다. 의존성을 추가한 뒤 `dependencyInsight`를 실행해 저장소의 AWS SDK
BOM/catalog 버전이 의도한 AWS SDK 버전을 계속 선택하는지 확인해야 한다.

### 저장소 선택

저장소 코드를 변경할 필요가 없다. `AbstractCoroutinesDynamoDbRepository`는 이미
`DynamoDbEnhancedAsyncClient`에 의존하며, enhanced async client는 현재 선택된
`DynamoDbAsyncClient`로 생성된다.

### 테스트

실제 DAX 클러스터 없이 `ApplicationContextRunner`만 사용한다.

필수 테스트는 다음과 같다.

- DAX가 비활성화되면 기본 DynamoDB 빈은 일반 SDK 클라이언트를 유지한다.
- `dax.enabled=true`여도 DAX 클래스 경로가 없으면 백오프한다.
- `dax.url` 없이 `dax.enabled=true`이면 명확한 검증 메시지와 함께 실패한다.
- URL이 있는 `dax.enabled=true`는 `DynamoDbAsyncClient` 하나를 등록한다.
- enhanced client도 계속 등록되고 선택된 async client 경로를 사용한다.
- 사용자 `DynamoDbAsyncClient`가 기본/DAX 자동 구성보다 우선한다.

`ClusterDaxAsyncClient.builder().build()`는 구성을 검증하고 빈 생성 중 credentials를
확인하므로 테스트는 dummy credentials를 제공하고 네트워크 호출을 피하며 빈 형태/선택만
검증해야 한다.

## 위험

- DAX 클라이언트 API는 AWS SDK BOM이 관리하지 않으며 별도 버전 계열을 사용한다.
- DAX client builder API는 일반 AWS SDK builder와 다르므로 전역/서비스 AWS SDK
  customizer를 직접 적용할 수 없다.
- DAX 클라이언트 POM은 이전 AWS SDK DynamoDB 의존성을 고정한다. Gradle 의존성 관리가
  저장소 AWS SDK 버전 계열을 계속 제어해야 한다.
- DAX는 일관성 tradeoff가 있는 read-through/write-through cache다. 문서에서 DAX가
  DynamoDB Local 또는 LocalStack과 같다고 암시하면 안 된다.

## 수용 기준 매핑

| 수용 기준 | 설계 범위 |
|---|---|
| opt-in하지 않으면 DAX 의존성 불필요 | `compileOnly`, 클래스 경로 guard, 문서 |
| 기본 동작 유지 | DAX 누락/비활성 테스트 |
| 클래스 경로 누락 검증 | `FilteredClassLoader("software.amazon.dax")` 테스트 |
| 활성 endpoint 검증 | DAX URL 프로퍼티와 context runner 테스트 |
| 저장소/클라이언트 선택 검증 | enhanced client가 선택된 async client 경로 사용 |
| README 예제/주의 사항 | 루트 및 모듈 locale README 갱신 |

## 완료 조건

- 명세 검토: `P0=0`, `P1=0`.
- 계획 검토: `P0=0`, `P1=0`.
- `:bluetape4k-aws-spring-boot:test` 대상 테스트 통과.
- `git diff --check` 통과.
- PR 본문은 `## DoD Status`로 끝난다.
