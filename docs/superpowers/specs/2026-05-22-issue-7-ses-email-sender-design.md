# 이슈 #7 SES 이메일 발신기 설계

## 배경

- 저장소: `bluetape4k-aws`
- 이슈: <https://github.com/bluetape4k/bluetape4k-aws/issues/7>
- 대상 모듈: `aws-spring-boot`
- 작업 유형: Spring Boot 공개 API 기능, 전체 설계 작업 흐름
- 기준 브랜치: `develop`

이슈 #7은 Spring Cloud AWS 또는 awspring을 런타임 의존성으로 추가하지 않고
`aws-spring-boot`에서 SES v2 이메일 발송을 지원하도록 요구합니다. 이 모듈은
AWS SDK v2 `SesV2AsyncClient`를 자동 구성하고, 코루틴 우선 메일 발신기를 노출하며,
SES 템플릿과 첨부 파일 API를 지원하고, 이미 이메일을 MIME으로 모델링한 사용자를 위해
선택적 Spring `JavaMailSender` 어댑터를 제공해야 합니다.

## 근거

- 현재 `aws-spring-boot` 패턴은 `compileOnly` AWS 서비스 SDK 의존성,
  `ApplicationContextRunner` 테스트, 문자열 기반 `@ConditionalOnClass`,
  서비스별 자동 구성 단계의 `@ConditionalOnProperty`를 사용합니다.
- `SnsAutoConfiguration`과 `SqsAutoConfiguration`은 예상 클라이언트 빌더 형태를 보여 줍니다.
  `AwsAutoConfiguration`을 먼저 적용하고, 선택적 `AwsCredentialsProvider`와
  `SdkAsyncHttpClient`, 속성 기반 `region`과 `endpointOverride`, 종료 메서드 `close`,
  `@ConditionalOnMissingBean` 백오프를 사용합니다.
- `aws` 모듈은 이미 `io.bluetape4k.aws.ses` 아래 SES v1 도우미를 포함하지만,
  이슈 #7은 `software.amazon.awssdk:sesv2`와 Spring Boot 통합을 명시적으로 대상으로 합니다.
  기존 v1 요청 빌더를 재사용하면 잘못된 SDK 모델이 노출됩니다.
- 기존 저장소 지침은 공개 데이터 클래스가 `Serializable`을 구현하도록 요구합니다.
  하지만 `ByteArray`를 가진 Kotlin 데이터 클래스는 참조 동등성을 사용합니다.
  따라서 바이트를 담는 첨부 파일/원시 요청 모델은 일반 직렬화 클래스이거나
  내용 기반 동등성을 명시적으로 구현해야 합니다.
- SES v2 발송에는 40 MB 메시지 크기 제한이 있습니다. 이 모듈은 제한을 넘는 원시 MIME
  바이트와 모델링된 첨부 파일에 명확한 검증 오류로 빠르게 실패하여 모호한 서비스 측
  발송 실패가 드러나지 않게 해야 합니다.
- AWS SES v2 `SendEmail`은 `EmailContent.simple`, `EmailContent.template`,
  `EmailContent.raw` 콘텐츠 모드를 받습니다.
  근거: <https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html>.
- 로컬 AWS SDK 소스 JAR은 현재 SES v2 모델 지원을 확인합니다. `Message`와 `Template`은
  모두 `attachments`를 포함하고, `Attachment`는 원시 콘텐츠와 MIME 메타데이터를 담으며,
  `RawMessage`는 JavaMail 방식 발송을 위한 전체 MIME 바이트를 담습니다.
- `software.amazon.awssdk:sesv2:2.44.9`의 로컬 AWS SDK 소스 JAR을 통해
  `software.amazon.awssdk.services.sesv2.model.Message`와 `Template`이 모두
  `headers(Collection<MessageHeader>)` 빌더를 노출함을 확인했습니다. 따라서 카탈로그의
  SDK 버전은 단순/템플릿 헤더 매핑을 지원합니다.
