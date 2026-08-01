# 이슈 #268 설계 - 핵심 Secrets Manager 및 Parameter Store 래퍼

## 배경

이슈 #268은 마일스톤 `0.5.0`을 대상으로 하며 핵심 SDK 모듈에 프레임워크 중립적인
Secrets Manager 및 SSM Parameter Store 도우미를 요구한다.

- `bluetape4k-aws-java`
- `bluetape4k-aws-kotlin`

루트 README는 Secrets Manager와 Parameter Store를 서비스 지원 범위로 이미 설명하지만,
핵심 모듈 행은 직접 SDK 사용자를 위한 일급 도우미를 노출하지 않는다. Spring Boot에는
이미 Environment 소스 지원이 있고 `bluetape4k-aws-exposed`에는 소스 서술자가 있다.
이 작업에서 Spring Environment 로딩을 핵심 모듈로 옮기면 안 된다.

## 현재 코드의 근거

- `aws-java/build.gradle.kts`에는 Java SDK `aws2-secretsmanager`와 `aws2-ssm`
  카탈로그 별칭이 이미 있지만 `aws-java`에는 선언되지 않았다.
- `aws-kotlin/build.gradle.kts`에는 DynamoDB, S3, SES, SNS, SQS, KMS, CloudWatch,
  Kinesis, STS용 AWS Kotlin SDK 서비스 의존성이 있지만 Secrets Manager나 SSM은
  아직 선언하지 않았다.
- Maven Central에는 JVM 변형 산출물과 함께 `aws.sdk.kotlin:secretsmanager:1.6.102`와
  `aws.sdk.kotlin:ssm:1.6.102`가 있다.
- AWS Kotlin SDK JVM 변형을 `javap`으로 확인한 결과 `SecretsManagerClient.getSecretValue`,
  `listSecrets`, `putSecretValue`, `createSecret`, `describeSecret`과
  `SsmClient.getParameter`, `getParameters`, `getParametersByPath`, `putParameter`,
  `describeParameters`가 있다.
- AWS Java SDK v2 `2.46.17`을 `javap`으로 확인한 결과 `SecretsManagerClient`,
  `SecretsManagerAsyncClient`, `SsmClient`, `SsmAsyncClient`에 대응하는 동기 및 비동기
  클라이언트 메서드가 있다.
- 기존 Java 모듈 패턴은 다음을 사용한다.
  - `snsClientOf(...)` 같은 서비스 클라이언트 팩토리
  - `.../model` 아래 요청 빌더
  - 비동기 `CompletableFuture` 도우미와 코루틴 `.await()` 래퍼
  - `compileOnly` 서비스 SDK 의존성과 영향받는 서비스의 `testImplementation`
- 기존 Kotlin 모듈 패턴은 다음을 사용한다.
  - `xxxClientOf(...)` 및 `withXxxClient { }`
  - 네이티브 suspend 확장 함수
  - `requireNotBlank`를 사용하는 요청 DSL 빌더
  - 단기 클라이언트 생명주기의 `useSafe`
- 의존성 작업은 명시적이다.
  - Java 별칭 `libs.aws2.secretsmanager`와 `libs.aws2.ssm`은 이미 있으며
    `aws-java`에 `compileOnly` 및 `testImplementation`으로 추가해야 한다.
  - Kotlin 별칭 `libs.aws.kotlin.secretsmanager`와 `libs.aws.kotlin.ssm`을
    `gradle/libs.versions.toml`에 추가한 뒤 `aws-kotlin`에서 `compileOnly` 및
    `testImplementation`으로 소비해야 한다.
- 브랜치 `feat/aws-secrets-parameter-core`의 이 worktree에 CodeGraph를 구축했다.
  파일 741개, 노드 4,754개, 엣지 38,526개다.

## 목표

