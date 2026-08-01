# 이슈 #309 EventBridge 통합 계획

목표: 원본 AWS SDK 응답과 선택적 런타임 서비스 의존성을 보존하면서 #308 코어 래퍼 위에
EventBridge Spring Boot 및 Ktor 통합을 추가한다.

## 작업 1 - RED 테스트 및 의존성 연결

- [ ] `aws-spring-boot`와 `aws-ktor`에 `libs.aws2.eventbridge`를 `compileOnly` 및 `testImplementation`으로 추가한다.
- [ ] 자동 구성 등록, 비활성화 백오프, 사용자 빈 백오프, 엔드포인트/리전 검증, 공유 기본값,
  customizer 순서, 클래스 경로 누락, 프로퍼티 바인딩을 위한 Spring RED 테스트를 추가한다.
- [ ] 주입된 operation, 비활성 접근자 동작, 애플리케이션 소유 클라이언트 보존, 플러그인 소유
  클라이언트 종료, 공유/서비스 customizer 순서, 기본 버스 바인딩의 Ktor RED 테스트를 추가한다.
- [ ] 프로덕션 구현으로 테스트를 통과시키기 전에 대상 테스트를 실행하고 예상 실패를 기록한다.

## 작업 2 - Spring EventBridge 통합

- [ ] `region`, `endpointOverride`, `defaultEventBusName`을 갖춘 `EventBridgeProperties`를 추가한다.
- [ ] 버스 생성/삭제, 규칙 등록/삭제, 대상 등록/제거, 규칙/대상 조회, 이벤트 등록을 위한 suspend 메서드를 갖춘 `EventBridgeOperations`를 추가한다.
- [ ] #308 코어 코루틴 확장에 위임하고 규칙/대상/조회 operation에만 `defaultEventBusName`을 적용하는 `EventBridgeCoroutinesTemplate`을 추가한다.
- [ ] 기존 AWS 기본값, 자격 증명, HTTP 클라이언트, 전역 customizer, 서비스 customizer를 사용하는 `EventBridgeAutoConfiguration`을 추가한다.
- [ ] 자동 구성을 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 등록한다.

## 작업 3 - Ktor EventBridge 통합

- [ ] `AwsKtorCore`를 `AwsKtorEventBridgeAsyncClientCustomizer`로 확장한다.
- [ ] `EventBridgeKtorOperations`, `EventBridgeKtorTemplate`, `EventBridgeKtorRuntime`,
  `EventBridgeKtorPluginConfig`, `EventBridgeKtorPlugin`을 추가한다.
- [ ] SES/SNS 플러그인 수명 주기 계약을 보존한다. 주입된 operation을 우선하고, 주입된
  클라이언트는 애플리케이션 소유로 유지하며, 플러그인이 만든 클라이언트는 한 번 닫는다.
- [ ] operation은 호출당 SDK 요청 하나만 수행하고 원본 SDK 응답을 반환한다.

## 작업 4 - 문서화, 검토 및 검증

- [ ] EventBridge Spring Boot/Ktor 범위와 런타임 의존성 요구 사항을 설명하도록 루트 및 대상 모듈 README 언어 쌍을 갱신한다.
- [ ] 저장소 로컬 Floci/LocalStack 근거로 에뮬레이터 지원을 확인한다. 지원할 때만 smoke를 추가하고, 그렇지 않으면 공백을 기록한다.
- [ ] 대상 EventBridge 테스트, 컴파일 검사, `git diff --check`를 실행한다.
- [ ] 7단계 검토 및 학습 아티팩트를 추가한다.
- [ ] Lore 트레일러와 함께 커밋하고, 이슈 메타데이터 일치 및 마지막 `## DoD Status`를 갖춘 #309 연결 PR을 연다.

## 검증 매트릭스

| 요구 사항 | 근거 |
|---|---|
| Spring EventBridge 빈 | `EventBridgeAutoConfigurationTest` |
| Spring 원본 응답 operation | `EventBridgeCoroutinesTemplateTest` |
| Ktor 플러그인 수명 주기 | `EventBridgeKtorPluginTest` |
| Ktor 원본 응답 operation | `EventBridgeKtorTemplateTest` |
| 선택적 런타임 SDK 의존성 | Gradle 의존성 선언 |
| README 언어 일치 | 루트, `aws-spring-boot`, `aws-ktor` README diff |
| 에뮬레이터 사실성 | Floci/LocalStack probe 또는 미지원 근거 |
| 최종 빌드 상태 | 대상 테스트, compileTestKotlin, `git diff --check` |
