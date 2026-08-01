# 이슈 294 코드 패턴 사전 점검 설계

## 목적

0.4.0 배포 전에 `bluetape4k-aws` 전체 코드에서 `bluetape4k-code-patterns` 위반과
bluetape4k 생태계 재사용 누락을 선별해 수정한다. 사용자 요청의 예시는 대표 신호이며,
이번 작업은 저장소 전체 검사 결과를 기준으로 P0/P1과 확신도가 높은 P2 개선을 우선 처리한다.

## 범위

- 대상 저장소: `bluetape4k-aws`
- 대상 브랜치: `refactor/issue-294-code-patterns-preflight`
- 대상 모듈:
  - 배포 모듈: `aws-java`, `aws-kotlin`, `aws-exposed`, `aws-ktor`, `aws-spring-boot`
  - 예제와 테스트 코드는 변경 범위에 속하거나 패턴 위반이 명확할 때만 포함
- 제외:
  - 대규모 공개 API 재설계
  - 새 의존성 도입
  - Testcontainers 기반 전체 매트릭스 병렬 실행
  - 배포 워크플로 실행

## 현재 스캔 증거

### 데이터 클래스 직렬화

프로덕션 `src/main` 데이터 클래스 검사 결과:

| 모듈 | 데이터 클래스 | `Serializable` 누락 | 직렬화 가능하지만 UID 누락 |
|---|---:|---:|---:|
| `aws-java` | 9 | 6 | 3 |
| `aws-kotlin` | 7 | 5 | 1 |
| `aws-exposed` | 7 | 1 | 0 |
| `aws-ktor` | 23 | 17 | 0 |
| `aws-spring-boot` | 60 | 21 | 1 |

규칙: 배포 모듈의 프로덕션 데이터 클래스가 공개 객체, 구성 객체, 값 객체를 이루거나 직렬화,
캐시, 테스트 픽스처 경계를 넘을 가능성이 있으면 `java.io.Serializable`을 구현하고
`serialVersionUID`를 정의해야 한다. 비공개 구현 레코드는 위험이 낮을 때 수정하고,
그렇지 않으면 후속 작업으로 기록한다.

### 생성자 검증과 팩토리 보호 절차

확인한 구체적인 대상:

- `aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsSecretString.kt`
  - 공개 생성자가 `init`에서 검증한다.
  - `AwsSecretString.of(value)`는 사전 검증 없이 위임한다.
  - `awsSecretStringOf(value)`는 사전 검증 없이 위임한다.

규칙: 생성자 입력을 검증하는 값 객체는 비공개 생성자와
`companion object operator fun invoke(...)` 조합을 우선 사용하고, 모든 공개 팩토리는
객체를 만들기 전에 입력을 검증해야 한다.

### 코루틴 블로킹 경계

현재 검사 결과:

- Ktor 런타임 종료 경로는 이미 `runInterruptible`을 사용한다.
- 여러 경로가 `withContext(Dispatchers.IO) { runInterruptible { ... } }`를 사용한다.
- Ktor 중지 훅은 동기식이므로 Ktor 플러그인 수명 주기 코드가 범위가 제한된
  `runBlocking(Dispatchers.IO)`을 사용한다. 로컬 비동기 수명 주기 API를 사용할 수 없다면
  검토를 마친 예외로 인정한다.

규칙: suspend 함수에서 직접 블로킹 정리를 수행할 때는 `runInterruptible(Dispatchers.IO)`을
사용해야 한다. 동기식 프레임워크 수명 주기 브리지는 프레임워크 훅이 suspend 함수가 아니고
호출 범위가 제한될 때만 `runBlocking(Dispatchers.IO)`을 사용할 수 있다.

### 생태계 재사용 누락

검사에서 확인한 신호:

- `AwsJdbcDataSourceFactory`는 Hikari와 DriverManager 기반 래퍼를 직접 사용한다. 카탈로그에서
  해당 의존성을 노출할 수 있다면 Hikari 생성에 `bluetape4k-jdbc` 도우미를 사용해야 한다.
  RDS IAM DriverManager 기반 래퍼는 물리 연결마다 갱신된 토큰을 주입해야 하므로 별도의
  재사용 가능한 추상화가 필요하다.
- 여러 테스트 파일이 여전히 JUnit/Kotlin assertion API를 직접 가져온다.
  - `HttpClientEngineProviderTest`
  - `CrtHttpEngineSupportTest`
  - `SesV2ClientExtensionsMockTest`
  - `KmsEncryptedFieldCodecTest`
  - `SqsClientExtensionsTest`
  - `SqsExamples`
  - `kotlin.test.assertFailsWith`를 사용하는 Kinesis 테스트
- 테스트와 예제, 최소 한 개의 DSL nonce 도우미에서 UUID/random 사용이 나타난다. 프로덕션
  nonce 생성은 결정적 동작과 생태계 유틸리티 재사용 관점에서 검토해야 한다. UUID 자체의
  고유성을 검증하는 테스트 이름은 UUID를 유지할 수 있지만, 그 밖에는 `Base58.randomString(8)`이나
  기존 테스트 도우미를 우선 사용한다.

## 우선순위

| 우선순위 | 범주 | 필수 조치 |
|---|---|---|
| P0 | 동작/보안 회귀 | PR 전에 수정한다. 현재 알려진 항목은 없다. |
| P1 | 잘못된 상태, 취소 누수, 공개 API 오용을 일으킬 수 있는 패턴 위반 | PR 전에 수정한다. |
| P2 | 광범위하지만 위험이 낮은 일관성 문제 | 기계적으로 수정할 수 있고 컴파일/테스트로 검증되면 수정하고, 그렇지 않으면 후속 작업을 만든다. |
| P3 | 외관/스타일 문제 | 변경한 영역이 아니면 미룬다. |

## 설계 결정

1. `AwsSecretString`는 비공개 생성자, companion `operator fun invoke`, 입력을 검증하는 `of`/최상위 팩토리 조합으로 개선한다.
2. 배포 모듈의 프로덕션 데이터 클래스에는 컴파일 안전성이 보장되는 범위에서 `Serializable`과 `serialVersionUID`를 보강한다.
3. 코루틴 정리 경로는 `runInterruptible(Dispatchers.IO)` 형태로 단순화하되, 동기식 프레임워크 수명 주기 브리지는 현재 예외로 남기고 검토 기록에 근거를 남긴다.
4. assertion 직접 import는 변경했거나 신호가 뚜렷한 파일부터 `bluetape4k-assertions`로 교체한다.
5. JDBC/DataSource 직접 사용은 Hikari 생성처럼 도우미 적용 범위가 정확한 영역만 이 PR에서 치환하고,
   RDS IAM 연결별 DriverManager 경로는 후속 이슈 #295로 전환한다.

## 수용 기준

- #294 이슈 본문과 설계/계획 문서의 저장소 전체 범위가 일치한다.
- 검사 근거를 검토 기록에 남긴다.
- P0/P1 발견 항목이 0개가 될 때까지 수정한다.
- 대상 Gradle 검사가 통과한다.
- PR 본문의 마지막 섹션은 `## DoD Status`다.
