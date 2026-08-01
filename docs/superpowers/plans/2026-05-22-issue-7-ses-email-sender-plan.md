# 이슈 #7 SES 이메일 발신기 계획

## 범위

다음 기준으로 `aws-spring-boot`의 이슈 #7을 구현한다.

- 명세: `docs/superpowers/specs/2026-05-22-issue-7-ses-email-sender-design.md`
- 브랜치: `feat/issue-7-ses-email-sender`
- 기준: `origin/develop`

## 단계

### 1. 빌드 설정

- `spring-context-support`를 Spring Boot BOM 관리 별칭으로 버전 카탈로그에 추가한다.
- `software.amazon.awssdk:sesv2`의 `compileOnly` 및 `testImplementation`
  의존성을 추가한다.
- `org.springframework:spring-context-support`의 `compileOnly` 및
  `testImplementation` 의존성을 추가한다.
- `dependencyInsight`로 `spring-context-support`가 Spring Boot BOM의 관리를
  받는지 확인한다. 그렇지 않으면 추측하지 말고 Spring Framework 버전을 명시적으로 고정한다.
- awspring SES 의존성을 추가하지 않는다.

### 2. SES 속성과 값 객체

- `io.bluetape4k.aws.spring.ses` 패키지를 추가한다.
- `enabled`, `region`, `endpointOverride`, `defaultFrom`,
  `configurationSetName`과 중첩 JavaMail 어댑터 설정을 갖는 `SesProperties`를 추가한다.
- `defaultFrom`이 설정되면 비어 있지 않고 CR/LF/NUL 문자를 포함하지 않는지 검증하고,
  `configurationSetName`이 설정되면 비어 있지 않은지 검증한다.
- 직렬화할 수 있는 요청/값 객체를 추가한다.
  - `SesEmailAddressSet`
  - `SesEmailBody`
  - `SesEmailAttachment`
  - `SesEmailRequest`
  - `SesTemplateEmailRequest`
  - `SesRawEmailRequest`
- 검증은 매핑 경계 가까이에 두고, 사용할 수 있으면 bluetape4k 검증 도우미를 사용한다.
- 직렬화 가능한 공개 값 객체에 `serialVersionUID`를 추가한다.
- Kotlin 데이터 클래스의 `ByteArray` 동등성에 의존하지 않고, 바이트를 포함하는
  모델에 내용 기반 equality/hashCode를 구현한다.
- `AttachmentContentDisposition` (`ATTACHMENT`, `INLINE`)와
  `AttachmentContentTransferEncoding` (`BASE64`, `SEVEN_BIT`,
  `QUOTED_PRINTABLE`)을 열거하고 매핑한다.
- 요청 헤더를 SES v2 `MessageHeader`에 매핑하기 전에 CR/LF 문자를 거부한다.
- `subject`, `from`, `replyTo`와 모든 수신자 문자열에서 CR/LF/NUL 문자를 거부한다.
- 헤더/주소 문자열 공통 검증 도우미와 원시 바이트 및 모델링된 첨부 파일을 합산한
  SES 40 MB 메시지 크기 제한을 추가한다.
- 공개 API에 영문 KDoc을 추가한다.

### 3. 코루틴 SES 발신기

- `SesOperations`를 추가한다.
- `SesCoroutinesMailSender`를 추가한다.
- JavaMail 어댑터가 사용하는 future 기반 `SesOperations` 메서드인
  `sendEmailAsync`, `sendTemplateEmailAsync`, `sendRawEmailAsync`, `sendAsync`를 추가한다.
- 단순 이메일 요청을 SES v2 `EmailContent.simple`에 매핑한다.
- 템플릿 이메일 요청을 SES v2 `EmailContent.template`에 매핑한다.
- 원시 이메일 요청을 SES v2 `EmailContent.raw`에 매핑한다.
- 첨부 파일을 SES v2 `Attachment`에 매핑한다.
- 요청 `headers`를 `Message`와 `Template`의 SES v2 `MessageHeader`에 매핑한다.
- `SesProperties`의 요청 재정의를 적용한다.
- 요청 값과 속성 기본값이 모두 null이면 `from` 및 `configurationSetName` SDK
  필드를 완전히 생략한다.
