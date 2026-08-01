# 이슈 #308 설계 - EventBridge 핵심 래퍼

## 배경

이슈 #308은 마일스톤 `0.5.0`을 대상으로 하며 핵심 AWS 모듈에 일급 Amazon
EventBridge 지원을 요구한다.

- `bluetape4k-aws-java`
- `bluetape4k-aws-kotlin`

이는 이후 Spring Boot 및 Ktor EventBridge 지원 같은 프레임워크 통합의 기반 작업이다.
핵심 모듈은 정책에 민감하거나 일반적이지 않은 연산을 위한 원시 AWS SDK를 숨기지
않으면서 일반 EventBridge 워크플로를 쉽게 만들어야 한다.

## 현재 코드의 근거

- `aws-java`는 서비스별 Java SDK v2 클라이언트를 지원 계층, 요청 빌더, 비동기
  `CompletableFuture`, 코루틴 `await()` 계층으로 이미 감싼다. 생성/등록/목록/삭제
  형태 연산에는 Kinesis가 가장 가까운 형태다.
- `aws-kotlin`은 네이티브 AWS Kotlin SDK 서비스를 suspend 확장 함수와 빌더
  람다로 이미 감싼다. 검증, 빌더 재정의, 응답 전달에는 Kinesis와 SQS가 가장 가까운 패턴이다.
- `gradle/libs.versions.toml`에는 Kinesis와 STS 별칭이 있지만 다음 EventBridge 별칭은 없다.
  - 누락된 `aws2-eventbridge`
  - 누락된 `aws-kotlin-eventbridge`
- `aws-java/build.gradle.kts`와 `aws-kotlin/build.gradle.kts`는 선택적 AWS 서비스 SDK
  의존성에 `compileOnly`를 사용하고, 테스트에 생성 타입이 필요할 때만 서비스 모듈을
  `testImplementation`에 추가한다.
- `bluetape4k-dependencies/gradle/libs.versions.toml`은 현재 공통 외부 버전을 관리하며
  관리 대상 AWS 핵심 하위 집합만 노출한다. 이 저장소가 소비하는 서비스 별칭은 여전히
  저장소 로컬 카탈로그에 있다.
- Context7에서 선택적 HTTP 클라이언트, 엔드포인트, 리전, 비동기 구성 hook을 갖는
  공식 Java SDK v2 클라이언트/빌더 패턴 `XxxClient.builder()` /
  `XxxAsyncClient.builder()`를 확인했다.
- Context7에서 `XxxClient.fromEnvironment()` 또는 클라이언트 생성과 요청 빌더
  람다를 사용하는 suspend 연산이라는 공식 AWS Kotlin SDK 패턴을 확인했다.
- 이 래퍼와 관련된 AWS EventBridge 공개 API 제약은 다음과 같다.
  - `PutEvents`는 일반적인 사용자 정의 이벤트 게시 연산이다. AWS는 효율을 위해
    배치를 허용하지만 한 요청의 전체 이벤트 항목 크기는 1 MB 미만이어야 하며
    항목 수는 10개를 넘을 수 없다.
  - `PutTargets`는 요청당 최대 10개 대상을 받는다.
  - 각 규칙에는 한 번에 최대 5개 대상을 연결할 수 있다. 이는 요청당 도우미 배치
    규칙이 아니라 AWS 측 규칙 상태 할당량이다.
  - `PutRule`은 규칙을 생성하거나 갱신하며 `eventPattern` 또는
    `scheduleExpression` 중 하나 이상의 일치 메커니즘을 요구한다.
  - 이벤트 버스, 규칙, 대상 변경은 AWS 측 제어 영역 연산이다. 도우미는 삭제를
    명시적으로 유지하고 숨은 정리를 추가하면 안 된다.

## 목표

