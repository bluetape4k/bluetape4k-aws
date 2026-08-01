# Issue #227 코드 검토

Date: 2026-06-08
범위: `aws-spring-boot` S3 Access Grants 구현

## 판정

PASS

- P0: 0
- P1: 0
- P2: 0

## 7단계 검토

### 1단계 - 정확성

PASS. 구현은 AWS SDK Java v2 `s3control` 서비스 모듈을 사용하고 검증된 Access Grants
메서드 `getDataAccess`, `listCallerAccessGrants`, `listAccessGrants`,
`listAccessGrantsInstances`, `listAccessGrantsLocations`를 노출한다. `javap`으로
`s3control-2.46.0.jar`의 `S3ControlAsyncClient`에 이 메서드가 있음을 확인했고 Kotlin 컴파일도 통과했다.

### 2단계 - API와 호환성

PASS. 공개 coroutine API는 추가 방식이며 `io.bluetape4k.aws.spring.s3.accessgrants` 아래로
격리된다. 기존 `S3Operations`는 변경되지 않고 관리용 Access Grants 메서드는 원시
`S3ControlClient` 및 `S3ControlAsyncClient` 빈을 통해 계속 사용할 수 있다.

### 3단계 - Spring Boot 자동 구성

PASS. `S3AccessGrantsAutoConfiguration`은 `AwsAutoConfiguration`과
`S3AutoConfiguration` 뒤에 등록되고, compile-only SDK 타입을 문자열 기반
`@ConditionalOnClass`로 보호하며, 상위 S3 통합과
`bluetape4k.aws.s3.access-grants.enabled=true`를 모두 요구한다. `FilteredClassLoader`
테스트는 S3 Control SDK가 없을 때 자동 구성이 물러남을 확인한다.

### 4단계 - Coroutine과 수명 주기

PASS. 템플릿은 AWS SDK 비동기 호출에 위임하고 기존 모듈 패턴에 맞춰
`CompletableFuture`를 `kotlinx.coroutines.future.await()`로 기다린다. 자동 구성된
클라이언트는 `destroyMethod = "close"`를 사용하며, 호출자가 제공한 클라이언트가 있으면
자동 생성 클라이언트는 물러난다.

### 5단계 - 의존성과 런타임 경계

PASS. `software.amazon.awssdk:s3control`을 `compileOnly`와 `testImplementation`으로
추가해 선택적 서비스 의존성 규칙을 지킨다. 런타임 README는 애플리케이션이
`runtimeOnly("software.amazon.awssdk:s3control")`을 추가해야 함을 명시한다.

### 6단계 - 테스트

PASS. 테스트는 기본 비활성화, 상위 S3 비활성화 backoff, SDK 누락 backoff, 사용자
클라이언트/작업 backoff, 공유 AWS 기본값, 전역/서비스 customizer 순서, 노출된 모든
메서드의 coroutine 위임을 다룬다.

### 7단계 - 문서와 교훈

PASS. `README.md`와 `README.ko.md`는 opt-in 속성, 런타임 의존성, Spring 주입 예제,
S3 Access Grants 구성 요소/흐름 다이어그램을 설명한다. 영구 교훈 문서는 Access Grants가
기본 S3 작업 API가 아닌 S3 Control에 속하는 이유와 다이어그램 검증 증거를 기록한다.

## 증거

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath --no-daemon --max-workers=1`
  통과했고 `software.amazon.awssdk:s3control:2.46.0`을 표시했다.
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1`
  통과했다.
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1`
  통과했다.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1`
  테스트 14개가 통과했다.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AutoConfigurationTest' --tests '*S3AccessGrants*' --no-daemon --max-workers=1`
  테스트 27개가 통과했다.
- S3 Access Grants 구성 요소 다이어그램 게이트:
  `nodes=10 routes=9 segments=28 badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 titleGap=54`.
- S3 Access Grants 흐름 다이어그램 게이트:
  `nodes=12 routes=10 segments=30 badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 titleGap=54`.
- 렌더링된 PNG를 직접 검사하고 두 README 언어판에 포함했다.
  `bluetape4k-aws-s3-access-grants-components-08.png` and
  `bluetape4k-aws-s3-access-grants-flow-09.png`.
- 새 공개 SVG 자산에서 로컬 경로와 UI 글꼴 편차를 검사했으며
  `/Users/debop`, `Inter`, `Arial`, `Helvetica` 일치 항목이 없었다.
- `git diff --check`가 통과했다.

## 잔여 위험

실제 AWS Access Grants 통합 테스트는 추가하지 않았다. Access Grants에는 계정 수준 AWS
설정이 필요하고 로컬 에뮬레이터 매트릭스 범위를 벗어나므로 의도된 결정이다.
