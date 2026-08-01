# Ktor SES v2 및 SNS 구현 계획

> **에이전트 작업자용:** 필수 하위 스킬: superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans로 이 계획을 작업별로 구현한다. 단계 추적에는 체크박스(`- [ ]`) 구문을 사용한다.

**목표:** 이슈 #271을 위해 `bluetape4k-aws-ktor`에 SES v2 및 SNS 통합 API를 추가한다.

**아키텍처:** 서비스 연산, 템플릿, 런타임, 플러그인 설정, 플러그인, 애플리케이션 접근자로 구성된 기존 `CloudWatchKtorPlugin` 패턴을 따른다. Ktor는 Spring 비의존 로컬 SES/SNS 값 객체를 사용하며, 이 PR에서 Spring 공개 API를 마이그레이션하지 않는다.

**기술 스택:** Kotlin 2.3, Java 25, Gradle Kotlin DSL, AWS SDK Java v2 SES v2/SNS, Ktor 3, JUnit 5, MockK, bluetape4k-assertions, 안정적인 경우 Floci/LocalStack.

---

## 사전 조건

- 기능 worktree:
  `/Users/debop/work/bluetape4k/bluetape4k-aws/.worktrees/feat-aws-ktor-ses-sns`
- 브랜치: `feat/aws-ktor-ses-sns`
- 기준: `c93f7d5`의 `origin/develop`
- 이슈: #271, 마일스톤 `0.5.0`, 담당자 `debop`
- 구현 전에 불러올 필수 스킬:
  - `bluetape4k-workflow`
  - `bluetape4k-full-feature`
  - `bluetape4k-code-patterns`
  - `ecc-kotlin-testing`
  - `test-driven-development`
  - `verification-before-completion`
  - `bluetape4k-diagram`
  - `bluetape4k-blog`

## 파일 목록

### 수정

- `aws-ktor/build.gradle.kts`: `libs.aws2.sesv2`와 `libs.aws2.sns`를
  `compileOnly` 및 `testImplementation`으로 추가한다.
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`: SES v2 및 SNS
  공통 커스터마이저 지원을 추가한다.
- `README.md`, `README.ko.md`: 필요하면 서비스 지원 설명/표 참조를 갱신한다.
- `aws-ktor/README.md`, `aws-ktor/README.ko.md`: SES v2 및 SNS 사용법을 문서화한다.
- `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
  및 일치하는 PNG: `aws-ktor` SES/v2 및 SNS 지원을 표시한다.

### 생성

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorModels.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPlugin.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorOperations.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorModels.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsHttpMessageParser.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorTemplate.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorRuntime.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPluginConfig.kt`
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPlugin.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorPluginTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/ses/SesKtorTemplateTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsHttpMessageParserTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorPluginTest.kt`
- `aws-ktor/src/test/kotlin/io/bluetape4k/aws/ktor/sns/SnsKtorTemplateTest.kt`
- `docs/review/2026-06-30-issue-271-ktor-ses-sns-code-review.md`
- `docs/lessons/2026-06-30-issue-271-ktor-ses-sns.md`

## 작업 1: 의존성과 공통 기본값

복잡도: 중간
스킬: `bluetape4k-code-patterns`

- [ ] `aws-ktor/build.gradle.kts`에 `compileOnly(libs.aws2.sesv2)`와
  `compileOnly(libs.aws2.sns)`를 추가한다.
- [ ] 일치하는 `testImplementation` 의존성을 추가한다.
- [ ] `AwsKtorDefaults` 생성자에 다음을 확장한다.
  - `sesV2AsyncClientCustomizers: List<AwsKtorSesV2AsyncClientCustomizer>`
  - `snsAsyncClientCustomizers: List<AwsKtorSnsAsyncClientCustomizer>`
- [ ] 두 목록을 transient 값으로 저장하고 공개 getter를 노출하며 `equalProperties`,
  `hashCode`, `buildStringHelper`에 포함한다.