1. `bluetape4k-aws-java`에 집중된 EventBridge 도우미를 추가한다.
2. `bluetape4k-aws-kotlin`에 집중된 EventBridge 도우미를 추가한다.
3. 일반적인 이벤트 버스, 규칙, 대상 및 `PutEvents` 워크플로를 지원한다.
4. 일반적이지 않거나 정책에 민감한 연산을 위한 원시 SDK 탈출구를 보존한다.
5. `compileOnly`를 사용해 소비자의 서비스 의존성을 선택 사항으로 유지한다.
6. 새 서비스 지원을 쉽게 찾도록 README 로케일 세트를 갱신한다.

## 제외 범위

- 이 PR에서 Spring Boot 또는 Ktor EventBridge 통합을 추가하지 않는다. 이는 후속
  이슈 #309의 범위다.
- EventBridge Scheduler 지원을 추가하지 않는다. 이는 이슈 #310의 범위다.
- EventBridge 서비스 API 전체를 감싸지 않는다.
- 숨은 재시도, 배치, 페이지 처리 또는 백그라운드 worker를 추가하지 않는다.
- 전역 엔드포인트, 계정 간 이벤트 버스 대상 오케스트레이션, 콘솔 전용 내장 대상,
  생성된 SDK 모델 매핑을 넘어서는 대상별 매개변수 검증을 추가하지 않는다. 이는
  원시 SDK의 책임으로 유지한다.
- 일반 생명주기 테스트가 요구하는 직접 이벤트 버스 및 규칙 삭제를 넘어 파괴적인
  삭제 편의 래퍼를 추가하지 않는다. 대상 제거는 호출자가 ID를 제공하는 명시적
  `RemoveTargets` 도우미로 유지한다.
- 실제 AWS smoke 테스트를 추가하지 않는다. 구성된 Floci 우선 에뮬레이터 경로가
  안정적인 EventBridge 지원을 제공하면 에뮬레이터 smoke 테스트를 범위에 포함한다.
  그렇지 않으면 미지원 에뮬레이터 근거를 기록하고 로컬 검증을 단위/요청 매핑 수준으로 유지한다.

## 접근 선택지

### 선택지 A - 요청 빌더만 제공

EventBridge 생성 SDK 타입용 요청 DSL 빌더만 추가한다.

이슈 #308이 핵심 래퍼와 코루틴 DSL을 명시적으로 요구하므로 거부한다. 빌더만으로는
직접 SDK 사용자에게 Kinesis, SQS, SNS에서 이미 제공하는 일급 연산을 제공할 수 없다.

### 선택지 B - SDK별 집중된 핵심 도우미

두 핵심 모듈에 일반 EventBridge 워크플로용 서비스별 도우미를 추가한다.

- Java SDK v2: 클라이언트 팩토리, 요청 빌더, 동기 확장, 비동기
  `CompletableFuture` 확장, 코루틴 어댑터
- AWS Kotlin SDK: 클라이언트 팩토리와 네이티브 suspend 도우미

기존 저장소 패턴과 일치하고 의존성을 선택 사항으로 유지하며, 모든 EventBridge
연산을 소유하는 척하지 않고 유용한 하위 집합을 제공하므로 선택한다.

### 선택지 C - 프레임워크 통합 우선

Spring Boot 또는 Ktor 추상화부터 시작해 핵심 API를 이끌게 한다.

#309가 명시적으로 #308의 후속 작업이므로 거부한다. 프레임워크 통합은 안정적인 핵심
도우미를 소비해야 하며 간접적으로 정의하면 안 된다.

## 선택한 설계

### Java SDK 모듈

다음 패키지를 추가한다.

- `io.bluetape4k.aws.eventbridge`
- `io.bluetape4k.aws.eventbridge.model`

서비스 의존성 별칭과 선언을 추가한다.

- `libs.aws2.eventbridge`를 `compileOnly` 및 `testImplementation`으로 선언

다음 공개 API를 추가한다.

- `eventBridgeClient { }`
- `eventBridgeClientOf(region, httpClient, builder)`
- `eventBridgeClientOf(endpoint, region, credentialsProvider, httpClient, builder)`
- `EventBridgeAsyncClient`를 사용하는 비동기 대응 API
- 다음 요청 빌더:
  - `CreateEventBusRequest`
  - `DeleteEventBusRequest`
  - `PutRuleRequest`
  - `DeleteRuleRequest`
  - `PutTargetsRequest`
  - `RemoveTargetsRequest`
  - `ListRulesRequest`
  - `ListTargetsByRuleRequest`
  - `PutEventsRequest`
  - `PutEventsRequestEntry`
