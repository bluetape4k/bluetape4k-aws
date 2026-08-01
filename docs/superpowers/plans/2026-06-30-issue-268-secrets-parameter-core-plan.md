# Secrets Manager 및 Parameter Store 핵심 구현 계획

> **에이전트 작업자 안내:** 필수 하위 스킬로 superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans를 사용해 이 계획을 작업 단위로 구현합니다. 진행 상황은 체크박스(`- [ ]`) 문법으로 추적합니다.

**목표:** `bluetape4k-aws-java`와 `bluetape4k-aws-kotlin`에 프레임워크 독립적인 Secrets Manager 및 SSM Parameter Store 도우미를 추가합니다.

**아키텍처:** 기존 서비스 래퍼 패턴을 따릅니다. Java SDK v2에는 동기·비동기·코루틴 어댑터를 제공하고 AWS Kotlin SDK에는 네이티브 일시 중단 도우미를 제공합니다. 비밀 값을 담은 데이터에는 모듈 로컬 마스킹 값 객체를 사용하며 원시 바이너리 페이로드 도우미는 원시 SDK 호출에 둡니다.

**기술 스택:** Kotlin 2.4, Java 21/25 호환 Gradle 모듈, AWS Java SDK v2 `secretsmanager`/`ssm`, AWS Kotlin SDK `secretsmanager`/`ssm`, MockK, JUnit 5, bluetape4k-assertions.

**실행 메모:** 구현을 시작하기 전에 이 명세와 계획을 커밋합니다. 구현 커밋은 계획 산출물 커밋과 분리해야 합니다.

---

## 파일 구성

- 수정: `gradle/libs.versions.toml`
  - `aws-kotlin-secretsmanager`와 `aws-kotlin-ssm` 별칭을 추가합니다.
- 수정: `aws-java/build.gradle.kts`
  - `libs.aws2.secretsmanager`와 `libs.aws2.ssm`의 `compileOnly` 및 `testImplementation` 의존성을 추가합니다.
- 수정: `aws-kotlin/build.gradle.kts`
  - `libs.aws.kotlin.secretsmanager`와 `libs.aws.kotlin.ssm`의 `compileOnly` 및 `testImplementation` 의존성을 추가합니다.
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/AwsSecretValue.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerAsyncClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerAsyncClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerAsyncClientCoroutinesExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/model/SecretsManagerRequestSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmAsyncClientSupport.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmAsyncClientExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/SsmAsyncClientCoroutinesExtensions.kt`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/ssm/model/SsmRequestSupport.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/AwsSecretValueTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/secretsmanager/SecretsManagerSupportTest.kt`
- 생성: `aws-java/src/test/kotlin/io/bluetape4k/aws/ssm/SsmSupportTest.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/AwsSecretValue.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/SecretsManagerClientSupport.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/SecretsManagerClientExtensions.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/model/SecretsManagerRequestSupport.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm/SsmClientSupport.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm/SsmClientExtensions.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm/model/SsmRequestSupport.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/AwsSecretValueTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/SecretsManagerClientSupportTest.kt`
- 생성: `aws-kotlin/src/test/kotlin/io/bluetape4k/aws/kotlin/ssm/SsmClientSupportTest.kt`
- 수정: `README.md`, `README.ko.md`, `aws-java/README.md`, `aws-java/README.ko.md`, `aws-kotlin/README.md`, `aws-kotlin/README.ko.md`
- 수정: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`
- 재생성: `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png`

## 작업 1: 의존성 및 마스킹 값

**복잡도:** 중간

**적용:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`

**파일:**
- 수정: `gradle/libs.versions.toml`
- 수정: `aws-java/build.gradle.kts`
- 수정: `aws-kotlin/build.gradle.kts`
- 생성: `aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager/AwsSecretValue.kt`
- 생성: `aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager/AwsSecretValue.kt`
- 파일 구성에 나열한 테스트를 생성합니다.

- [x] **1단계: 실패하는 마스킹 테스트 작성**