1. Secrets Manager 및 SSM Parameter Store용 저수준 Java SDK v2 도우미를 추가한다.
2. Secrets Manager 및 SSM용 저수준 AWS Kotlin SDK suspend 도우미를 추가한다.
3. `compileOnly`를 사용해 소비자의 서비스 의존성을 선택 사항으로 유지한다.
4. 일반 get/list/put 흐름을 위한 집중된 요청 빌더와 편의 연산을 제공한다.
5. 값 객체 `toString()`이나 오류 메시지를 통한 비밀 값 유출을 방지한다.
6. 영문과 한글 README 모듈 설명 및 서비스 지원 표를 갱신한다.

## 제외 범위

- Spring Environment 후처리기를 핵심 모듈로 옮기지 않는다.
- Spring Boot 자동 구성을 추가하지 않는다.
- 캐시 또는 새로고침 계층을 만들지 않는다.
- Spring, Exposed, 핵심 모듈에 걸친 새 공통 구성 추상화를 추가하지 않는다.
- AWS 서비스 모듈에 `api`를 사용해 소비자에게 새 런타임 서비스 의존성을 추가하지 않는다.
- 이 PR에서 `SecretBinary` 편의 도우미를 추가하지 않는다. 마스킹된 바이너리 값 타입을
  설계할 때까지 바이너리 페이로드는 원시 SDK 호출에 남긴다.
- 이 PR에서 전체 페이지 수집 도우미를 추가하지 않는다. 단일 페이지 연산은 SDK 요청과
  응답 타입을 통해 `nextToken` / `maxResults`를 노출해야 한다.

## 접근 선택지

### 선택지 A - 요청 빌더만 제공

`GetSecretValueRequest`, `GetParameterRequest`와 유사한 DSL 빌더만 추가한다.

이슈 #268은 요청 팩토리만이 아니라 일급 래퍼를 요구하므로 거부한다. 직접 SDK
사용자에게는 여전히 일반적인 get/list/put 연산이 부족하다.

### 선택지 B - SDK별 집중된 핵심 도우미

`aws-java`에 서비스 클라이언트 팩토리, 요청 빌더, 동기/비동기/suspend 연산을
추가하고 `aws-kotlin`에 네이티브 suspend 연산을 추가한다.

기존 SQS/SNS/Kinesis 형태와 일치하고 Spring 동작을 핵심 모듈 밖에 유지하며, 큰
아키텍처 변경 없이 직접 SDK 사용자에게 유용한 API를 제공하므로 선택한다.

### 선택지 C - Spring Environment 소스 로직을 핵심으로 승격

평탄화, 소스 서술자, Environment 로딩 개념을 핵심 모듈로 옮긴다.

#180 관련 Spring 동작을 중복하고 핵심 모듈이 Spring 형태 속성 소스 의미를 소유하게
되므로 거부한다.

## 선택한 설계

### Java SDK 모듈

다음 패키지를 추가한다.

- `io.bluetape4k.aws.secretsmanager`
- `io.bluetape4k.aws.secretsmanager.model`
- `io.bluetape4k.aws.ssm`
- `io.bluetape4k.aws.ssm.model`

Java 모듈 API:

- `secretsManagerClient { }`, `secretsManagerClientOf(...)`
- `secretsManagerAsyncClient { }`, `secretsManagerAsyncClientOf(...)`
- `SsmClient` / `SsmAsyncClient` 대응 API
- 다음 요청 빌더:
  - Secrets Manager: 비밀 값 조회, 일괄 조회, 목록, 설명, 생성, 등록
  - SSM: 매개변수 조회, 여러 매개변수 조회, 경로별 조회, 매개변수 등록, 매개변수 설명
- 일반 호출의 동기 확장 함수
- `CompletableFuture`를 반환하는 비동기 확장 함수
- `.await()`를 사용하는 비동기 클라이언트의 코루틴 확장 함수
- 삭제 편의 래퍼 없음. 파괴적인 삭제 호출은 호출 지점에서 명시적으로 유지하도록
  원시 SDK 클라이언트에 남긴다.
- 도우미 팩토리가 생성한 Java 클라이언트는 기존 Java 모듈 소유권을 따른다. 도우미는
  생성한 클라이언트를 `ShutdownQueue`에 등록하며, 단기 클라이언트를 소유한 호출자는
  여전히 명시적으로 닫을 수 있다.