- 같은 연산의 동기 확장 함수
- `CompletableFuture`를 반환하는 비동기 확장 함수
- `.await()`를 사용하는 비동기 클라이언트의 코루틴 확장 함수
- Java 클라이언트 팩토리는 기존 모듈 소유권을 따라야 한다. 생성한 클라이언트를
  `ShutdownQueue`에 등록하고, 테스트는 가장 가까운 기존 서비스 팩토리 생성 검사를 따른다.

검증 규칙:

- 이벤트 버스 이름, 규칙 이름, 대상 ID, 대상 ARN, 이벤트 소스, 상세 타입, 상세는
  bluetape4k 검증 도우미로 빈 값을 거부해야 한다.
- 선택적 `PutEvents` 리소스는 없을 수 있지만 제공한 리소스 값에는 빈 문자열이 없어야 한다.
- `PutRule` 편의 도우미는 비어 있지 않은 `eventPattern` 또는 `scheduleExpression`을
  하나 이상 요구해야 한다. 도우미는 `PutRule`이 AWS 생성 또는 갱신 연산이며,
  호출자를 대신해 생략된 필드를 병합하지 않는다고 문서화해야 한다.
- `PutEvents` 항목은 비어 있지 않고 AWS 요청 제한인 10개를 넘지 않아야 한다.
  도우미는 전체 항목 크기 1 MB 제한을 문서화하지만, SDK 요청 항목을 호출자 제공
  필드로 확장할 수 있고 AWS가 최종 권한을 가지므로 JSON 바이트 크기를 추정하지 않는다.
- `PutTargets` 대상은 비어 있지 않고 AWS 요청 제한인 10개를 넘지 않아야 하며,
  각 대상 도우미는 비어 있지 않은 ID와 ARN을 검증해야 한다.
- `RemoveTargets` ID는 비어 있지 않고 AWS 요청 제한인 10개를 넘지 않아야 한다.
- 삭제 도우미는 생성/등록 도우미와 이미 쌍을 이루는 일반 생명주기 연산인 이벤트
  버스와 규칙에만 제공한다. 대상 삭제는 동작 이름이 AWS 의미와 일치하도록
  `removeTargets`로 유지한다.

### AWS Kotlin SDK 모듈

다음 패키지를 추가한다.

- `io.bluetape4k.aws.kotlin.eventbridge`
- `io.bluetape4k.aws.kotlin.eventbridge.model`

서비스 의존성 별칭과 선언을 추가한다.

- `libs.aws.kotlin.eventbridge`를 `compileOnly` 및 `testImplementation`으로 선언

다음 공개 API를 추가한다.

- `eventBridgeClientOf(...)`
- `withEventBridgeClient { }`
- AWS Kotlin 생성 빌더 형태와 일치하는 요청 빌더 도우미
- 다음 네이티브 suspend 도우미:
  - `createEventBus`
  - `deleteEventBus`
  - `putRule`
  - `deleteRule`
  - `putTargets`
  - `removeTargets`
  - `listRules`
  - `listTargetsByRule`
  - `putEvents`
- `eventBridgeClientOf(...)`는 호출자 소유 클라이언트를 반환한다.
- `withEventBridgeClient { }`는 기존 AWS Kotlin 클라이언트 생명주기 패턴에 맞춰
  `useSafe`를 통해 단기 클라이언트를 소유하고 닫는다.

Kotlin 모듈은 `aws-java`에 의존하면 안 된다. 두 모듈은 패키지 루트와 SDK 클라이언트
타입이 다르므로 같은 연산 이름을 노출할 수 있다.

### 이벤트 항목 매핑

`PutEventsRequestEntry` 편의 빌더는 호출자가 원시 빌더로 내려가지 않아도 일반적인
필수 및 선택 필드를 지원해야 한다.