- [ ] `AwsKtorCoreConfig`에 가변 커스터마이저 목록과 다음 공개 DSL 메서드를 확장한다.
  - `sesV2AsyncClient { ... }`
  - `snsAsyncClient { ... }`
- [ ] `SesV2AsyncClientBuilder`와 `SnsAsyncClientBuilder`의 fun 인터페이스를 추가한다.
- [ ] 기본값이 두 커스터마이저 목록을 유지하고 동등성/hash 문자열 동작이 일관됨을
  입증하는 테스트를 `AwsKtorCoreTest`에 추가한다.
- [ ] RED 명령:
  `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.AwsKtorCoreTest'`
- [ ] GREEN 명령: 구현 후 같은 명령이 통과한다.

## 작업 2: SES v2 Ktor 연산

복잡도: 높음
스킬: `bluetape4k-code-patterns`, `ecc-kotlin-testing`, `test-driven-development`

- [ ] `SesKtorPluginTest`에 다음 실패 테스트를 작성한다.
  - 주입된 연산을 `Application.attributes`에 저장
  - 비활성 플러그인은 연산을 저장하지 않음
  - 주입된 클라이언트는 애플리케이션 소유로 유지
  - Ktor `ApplicationStopping`이 플러그인 소유 클라이언트를 정확히 한 번 닫음
  - 반복 중지는 멱등
  - 주입된 클라이언트는 `ApplicationStopping` 후에도 열려 있음
  - 플러그인 생성 클라이언트를 설치/시작 시 한 번 만들고 여러 연산 호출에서 재사용
  - 공통 커스터마이저가 서비스 커스터마이저보다 먼저 실행
- [ ] `SesKtorTemplateTest`에 다음 실패 테스트를 작성한다.
  - 단순 이메일 요청이 목적지, 제목, 본문, 발신자, 회신 주소, 헤더, 첨부 파일, 구성 세트를 매핑
  - 템플릿 이메일이 템플릿 이름 또는 ARN을 매핑
  - 원시 이메일이 SDK 바이트와 선택적 목적지를 매핑
  - 요청에서 발신자를 생략하면 기본 `from` 필요
  - AWS SDK 예외가 일반 래핑 없이 전파
  - 코루틴 취소가 진행 중인 `CompletableFuture`를 취소하거나 대기를 중단
- [ ] SES 취소 테스트 이름을 suspend API별로 지정한다.
  - `sendEmail cancels the backing future when coroutine is cancelled`;
  - `sendTemplateEmail cancels the backing future when coroutine is cancelled`;
  - `sendRawEmail cancels the backing future when coroutine is cancelled`;
  - `send raw SDK request cancels the backing future when coroutine is cancelled`.
- [ ] 이메일 헤더 삽입 방지 검증 테스트를 추가한다.
  - 사용자 정의 헤더 이름에서 CR, LF, NUL, 콜론, 공백, token이 아닌 문자 거부
  - 헤더 값에서 CR, LF, NUL 거부
  - `from`, `replyTo`, 원시 이메일 메타데이터, 첨부 메타데이터에서 CR, LF, NUL 거부
- [ ] `toString`과 검증 예외에 원시 이메일 바이트, 첨부 바이트, 수신자 목록, AWS
  자격 증명, SNS 토큰 또는 서명이 없음을 테스트나 리뷰 체크리스트로 검증한다.
- [ ] 다음 실패하는 SES 모델 테스트를 추가한다.
  - 본문 텍스트 및 기본 발신자 검증
  - 원시 이메일과 첨부 파일 바이트 배열의 방어적 복사
  - 40 MB 첨부 파일 제한
- [ ] 모든 SES SDK 비동기 호출을 `CompletableFuture.await()` 또는 기존 저장소 코루틴
  도우미로 구현한다. SES 연산 경로에서 `get()`, `join()`, `runBlocking`, 블로킹 대기를 사용하지 않는다.