비밀 문자열 반환값은 마스킹된 값 객체로 감싸야 한다.

- `reveal()`을 제공하는 `AwsSecretValue`
- `toString()`은 `"****"`를 반환한다.
- 빈 값을 거부한다.

래퍼는 의도적으로 작고 프레임워크 중립적이다. RDS IAM 토큰 마스킹 규칙을 따르지만
Secrets Manager와 Parameter Store는 인증 토큰이 아니므로 해당 타입을 재사용하지 않는다.

비밀 값을 받는 쓰기 경로 도우미도 원시 `String` 값이 아니라 마스킹된 래퍼를 받아야 한다.

- `createSecret` / `putSecretValue` 편의 도우미는 `SecretString`에 `AwsSecretValue`를 받는다.
- `putParameter` 도우미는 `SecureString` 값에는 `AwsSecretValue`를 받고, 명시적으로
  비밀이 아닌 `String` / `StringList` 매개변수 타입에만 일반 `String`을 받는다.
- 원시 `SecretBinary` 편의 도우미는 범위에서 제외한다.
- 도우미는 AWS SDK 요청을 생성하는 내부에서만 원시 값을 드러낸다.

### AWS Kotlin SDK 모듈

다음 패키지를 추가한다.

- `io.bluetape4k.aws.kotlin.secretsmanager`
- `io.bluetape4k.aws.kotlin.secretsmanager.model`
- `io.bluetape4k.aws.kotlin.ssm`
- `io.bluetape4k.aws.kotlin.ssm.model`

Kotlin 모듈 API:

- `secretsManagerClientOf(...)`, `withSecretsManagerClient { }`
- `ssmClientOf(...)`, `withSsmClient { }`
- Secrets Manager 네이티브 suspend 도우미:
  - `getSecretString`
  - `listSecrets`
  - `describeSecret`
  - `createSecret`
  - `putSecretValue`
  - `batchGetSecretValues`
- SSM 네이티브 suspend 도우미:
  - `getParameter`
  - `getSecureParameter`
  - `getParameters`
  - `getParametersByPath`
  - `describeParameters`
  - `putParameter`
- 생성된 AWS Kotlin SDK 빌더 형태와 일치하는 요청 빌더 도우미
- `aws-kotlin` 내부의 마스킹된 `AwsSecretValue` 래퍼
- 삭제 편의 래퍼 없음. 파괴적인 삭제 호출은 호출 지점에서 명시적으로 유지하도록
  원시 SDK 클라이언트에 남긴다.
- Kotlin `xxxClientOf(...)` 도우미는 호출자 소유 클라이언트를 반환하며 호출자가
  명시적으로 닫는다. Kotlin `withXxxClient { }` 도우미는 `useSafe`를 통해 클라이언트를
  소유하고 닫는다.

Kotlin 모듈은 `aws-java`에 의존하면 안 된다. 래퍼는 AWS Kotlin SDK 타입에 네이티브해야
한다. 모듈/패키지 접두사가 Java SDK와 AWS Kotlin SDK API를 이미 구분하므로 두 모듈은
각자 패키지에서 같은 공개 타입 이름 `AwsSecretValue`를 사용할 수 있다.

## 값 의미

- Secrets Manager `getSecretString` 도우미는 `AwsSecretValue`를 반환한다.
- Secrets Manager가 `SecretString` 없이 `SecretBinary`를 반환하면 문자열 도우미
  함수는 페이로드 내용이 아니라 연산과 비밀 ID만 포함한 `IllegalStateException`으로 실패한다.
- `SecretBinary`가 필요한 호출자를 위해 원시 `GetSecretValueResponse` 도우미를 유지한다.
- SSM `getSecureParameter` 도우미는 마스킹된 비밀 값을 반환하고
  `withDecryption = true`를 명시적으로 설정한다.