다음 내용을 검증하는 테스트를 작성합니다.

- 빈 값은 `IllegalArgumentException`을 던집니다.
- `reveal()`은 원시 값을 반환합니다.
- `toString()`은 `"****"`입니다.
- 같은 원시 값은 동등하지만 원시 값을 노출하지 않습니다.
- `hashCode()`는 마스킹 표식의 해시와 같습니다.
- 예외 메시지와 문자열 표현에 센티널 원시 값이 없습니다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*AwsSecretValueTest' :bluetape4k-aws-kotlin:test --tests '*AwsSecretValueTest' --no-configuration-cache
```

예상 결과: 클래스가 없으므로 실패합니다.

- [x] **2단계: 의존성 별칭과 선언 추가**

`gradle/libs.versions.toml`에 다음을 추가합니다.

```toml
aws-kotlin-secretsmanager = { module = "aws.sdk.kotlin:secretsmanager", version.ref = "aws-kotlin" }
aws-kotlin-ssm = { module = "aws.sdk.kotlin:ssm", version.ref = "aws-kotlin" }
```

`aws-java/build.gradle.kts`의 서비스 의존성에 다음을 추가합니다.

```kotlin
compileOnly(libs.aws2.secretsmanager)
compileOnly(libs.aws2.ssm)
testImplementation(libs.aws2.secretsmanager)
testImplementation(libs.aws2.ssm)
```

`aws-kotlin/build.gradle.kts`의 서비스 의존성에 다음을 추가합니다.

```kotlin
compileOnly(libs.aws.kotlin.secretsmanager)
compileOnly(libs.aws.kotlin.ssm)
testImplementation(libs.aws.kotlin.secretsmanager)
testImplementation(libs.aws.kotlin.ssm)
```

- [x] **3단계: Java `AwsSecretValue` 구현**

`AwsRdsIamAuthToken` 패턴을 따른 일반 `Serializable` 클래스를 사용하며 다음을 포함합니다.

- 비공개 생성자
- `reveal()`
- 마스킹된 `toString()`
- `MessageDigest.isEqual`을 사용한 상수 시간 동등성 비교
- 마스킹된 `hashCode()`
- 컴패니언의 `REDACTED`, `invoke`, `of`
- 최상위 `awsSecretValueOf`
- 공개 클래스, 팩토리, `reveal()`에 영어 KDoc을 추가하고 원시 값은 명시적인 소비자 경계에서만 전달해야 함을 경고합니다.

- [x] **4단계: Kotlin 모듈의 `AwsSecretValue` 구현**

`io.bluetape4k.aws.kotlin.secretsmanager` 패키지에서 같은 계약을 사용합니다.
Java 모듈 래퍼와 같은 KDoc 및 마스킹 보장을 적용합니다.

- [x] **5단계: 마스킹 테스트 실행**

1단계와 같은 Gradle 명령을 실행합니다.

예상 결과: 통과합니다.

## 작업 2: Java SDK v2 Secrets Manager 도우미

**복잡도:** 높음

**적용:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`

**파일:** 파일 구성에 나열한 Java Secrets Manager 메인/테스트 파일입니다.

- [x] **1단계: 실패하는 요청·클라이언트·확장 테스트 작성**

테스트는 다음을 포함해야 합니다.

