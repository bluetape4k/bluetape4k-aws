# 이슈 #271 Ktor SES v2 및 SNS 설계

날짜: 2026-06-30
이슈: https://github.com/bluetape4k/bluetape4k-aws/issues/271
작업 유형: 유형 A 전체 기능

## 문제

`bluetape4k-aws-ktor`는 공통 AWS 기본값, SigV4, S3, S3 Access Grants,
S3 Vectors, SQS, DynamoDB, IMDS, CloudWatch 및 CloudWatch Logs를 위한 Ktor
도우미를 이미 제공한다. `aws-spring-boot`에는 SES v2와 SNS 코루틴 연산이 있지만,
루트 README 서비스 지원 표는 여전히 `aws-ktor`의 SES/v2와 SNS를 미지원으로 표시한다.

Ktor 애플리케이션에는 Spring Boot에 의존하지 않는 동일한 경량 이메일 및 메시징
API가 필요하다. 구현은 다음 기존 Ktor 플러그인 형태를 따라야 한다.

- 애플리케이션 수준 기본값은 `AwsKtorCore`에 있으며, 서비스 로컬 구성이 재정의하지
  않는 한 서비스 플러그인이 상속한다.
- 서비스 플러그인은 연산을 `Application.attributes`에 저장한다.
- 플러그인이 생성한 AWS Java v2 비동기 클라이언트는 플러그인 런타임이 소유하며
  `ApplicationStopping`에서 닫는다.
- 주입된 클라이언트와 연산은 애플리케이션 소유로 유지한다.
- 플러그인이 생성한 클라이언트는 플러그인 설치 중 한 번 만들고 모든 연산 호출에서
  재사용하며 종료 중 한 번 닫는다.

## 현재 근거

- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/AwsKtorCore.kt`에는 SQS,
  CloudWatch, CloudWatch Logs, S3 Control 및 S3 Vectors용 Java SDK v2 빌더
  커스터마이저 목록이 이미 있다.
- `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/cloudwatch/*`는 가장 가까운
  서비스 패턴인 `Operations`, `Template`, `Runtime`, `PluginConfig`, `Plugin`을 제공한다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/ses/*`에는 SES v2
  요청 값 객체와 `SesV2AsyncClient` 기반 코루틴 발신기가 있다.
- `aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/sns/*`에는 SNS 게시,
  SMS, 토픽 및 HTTP 메시지 파싱 모델이 있다.
- `aws-java/src/main/kotlin/io/bluetape4k/aws/sns/*`에는 SNS Java SDK v2 코루틴
  도우미가 있다. 기존 SES 도우미는 SES v1을 대상으로 하므로 SES v2에는 좁은 범위의
  새 도우미 또는 직접 Ktor 템플릿 호출이 필요하다.
- `gradle/libs.versions.toml`에는 `libs.aws2.sesv2`와 `libs.aws2.sns`가 이미 있지만
  `aws-ktor/build.gradle.kts`에는 아직 선언되지 않았다.
- `aws-spring-boot`의 SNS HTTP 파싱은 Spring Boot `JsonParserFactory`를 사용하므로
  Ktor에는 Spring 비의존 파서 경로가 필요하다.

## 범위

### 포함

- `io.bluetape4k.aws.ktor.ses` 아래에 Ktor SES v2 플러그인 지원을 추가한다.
- `io.bluetape4k.aws.ktor.sns` 아래에 Ktor SNS 플러그인 지원을 추가한다.
- `SesV2AsyncClientBuilder`와 `SnsAsyncClientBuilder`를 위한 `AwsKtorDefaults`
  커스터마이저 지원을 추가한다.
- `aws-ktor`에서 이미 사용할 수 있는 선택적 Jackson 3 의존성을 기반으로 Spring에
  의존하지 않는 Ktor SNS HTTP 메시지 파싱 도우미를 추가한다. 파서는 SNS HTTP(S)
  엔드포인트 페이로드를 파싱하고 선택적으로 `x-amz-sns-message-type` 헤더를 JSON
  `Type`과 비교해 검증할 수 있다.
- 파싱한 SNS HTTP 메시지는 신뢰할 수 없는 데이터다. 상태 변경 도우미는 검증되지
  않은 파싱 메시지를 직접 받지 않으며, 메시지를 통한 확인에는 호출자가 명시적으로
  검증한 래퍼가 필요하다.
- 적합한 곳에서 기존 `aws-java` SNS 코루틴 확장을 재사용한다. SES v2에서는 더 넓은
  SES v2 도우미 설계를 강제하지 않으면서 중복을 줄일 때만 좁은 `aws-java` 코루틴
  확장을 추가한다.
- 플러그인 생명주기, 요청 매핑, 파싱 검증, 대표 전송/게시 동작 테스트를 추가한다.
- 루트 및 모듈 README 로케일 세트와 서비스 지원 표를 갱신한다.

### 제외

- 완전한 SNS 서명 암호학적 검증. 파서는 서명 필드를 노출하고 인증서 URL 형태를
  검증하지만 호출자는 메시지를 처리하기 전에 여전히 신뢰 검증을 수행해야 한다.
- Spring Boot 공개 API 마이그레이션. 이 PR에서 광범위한 소스 호환성 변경을 피하도록
  기존 Spring 패키지 모델을 유지한다.
- 새 예제 모듈. 새 모듈 등록 없이 갱신할 수 있는 기존 예제 워크플로가 구현 중
  드러나지 않는 한 #271에는 README 예제와 플러그인 hook으로 충분하다.
- 로컬 에뮬레이터가 SES v2를 안정적으로 지원하지 않을 때의 SES 에뮬레이터 증명.
  공백은 PR DoD와 README 참고 사항에 기록한다.

## 설계 선택지

### 선택지 A: Ktor 로컬 값 객체를 사용하는 Ktor 로컬 플러그인

현재 CloudWatch 플러그인 패턴을 따르는 SES/SNS Ktor 패키지를 만든다. 모듈 경계로
직접 재사용할 수 없는 기존 Spring 요청 모델 형태를 복사해 조정한다.

장점:
- `aws-ktor`에 Spring 의존성을 추가하지 않는다.
- 기존 Ktor 플러그인 생명주기 및 기본값과 일치한다.
- #271의 영향 범위를 주로 `aws-ktor` 내부와 작은 `AwsKtorCore` 확장으로 제한한다.

단점:
- 향후 공통 모델 추출의 호환성 비용을 감수할 가치가 생길 때까지 일부 Spring 모델
  형태가 중복된다.

### 선택지 B: 공통 SES/SNS 모델을 `aws-java`로 추출

공통 SES/SNS 요청 모델을 Spring 비의존 패키지로 이동하거나 도입한 뒤 Spring과
Ktor가 함께 사용하게 한다.

장점:
- 재사용성이 가장 높다.
- 장기적인 검증 규칙 중복을 피한다.

단점:
- PR 범위가 Spring API 마이그레이션으로 넓어진다.
- 호환성 별칭이나 사용 중단 전략이 필요하다.
- 리뷰와 회귀 범위가 #271을 넘어 커진다.

### 선택지 C: Ktor 플러그인을 통해 원시 AWS SDK 클라이언트만 노출

SES/SNS 클라이언트를 설치하고 모든 요청 생성을 애플리케이션 코드에 맡긴다.

장점:
- 구현이 가장 작다.

단점:
- 이슈의 도우미 및 매핑 요구 사항을 충족하지 못한다.
- Spring SES/SNS 지원보다 발견성이 낮다.
- 모든 Ktor 애플리케이션에서 상용구를 반복한다.

## 결정

이 PR에서는 선택지 A를 사용한다. Ktor 로컬 SES/SNS 연산과 요청 모델을 추가하고,
이미 있는 곳에서는 `aws-java` SNS 코루틴 도우미를 재사용한다. 공통 모델 추출은
리뷰에서 중복이 실질적으로 해롭다고 판단할 때만 가능한 후속 작업으로 남긴다.

## API 형태

### SES v2

- `SesKtorPlugin`
- `SesKtorPluginConfig`
- `SesKtorRuntime`
- `SesKtorOperations`
- `SesKtorTemplate`
- Spring SES 모델에서 조정한 값 객체:
  - `SesEmailAddressSet`
  - `SesEmailBody`
  - `SesEmailAttachment`
  - `SesEmailRequest`
  - `SesTemplateEmailRequest`
  - `SesRawEmailRequest`

연산 API는 다음을 노출한다.

- `suspend fun sendEmail(request: SesEmailRequest): SendEmailResponse`
- `suspend fun sendTemplateEmail(request: SesTemplateEmailRequest): SendEmailResponse`
- `suspend fun sendRawEmail(request: SesRawEmailRequest): SendEmailResponse`
- `suspend fun send(request: SendEmailRequest): SendEmailResponse`

### SNS

- `SnsKtorPlugin`
- `SnsKtorPluginConfig`
- `SnsKtorRuntime`
- `SnsKtorOperations`
- `SnsKtorTemplate`
- Spring SNS 모델에서 조정한 값 객체:
  - `SnsPublishRequest`
  - `SnsSmsRequest`
  - `SnsSmsType`
  - `SnsFifoThroughputScope`
  - `SnsHttpMessageType`
- `SnsHttpMessage`
- `TrustedSnsHttpMessage`
  - `SnsHttpMessageParser`

연산 API는 다음을 노출한다.

- 토픽 생성과 조회
- 토픽 게시
- 직접 SMS 게시
- 명시적 토픽/토큰을 통한 확인
- 호출자가 검증한 `TrustedSnsHttpMessage`를 통한 확인

### 기본값

`AwsKtorCoreConfig`에 다음을 추가한다.

- `fun sesV2AsyncClient(customizer: AwsKtorSesV2AsyncClientCustomizer)`
- `fun snsAsyncClient(customizer: AwsKtorSnsAsyncClientCustomizer)`

`AwsKtorDefaults`는 두 커스터마이저 목록을 저장하고 노출한다. CloudWatch 테스트와
같이 서비스 로컬 커스터마이저는 공통 커스터마이저 다음에 실행한다.

## 실패 모드와 완화책

1. **`aws-ktor`로 Spring 의존성 유출**
   - 완화: 모든 Ktor SES/SNS 값 객체를 `aws-ktor` 패키지 아래에 두고, Spring Boot
     API 대신 Jackson 3로 SNS JSON을 파싱한다.
2. **플러그인 생성 클라이언트의 생명주기 누수**
   - 완화: 기존 런타임 소유권 모델을 따르고 실제 Ktor `ApplicationStopping`
     테스트로 주입 클라이언트와 소유 클라이언트의 닫기 동작을 비교한다. 플러그인
     생성 클라이언트는 한 번 만들고 재사용하며, 주입된 연산은 클라이언트를 만들지 않는다.
3. **안전하지 않은 SNS HTTP 신뢰 가정**
   - 완화: 파서 KDoc과 README에 서명 필드를 노출하지만 암호학적 검증은 호출자
     책임임을 명시한다. 파서는 HTTPS, Amazon SNS 호스트 형태, `.pem`으로 끝나는
     인증서 경로를 검증하지만 인증서를 가져오거나 서명을 검증하지 않는다. 파싱한
     `SnsHttpMessage` 값은 항상 신뢰할 수 없다. 호출자는 직접 암호학적 검증을 수행한
     뒤 메시지 기반 확인 도우미를 사용하기 전에 `TrustedSnsHttpMessage`로 감싸야 한다.
4. **SES/SNS 로컬 에뮬레이터 차이**
   - 완화: 단위 테스트로 매핑과 생명주기를 검증한다. 저장소의 Floci 우선 정책에 따라
     에뮬레이터 기반 SNS를 시도하고 필요할 때만 LocalStack으로 대체하며, 지원하지
     않으면 SES v2 공백을 문서화한다.
5. **README 지원 범위 불일치**
   - 완화: 최종 문서 작성 전에 소스에서 공개 API 이름을 검색하고, 영문과 한글
     README 및 서비스 지원 표를 함께 갱신한다.
6. **비동기 취소 또는 실패한 SDK future 은폐**
   - 완화: 모든 suspend SES/SNS 연산은 블로킹 `get()`/`join()`/`runBlocking` 대신
     코루틴 친화적인 `CompletableFuture.await()`나 기존 bluetape4k 코루틴 도우미를
     사용한다. 테스트는 취소 시 기반 future를 취소하거나 대기를 중단하며, 실패한
     future가 원래 AWS SDK 오류 계약을 전파함을 입증해야 한다.
7. **큰 원시 이메일/첨부 페이로드의 반복 복사**
   - 완화: 불변성을 위해 공개 신뢰 경계에서 바이트 배열을 복사한 뒤, AWS SDK API가
     이미 방어적으로 복사한 데이터를 안전하게 소비할 수 있는 요청 매핑에서는 추가
     대용량 버퍼 복사를 피한다.
8. **SNS HTTP 파서가 위장 인증서 URL 또는 악성 JSON 허용**
   - 완화: Jackson 객체 파싱만 사용하고 페이로드 크기를 제한한다. 객체가 아닌
     페이로드, 누락되거나 문자열이 아닌 필수 필드, 중복된 보안 민감 필드를 거부한다.
     `SigningCertURL`은 `https`, 사용자 정보 없음, 쿼리 없음, 프래그먼트 없음,
     사용자 지정 포트 없음, 지원하는 Amazon SNS 호스트, `.pem` 인증서 경로,
     둘 다 있을 때 `TopicArn`과 리전/파티션 일치라는 정확한 URL 규칙으로 검증한다.
9. **페이로드가 제어하는 클라이언트 설정**
   - 완화: AWS 리전, 엔드포인트 재정의, 자격 증명, 클라이언트 커스터마이저는
     애플리케이션 설정, 주입 클라이언트 또는 Ktor 플러그인 설정에서만 읽는다. SNS
     HTTP 페이로드/헤더 필드는 AWS 클라이언트 엔드포인트, 리전, 자격 증명 또는
     서명 동작을 절대 제어하지 않는다.
10. **진단 정보에 민감한 데이터 노출**
   - 완화: 새 모델의 `toString`/예외/로깅 경로는 AWS 자격 증명, SNS 확인 토큰,
     서명, 원시 이메일 내용, 첨부 파일 바이트 또는 수신자 목록을 노출하지 않아야 한다.
     연산은 래핑하지 않은 SDK 응답을 반환하므로 라이브러리가 페이로드를 로그에 남기지
     않아도 호출자가 SES/SNS 메시지 ID와 SDK 요청 메타데이터를 확인할 수 있다.

## 인수 기준

- `aws-ktor`가 SES v2 및 SNS의 선택적/테스트 AWS SDK 의존성을 선언한다.
- `AwsKtorCore`가 공통 SES v2 및 SNS 비동기 클라이언트 커스터마이저를 저장한다.
- `SesKtorPlugin`과 `SnsKtorPlugin`이 연산을 Ktor 애플리케이션 속성에 설치하고
  공통 기본값을 상속한다.
- 플러그인이 생성한 SES/SNS 비동기 클라이언트는 플러그인 설치마다 한 번 생성하고
  연산 사이에서 재사용하며 Ktor 중지 hook에서 한 번 닫는다.
- SES 연산이 bluetape4k 요청 값 객체를 SES v2 `SendEmailRequest` 변형에 매핑한다.
- SNS 연산이 토픽 생성, FIFO 토픽 생성, 토픽 ARN 조회, 게시, SMS 게시 및 구독 확인을 지원한다.
- SNS HTTP 메시지 파싱은 Spring Boot 의존성 없이 필수 필드, 타입/헤더 일치,
  확인 토큰 요구 사항, 엄격한 JSON 객체/문자열 필드 구조, 제한된 본문 크기,
  정확한 Amazon SNS 서명 인증서 URL 규칙을 검증한다.
- SNS 토픽 게시와 SMS 게시는 별도 요청 타입이다. 토픽 게시는 비어 있지 않은 토픽
  ARN/메시지, FIFO 토픽에서만 허용되는 FIFO 필드, FIFO 토픽의 `messageGroupId`를
  검증한다. SMS 게시는 토픽 필드를 허용하지 않고 전화번호 대상, 메시지, SMS 전용
  속성을 검증한다.
- SES/SNS suspend 연산은 AWS SDK 실패와 취소를 일반적인 성공/실패 래퍼로 변환하지
  않고 전파한다.
- SNS 토픽 ARN 조회는 빈번한 게시 경로가 아니라 페이지 조회 경로라고 문서화한다.
  반복 게시 시 호출자가 토픽 ARN을 캐시해야 한다.
- README 예제는 런타임 SES/SNS AWS SDK 의존성, 리전, 로컬 에뮬레이터 엔드포인트
  재정의, 테스트 자격 증명, 운영 자격 증명 공급자 주의 사항, SES 샌드박스/검증된
  자격 제약, 40 MB SES 첨부 파일 제한, 에뮬레이터 지원 주의 사항, 메시지 ID/요청 ID
  진단 표면, 플러그인 비활성화 또는 주입/원시 AWS SDK 클라이언트 사용 시 롤백과
  소유권 경계를 문서화한다.
- 테스트는 플러그인 생명주기, 매핑, 파서 검증, 대표 전송/게시 동작, 사용할 수 있다면
  신뢰할 수 있는 에뮬레이터 기반 SNS 동작을 검증한다.
- `README.md`, `README.ko.md`, `aws-ktor/README.md`, `aws-ktor/README.ko.md`와
  서비스 지원 표에 SES v2 및 SNS Ktor 지원을 반영한다.
- PR 생성 전에 리뷰 게이트를 P0 = 0, P1 = 0으로 수렴한다.

## 중단 조건

PR 생성, PR 생성 후 리뷰, CI 검증, 9단계 DoD 보고를 마치면 중단한다. 사용자가
명시적으로 병합을 요청할 때까지 병합하지 않는다.