- 비밀이 아닌 값의 SSM `getParameter` 도우미는 일반 SDK 응답 또는 `String` 값을
  반환하며 기본값은 `withDecryption = false`다.
- SSM `StringList` 도우미는 호출자가 비밀이 아닌 도우미를 선택할 때만 일반 목록/문자열 값을 반환한다.
- 누락된 비밀 또는 매개변수의 SDK 예외는 전파한다. 도우미는 누락된 리소스를 빈 문자열이나 성공으로 정규화하면 안 된다.
- 마스킹된 값 객체는 `data class`나 value class 선언이 아닌 일반 클래스다. 비공개
  원시 값, `reveal()`, 마스킹된 `toString`, 마스킹된 상수 `hashCode`, 실용적인 범위의
  상수 시간 동등성, 직렬화할 수 있을 때 `readResolve`를 갖는 `Serializable`, companion
  팩토리, 명시적 직렬화 경계 경고를 제공한다.
- Java와 Kotlin 모듈 모두 패키지 로컬 최상위 팩토리 `awsSecretValueOf(...)`와
  companion `of(...)` / `invoke(...)` 팩토리를 제공한다.
- 마스킹 래퍼는 반환된 비밀/문자열 값만 감싸고 목록 메타데이터 항목은 감싸지 않는다.

## 페이지 처리 및 배치 계약

- 목록/설명/경로 도우미는 기본적으로 SDK 페이지 하나를 반환하고 요청 빌더를 통해
  토큰/최대 결과 수 필드를 노출한다.
- 이 PR은 숨은 전체 페이지 즉시 수집 도우미를 추가하지 않는다.
- 나중에 전체 페이지 도우미를 추가하면 이름에 `All` 또는 `Flow`를 포함하고
  cold/lazy 방식이어야 하며 제한 없는 즉시 수집을 피해야 한다.
- Secrets Manager 일괄 조회 도우미는 비밀 ID 20개 초과를 거부한다.
- SSM 매개변수 조회 도우미는 이름 10개 초과를 거부한다.
- 배치/컬렉션 도우미는 부분 실패 정보를 보존해야 한다. 단순화한 도우미는
  `errors` / `invalidParameters`를 포함한 원시 SDK 응답을 반환하거나 명시적으로
  실패해야 하며, 찾은 값만 전체 성공으로 반환하면 안 된다.
- 이 PR에서 어떤 도우미도 배치 분할에 제한 없는 `async`, 제한 없는
  `CompletableFuture.allOf` 또는 암시적 병렬 fan-out을 사용하면 안 된다.
- null이 아닌 토큰 매개변수가 비어 있으면 거부한다. SDK/서비스 계약이 검증을
  요구하지 않는 한 `maxResults`는 암시적 루프나 범위 제한 없이 호출자가 제어한다.

## 검증 및 오류 처리

- 호출자 입력에는 bluetape4k `requireNotBlank`와 컬렉션 검증 도우미를 사용한다.
- suspend 코드에서 광범위한 예외 처리보다 먼저 `CancellationException`을 다시 던진다.
- 마스킹 이유가 없으면 편의 연산에서 모든 AWS SDK 예외를 잡아 감싸지 않는다. 호출자가
  AWS별 오류 타입을 처리할 수 있도록 서비스 예외를 전파한다.
- 도우미는 사용자 정의 재시도, backoff, 시간 초과 또는 기한 루프를 추가하지 않는다.
  SDK 클라이언트/요청 재정의 설정에 의존하고 코루틴 취소를 보존하며, SDK 정책 밖에서
  쓰기 도우미를 재시도하지 않는다.
- 마스킹 안전 래퍼는 `toString()`을 통해 원시 값을 유출하면 안 된다.
- 테스트는 누락 리소스 예외가 성공으로 정규화되지 않음을 검증해야 한다. 호출자가
  누락된 비밀/매개변수를 요청하면 SDK 서비스 예외 타입이 전파돼야 한다.