- [ ] 제어 가능한 `CompletableFuture`를 사용하는 이름 있는 취소 테스트를 다음에 추가한다.
  - `sendEmail`;
  - `sendTemplateEmail`;
  - `sendRawEmail`;
  - raw `send(SendEmailRequest)`.
- [ ] 단순, 템플릿, 원시, 원시 SDK 요청의 SES SDK 실패가 원래 오류 계약을 보존함을
  입증하는 이름 있는 exceptional-future 테스트를 추가한다.
- [ ] Ktor 로컬 SES 코루틴 래퍼를 추가하기 전에 기존 `aws-java` 도우미가 같은
  대기/오류/취소 계약을 표현할 수 있는지 확인한다. Ktor 관심사를 `aws-java`로
  가져오지 않으면서 중복을 줄일 때만 좁은 SES 도우미를 추가한다.
- [ ] SES 값 객체와 원시 `SendEmailRequest`의 suspend 메서드를 갖는 `SesKtorOperations`를 구현한다.
- [ ] Spring SES 모델에서 조정한 `SesKtorModels`를 구현한다. 값 객체를 `Serializable`로
  유지하고 바이트 배열을 방어적으로 복사하며 CR/LF/NUL 헤더 값을 검증하고 SES 40 MB 제한을 보존한다.
- [ ] 바이트 배열 복사는 공개 신뢰 경계로 제한한다. `SdkBytes` 생성 시 방어적 모델
  복사를 넘어 대용량 버퍼를 추가 복사하지 않도록 원시 이메일과 첨부 매핑을 검토한다.
- [ ] `SesV2AsyncClient` 기반 `SesKtorTemplate`을 구현한다.
- [ ] `SesKtorRuntime`, `SesKtorPluginConfig`, `SesKtorPlugin`을 구현한다.
- [ ] 공개 클래스, 인터페이스, 접근자에 영문 KDoc을 추가한다.
- [ ] RED 명령:
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SesKtorPluginTest'`
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SesKtorTemplateTest'`
- [ ] GREEN 명령: 구현 후 같은 명령이 통과한다.

## 작업 3: SNS Ktor 연산과 HTTP 파서

복잡도: 높음
스킬: `bluetape4k-code-patterns`, `ecc-kotlin-testing`, `test-driven-development`

- [ ] `SnsKtorPluginTest`에 다음 실패 테스트를 작성한다.
  - 주입된 연산을 `Application.attributes`에 저장
  - 비활성 플러그인은 연산을 저장하지 않음
  - 주입된 클라이언트는 애플리케이션 소유로 유지
  - Ktor `ApplicationStopping`이 플러그인 소유 클라이언트를 정확히 한 번 닫음
  - 반복 중지는 멱등
  - 주입된 클라이언트는 `ApplicationStopping` 후에도 열려 있음
  - 플러그인 생성 클라이언트를 설치/시작 시 한 번 만들고 여러 연산 호출에서 재사용
  - 공통 커스터마이저가 서비스 커스터마이저보다 먼저 실행
- [ ] `SnsKtorTemplateTest`에 다음 실패 테스트를 작성한다.
  - 표준 토픽 생성
  - FIFO 토픽 생성 속성
  - 페이지 처리된 `ListTopics` 전체의 토픽 조회
  - 토픽 게시 요청 매핑
  - SMS 게시 요청 매핑
  - 명시적 토큰과 호출자가 검증한 신뢰 HTTP 메시지로 구독 확인
  - AWS SDK 예외가 일반 래핑 없이 전파
  - 코루틴 취소가 진행 중인 `CompletableFuture`를 취소하거나 대기를 중단