- `secretsManagerClientOf`와 `secretsManagerAsyncClientOf`는 로컬 엔드포인트, 리전, 정적 더미 자격 증명으로 클라이언트를 생성합니다.
- Java 동기 및 비동기 클라이언트 팩토리는 기존 `ShutdownQueue` 소유권을 따릅니다. 직접 관찰할 수 없다면 가장 가까운 기존 S3/SNS/STS 팩토리 테스트 패턴을 사용하고 관찰 공백을 DoD에 기록합니다.
- 요청 빌더는 빈 비밀 ID를 검증하고 배치 ID가 20개를 초과하면 거부합니다.
- `getSecretString`은 `secretString`을 `AwsSecretValue`로 감쌉니다.
- `getSecretString`은 `secretBinary`만 있을 때 안전하게 실패합니다.
- `createSecret`과 `putSecretValue`는 `AwsSecretValue`를 받고 도우미의 `toString()`을 통해 센티널 값을 노출하지 않습니다.
- 비동기 코루틴 어댑터는 비동기 메서드를 호출하고 `await()`합니다.
- 코루틴 어댑터는 일시 중단된 비동기 호출의 `CancellationException`을 감싸지 않고 전파합니다.
- Java 동기·비동기·코루틴 도우미는 `ResourceNotFoundException` 같은 SDK 리소스 누락 예외를 빈 성공으로 정규화하지 않고, 원래 AWS 예외 형식·원인·요청 메타데이터·메시지를 일반 예외로 감싸지 않은 채 전파합니다.
- 코루틴 취소 테스트에는 실제 `runTest` 취소와 예외 완료된 `CompletableFuture(CancellationException)` 사례를 포함합니다.
- 목록/배치 도우미는 호출마다 SDK를 한 번 호출하고 `nextToken`/`maxResults`를 보존하며, 배치를 분할하거나 `CompletableFuture.allOf`를 호출하거나 제한 없는 `async` 팬아웃을 시작하지 않습니다.
- 배치 도우미는 성공 항목만 반환하지 않고 원시 SDK 응답 오류를 보존합니다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*SecretsManager*' --no-configuration-cache
```

예상 결과: 도우미가 없으므로 실패합니다.

- [x] **2단계: 클라이언트 팩토리 구현**

`SnsClientSupport.kt`와 `SnsAsyncClientSupport.kt`를 따릅니다.

- `secretsManagerClient { }`
- `secretsManagerClientOf(region, httpClient, builder)`
- `secretsManagerClientOf(endpoint, region, credentialsProvider, httpClient, builder)`
- 대응하는 비동기 팩토리
- Java 클라이언트를 `ShutdownQueue`에 등록합니다.

- [x] **3단계: 요청 빌더 구현**

범위가 명확한 다음 빌더를 생성합니다.

- `getSecretValueRequestOf(secretId, versionId?, versionStage?, overrideConfiguration?, builder)`
- `batchGetSecretValueRequestOf(secretIds, maxResults?, nextToken?, overrideConfiguration?, builder)`
- `listSecretsRequestOf(maxResults?, nextToken?, overrideConfiguration?, builder)`
- `describeSecretRequestOf(secretId, overrideConfiguration?, builder)`
- `createSecretRequestOf(name, secretValue, description?, clientRequestToken?, overrideConfiguration?, builder)`
- `putSecretValueRequestOf(secretId, secretValue, clientRequestToken?, versionStages?, overrideConfiguration?, builder)`

- [x] **4단계: 동기·비동기·코루틴 확장 구현**

공통 조회/목록/저장 도우미를 추가합니다. 삭제 래퍼는 추가하지 않습니다. 배치 도우미는 부분 실패를 보존하도록 원시 SDK 응답을 반환합니다.
공개 팩토리, 요청 빌더, 확장 도우미에 영어 KDoc을 추가합니다. 변경 도우미는 AWS 측 변경/버전 의미를 명시하고 비밀 값을 로그에 남기거나 출력하지 않아야 합니다.
문자열 도우미가 바이너리 페이로드만 받는 경우처럼 마스킹에 특화된 안전한 실패를 제외하고 광범위한 포착/래핑 블록을 추가하지 않습니다.

- [x] **5단계: Java Secrets Manager 테스트 실행**

1단계의 명령을 실행합니다.

예상 결과: 통과합니다.

## 작업 3: Java SDK v2 SSM 도우미

**복잡도:** 높음

**적용:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`

**파일:** 파일 구성에 나열한 Java SSM 메인/테스트 파일입니다.

- [x] **1단계: 실패하는 SSM 테스트 작성**

테스트는 다음을 포함해야 합니다.