- 도우미는 비밀/매개변수 값, `SecretString`, `SecretBinary` 또는 SSM `value`를
  로그에 기록하면 안 된다. 안전한 진단에는 연산 이름, 비밀 ID/ARN, 매개변수 경로/이름,
  사용할 수 있을 때 AWS 요청 ID, 예외 타입을 포함할 수 있다.
- 문서 예제는 드러낸 값을 출력하거나 로그에 기록하면 안 된다. `reveal()`은 명시적
  소비자 경계에서만 나타날 수 있다.

## 테스트 전략

- 요청 빌더와 검증 단위 테스트
- Java 비동기 코루틴 어댑터 및 Kotlin suspend 확장의 MockK 테스트
- 비밀 값 래퍼의 마스킹 테스트
- AWS에 접속하지 않고 리전/엔드포인트 구성으로 클라이언트를 생성하고 닫는 클라이언트 팩토리 테스트
- 클라이언트 팩토리 테스트는 더미 자격 증명, 명시적 로컬 엔드포인트, 명시적 리전을
  사용해야 한다. 단위 범위 테스트는 운영 AWS 엔드포인트나 기본 자격 증명 체인 해석에
  의존하면 안 된다.
- 테스트는 sentinel 원시 비밀 값을 사용하고 도우미/모델 `toString()`, 예외 메시지,
  진단 정보에 sentinel이 없음을 검증한다.
- 테스트는 보안 및 비보안 읽기의 SSM `withDecryption` 매핑을 검증한다.
- 테스트는 Secrets Manager 배치 제한 `21` 거부와 SSM 매개변수 조회 제한 `11` 거부를 검증한다.
- 테스트는 Secrets Manager 일괄 조회와 SSM 매개변수 조회에서 부분 배치 실패 보존을 검증한다.
- 테스트는 블록이 예외를 던지거나 취소될 때 `withSecretsManagerClient` /
  `withSsmClient`가 클라이언트를 닫고 Java 팩토리 소유권이 기존 `ShutdownQueue`
  동작을 따름을 입증한다.
- 에뮬레이터 기반 테스트는 이 PR에서 선택 사항이다. 현재 저장소에서 Secrets Manager
  또는 SSM의 Floci/LocalStack 지원이 안정적이지 않으면 대체 이유를 기록하고 이 PR을
  SDK 래퍼/단위 테스트 범위로 유지한다.
- 에뮬레이터 smoke를 시도하면 `-Dbluetape4k.aws.emulator=floci`로 Floci를 먼저
  실행한다. Floci 지원이 부족하면 정확한 공백을 기록하고 관련 smoke만 명시적 대체
  경로인 `localstack`으로 재시도한다. 에뮬레이터 기반 검사는 순차 실행한다.

## 문서

- 사용자 대상 README 로케일 세트를 갱신한다.
  - `README.md`
  - `README.ko.md`
  - `aws-java/README.md`
  - `aws-java/README.ko.md`
  - `aws-kotlin/README.md`
  - `aws-kotlin/README.ko.md`
- 필수 내용:
  - `bluetape4k-aws-java` 및 `bluetape4k-aws-kotlin` 모듈 행
  - 소비자 애플리케이션의 런타임 의존성 코드 조각:
    `software.amazon.awssdk:secretsmanager`, `software.amazon.awssdk:ssm`,
    `aws.sdk.kotlin:secretsmanager`, and `aws.sdk.kotlin:ssm`
  - bluetape4k 서비스 SDK 의존성은 `compileOnly`이므로 애플리케이션/테스트가 사용하는
    서비스 SDK 모듈을 추가해야 한다는 설명
  - Java SDK 및 AWS Kotlin SDK의 필수 직접 예제: 비밀 문자열 조회, 매개변수 조회,
    경로별 매개변수 조회
  - 예제는 자리표시자를 사용하고 현실적인 비밀을 포함하지 않으며 드러낸 값을 로그에
    남기거나 출력하지 않는다.
  - 미지원 기능 참고 사항: Spring Environment 로딩, JSON 평탄화, 캐시, 새로고침,
    회전 오케스트레이션, IAM/KMS 정책 관리, 전체 페이지 처리 추상화 없음
  - 생성/등록 도우미의 변경 참고 사항: AWS 측 변경/버전 관리 의미, SSM `overwrite`
    동작, 멱등성 제한, 읽기 전용 예제 우선
  - 빈번 호출 경로 지침: 래퍼는 일회성 SDK 호출이다. 호출자는 애플리케이션 범위
    클라이언트를 재사용하고 빈번한 요청 경로에 호출자 소유 캐시를 추가해야 한다.
  - 현재 저장소 문서와 일치하는 의존성 코드 조각 버전 전략. 기존 규칙인 모듈
    README의 `${bluetape4kVersion}`을 우선한다.