- [ ] SNS 취소 테스트 이름을 suspend API별로 지정한다.
  - `createTopic cancels the backing future when coroutine is cancelled`;
  - `createFifoTopic cancels the backing future when coroutine is cancelled`;
  - `findTopicArn cancels paged listing when coroutine is cancelled`;
  - `publish cancels the backing future when coroutine is cancelled`;
  - `publishSms cancels the backing future when coroutine is cancelled`;
  - `confirmSubscription cancels the backing future when coroutine is cancelled`.
- [ ] SNS 요청 제약의 이름 있는 부정 테스트를 추가한다.
  - `SnsPublishRequest`가 빈 토픽 ARN과 빈 메시지를 거부
  - 표준 토픽 게시가 FIFO 전용 필드를 거부
  - FIFO 토픽 게시가 `messageGroupId`를 요구
  - `SnsSmsRequest`가 빈 전화번호와 빈 메시지를 거부
  - SMS 전용 필드는 토픽 게시가 아니라 `SnsSmsRequest`에만 모델링
- [ ] 파싱한 신뢰할 수 없는 `SnsHttpMessage`를 상태 변경 확인 도우미에 직접 전달할 수
  없음을 테스트한다. 메시지 기반 도우미는 호출자가 명시적으로 검증한 래퍼/팩토리가
  생성한 `TrustedSnsHttpMessage`만 받는다.
- [ ] 모든 SNS SDK 비동기 호출을 `CompletableFuture.await()` 또는 기존 저장소 코루틴
  도우미로 구현한다. SNS 연산 경로에서 `get()`, `join()`, `runBlocking`, 블로킹 대기를 사용하지 않는다.
- [ ] 요청 및 오류 계약이 Ktor API와 일치하면 기존 `aws-java` SNS 코루틴 확장을
  재사용한다. Ktor 로컬 래퍼가 여전히 필요하면 구현 주석이나 리뷰 산출물에 불일치를 문서화한다.
- [ ] 제어 가능한 `CompletableFuture`를 사용하는 이름 있는 취소 테스트를 다음에 추가한다.
  - `createTopic`;
  - `createFifoTopic`;
  - `findTopicArn`;
  - `publish`;
  - `publishSms`;
  - `confirmSubscription`.
- [ ] 토픽 생성, 토픽 목록, 게시, SMS 게시, 구독 확인 경로에서 SNS SDK 실패가 원래
  오류 계약을 보존함을 입증하는 이름 있는 exceptional-future 테스트를 추가한다.
- [ ] Spring 파서 테스트에서 조정한 다음 실패 테스트를 `SnsHttpMessageParserTest`에 작성한다.
  - 구독 확인 페이로드
  - 알림 페이로드
  - 구독 취소 확인 페이로드
  - 헤더와 JSON 타입 불일치 거부
  - 토큰 없는 확인 페이로드 거부
  - HTTPS가 아닌 서명 인증서 URL 거부
  - SNS가 아닌 서명 인증서 호스트 거부
  - `.pem`으로 끝나지 않는 서명 인증서 경로 거부
  - `sns.us-west-2.amazonaws.com.evil.example` 같은 위장 호스트
  - URL의 userinfo
  - 사용자 정의 포트
  - 쿼리 또는 프래그먼트
  - 잘못된 인증서 경로
  - `TopicArn`과 `SigningCertURL`의 리전 불일치
  - `TopicArn`과 `SigningCertURL`의 파티션 불일치
  - 잘못된 JSON
  - 객체가 아닌 JSON
  - 크기 제한을 넘은 JSON 본문
  - `Type`, `TopicArn`, `SigningCertURL`, `Signature` 같은 보안 민감 필드 중복
  - 필수 필드 누락
  - 문자열이 아닌 scalar/객체/배열 필수 필드 값
- [ ] `SnsKtorOperations`, 모델, 파서, 템플릿, 런타임, 설정, 플러그인을 구현한다.
- [ ] `compileOnly(libs.bluetape4k.jackson3)`을 통해 `aws-ktor`에서 이미 사용할 수 있는
  Jackson 3 `tools.jackson.databind.ObjectMapper`로 Spring 비의존 JSON 파싱을 구현한다.