- 로컬 엔드포인트와 정적 자격 증명을 사용한 클라이언트 팩토리 생성
- Java 동기 및 비동기 클라이언트 팩토리는 기존 `ShutdownQueue` 소유권을 따릅니다. 직접 관찰할 수 없다면 가장 가까운 기존 S3/SNS/STS 팩토리 테스트 패턴을 사용하고 관찰 공백을 DoD에 기록합니다.
- 빈 이름/경로/토큰 요청 검증
- `getSecureParameter`는 `withDecryption = true`를 매핑하고 `AwsSecretValue`를 반환합니다.
- 비보안 `getParameter`는 `withDecryption = false`를 매핑합니다.
- `putSecureParameter`는 `SecureString`에 `AwsSecretValue`를 받으며, 원시 `String` 쓰기 도우미는 명시적으로 비밀이 아닌 `String` / `StringList` 파라미터 API로 제한합니다.
- 원시 문자열 `SecureString` 편의 오버로드가 없고, 보안 쓰기 도우미의 `toString()` / 검증 오류에는 센티널 비밀 값이 없습니다.
- `getParameters`는 이름이 10개를 초과하면 거부합니다.
- `getParametersByPath`는 숨겨진 반복 없이 `nextToken`과 `maxResults`를 노출합니다.
- 부분적으로 잘못된 파라미터를 원시 SDK 응답에 보존합니다.
- 비동기 코루틴 어댑터는 비동기 호출을 기다립니다.
- 코루틴 어댑터는 일시 중단된 비동기 호출의 `CancellationException`을 감싸지 않고 전파합니다.
- Java 동기·비동기·코루틴 도우미는 `ParameterNotFoundException` 같은 SDK 리소스 누락 예외를 빈 성공으로 정규화하지 않고, 원래 AWS 예외 형식·원인·요청 메타데이터·메시지를 일반 예외로 감싸지 않은 채 전파합니다.
- 코루틴 취소 테스트에는 실제 `runTest` 취소와 예외 완료된 `CompletableFuture(CancellationException)` 사례를 포함합니다.
- 경로/설명 도우미는 호출마다 SDK를 한 번 호출하고 `nextToken`/`maxResults`를 보존하며, 배치를 분할하거나 `CompletableFuture.allOf`를 호출하거나 제한 없는 `async` 팬아웃을 시작하지 않습니다.

실행:

```bash
./gradlew :bluetape4k-aws-java:test --tests '*Ssm*' --no-configuration-cache
```

예상 결과: 구현 전에는 실패합니다.

- [x] **2단계: SSM 팩토리 및 요청 빌더 구현**

Secrets Manager 형식을 따릅니다.

- `ssmClient { }`, `ssmClientOf(...)`
- `ssmAsyncClient { }`, `ssmAsyncClientOf(...)`
- 파라미터 조회, 여러 파라미터 조회, 경로별 파라미터 조회, 보안 파라미터 저장, 문자열 파라미터 저장, 문자열 목록 파라미터 저장, 파라미터 설명용 요청 빌더

- [x] **3단계: 동기·비동기·코루틴 확장 구현**

공통 조회/목록/저장 도우미를 추가합니다. 삭제 래퍼나 숨겨진 전체 페이지 수집 도우미는 추가하지 않습니다.
공개 팩토리, 요청 빌더, 확장 도우미에 영어 KDoc을 추가합니다. `putSecureParameter` KDoc은 SecureString 평문 처리, `overwrite` 의미, 호출자 책임을 명시해야 합니다. 비보안 쓰기 도우미는 별도 이름을 사용하고 SecureString 쓰기에 원시 문자열을 받지 않아야 합니다.

- [x] **4단계: Java SSM 테스트 실행**

1단계의 명령을 실행합니다.

예상 결과: 통과합니다.

## 작업 4: AWS Kotlin SDK Secrets Manager 및 SSM 도우미

**복잡도:** 높음

**적용:** `$bluetape4k-code-patterns`, `$ecc-kotlin-testing`, `$kotlin-coroutines-skill`

**파일:** 파일 구성에 나열한 Kotlin Secrets Manager 및 SSM 파일입니다.