- 도우미 필수값: `source`, `detailType`, `detail`
- 선택적 직접 매개변수: `eventBusName`, `resources`, `time`, `traceHeader`
- 재정의 빌더 람다: 래퍼가 명시적으로 모델링하지 않는 최종 생성 SDK 필드
- `detail`은 호출자가 제공한 비어 있지 않은 JSON 문자열이어야 한다. 임의 객체를
  직렬화하면 핵심 AWS 도우미 경계를 벗어난 codec과 스키마 의미가 추가되므로 래퍼는
  이를 수행하지 않는다.

생성 SDK가 받을 수 있으면 도우미는 호출자가 제공한 `List` 값을 직접 할당해야 한다.
사용 편의를 위한 vararg 오버로드를 둘 수 있지만, 목록 기반 도우미가 `PutEvents`,
`PutTargets`, `RemoveTargets`의 기본 빈번 호출 API다.

### 취소, 생명주기 및 삭제 순서

Suspend 및 코루틴 도우미는 suspend 호출을 `runCatching`으로 감싸면 안 된다.
호출자 취소, 시간 초과, SDK 예외는 원래 타입과 원인으로 전파돼야 한다. 테스트는
실용적인 범위에서 Java 코루틴 어댑터 취소와 Kotlin `withEventBridgeClient` 닫기
동작을 검증해야 한다.

삭제 도우미는 숨은 정리를 수행하지 않는다. 공개 KDoc은 호출자가 먼저 대상을
제거하고 실패한 대상 제거를 확인한 다음 규칙을 삭제해야 하며, 마찬가지로 사용자
정의 또는 파트너 이벤트 버스를 삭제하기 전에 규칙을 삭제해야 한다고 명시해야 한다.
부분 대상 실패 처리가 호출자에게 남도록 원시 AWS 응답을 계속 노출한다.

## 실패 모드와 완화책

1. **SDK API 차이**: EventBridge 생성 메서드 이름이나 빌더 속성 이름은 Java SDK
   v2와 AWS Kotlin SDK에서 다를 수 있다.
   - 완화: 컴파일 우선 TDD로 검증하고 컴파일 오류가 나타나면 로컬 의존성 클래스와
     소스를 확인한다.
2. **AWS 정책 API 과잉 래핑**: 모든 연산을 감싸면 유지보수 비용이 큰 추상화를 만들고
   호출자 책임을 흐리게 한다.
   - 완화: 이벤트 버스, 규칙, 대상, 목록, `PutEvents` 워크플로만 범위에 둔다.
3. **숨은 배치 의미**: 대상이나 이벤트를 자동 분할하면 부분 실패를 숨기거나 AWS
   요청 의미를 바꿀 수 있다.
   - 완화: 도우미 호출 한 번은 SDK 요청 한 번을 수행하고, 호출자가 배치와 부분 실패를
     명시적으로 처리한다. 테스트는 반복된 도우미 호출이 호출마다 SDK를 정확히 한 번
     실행하고 재시도, 백그라운드 발송 또는 `CompletableFuture.allOf` fan-out을 시작하지
     않는다고 검증해야 한다.
4. **선택적 의존성 유출**: 서비스 모듈에 `api` 또는 `implementation`을 사용하면 모든
   소비자에게 EventBridge를 강제한다.
   - 완화: 저장소 서비스 의존성 정책에 맞춰 `compileOnly`와 `testImplementation`을 사용한다.
5. **생명주기 누수**: 새 서비스 클라이언트가 기존 Java `ShutdownQueue` 또는 AWS
   Kotlin `useSafe` 닫기 패턴을 우회할 수 있다.
   - 완화: 기존 서비스 팩토리 계약을 따르고 Java 및 Kotlin 클라이언트 생명주기의
     테스트나 명시적 생성 근거를 추가한다.