- [ ] 선택적 런타임 사용자를 위해 파서 생성을 충분히 명시적으로 유지한다. 기본
  파서와 호출자가 이미 관리할 때 애플리케이션 `ObjectMapper`를 받는 오버로드/팩토리를 노출한다.
- [ ] SNS HTTP JSON에 임시 문자열 파싱이나 정규식 파싱을 사용하지 않는다. JSON
  트리/객체 맵으로만 파싱하고 파싱 전에 원시 본문 크기를 제한하며 SNS에 필요한 문자열 필드만 추출한다.
- [ ] 파서 KDoc에 구조와 인증서 URL 형태를 검증할 뿐 암호학적 서명은 검증하지 않는다고 명시한다.
- [ ] `findTopicArn`이 SNS 토픽을 페이지 처리하므로 빈번한 게시 경로에서 메시지마다
  실행하지 말고 반복 게시 시 토픽 ARN을 캐시해야 한다는 KDoc/README 경고를 추가한다.
- [ ] SNS HTTP 페이로드/헤더 필드가 AWS 클라이언트 리전, 엔드포인트 재정의, 자격
  증명 또는 커스터마이저에 절대 영향을 주지 않음을 불변 테스트나 리뷰로 확인한다.
  이 값은 Ktor/AWS 애플리케이션 구성 또는 주입 클라이언트에서만 온다.
- [ ] RED 명령:
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsKtorPluginTest'`
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsKtorTemplateTest'`
  - `./gradlew :bluetape4k-aws-ktor:test --tests '*SnsHttpMessageParserTest'`
- [ ] GREEN 명령: 구현 후 같은 명령이 통과한다.

## 작업 4: 안정적인 에뮬레이터 증명

복잡도: 중간
스킬: `ecc-kotlin-testing`

- [ ] 기존 `aws-ktor` Floci/LocalStack 테스트에서 SQS/S3/DynamoDB 패턴과 환경 플래그를 확인한다.
- [ ] Floci 우선 SNS smoke 검사를 순차 실행한다.
  `./gradlew :bluetape4k-aws-ktor:test -Dbluetape4k.aws.emulator=floci --tests '*Sns*Ktor*LocalStackTest' --no-build-cache`
  에뮬레이터 기반 테스트 클래스를 만든 뒤 실행하거나, Floci가 필요한 SNS 연산 집합을
  지원하지 않아 일치하는 클래스를 추가하지 않았다고 기록한다.
- [ ] 필수 SNS 에뮬레이터 연산 집합은 표준 토픽 생성, 토픽 게시, SDK 응답의 비어 있지
  않은 메시지 ID 확인이다. 하나라도 Floci가 지원하지 않으면 부분 통과가 아니라 Floci
  SNS 미지원으로 분류한다.
- [ ] Floci가 미지원이면 LocalStack 대체 명령을 한 번 순차 실행한다.
  `./gradlew :bluetape4k-aws-ktor:test -Dbluetape4k.aws.emulator=localstack --tests '*Sns*Ktor*LocalStackTest' --no-build-cache`.
- [ ] 소스 변경 없이 같은 명령을 즉시 두 번 순차 실행했을 때 통과/실패 결과가
  일관되지 않으면 에뮬레이터 검증을 flaky로 취급한다. flaky한 skip 테스트를 병합하지
  말고 정확한 명령, 에뮬레이터, 결과, 미지원/flaky 이유를 PR DoD에 문서화한다.
- [ ] 같은 순차 2회 규칙에서 현재 에뮬레이터 경로가 SES v2 `SendEmail`을 안정적으로
  지원하지 않으면 SES 에뮬레이터 증명을 추가하지 않는다.
- [ ] 에뮬레이터 지원이 없거나 flaky하면 거짓 양성이나 skip 테스트를 추가하지 말고
  공백을 PR DoD와 교훈에 문서화한다.