- `SesRawEmailRequest.destination`이 null이면 원시 이메일의 SDK `Destination`을 생략한다.
- 대체값을 적용한 뒤 확정된 기본값을 검증한다.
- 직접 호출하는 `send(SendEmailRequest)`를 그대로 존중하고 이 탈출구에는 속성 기본값을 적용하지 않는다.
- `kotlinx.coroutines.future.await()`를 사용하고 suspend 호출 주변의 광범위한 예외 처리를 피한다.
- suspend 메서드는 JavaMail이 사용하는 것과 같은 future 기반 요청 매핑에 위임한 뒤
  future에 `.await()`를 호출한다.
- 직접 호출하는 `send(SendEmailRequest)` 탈출구를 추가한다.
- 모의 SDK 클라이언트가 반환하는 대기 중인 `CompletableFuture`로 취소 전파 테스트를 추가한다.
- 본문 텍스트, 템플릿 데이터, 첨부 파일 바이트, 원시 MIME 바이트 또는 전체 수신자
  목록을 로그에 기록하지 않는다.

### 4. JavaMail 어댑터

- 같은 SES 패키지에 `SesJavaMailSender`를 추가한다.
- `JavaMailSender`를 구현한다.
  - `createMimeMessage()`
  - `createMimeMessage(InputStream)`
  - `send(MimeMessage...)`
  - `send(SimpleMailMessage...)`
- MIME 메시지를 바이트로 직렬화해 SES 원시 이메일로 전송한다.
- 호출자가 명시적 envelope를 제공하지 않았으면 `sendRawEmail` 전에 MIME 헤더에서
  `From`과 모든 수신자를 추출한다.
- Spring 단순 메시지를 `SesEmailRequest`로 변환한다.
- 어댑터가 사용자 정의 연산 빈을 존중하도록 `SesV2AsyncClient` 대신
  `SesOperations`에 의존한다.
- 블로킹 `JavaMailSender` 메서드에는 suspend가 아닌 future 기반 브리지를 사용하고,
  어댑터 내부에서 `runBlocking`을 사용하지 않는다.
- `SimpleMailMessage.text`가 null이거나 비어 있으면 `MailParseException`을 던진다.
- 실용적인 범위에서 전송 실패를 Spring `MailException` 하위 타입으로 변환한다.
- `MailParseException`, `MailAuthenticationException`, `MailSendException`,
  `CompletionException` 래핑 해제, `CancellationException` 재발생에는 명세의
  명시적 매핑을 사용한다.
- 모든 메시지의 전송을 시도하고 실패한 메시지 상세를 `MailSendException`으로
  보고하도록 일괄 전송을 구현한다.
- Spring `JavaMailSender`가 블로킹 계약이므로 이 어댑터도 블로킹임을 문서화한다.

### 5. 자동 구성

- `AwsAutoConfiguration` 다음에 `SesAutoConfiguration`을 추가한다.
- 문자열 기반 `@ConditionalOnClass`로 SES SDK 클래스를 보호한다.
- SES 단계에 `@ConditionalOnProperty`를 적용한다.
- 소멸 메서드 `close`를 지정해 `SesV2AsyncClient`를 등록한다.
- `SesCoroutinesMailSender`를 `SesOperations`로 등록한다.
- `SesAutoConfiguration` 다음 순서의 별도 클래스인
  `SesJavaMailSenderAutoConfiguration`을 추가한다.