- [x] **1단계: 실패하는 Kotlin SDK 테스트 작성**

테스트는 다음을 포함해야 합니다.

- `secretsManagerClientOf`, `ssmClientOf`, `withSecretsManagerClient`, `withSsmClient`
- 클라이언트 팩토리 테스트는 더미 정적 자격 증명, localhost 엔드포인트, 명시적 리전을 사용하며 기본 자격 증명 공급자 체인과 운영 AWS 엔드포인트를 구조적으로 피해야 합니다.
- `withXxxClient`는 정상 반환, 예외 발생, 취소 시 닫힙니다.
- 요청 빌더 검증과 배치 제한
- 명세에 정의된 정확한 작업: `getSecretString`, `listSecrets`, `describeSecret`, `createSecret`, `putSecretValue`, `batchGetSecretValues`, `getParameter`, `getSecureParameter`, `getParameters`, `getParametersByPath`, `describeParameters`, `putParameter`
- SSM 쓰기 API는 보안/비보안 쓰기를 분리합니다. `SecureString`에는 `putSecureParameter(..., AwsSecretValue, ...)`를 사용하고, 비밀이 아닌 원시 문자열 도우미는 `String` / `StringList`에만 제공하며 원시 문자열 `SecureString` 오버로드는 두지 않습니다.
- 보안 쓰기 도우미의 `toString()` / 검증 오류에는 센티널 비밀 값이 없습니다.
- `toString()`이나 예외 메시지에 원시 센티널 값이 나타나지 않습니다.
- SDK 누락 예외가 전파됩니다.
- 광범위한 예외를 포착하는 일시 중단 도우미가 있다면 래핑하거나 로깅하기 전에 `CancellationException`을 다시 던집니다.
- 취소 테스트에는 `withSecretsManagerClient`와 `withSsmClient`의 실제 `runTest` / `Job.cancel()` 검증을 포함합니다.
- 목록/경로/설명 도우미는 호출마다 SDK를 한 번 호출하고 `nextToken`/`maxResults`를 보존하며, 배치를 분할하거나 제한 없는 `async` 팬아웃을 시작하지 않습니다.
- 컬렉션 도우미는 원시 SDK 응답을 통해 부분 오류/잘못된 파라미터를 보존하고 성공 항목만 조용히 반환하지 않습니다.

실행:

```bash
./gradlew :bluetape4k-aws-kotlin:test --tests '*SecretsManager*' --tests '*Ssm*' --no-configuration-cache
```

예상 결과: 구현 전에는 실패합니다.

- [x] **2단계: Kotlin 클라이언트 팩토리 구현**

기존 `sqsClientOf` / `withSqsClient` 패턴을 따릅니다.

- `secretsManagerClientOf(endpointUrl, region, credentialsProvider, httpClient, builder)`
- `withSecretsManagerClient(...)`
- `ssmClientOf(...)`
- `withSsmClient(...)`

- [x] **3단계: Kotlin 요청 빌더 및 확장 구현**

명세에 정의된 작업을 정확히 구현합니다. 포착 블록을 도입한다면 광범위한 예외 포착 전에 `CancellationException`을 다시 던집니다.
공개 팩토리, 요청 빌더, 일시 중단 도우미에 영어 KDoc을 추가합니다. `xxxClientOf` 도우미는 호출자가 소유하며, `withXxxClient` 도우미는 정상 반환, 예외 발생, 취소 시 `useSafe`로 클라이언트를 닫습니다.

- [x] **4단계: Kotlin SDK 테스트 실행**

1단계의 명령을 실행합니다.

예상 결과: 통과합니다.

## 작업 5: 문서 및 다이어그램 자산

**복잡도:** 중간

**적용:** `$bluetape4k-code-patterns`, `$bluetape4k-diagram`

**파일:** README 언어별 문서 세트와 서비스 지원 범위 차트입니다.

- [x] **1단계: README 언어별 문서 세트 갱신**

필요한 모든 README 파일에 다음 내용을 반영합니다.

