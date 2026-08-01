# 이슈 #309 설계 - EventBridge Spring Boot 및 Ktor 통합

## 배경

이슈 #309는 마일스톤 `0.5.0`을 대상으로 하며 `bluetape4k-aws-java`와
`bluetape4k-aws-kotlin`에 집중형 EventBridge 코어 래퍼를 추가한 #308을 잇는다.
프레임워크 계층은 새로운 EventBridge 의미를 추가하지 않고 Spring Boot 자동 구성과 Ktor
플러그인을 통해 해당 도우미를 노출해야 한다.

## 근거

- #308 코어 도우미는 이미 이벤트 버스, 규칙, 대상, 조회, `PutEvents` 워크플로용 Java SDK v2 요청 빌더와 코루틴 어댑터를 제공한다.
- `aws-spring-boot` Kinesis가 가장 가까운 Spring 패턴이다. 선택적 서비스 SDK 의존성,
  `@ConditionalOnClass`, `@ConditionalOnProperty`, 클라이언트/customizer 순서, 프로퍼티
  바인딩, 코루틴 템플릿 빈을 사용한다.
- `aws-ktor` SES/SNS 방식 플러그인이 가장 가까운 Ktor 패턴이다. 선택적 Java SDK v2 비동기
  클라이언트, 주입된 operation 재정의, 애플리케이션 소유 클라이언트 처리, 플러그인 소유
  클라이언트 종료, 공유 `AwsKtorCore` 기본값/customizer를 사용한다.
- EventBridge `PutEvents`, `PutTargets`, `RemoveTargets`는 요청 수준에서 성공하면서 항목별 실패를 반환할 수 있다. 호출자가 실패 횟수/상세를 확인하도록 프레임워크 통합은 원본 SDK 응답을 반환해야 한다.
- 아직 신뢰할 수 있는 저장소 로컬 EventBridge 에뮬레이터 smoke 기반이 없다. 이 작업에서 좁은 Floci/LocalStack smoke를 입증하거나 미지원 공백을 정직하게 기록해야 한다.

## 목표

1. EventBridge용 Spring Boot 자동 구성을 추가한다.
2. 코루틴 친화적인 Spring `EventBridgeOperations` 템플릿을 추가한다.
3. 공유 기본값/customizer를 지원하는 Ktor EventBridge 플러그인을 추가한다.
4. #308 코어 도우미를 재사용하고 원본 AWS SDK 응답 객체를 보존한다.
5. 소비자에게 EventBridge 서비스 SDK를 선택 사항으로 유지한다.
6. 대상 모듈의 영어 및 한국어 README 파일을 갱신한다.

## 제외 범위

- EventBridge Scheduler 지원을 추가하지 않는다. 이는 #310 범위다.
- 전역 엔드포인트, 계정 간 대상 오케스트레이션, 아카이브, replay, pipes, 스키마 레지스트리, 대상별 검증을 추가하지 않는다.
- 숨겨진 배치, 재시도, 정리, 백그라운드 게시, 응답 축약을 추가하지 않는다.
- 실제 AWS 테스트를 추가하지 않는다.

## 선택한 설계

### Spring Boot 통합

`io.bluetape4k.aws.spring.eventbridge` 패키지를 추가한다.

공개 API:

- `EventBridgeProperties`
- `EventBridgeOperations`
- `EventBridgeCoroutinesTemplate`
- `EventBridgeAutoConfiguration`

구성 접두사:

- `bluetape4k.aws.eventbridge.enabled`
- `bluetape4k.aws.eventbridge.region`
- `bluetape4k.aws.eventbridge.endpoint-override`
- `bluetape4k.aws.eventbridge.default-event-bus-name`

자동 구성은 Java SDK v2 EventBridge 런타임 클래스가 있고 통합이 비활성화되지 않았을 때만
`EventBridgeAsyncClient`와 `EventBridgeOperations`를 등록한다. 다른 Spring 통합과 같은 customizer 순서를 사용한다.

1. 저장소 전체 AWS 기본값
2. 선택적 비동기 HTTP 클라이언트
3. 서비스 이름 `eventbridge`를 사용하는 전역 비동기 클라이언트 customizer
4. 서비스별 `AwsClientCustomizer<EventBridgeAsyncClientBuilder>`

`EventBridgeCoroutinesTemplate`은 #308 코어 코루틴 어댑터에 위임한다. 규칙과 대상 operation은
호출자가 `eventBusName`을 생략했을 때만 구성된 `defaultEventBusName`을 사용한다. 각 항목이
이벤트 버스 선택을 소유하므로 `PutEvents` 항목은 다시 쓰지 않는다.

### Ktor 통합

`io.bluetape4k.aws.ktor.eventbridge` 패키지를 추가한다.

공개 API:

- `EventBridgeKtorOperations`
- `EventBridgeKtorTemplate`
- `EventBridgeKtorPluginConfig`
- `EventBridgeKtorRuntime`
- `EventBridgeKtorPlugin`
- `Application.eventBridge()`
- `Application.eventBridgeOrNull()`

`AwsKtorCore`를 `AwsKtorEventBridgeAsyncClientCustomizer`로 확장한다.

플러그인은 다음을 지원한다.

- 클라이언트 생성을 건너뛰는 주입된 operation
- 애플리케이션이 소유하는 주입된 `EventBridgeAsyncClient`
- 공유 Ktor AWS 기본값을 사용하는 플러그인 생성 클라이언트
- 공유 customizer 뒤에 적용하는 서비스 customizer
- `ApplicationStopping`에서 플러그인 소유 클라이언트 종료

Ktor 템플릿은 Spring operation 계약을 반영하고 #308 코어 도우미에 위임한다. Spring 의존성은 없다.

## 수용 기준

- Spring 자동 구성이 클라이언트/프로퍼티/operation/템플릿을 등록하고, 비활성 구성, SDK 클래스 경로 누락, 사용자 클라이언트, 사용자 operation에서 백오프한다.
- Spring 프로퍼티 바인딩이 공백이 아닌 `defaultEventBusName`을 검증한다.
- Spring 및 Ktor operation이 부분 실패 검사를 위해 원본 EventBridge 응답을 노출한다.
- Ktor 플러그인이 주입된 operation을 저장하고, 비활성 접근자를 처리하며, 애플리케이션 소유
  클라이언트를 보존하고, 플러그인 소유 클라이언트를 한 번 닫고, 서비스 customizer보다 공유 customizer를 먼저 적용한다.
- `aws-spring-boot`와 `aws-ktor`가 `libs.aws2.eventbridge`를 `compileOnly` 및 `testImplementation`으로 선언한다.
- README 언어 쌍이 EventBridge 범위와 런타임 의존성 요구 사항을 문서화한다.
- 두 대상 모듈에서 대상 테스트와 `compileTestKotlin`이 통과한다.

## 완료 조건

- 프로덕션 소스 편집 전에 설계와 구현 계획이 존재한다.
- #309는 마일스톤 `0.5.0`과 담당자 `debop`을 유지한다.
- `git diff --check`가 통과한다.
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`이 통과한다.
- `aws-spring-boot`와 `aws-ktor`의 대상 EventBridge 테스트가 통과한다.
- 에뮬레이터 지원을 검증하거나 미지원으로 명시적으로 기록한다.
- PR 메타데이터가 이슈 담당자, 마일스톤, 레이블과 일치하며 마지막에 `## DoD Status` 섹션을 둔다.