- [ ] Testcontainers/에뮬레이터 명령은 순차로만 실행한다.

## 작업 5: README 로케일 세트와 지원 표

복잡도: 중간
스킬: `bluetape4k-code-patterns`, `bluetape4k-blog`, `bluetape4k-diagram`

- [ ] 표 소스가 바뀌면 루트 영문/한글 README 서비스 지원 설명과 표 삽입을 갱신한다.
- [ ] `aws-ktor/README.md`와 `aws-ktor/README.ko.md`의 기능 목록, 의존성 코드 조각,
  공통 기본값 섹션, SES/SNS 사용 예제, SNS HTTP 파싱 경고를 갱신한다.
- [ ] `aws-ktor`가 선택적/compileOnly로 유지하므로 README 코드 조각에 런타임
  `software.amazon.awssdk:sesv2`, `software.amazon.awssdk:sns` 의존성을 포함한다.
- [ ] README 예제는 리전, 로컬 Floci/LocalStack 엔드포인트 재정의, 더미 로컬 테스트
  자격 증명, 운영 자격 증명 공급자 주의 사항을 보여 준다.
- [ ] README/KDoc SNS HTTP 예제는 호출자 소유 암호학적 서명 검증이 성공하기 전까지
  파싱 메시지를 신뢰할 수 없다고 표시하며, 검증 전에 처리하거나 확인하지 않는다.
- [ ] README 경고는 SES 샌드박스와 검증된 자격 제약, 리전별 SES 자격, SES 40 MB
  첨부 제한, SES/SNS 로컬 에뮬레이터 제한을 다룬다.
- [ ] README 진단 지침은 SES `messageId`, 사용할 수 있는 SNS 게시/확인 응답 ID,
  실패한 전송/게시의 AWS SDK 요청 메타데이터/오류 API를 언급한다.
- [ ] README/KDoc 롤백 지침은 호출자가 플러그인을 비활성화하고 애플리케이션 소유
  클라이언트/연산을 주입하거나 원시 AWS SDK 클라이언트로 돌아갈 수 있다고 설명한다.
  주입 클라이언트는 호출자 소유로 유지하며 플러그인이 닫지 않는다.
- [ ] README 일치 체크리스트를 추가한다. `README.md`/`README.ko.md`와
  `aws-ktor/README.md`/`aws-ktor/README.ko.md`는 일치하는 기능 목록, 의존성 코드
  조각, SES/SNS 예제, SNS 신뢰 경고, 해당 시 SES 에뮬레이터 주의 사항, 표 주변 설명,
  언어 전환을 포함해야 한다.
- [ ] 루트 README 표 주변 설명이나 캡션이 `aws-ktor` SES v2 및 SNS 지원을 명시하고
  독자를 `aws-ktor/README.md`로 연결하게 한다. 검색 가능한 설명을 `README.ko.md`에도 반영한다.
- [ ] 저장소 다이어그램 워크플로로 SVG 서비스 지원 표를 갱신하고 PNG를 렌더링한다.
- [ ] SVG XML을 검증한다.
- [ ] CairoSVG로 PNG를 렌더링한다.
- [ ] 변경한 PNG를 전체 크기로 확인한다.
- [ ] 최종 문서 주장 전에 README API 이름을 소스와 비교 검색한다.
  `rg -n 'SesKtorPlugin|SnsKtorPlugin|SesKtorOperations|SnsKtorOperations|SnsHttpMessageParser' aws-ktor/src/main/kotlin aws-ktor/README.md aws-ktor/README.ko.md`

## 작업 6: 모듈 검증

복잡도: 중간
스킬: `verification-before-completion`

- [ ] 대상 테스트 실행:
  `./gradlew :bluetape4k-aws-ktor:test --tests '*Ses*' --tests '*Sns*'`