- 런타임 의존성
- compileOnly 설명
- 비밀 문자열 조회, 파라미터 조회, 경로별 파라미터 조회의 직접 예제
- 지원하지 않는 기능
- 변경 작업 경고
- 핫 패스에서 호출자가 소유하는 캐시 지침
- 공개한 비밀 값을 로그에 남기거나 출력하는 예제 금지
- 차트 갱신 후에도 해석되는 로컬 이미지/링크 참조

루트 README 쌍에 간단한 기능 경계를 둡니다. 두 모듈 README 언어별 쌍에 `Not provided by this module` 섹션을 추가하고 Spring Environment 로딩, JSON 평탄화, 캐싱, 새로 고침, 로테이션 오케스트레이션, IAM/KMS 정책 관리, 전체 페이지 페이지네이션 추상화를 다룹니다.

`README.md`/`README.ko.md`, `aws-java/README.md`/`aws-java/README.ko.md`, `aws-kotlin/README.md`/`aws-kotlin/README.ko.md`에 걸쳐 README 동등성 감사를 실행하고 기록합니다.

- 필수 제목/섹션이 두 언어에 모두 존재합니다.
- 필수 런타임 의존성 스니펫이 두 언어에 모두 존재합니다.
- 필수 예제가 두 언어에 모두 존재합니다.
- 지원하지 않는 기능과 변경 경고가 두 언어에 모두 존재합니다.
- 코드 블록 수와 서비스 이름 키워드 수에 명백한 차이가 없는지 검토합니다.

README 예제는 컴파일되는 테스트 픽스처에서 복사하거나 구현된 API 이름과 수동으로 대조해야 합니다. 스니펫을 컴파일하지 않았다면 PR DoD에 `manual source-checked, not compiled`를 기록합니다.

- [x] **2단계: 서비스 지원 범위 차트 SVG 갱신**

기존 차트 범례에 따라 Secrets Manager 및 Parameter Store의 `bluetape4k-aws-java`와 `bluetape4k-aws-kotlin` 지원 범위를 안정/지원 상태로 표시합니다.

- [x] **3단계: PNG 재생성 및 시각 검사**

실행:

```bash
svg=docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg
png=docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.png
xmllint --noout "$svg"
~/.local/bin/cairosvg "$svg" -o "$png" -s 2
file "$png"
grep -RInE 'println\\([^)]*reveal\\(|logger\\.[a-z]+\\([^)]*reveal\\(|log\\.[a-z]+\\([^)]*reveal\\(' README.md README.ko.md aws-java/README.md aws-java/README.ko.md aws-kotlin/README.md aws-kotlin/README.ko.md && exit 1 || true
python3 - <<'PY'
import re
from pathlib import Path
readmes = ["README.md", "README.ko.md", "aws-java/README.md", "aws-java/README.ko.md", "aws-kotlin/README.md", "aws-kotlin/README.ko.md"]
inline_link = re.compile(r'!?\[[^\]]*\]\(([^)\\s]+)(?:\\s+"[^"]*")?\)')
reference_def = re.compile(r'^\s*\[[^\]]+\]:\s+(\S+)', re.MULTILINE)
for readme in readmes:
    text = Path(readme).read_text()
    links = [m.group(1) for m in inline_link.finditer(text)]
    links.extend(m.group(1) for m in reference_def.finditer(text))
    missing = 0
    checked = 0
    for link in links:
        if link.startswith(("http://", "https://", "#", "mailto:")):
            continue
        target = (Path(readme).parent / link.split("#", 1)[0]).resolve()
        checked += 1
        if not target.exists():
            missing += 1
            print(f"{readme}: missing {link}")
    print(f"{readme}: local_links_checked={checked} missing={missing}")
    assert missing == 0
PY
```