- 서비스 지원 표 자산을 갱신한다.
  - `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
  - 일치하는 PNG 재생성
- 생성한 다이어그램 레이블은 영어로 유지한다.

## 롤백 / 되돌리기

- 릴리스 전: 도우미 패키지, 컴파일/테스트 의존성 선언, README 로케일 세트, SVG 표,
  PNG 표를 함께 되돌린다.
- 릴리스 후: 기능이 게시되지 않은 경우가 아니면 패치 릴리스에서 공개 API를 제거하지
  않는다. 사용 중단하거나 호환 릴리스에서 후속 처리한다.
- 런타임 롤백: 래퍼는 AWS 리소스 마이그레이션이나 서비스 측 상태를 만들지 않는다.
  소비자 런타임 의존성은 애플리케이션 소유로 유지한다.
- 문서 롤백: 도우미를 되돌리면 README와 표의 주장도 코드와 함께 되돌려야 한다.

## 위험

1. AWS Kotlin SDK KMP 산출물 좌표는 일반 Maven 산출물 이름과 다르게 해석될 수 있다.
   완화: 카탈로그 별칭을 추가하고 Gradle 변형 해석으로 컴파일을 입증한다.
2. Secrets Manager/SSM 에뮬레이터 동작이 고르지 않을 수 있다. 완화: 에뮬레이터
   smoke 테스트가 안정적이지 않으면 첫 PR을 SDK 래퍼 테스트로 제한한다.
3. 공개 API가 파괴적 연산을 과도하게 장려할 수 있다. 완화: 삭제 편의 래퍼를 추가하지
   않는다. 정책을 바꾸는 삭제 호출은 원시 SDK 클라이언트에 남기고 호출 지점에서
   명시적으로 사용해야 한다.
4. 비밀 값이 로그에 유출될 수 있다. 완화: 마스킹된 값 객체와 진단 출력 테스트를 사용한다.

## 인수 기준

- `aws-java`가 일반적인 get/list/put 흐름을 위한 Secrets Manager 및 SSM 클라이언트
  팩토리, 요청 빌더, 동기 확장, 비동기 확장, 코루틴 어댑터를 노출한다.
- `aws-kotlin`이 일반적인 get/list/put 흐름을 위한 Secrets Manager 및 SSM 클라이언트
  팩토리와 네이티브 suspend 확장을 노출한다.
- AWS 서비스 의존성은 선택적 `compileOnly` 의존성으로 유지하며 테스트에는
  `testImplementation`을 사용한다.
- 테스트는 요청 검증, 마스킹, 클라이언트 팩토리 생성, 비동기 코루틴 어댑터,
  Kotlin suspend 확장, 대표 누락 리소스 전파를 검증한다.
- 테스트는 단일 페이지 도우미의 페이지 처리 토큰 노출을 검증하고 숨은 전체 페이지
  즉시 수집 동작을 거부한다.
- 테스트는 Secrets Manager와 SSM 컬렉션 도우미의 배치 제한 검증을 다룬다.
- README 루트/모듈 로케일 세트, SVG 표, PNG 표를 갱신한다.
- PR DoD에 시도한 에뮬레이터 백엔드나 에뮬레이터 검증을 생략한 이유를 기록한다.
- 대상 컴파일/테스트와 `git diff --check`가 통과한다.