- [ ] 명시적인 취소 집중 테스트 실행:
  `./gradlew :bluetape4k-aws-ktor:test --tests '*SesKtorTemplateTest*cancel*' --tests '*SnsKtorTemplateTest*cancel*'`
- [ ] 영향받는 모듈 전체 테스트 실행:
  `./gradlew :bluetape4k-aws-ktor:test`
- [ ] 컴파일 게이트 실행:
  `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --warning-mode all`
- [ ] 정적/빌드 준비 게이트 실행:
  - `./gradlew detekt`
  - `./gradlew build -x test --parallel`
- [ ] 원격 작업 없는 게시 메타데이터 검증 실행:
  `./gradlew :bluetape4k-aws-ktor:generateMetadataFileForBluetapeAwsPublication :bluetape4k-aws-ktor:generatePomFileForBluetapeAwsPublication`
- [ ] 공백 검사 실행:
  `git diff --check`
- [ ] README 표가 바뀌면 리뷰와 PR DoD에 다이어그램 검증 원장을 포함한다.

## 작업 7: 리뷰, 교훈, 커밋, PR

복잡도: 높음
스킬: `bluetape4k-full-feature`, `verification-before-completion`

- [ ] 이 명세와 계획에 5단계 verifier를 실행한다.
- [ ] `:bluetape4k-aws-ktor`에 6-R 단계 7단계 코드 리뷰를 실행한다.
- [ ] 리뷰 산출물을 다음에 저장한다.
  `docs/review/2026-06-30-issue-271-ktor-ses-sns-code-review.md`.
- [ ] P0 = 0, P1 = 0이 될 때까지 모든 P0/P1을 수정하고 영향받는 테스트/리뷰를 재실행한다.
- [ ] `docs/lessons/2026-06-30-issue-271-ktor-ses-sns.md`를 작성한다.
- [ ] Lore 트레일러로 커밋한다.
- [ ] 브랜치를 push하고 다음 조건으로 PR을 생성한다.
  - 영문 제목
  - `Closes #271`;
  - 담당자 `debop`
  - 마일스톤 `0.5.0`
  - GitHub가 허용하는 범위에서 이슈 레이블 반영
  - 마지막 Markdown 섹션은 정확히 `## DoD Status`
- [ ] 실제 PR 메타데이터와 본문을 검증한다.
  `gh pr view <number> --json body,assignees,milestone,labels`
- [ ] 7-R 단계 PR 생성 후 리뷰를 실행한다.
- [ ] 모든 필수 검사가 `SUCCESS` 또는 `SKIPPED`가 될 때까지 CI를 기다리거나 statusCheckRollup을 확인한다.
- [ ] 9단계 DoD 보고를 전달하고 사용자에게 병합을 요청한다.

## 롤백 지점

- SES/SNS Ktor 모델이 과도하게 중복된 API를 만들면 구현 전에 중단하고 공통 모델
  추출 방향으로 명세를 수정한다.
- JSON 파싱에 새 의존성이 필요하면 중단하고 의존성이 정당한지, 더 좁은 파서를
  구현해야 하는지 평가한다.
- 에뮬레이터 기반 SNS 또는 SES 테스트가 불안정하면 skip/flaky 테스트를 병합하지
  않고 결정적 단위 테스트를 유지하며 에뮬레이터 공백을 문서화한다.

## 인수 기준 매핑

| 명세 기준 | 계획 작업 |
|---|---|
| SES/SNS 의존성 | 작업 1 |
| 공통 기본값 커스터마이저 | 작업 1 |
| SES 플러그인과 연산 | 작업 2 |
| SNS 플러그인과 연산 | 작업 3 |
| SNS HTTP 파싱 | 작업 3 |
| 에뮬레이터 기반 증명 또는 문서화한 공백 | 작업 4 |
| README 로케일 세트와 표 | 작업 5 |
| 테스트와 컴파일 검증 | 작업 6 |
| 리뷰, 교훈, PR, CI, DoD | 작업 7 |