예상 결과: SVG가 유효하고 CairoSVG가 예상 크기의 PNG를 생성하며 로컬 README 이미지/링크 참조가 해석되고 공개한 비밀 값을 로그에 남기거나 출력하는 README 예제가 없습니다. SVG 파싱, CairoSVG 렌더링, PNG 크기, 원본 크기 PNG 검사, 잘린 텍스트 없음, 겹치는 레이블 없음, 영어 레이블, 올바른 Secrets Manager / Parameter Store 셀을 시각 QA 증거 원장에 기록합니다. 이 서비스 지원 범위 차트는 커넥터/카드 흐름 다이어그램이 아니므로 `connector-heavy audits=N/A`도 기록합니다.

## 작업 6: 검증, 리뷰, 교훈, PR

**복잡도:** 높음

**적용:** `$verification-before-completion`, `$bluetape4k-code-patterns`

- [x] **1단계: 대상별 컴파일/테스트 실행**

실행:

```bash
./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileKotlin :bluetape4k-aws-kotlin:compileTestKotlin :bluetape4k-aws-java:test --tests '*SecretsManager*' --tests '*Ssm*' :bluetape4k-aws-kotlin:test --tests '*SecretsManager*' --tests '*Ssm*' --no-configuration-cache
```

예상 결과: 빌드가 성공합니다.

- [x] **2단계: 정적 검사 및 문서 검사 실행**

실행:

```bash
git diff --check
grep -RInE 'CompletableFuture\\.allOf|\\basync\\s*\\{|\\bwithTimeout(OrNull)?\\b|\\bdelay\\(|retry\\b|backoff\\b' aws-java/src/main/kotlin/io/bluetape4k/aws/secretsmanager aws-java/src/main/kotlin/io/bluetape4k/aws/ssm aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/secretsmanager aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/ssm || true
```

예상 결과: `git diff --check` 출력이 없습니다. 정적 grep은 변경한 도우미에서 사용자 정의 재시도/백오프/기한/팬아웃을 찾지 않아야 합니다. 의도적인 일치 항목은 설명하고 수동 재시도 로직이 아니라 SDK/요청 재정의 구성과 연결해야 합니다.

- [x] **3단계: API 문서 및 경고 검사 실행**

변경한 공개 API의 영어 KDoc을 검토하고 컴파일 경고를 실행합니다.

```bash
./gradlew :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-kotlin:compileTestKotlin --warning-mode all --no-configuration-cache
```

예상 결과: 변경한 코드에 해결되지 않은 사용 중단 경고나 공개 API 문서 공백이 없습니다.

- [x] **4단계: 명세와 계획에 대해 5단계 검증기 실행**

모든 인수 기준이 구현과 테스트에 대응하는지 확인합니다.

- [x] **5단계: 6-R 코드 리뷰 실행**

`aws-java`, `aws-kotlin`, 문서/차트 변경을 모듈별로 나눠 리뷰합니다. P0/P1은 0건이어야 합니다.

- [x] **6단계: 교훈 추가, 커밋, 푸시, PR 생성**

`docs/lessons/2026-06-30-issue-268-secrets-parameter-core.md`를 생성하고 Lore 트레일러와 함께 커밋한 뒤 푸시합니다. #268을 닫는 PR을 생성하고 `debop`을 할당하며 이슈 마일스톤과 레이블을 복사합니다. 실제 PR 본문의 마지막 섹션이 `## DoD Status`인지 확인합니다.

PR DoD 행에는 다음 내용을 포함해야 합니다.

- 루트, `aws-java`, `aws-kotlin`의 README 영문/한글 동등성 감사
- 런타임 의존성 스니펫과 `compileOnly` 설명
- 검증된 예제와 공개한 비밀 값 로깅 없음
- 지원하지 않는 기능과 변경 경고
- SVG 파싱, CairoSVG 렌더링, PNG 크기, 원본 크기 시각 검사, 차트 증거 원장
- 로컬 이미지/링크 검증
- 대상별 컴파일/테스트와 정적 재시도/팬아웃 grep

- [ ] **7단계: CI 및 병합 게이트**

PR 검사가 통과하면 리뷰/댓글을 확인한 뒤 DoD를 보고하고 사용자의 병합 지시를 기다립니다.