- JavaMail 단계에 `@AutoConfiguration(after = [SesAutoConfiguration::class])`를 사용한다.
- 문자열 기반 `@ConditionalOnClass`로 JavaMail 타입을 보호한다.
- JavaMail 단계가 `SesOperations`를 조건으로 삼게 한다.
- JavaMail 단계에 `@ConditionalOnMissingBean(JavaMailSender::class)`를 추가한다.
- JavaMail 단계에 `@ConditionalOnProperty`를 적용한다.
- 두 자동 구성 클래스를
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`에 등록한다.

### 6. 테스트

- 다음 항목을 ApplicationContextRunner로 검증하는 `SesAutoConfigurationTest`를 추가한다.
  - 기본 빈 등록
  - 비활성화 속성
  - 사용자 정의 클라이언트가 있을 때 물러나기
  - 사용자 정의 연산이 있을 때 물러나기
  - SDK 클래스패스 부재
  - 엔드포인트 재정의 검증
  - `bluetape4k.aws.ses.java-mail-sender.enabled`의 kebab 표기 바인딩
  - SES 자동 구성이 비활성화되고 `SesOperations`가 없을 때 JavaMail 어댑터 미등록
  - JavaMail 활성화/비활성화 등록
  - JavaMail 클래스패스 부재
  - 사용자 정의 JavaMailSender가 있을 때 물러나기
- 다음 항목을 MockK로 검증하는 `SesCoroutinesMailSenderTest`를 추가한다.
  - 단순 이메일 매핑
  - 템플릿 이메일 매핑
  - 원시 이메일 매핑
  - 첨부 파일
  - 속성 기본값
  - CR/LF 헤더 거부
  - CR/LF/NUL 제목/주소 거부
  - 지원하지 않는 문자 집합 거부
  - 템플릿 이름/ARN XOR 검증
  - 크기 제한을 넘은 원시 데이터와 첨부 파일 페이로드 거부
  - 코루틴 취소 전파
  - SDK 호출 전 검증 실패
- 다음 항목을 MockK로 검증하는 `SesJavaMailSenderTest`를 추가한다.
  - MIME 원시 전송
  - MIME 전용 envelope 추출
  - 단순 메시지 변환
  - null 또는 빈 `SimpleMailMessage.text` 실패
  - 사용자 정의 `SesOperations` 위임
  - 실패를 Spring 메일 예외로 변환
  - 실패 메시지에서 본문 텍스트 및 첨부/원시 바이트 제외
  - Spring 기본 메서드에서 아직 다루지 않았다면 preparator 경로
- bluetape4k assertion만 사용한다.
- mock을 클래스 수준 필드로 유지하고 `@BeforeEach`에서 초기화한다.

### 7. README와 교훈

- `aws-spring-boot/README.md`를 갱신한다.
- `aws-spring-boot/README.ko.md`를 갱신한다.
- 배경, 결정, 결과, 검증 근거, 향후 가드레일을 담은
  `docs/lessons/2026-05-22-issue-7-ses-email-sender.md`를 추가한다.

### 8. 검증

다음 순서로 실행한다.

1. `rg '[가-힣]' aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/ses`
2. `rg 'Ses|ses|java-mail-sender|sesv2' aws-spring-boot/README.md aws-spring-boot/README.ko.md`
3. `git diff --check`
4. `./gradlew :bluetape4k-aws-spring-boot:compileKotlin`
5. `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin`
6. `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.ses.*'`
7. `./gradlew :bluetape4k-aws-spring-boot:detekt`
8. `./gradlew :bluetape4k-aws-spring-boot:koverHtmlReport`
9. `./gradlew :bluetape4k-aws-spring-boot:koverVerify`
10. `./gradlew :bluetape4k-aws-spring-boot:test`

IntelliJ 진단 도구를 사용할 수 없으면 그 공백을 기록하고 Gradle 컴파일/테스트를
대체 근거로 사용한다.

### 9. 리뷰, 커밋, PR

- 구현 후 필수 `bluetape4k-design` 참조를 사용해 6-R 단계 코드 리뷰를 실행한다.
- 모든 P0/P1 지적을 해결한다.
- Lore 트레일러와 함께 커밋한다.
- 브랜치를 push한다.
- 이슈 #7에 연결하고 `debop`에게 할당한 `develop` 대상 PR을 연다.
- 사용자가 요청하지 않으면 PR을 병합하지 않는다.

## 리뷰 체크리스트