- Spring `JavaMailSender`는 `org.springframework:spring-context-support`에 있고 Jakarta Mail
  `MimeMessage`를 사용합니다. 현재 `aws-spring-boot` 컴파일 클래스 경로에 없으므로
  선택 사항으로 유지하고 클래스 경로 가드를 적용해야 합니다.
- Spring Boot 자동 구성은
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`를 통해
  발견됩니다. 순서와 클래스 경로 가드가 중요할 때 선택 단계는 별도 클래스여야 합니다.
  근거: <https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html>.

## 목표

1. `SesV2AsyncClient`와 코루틴 SES 작업을 위한 `SesAutoConfiguration`을 추가합니다.
2. `bluetape4k.aws.ses`에 바인딩되는 `SesProperties`를 추가합니다.
3. 단순, 템플릿, 원시, 직접 SDK `SendEmailRequest` 발송을 위한 일시 중단 API가 있는
   `SesOperations`와 `SesCoroutinesMailSender`를 추가합니다.
4. 같은 형식의 모호한 파라미터 목록을 피하도록 단순/템플릿 요청을 이름 있는 값 객체로 모델링합니다.
5. 단순 및 템플릿 콘텐츠에서 SES v2 `Attachment`를 통해 첨부 파일을 지원합니다.
6. `MimeMessage`를 SES v2 원시 이메일 콘텐츠로 변환하는 선택적 `JavaMailSender` 어댑터를 추가합니다.
7. 모든 SES 자동 구성 단계를 `AutoConfiguration.imports`를 통해 등록합니다.
8. 런타임 의존성, 구성, 코루틴 사용법, 템플릿 사용법, 첨부 파일 사용법, JavaMail 어댑터
   메모를 `README.md`와 `README.ko.md`에 반영합니다.

## 제외 범위

- awspring 또는 Spring Cloud AWS 런타임 의존성을 추가하지 않습니다.
- SES 자격 증명/도메인/템플릿 관리 API를 제공하지 않습니다.
- 대량 이메일 API를 제공하지 않습니다.
- 수신 이메일, 수신 규칙, SNS 반송/불만 처리를 제공하지 않습니다.
- 블로킹 `SesV2Client`를 제공하지 않습니다.
- CI에서 이메일을 발송하는 실제 AWS 통합 테스트를 추가하지 않습니다.
- 이 PR에는 예제 모듈을 추가하지 않으며 예제는 이슈 #82가 담당합니다.

## 공개 API

패키지: `io.bluetape4k.aws.spring.ses`

### SesProperties 속성

구성 접두사: `bluetape4k.aws.ses`

필드:

- `enabled: Boolean = true`
- `region: String? = null`
- `endpointOverride: URI? = null`
- `defaultFrom: String? = null`
- `configurationSetName: String? = null`
- `javaMailSender: JavaMailSenderProperties = JavaMailSenderProperties()`

중첩된 `JavaMailSenderProperties` 필드:

- `enabled: Boolean = true`

검증:

- `endpointOverride`는 기존 SNS/SQS/KMS 속성 규칙과 같이 `region`이 필요합니다.
- `defaultFrom`을 설정하면 비어 있지 않아야 합니다.
- `defaultFrom`을 설정하면 CR/LF/NUL 문자를 거부해야 합니다.
- `configurationSetName`을 설정하면 비어 있지 않아야 합니다.

### SesEmailAddressSet 수신자 집합

수신자용 직렬화 가능 값 객체:

- `to: List<String>`
- `cc: List<String> = emptyList()`
- `bcc: List<String> = emptyList()`

검증:

- 수신자가 한 명 이상 있어야 합니다.
- 수신자 문자열은 비어 있지 않아야 합니다.
- 수신자 문자열은 SES SDK 호출 전에 CR/LF/NUL 문자를 거부해야 합니다.

### SesEmailBody 본문

단순 콘텐츠용 직렬화 가능 값 객체:

- `text: String? = null`
- `html: String? = null`
- `charset: String = "UTF-8"`

검증:

- `text` 또는 `html` 중 하나 이상이 존재하고 비어 있지 않아야 합니다.
- `charset`은 비어 있지 않고 `java.nio.charset.Charset`이 지원해야 합니다.

### SesEmailAttachment 첨부 파일

SES v2 첨부 파일용 직렬화 가능 값 객체입니다. 기본 Kotlin `data class`의 `ByteArray`
동등성 의미에 의존하지 않아야 하며, 내용 기반 동등성/hashCode를 구현하거나 명시적 메서드가
있는 비데이터 클래스를 사용합니다. 또한 `Serializable`을 구현하고 `serialVersionUID`를 정의해야 합니다.

- `fileName: String`
- `content: ByteArray`
- `contentType: String`
- `contentDisposition: AttachmentContentDisposition = ATTACHMENT`
- `contentTransferEncoding: AttachmentContentTransferEncoding = BASE64`
- `contentDescription: String? = null`
- `contentId: String? = null`

검증:

- `fileName`, `contentType`, `content`는 비어 있지 않아야 합니다.
- 인라인 첨부 파일은 `contentId`를 설정할 수 있습니다.
- 바이트 배열에는 내용 기반 동등성 테스트가 필요합니다.
- 첨부 파일 페이로드는 SES 40 MB 메시지 크기 가드에 포함됩니다.

열거형 매핑:

- `AttachmentContentDisposition`: `ATTACHMENT`, `INLINE`.
- `AttachmentContentTransferEncoding`: `BASE64`, `SEVEN_BIT`,
  `QUOTED_PRINTABLE`.

### SesEmailRequest 요청

단순 SES 이메일용 직렬화 가능 값 객체:

- `from: String? = null`
- `destination: SesEmailAddressSet`
- `subject: String`
- `body: SesEmailBody`
- `replyTo: List<String> = emptyList()`
- `attachments: List<SesEmailAttachment> = emptyList()`
- `headers: Map<String, String> = emptyMap()`
- `configurationSetName: String? = null`

기본값:

- `from`은 `SesProperties.defaultFrom`으로 대체됩니다.
- `configurationSetName`은 `SesProperties.configurationSetName`으로 대체됩니다.

검증:

- `subject`는 비어 있지 않아야 합니다.
- 헤더 삽입을 막도록 헤더 이름과 값은 비어 있지 않고 CR/LF 문자를 거부해야 합니다.
- `subject`, `from`, `replyTo`와 모든 대상 문자열은 CR/LF/NUL 문자를 거부해야 합니다.

### SesTemplateEmailRequest 템플릿 요청

SES 템플릿 이메일용 직렬화 가능 값 객체:

- `from: String? = null`
- `destination: SesEmailAddressSet`
- `templateName: String? = null`
- `templateArn: String? = null`
- `templateData: String? = null`
- `attachments: List<SesEmailAttachment> = emptyList()`
- `headers: Map<String, String> = emptyMap()`
- `configurationSetName: String? = null`

검증:

- `templateName` 또는 `templateArn` 중 정확히 하나를 제공해야 합니다.
- `templateData`를 설정하면 비어 있지 않아야 합니다. SES가 데이터를 문자열로 받고 이 모듈이
  JSON 의존성을 강제하지 않아야 하므로 JSON 유효성은 호출자가 책임집니다.
- 헤더 삽입을 막도록 헤더 이름과 값은 비어 있지 않고 CR/LF 문자를 거부해야 합니다.
- `from`과 모든 대상 문자열은 CR/LF/NUL 문자를 거부해야 합니다.

### SesRawEmailRequest 원시 요청

원시 MIME 이메일용 직렬화 가능 값 객체입니다. 기본 Kotlin `data class`의 `ByteArray`
동등성 의미에 의존하지 않아야 하며, 내용 기반 동등성/hashCode를 구현하거나 명시적 메서드가
있는 비데이터 클래스를 사용합니다. 또한 `Serializable`을 구현하고 `serialVersionUID`를 정의해야 합니다.

- `rawContent: ByteArray`
- `from: String? = null`
- `destination: SesEmailAddressSet? = null`
- `configurationSetName: String? = null`

검증:

- `rawContent`는 비어 있지 않아야 합니다.
- `rawContent`는 SES v2의 40 MB 메시지 크기 제한을 초과하지 않아야 합니다.
- 원시 MIME이 헤더를 포함하므로 `from`과 `destination`은 선택 사항이지만, 이를 설정하면
  필요할 때 명시적 봉투 값을 SES에 전달해야 합니다.
- `from`과 명시적 대상 문자열은 CR/LF/NUL 문자를 거부해야 합니다.

### SesOperations 작업 인터페이스

코루틴 우선 인터페이스:

- `suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse`
- `suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse`
- `suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse`
- `suspend fun send(request: SendEmailRequest): SendEmailResponse`
- `fun sendEmailAsync(request: SesEmailRequest): CompletableFuture<SendEmailResponse>`
- `fun sendTemplateEmailAsync(request: SesTemplateEmailRequest): CompletableFuture<SendEmailResponse>`
- `fun sendRawEmailAsync(request: SesRawEmailRequest): CompletableFuture<SendEmailResponse>`
- `fun sendAsync(request: SendEmailRequest): CompletableFuture<SendEmailResponse>`

직접 `SendEmailRequest` 메서드는 편의 값 객체가 모델링하지 않은 고급 SES 필드를 위한 탈출구입니다.
비동기 메서드는 `JavaMailSender` 같은 블로킹 어댑터 브리지를 위해 존재합니다. 일시 중단 메서드는
같은 요청 매핑을 통해 위임하고 퓨처를 기다립니다.

### SesCoroutinesMailSender 코루틴 발신기

`SesV2AsyncClient`를 기반으로 하는 구체적인 `SesOperations` 구현입니다.

계약:

- 단순 요청을 `EmailContent.simple`로 매핑합니다.
- 템플릿 요청을 `EmailContent.template`로 매핑합니다.
- 원시 요청을 `EmailContent.raw`로 매핑합니다.
- 첨부 파일을 `Message` 또는 `Template`의 SES v2 `Attachment`로 매핑합니다.
- `kotlinx.coroutines.future.await()`로 SDK `CompletableFuture`를 기다립니다.
- AWS SDK 예외를 그대로 전파합니다.
- `CancellationException`을 포착하지 않습니다.
- 원시 이메일 콘텐츠, 템플릿 데이터, 본문 텍스트, 첨부 파일, 전체 수신자 목록을 로그에
  남기지 않습니다. 이후 진단 로그를 추가하더라도 메타데이터만 기록해야 합니다.
- 직접 `send(SendEmailRequest)`에서는 호출자가 제공한 SDK 요청을 정확히 따르고
  `SesProperties` 기본값을 적용하지 않습니다. 편의 요청 메서드가 기본 `from`과
  `configurationSetName` 동작을 담당합니다.

### SesJavaMailSender 어댑터

`SesOperations`를 기반으로 하는 선택적 `JavaMailSender` 구현입니다.

계약:

- `createMimeMessage()`는 로컬 `Session`을 사용해 Jakarta Mail `MimeMessage`를 생성합니다.
- `send(MimeMessage...)`는 각 MIME 메시지를 바이트로 직렬화하고
  `SesOperations.sendRawEmail`을 통해 SES v2 원시 이메일을 호출합니다.
- `send(SimpleMailMessage...)`는 Spring 단순 메시지를 `SesEmailRequest`로 변환하고
  `SesOperations.sendEmail`을 통해 발송합니다.
- `JavaMailSender`가 블로킹 Spring 계약이므로 어댑터도 블로킹입니다. `runBlocking`을 사용하지
  않아야 하며, 일시 중단이 아닌 퓨처 기반 `SesOperations` 발송 경로에 연결해 호출자 스레드를
  의도적으로 블로킹해야 합니다.
- `send(MimeMessage...)`는 명시적 봉투 필드가 없을 때 MIME 헤더에서 외부 SES 봉투를
  추출해야 합니다. `From`은 `getFrom()`에서, 수신자는 `getAllRecipients()`에서 가져옵니다.
- `send(SimpleMailMessage...)`는 null 또는 빈 메시지 텍스트를 `MailParseException`으로 거부해야 합니다.
- 실패 매핑:
  - Jakarta `MessagingException`과 MIME 직렬화 실패는 `MailParseException`으로 매핑합니다.
  - SES `NotAuthorizedException`, 접근 거부 형태의 SDK 서비스 실패, AWS SDK 인증/자격 증명
    실패는 `MailAuthenticationException`으로 매핑합니다.
  - 그 밖의 `SesV2Exception`, `SdkClientException`, 퓨처 완료 실패는 `CompletionException`을
    해제한 뒤 `MailSendException`으로 매핑합니다.
  - `CancellationException`은 다시 던집니다.
- Spring의 배치 발송 계약에 맞게 배치 `send(MimeMessage...)`와
  `send(SimpleMailMessage...)`는 모든 메시지를 시도하고 하나 이상 실패하면 실패한 메시지
  세부 정보와 함께 `MailSendException`을 던져야 합니다.
- 이 빈은 `org.springframework.mail.javamail.JavaMailSender`와 Jakarta Mail 클래스가
  존재할 때만 생성합니다.
- KDoc은 AWS SDK 비동기 클라이언트가 네트워크 IO를 수행하는 동안 JavaMail 발송 메서드가
  호출자 스레드를 블로킹한다고 경고해야 합니다.

## 자동 구성

### SesAutoConfiguration 자동 구성

SES SDK 클라이언트와 코루틴 작업을 등록합니다.

규칙:

- `@AutoConfiguration(after = [AwsAutoConfiguration::class])`
- `@ConditionalOnClass(name = ["software.amazon.awssdk.http.async.SdkAsyncHttpClient", "software.amazon.awssdk.services.sesv2.SesV2AsyncClient"])`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.ses", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- `@EnableConfigurationProperties(SesProperties::class)`
- `SesV2AsyncClient` 빈은 `destroyMethod = "close"`를 사용합니다.
- 사용자가 제공한 `SesV2AsyncClient`가 있으면 물러납니다.
- 사용자가 제공한 `SesOperations`가 있으면 물러납니다.

### SesJavaMailSenderAutoConfiguration 자동 구성

선택적 JavaMail 어댑터를 등록합니다.

규칙:

- 별도 클래스를 `AutoConfiguration.imports`에 직접 등록하고 `SesAutoConfiguration` 뒤에 배치합니다.
- `@AutoConfiguration(after = [SesAutoConfiguration::class])`를 사용하며,
  `@ConditionalOnBean(SesOperations::class)`에 대해 가져오기 파일 순서에만 의존하지 않습니다.
- `@ConditionalOnClass(name = ["org.springframework.mail.javamail.JavaMailSender", "jakarta.mail.internet.MimeMessage"])`
- `@ConditionalOnBean(SesOperations::class)`
- `@ConditionalOnMissingBean(JavaMailSender::class)`
- `@ConditionalOnProperty(prefix = "bluetape4k.aws.ses.java-mail-sender", name = ["enabled"], havingValue = "true", matchIfMissing = true)`
- compileOnly `JavaMailSender` 형식은 이 클래스에만 나타나며 문자열 기반
  `@ConditionalOnClass`로 보호합니다.
- 사용자 정의 `SesOperations` 빈이 같은 백오프와 확장 동작을 유지하도록 어댑터는
  `SesV2AsyncClient`가 아니라 `SesOperations`에 의존해야 합니다.

## 의존성

`aws-spring-boot`에 다음을 추가합니다.

- `compileOnly(libs.aws2.sesv2)`
- `testImplementation(libs.aws2.sesv2)`
- Spring Boot BOM을 기반으로 하는 버전 카탈로그 별칭을 추가한 뒤
  `compileOnly(libs.spring.context.support)`를 추가합니다.
- `testImplementation(libs.spring.context.support)`

awspring SES 스타터 의존성은 추가하지 않습니다.

## 테스트

ApplicationContextRunner 테스트:

- SES SDK 클래스가 있으면 `SesV2AsyncClient`, `SesProperties`, `SesOperations`,
  `SesCoroutinesMailSender`를 등록합니다.
- `bluetape4k.aws.ses.enabled=false`이면 등록하지 않습니다.
- 사용자 정의 `SesV2AsyncClient`가 있으면 물러납니다.
- 사용자 정의 `SesOperations`가 있으면 물러납니다.
- `SesV2AsyncClient`가 필터링되면 등록하지 않습니다.
- `region` 없이 `endpointOverride`를 설정하면 검증에 실패합니다.
- `defaultFrom`, `configurationSetName`, JavaMail 어댑터 속성을 바인딩합니다.
- JavaMail 클래스가 있고 어댑터 속성이 활성화됐을 때만 `JavaMailSender`를 등록합니다.
- `bluetape4k.aws.ses.java-mail-sender.enabled=false`를 바인딩하면 JavaMail 어댑터를 건너뜁니다.
- 사용자 정의 `JavaMailSender`가 있으면 물러납니다.
- JavaMail 클래스가 필터링되면 `JavaMailSender`를 등록하지 않습니다.

단위 테스트:

- 단순 이메일은 발신자, 대상, 제목, 텍스트/HTML 본문, 회신 주소, 헤더, 첨부 파일,
  구성 세트를 매핑합니다.
- 템플릿 이메일은 템플릿 이름/ARN, 템플릿 데이터, 헤더, 첨부 파일, 대상, 구성 세트를 매핑합니다.
- 원시 이메일은 바이트를 `RawMessage`로 매핑합니다.
- `SesRawEmailRequest.destination`이 null이면 원시 이메일은 SDK `Destination` 필드를 생략합니다.
- 요청 수준 값이 null이면 `SesProperties`의 기본값을 적용합니다.
- 대체 후 해석된 기본값을 검증하여 오염된 `SesProperties.defaultFrom`이 SDK 호출 전에 실패합니다.
- 단순 및 템플릿 발송에서 `defaultFrom` 없이 `from`이 누락되면 AWS 호출 전에 실패합니다.
- 빈 제목/본문/수신자/템플릿 필드는 AWS 호출 전에 실패합니다.
- 헤더 CR/LF, 주소 CR/LF/NUL, 제목 CR/LF/NUL, 크기 초과 원시 또는 첨부 파일 페이로드는
  AWS 호출 전에 실패합니다.
- 지원하지 않는 문자 집합 이름과 템플릿 이름/ARN XOR 위반은 AWS 호출 전에 실패합니다.
- 검증 실패는 `require*` 도우미를 통해 `IllegalArgumentException`을 사용하고 테스트는
  bluetape4k `assertFailsWith<IllegalArgumentException>`를 사용합니다.
- 바이트 배열 첨부 파일 검증은 내용 동등성을 사용합니다.
- 기반 SDK 퓨처가 아직 대기 중이면 일시 중단 발송 취소가 전파됩니다.
- JavaMail 어댑터는 `MimeMessage`를 원시 SES 콘텐츠로 직렬화하고
  `SimpleMailMessage`를 단순 SES 경로로 변환합니다.
- JavaMail 어댑터는 SES 원시 봉투를 만들 때 MIME 메시지에서 `From`과 모든 수신자를 추출합니다.
- JavaMail 어댑터는 예외 메시지에 본문이나 첨부 파일 콘텐츠를 포함하지 않고 발송 실패를
  Spring 메일 예외로 변환합니다.

통합 테스트:

- 이 이슈에는 실제 AWS 발송이 필요하지 않습니다. CI의 SES 전송에는 검증된 자격 증명과
  인증 정보가 필요합니다.
- 에뮬레이터 스택이 충분한 SES v2 동작을 노출하면 환경 기본 비활성 또는 에뮬레이터 게이트가
  있는 스모크 테스트를 하나 추가합니다. 그렇지 않으면 요청 매핑에는 MockK/단위 테스트를,
  자동 구성에는 ApplicationContextRunner를 사용합니다.
- 새 테스트의 기본값으로 LocalStack을 추가하지 않습니다. 저장소 기본 에뮬레이터는 floci입니다.

## README 갱신

`aws-spring-boot/README.md`와 `aws-spring-boot/README.ko.md`를 모두 갱신합니다.

- SES 기능 목록 항목
- `software.amazon.awssdk:sesv2` 런타임 의존성 스니펫
- `org.springframework:spring-context-support` 선택적 JavaMail 의존성 스니펫
- 기존 `bluetape4k-aws-bom`은 서드파티 의존성 제약이 아니라 모듈을 게시하므로 이 PR에서
  BOM 항목을 변경할 필요가 없습니다.
- `bluetape4k.aws.ses` 구성 스니펫
- 코루틴 단순 이메일 예제
- 템플릿 이메일 예제
- 첨부 파일 예제
- 블로킹 의미와 원시 MIME 발송을 설명하는 JavaMail 어댑터 메모

## 인수 기준

- `aws-spring-boot`는 SES SDK와 JavaMail 의존성을 `compileOnly`로 사용해 컴파일됩니다.
- SES 자동 구성 단계가 `AutoConfiguration.imports`에 나열됩니다.
- 새 공개 API에 영어 KDoc과 유용한 곳의 현실적인 예제가 있습니다.
- `README.md`와 `README.ko.md`가 동기화 상태를 유지합니다.
- 대상 SES 테스트가 통과합니다.
- `./gradlew :bluetape4k-aws-spring-boot:test`가 통과하거나 에뮬레이터 전용 환경 공백을
  증거와 함께 기록합니다.
- 엄격 리뷰에 해결되지 않은 P0/P1 정확성, 취소, Spring Boot, 공개 API 차단 항목이 없습니다.
- `docs/lessons/` 아래에 간결한 교훈을 추가합니다.
- `develop`을 대상으로 PR을 열고 `debop`에 할당하며 이슈 #7에 연결합니다.

## 2-R 단계 리뷰 메모

### Codex 다중 관점 검토 결과

┌──────────┬──────────────┬────────────────────────────────────────────────────┬──────────────────────────────┐
│ 우선순위 │ 영역         │ 발견 사항                                          │ 결정                         │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ API 모델     │ 첨부/원시 모델의 ByteArray는 Kotlin 데이터 클래스  │ 수용함. 명세에서 내용 기반   │
│          │              │ 참조 동등성에 의존하지 않아야 합니다.              │ 동등성을 요구합니다.         │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ Spring 통합  │ 사용자 정의 작업 백오프를 보존하도록 JavaMail      │ 수용함. 자동 구성에서        │
│          │              │ 어댑터는 직접 클라이언트가 아닌 `SesOperations`에  │ `SesOperations`를 사용합니다.│
│          │              │ 의존해야 합니다.                                   │                              │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ 보안         │ 헤더 값은 CR/LF를 거부하고 콘텐츠를 로그에 남기지  │ 수용함. 검증 및 콘텐츠       │
│          │              │ 않아야 합니다.                                     │ 로깅 금지 규칙을 추가했습니다.│
│          │              │                                                    │                              │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P2       │ 테스트       │ 취소 전파를 명시해야 합니다.                       │ 수용함. 테스트 요구 사항을   │
│          │              │                                                    │ 추가했습니다.                │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P1       │ Claude 명세  │ 원시 요청 ByteArray 동등성, JavaMail 순서, 예외    │ 수용함. 명세에 명시적        │
│          │ 리뷰         │ 매핑의 정의가 부족했습니다.                        │ 계약을 추가했습니다.         │
├──────────┼──────────────┼────────────────────────────────────────────────────┼──────────────────────────────┤
│ P2       │ Claude 명세  │ 주소 삽입, SES 크기 제한, 검증 예외/단언 기대가    │ 수용함. 검증 및 테스트       │
│          │ 리뷰         │ 암묵적이었습니다.                                  │ 요구 사항을 추가했습니다.    │
└──────────┴──────────────┴────────────────────────────────────────────────────┴──────────────────────────────┘

### Claude Code Opus 자문

산출물:
`.omx/artifacts/claude-issue-7-ses-spec-review-2026-05-22.md`

최신 산출물:
`.omx/artifacts/claude-issue-7-ses-spec-review-2026-05-22-rerun.md`

P2 반영 후 산출물:
`.omx/artifacts/claude-issue-7-ses-spec-post-p2-review-2026-05-22.md`

최종 산출물:
`.omx/artifacts/claude-issue-7-ses-spec-final-rereview-2026-05-22.md`

┌──────────┬────────────────────────────────────────────────────┬──────────────────────────────────────────────┐
│ 우선순위 │ 발견 사항                                          │ 결정                                         │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 해당 없음│ 사용 크레딧이 Asia/Seoul 오전 4시까지 소진되어      │ 자문 공백으로 기록하고 수렴에는 Codex        │
│          │ Claude CLI를 실행할 수 없었습니다.                  │ 리뷰를 사용했습니다.                         │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P1/P2    │ 재실행에서 P1 계약 공백 3건과 P2 검증/테스트 공백   │ 수용하고 명세에 반영했습니다.                 │
│          │ 4건을 발견했습니다.                                 │                                              │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ P1       │ P2 반영 후 재실행에서 단순/템플릿 헤더 매핑의 SDK  │ 수용함. SES v2 2.44.9 소스 JAR 근거를        │
│          │ 근거를 요구했습니다.                                │ 추가했습니다.                                │
├──────────┼────────────────────────────────────────────────────┼──────────────────────────────────────────────┤
│ 0        │ 최종 재실행에서 P0 = 0, P1 = 0으로 보고했습니다.    │ 2-R 단계를 종료했습니다.                     │
└──────────┴────────────────────────────────────────────────────┴──────────────────────────────────────────────┘

### 2-R 단계 수렴

최신 통합 검토 결과는 P0 = 0, P1 = 0입니다. 2-R 단계를 종료했습니다.

## 1-R 단계 체크리스트 완료 보고서

┌──────────────────────────────────────┬────────┬──────────────────────────────────────────────────────────────┐
│ 항목                                 │ 상태   │ 메모                                                         │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 대상 저장소 확인                     │ 완료   │ 작업 트리는 bluetape4k-aws의 `feat/issue-7-ses-email-sender`입니다. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 지속 지식 검색                       │ 완료   │ GNO에서 기존 SNS/KMS/S3 Spring Boot 설계와 교훈 참조를 찾았습니다. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 공식 API 근거 확인                   │ 완료   │ AWS SES v2 API 문서와 Spring Boot 자동 구성 문서를 확인했습니다. │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 로컬 소스/API 근거 확인              │ 완료   │ 현재 API를 위해 SES v2 및 JavaMail 소스 JAR을 검사했습니다.    │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 저장소 재사용 검색                   │ 완료   │ SNS/SQS/KMS 자동 구성과 기존 SES v1 도우미를 검사했습니다.      │
├──────────────────────────────────────┼────────┼──────────────────────────────────────────────────────────────┤
│ 경계 명확화                          │ 완료   │ awspring, 실제 AWS 발송, 이슈 #82 예제 모듈을 제외했습니다.    │
└──────────────────────────────────────┴────────┴──────────────────────────────────────────────────────────────┘