6. **릴리스 근거 공백**: 제어 영역 도우미가 컴파일되더라도 구성된 로컬 AWS
   에뮬레이터에서 실패할 수 있다.
   - 완화: 에뮬레이터가 지원하면 Floci 우선 EventBridge smoke probe를 실행한다.
     Floci와 명시적 LocalStack 대체 경로가 필요한 EventBridge 워크플로를 안정적으로
     지원하지 않으면 probe 결과를 기록하고 PR에 단위 테스트만 있는 로컬 근거를 정직하게 밝힌다.
7. **부분 실패**: 요청 자체가 성공해도 `PutEvents`, `PutTargets`, `RemoveTargets`가
   항목별 실패를 반환할 수 있다.
   - 완화: 도우미는 원시 SDK 응답을 반환하고 KDoc은 보상 전에 실패 항목 수나 실패
     대상 항목을 확인하도록 안내한다. 어떤 도우미도 부분 성공을 Boolean 성공 값으로 축약하지 않는다.

## 인수 기준

- `bluetape4k-aws-java`가 선택한 연산의 EventBridge 클라이언트 팩토리, 요청 빌더,
  동기 확장, 비동기 확장, 코루틴 어댑터를 노출한다.
- `bluetape4k-aws-kotlin`이 선택한 연산의 EventBridge 클라이언트 팩토리, 요청 빌더,
  네이티브 suspend 도우미를 노출한다.
- 테스트가 `PutEvents` 및 `PutTargets` 요청 개수 제한을 포함해 검증, 요청 매핑,
  비동기/코루틴 전달, 숨은 배치 없음 동작을 입증한다.
- 테스트가 `PutEvents`, `PutTargets`, `RemoveTargets` 도우미의 원시 SDK 응답 객체
  보존을 입증해 실패 항목 수와 항목별 실패 상세가 호출자에게 계속 보이게 한다.
- 테스트가 `PutRule` 편의 도우미가 비어 있거나 누락된
  `eventPattern`/`scheduleExpression` 입력을 거부함을 입증한다.
- 테스트 또는 리뷰 근거가 Java 클라이언트 팩토리 소유권과 Kotlin
  `withEventBridgeClient` 닫기 의미가 기존 서비스 패턴과 일치함을 입증한다.
- KDoc이 취소 전파와 `deleteRule` 전에 `removeTargets`, 이벤트 버스 삭제 전에 규칙
  삭제라는 운영 순서를 문서화한다.
- 공개 API KDoc은 영어로 작성하고 새 영속 도우미 계열의 요약, 계약, 현실적인 사용
  예제를 포함한다.
- 루트 `README.md` / `README.ko.md`와 모듈 README 쌍인 `aws-java/README.md`,
  `aws-java/README.ko.md`, `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`가 EventBridge
  지원, 런타임 의존성 요구 사항, 부분 실패 확인, 지원하지 않는 경계 기능을 문서화한다.
- Floci 우선 EventBridge smoke probe가 최소 이벤트 버스/규칙/대상/`PutEvents`
  워크플로에서 통과하거나, PR이 정확한 에뮬레이터 지원 공백과 LocalStack 대체 결과를 기록한다.
- KDoc은 `PutEvents`, `PutTargets`, `RemoveTargets`가 부분 실패를 반환할 수 있고
  호출자가 원시 SDK 응답을 확인해야 한다고 문서화한다.
- README와 README.ko는 EventBridge 서비스 지원을 설명하고 선택적 런타임 서비스
  의존성을 언급한다.
- `bluetape4k-aws-java`와 `bluetape4k-aws-kotlin`의 대상 컴파일 및 테스트가 통과한다.
- PR 생성 전에 7단계 리뷰에서 P0/P1 = 0을 확인한다.

## 완료 정의

- 구현 전에 명세와 구현 계획을 커밋한다.
- #308은 `debop`에게 할당하고 마일스톤 `0.5.0`을 유지한다.
- `git diff --check`가 통과한다.
- `./gradlew :bluetape4k-aws-java:compileTestKotlin --warning-mode all`이 통과한다.
- `./gradlew :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all`이 통과한다.
- 영향받는 두 모듈에서 대상 EventBridge 테스트가 통과한다.
- PR 본문의 마지막 섹션은 `## DoD Status`다.