- awspring 또는 Spring Cloud AWS 런타임 의존성이 없다.
- SES SDK와 JavaMail 지원은 컴파일/런타임 경계에서 선택 사항으로 유지한다.
- 빈 시그니처의 `compileOnly` 타입을 문자열 기반 `@ConditionalOnClass`로 보호한다.
- 각 자동 구성 단계가 자체 `@ConditionalOnProperty`를 갖는다.
- 요청 값 객체는 타입이 같은 위치 기반 매개변수의 모호성을 피한다.
- 단순/템플릿 전송의 첨부 파일은 SES v2 네이티브 `Attachment`를 사용한다.
- JavaMail 어댑터는 원시 MIME을 전송하며 블로킹이라고 문서화한다.
- JavaMail 어댑터에서 `runBlocking`을 사용하지 않는다.
- 코루틴 발신기는 취소를 잡거나 삼키지 않는다.
- 공개 API KDoc은 영어다.
- 영문 README와 한글 README를 동기화한다.
- 테스트는 실제 AWS 자격 증명이나 검증된 SES 자격을 요구하지 않는다.

## 2단계 체크리스트 완료 보고서

┌──────────────────────────────────────┬────────┬──────────────────────────────────────────────────────────────┐
│ 항목                                 │ 상태   │ 비고                                                         │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 기능 worktree에 명세 작성            │ 완료   │ `docs/superpowers/specs/2026-05-22-issue-7-ses-email-sender-design.md` │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 목표와 제외 범위 기록                │ 완료   │ 범위에서 awspring, 실제 AWS 전송, 대량/수신 SES, 예제를 제외함. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ API와 자동 구성 형태 명시            │ 완료   │ 코루틴 발신기, 선택적 JavaMail 단계, 속성, 값 객체.             │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 테스트와 인수 기준 나열              │ 완료   │ 자동 구성, 매핑, JavaMail, README, 검증 기준을 포함함.          │
└──────────────────────────────────────┴────────┴──────────────────────────────────────────────────────────────┘

## 3단계 체크리스트 완료 보고서

┌──────────────────────────────────────┬────────┬──────────────────────────────────────────────────────────────┐
│ 항목                                 │ 상태   │ 비고                                                         │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 기능 worktree에 계획 작성            │ 완료   │ `docs/superpowers/plans/2026-05-22-issue-7-ses-email-sender-plan.md` │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 순서가 있는 구현 작업 나열           │ 완료   │ 빌드, API, 발신기, JavaMail, 자동 구성, 테스트, 문서, 검증.     │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 검증 명령 나열                       │ 완료   │ 대상 컴파일/테스트와 전체 모듈 테스트를 포함함.                │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 리뷰와 PR 중단 조건 나열             │ 완료   │ P0/P1 리뷰 수렴과 자동 병합 금지 규칙을 기록함.                │
└──────────────────────────────────────┴────────┴──────────────────────────────────────────────────────────────┘

## 3-R 단계 리뷰 기록

### Claude Code Opus 자문

산출물:

- `.omx/artifacts/claude-issue-7-ses-plan-review-2026-05-22.md`
- `.omx/artifacts/claude-issue-7-ses-plan-rereview-2026-05-22.md`
- `.omx/artifacts/claude-issue-7-ses-plan-final-rereview-2026-05-22.md`

┌──────────┬────────────────────────────────────────────────────┬──────────────────────────────────────────────┐
│ 우선순위 │ 지적                                               │ 결정                                         │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P1       │ 원시 MIME envelope 추출, JavaMail 블로킹 브리지,    │ 수용. 명시적 계획 작업을 추가함.             │
│          │ CR/LF 검증 범위가 불완전했음.                       │                                              │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P2       │ 헤더 매핑, 원시 목적지 생략, 속성 검증,             │ 수용. 구체적인 2/3/5/6/8단계 작업을 추가함.  │
│          │ serialVersionUID, JavaMail 물러나기, 리뷰 위생의    │                                              │
│          │ 명세가 부족했음.                                   │                                              │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 0        │ 최종 재실행 결과 P0 = 0, P1 = 0을 보고함.           │ 3-R 단계를 종료함.                           │
└──────────┴────────────────────────────────────────────────────┴──────────────────────────────────────────────┘
