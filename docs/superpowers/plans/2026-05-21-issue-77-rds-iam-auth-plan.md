# 이슈 #77 RDS IAM 인증 계획

날짜: 2026-05-21
설계: `docs/superpowers/specs/2026-05-21-issue-77-rds-iam-auth-design.md`

## 작업 1: RDS IAM 구성 모델 추가

복잡도: 중간

- `AwsDatabaseAuthenticationMode`를 추가한다.
- `AwsRdsIamAuthenticationProperties`를 추가한다.
- 명시적 인증 모드와 선택적 RDS IAM 설정으로 `AwsDatabaseConnectionProperties`를 확장한다.
- 기존 호출 지점의 기본값을 `STATIC_PASSWORD`로 설정해 소스 호환성을 유지한다.
- 공백이 아닌 리전/호스트 이름/사용자 이름, `port in 1..65535`, `tokenTtl <= 15 minutes`,
  `refreshBeforeExpiry < tokenTtl`, 잘못 혼합된 정적 비밀번호/RDS IAM 설정을 검증한다.
- 새로 만들거나 변경한 모든 공개 타입에 영어 KDoc을 추가한다.
- 모든 공개 모델 클래스를 `serialVersionUID`를 포함해 직렬화 가능하게 유지한다.
- 검증: `./gradlew :bluetape4k-aws-exposed:compileKotlin`.

## 작업 2: 토큰 요청 및 생성기 계약 추가

복잡도: 중간

- `AwsRdsIamAuthTokenRequest`를 추가한다.
- `AwsRdsIamAuthTokenGenerator`를 추가한다.
- AWS SDK Java v2 `RdsUtilities.generateAuthenticationToken` 기반 `AwsSdkRdsIamAuthTokenGenerator`를 추가한다.
- 마스킹 안전 실패 메시지를 갖춘 `AwsRdsIamAuthTokenException`을 추가한다.
- 가능한 경우 SDK 기반 생성기 설정을 즉시 검증한다. `Region.of(...)`를 파싱하고
  `RdsUtilities` 클래스 사용 가능 여부를 확인하며, SDK 지원이 없으면 마스킹 안전한
  `AwsRdsIamAuthTokenException`으로 실패한다.
- KDoc에 생성기 수명 주기를 명시한다. 호출자가 제공한 `RdsUtilities`는 호출자가 관리하며 생성기가 닫지 않는다.
- 의존성을 추가한 뒤 로컬 소스/jar에서 정확한 `RdsUtilities` 및 `GenerateAuthenticationTokenRequest` 빌더 API를 검증한다.
- 새 공개 생성기/요청/예외 타입마다 영어 KDoc을 추가한다.
- 컴파일 전용 서비스 SDK 정책과 테스트 의존성을 사용해 `aws2-rds` 버전 카탈로그 별칭 및 `aws-exposed` 의존성을 추가한다.
- 검증: 컴파일, 요청 형태 및 생성기 실패 단위 테스트.

## 작업 3: 새로 고침 인식 비밀번호 공급자 추가

복잡도: 높음

- `AwsDatabasePasswordProvider`를 추가한다.
- `AwsDatabaseConnectionProperties.authenticationMode`에 따라 정적 또는 RDS IAM 공급자 동작을
  선택하는 `AwsDatabasePasswordProviders` 팩토리 도우미를 추가한다.
- 기존 모드의 정적 비밀번호 공급자 동작을 추가한다.
- 구성된 새로 고침 경계까지만 토큰을 캐시하는 RDS IAM 공급자를 추가한다.
- 결정적 새로 고침 테스트를 위해 `Clock`을 주입한다.
- 동시 새로 고침을 `java.util.concurrent.locks.ReentrantLock`으로 통합하고
  `synchronized`/`@Synchronized`를 사용하지 않는다.
- 새로 고침 구간 안에 있는 토큰을 반환하지 않도록 보장한다.
- 공개 공급자/팩토리 타입에 영어 KDoc을 추가한다.
- 반환하는 모든 시크릿이 `AwsSecretString`을 사용하도록 보장한다.
- 검증: 가짜 생성기와 가변 시계를 사용한 토큰 재사용, 새로 고침 경계, 동시 단일 실행,
  마스킹 테스트. 예외 메시지/원인 체인 검사도 포함한다.

## 작업 4: Hikari DataSource 생성 연결

복잡도: 높음

- 현재 Hikari 정적 비밀번호 경로를 변경하지 않는다.
- RDS IAM 모드에서는 `HikariConfig.dataSource`를 통해 할당한 내부 갱신 `DataSource`로
  Hikari를 구성하고 `HikariConfig.username` 또는 `HikariConfig.password`를 설정하지 않는다.
- Hikari가 물리 연결에 호출하는 래퍼의 인수 없는 `getConnection()` 경로를 구현한다. 사용자/비밀번호
  오버로드는 거부하거나 공급자를 우회하지 않도록 안전하게 위임한다.
- 풀 설정과 JDBC 데이터 소스 프로퍼티를 보존한다.
- `driverClassName`을 구성했다면 `DriverManager.getConnection(...)` 전에 명시적으로 로딩한다.
- 연결 호출마다 토큰을 포함한 JDBC `Properties`를 만들고 구성된 데이터 소스 프로퍼티를 전달하며 호출 후 해당 인스턴스를 보관하지 않는다.
- 원본 토큰 노출을 `DriverManager.getConnection` 호출로 제한한다.
- 기존 JDBC 프로퍼티를 갱신 `DataSource`에 전달해 드라이버 수준 SSL/TLS와 데이터 소스 프로퍼티를 보존한다.
- 검증: 가능한 범위에서 공급자 호출 및 Hikari 모드 선택의 컴파일과 단위 테스트.

## 작업 5: 문서 갱신

복잡도: 중간

- `aws-exposed/README.md`를 갱신한다.
- `aws-exposed/README.ko.md`를 갱신한다.
- 루트 `CHANGELOG.md`의 Unreleased 섹션을 갱신한다.
- `rds-db:connect`, 정확한 RDS 엔드포인트 요구 사항, 토큰 TTL, 런타임 AWS SDK RDS 의존성을 설명한다.
- RDS IAM 인증의 SSL/TLS JDBC 프로퍼티는 호출자 책임임을 설명한다.
- 검증: README/소스 검색 및 `git diff --check`.

## 작업 6: 검토, 학습 문서, 커밋, PR

복잡도: 중간

- 대상 `aws-exposed` 컴파일/테스트/Kover를 실행한다.
- `:bluetape4k-aws-exposed:detekt`를 실행하고 모듈이 임계값 작업을 제공하면 Kover 검증을 추가한다.
- DB/Exposed/공개 API 범위를 대상으로 현재 세션 코드 검토를 수행한다.
- Claude Code CLI 자문 검토를 시도하고 차단되면 제한 시간/할당량 공백을 기록한다.
- `docs/lessons/2026-05-21-issue-77-rds-iam-auth.md`를 추가한다.
- Lore 트레일러와 함께 커밋하고 푸시한 뒤 `debop`에게 할당한 PR을 만든다.
- PR 생성 후 검토 댓글/공식 검토를 추가하고 CI를 모니터링한다.

## 3-R 단계 검토 기록

### Claude Code Opus 자문

아티팩트: `.omx/artifacts/claude-issue-77-plan-review-20260521-213703.md`
모델: `${CLAUDE_ADVISOR_MODEL:-claude-opus-4-7}`

| 우선순위 | 발견 사항 | 결정 | 후속 조치 |
|---|---|---|---|
| P1 | 작업에 `AwsDatabasePasswordProviders` 팩토리 도우미가 누락되었다. | 수용 | 작업 3에 팩토리 진입점을 추가했다. |
| P1 | SDK 기반 생성기의 즉시 검증 설명이 부족했다. | 수용 | 작업 2에 리전 파싱, SDK 클래스 사용 가능 여부 검사, 마스킹 안전 실패를 명시했다. |
| P1 | 생성기의 소유권/종료 수명 주기를 명시하지 않았다. | 수용 | 작업 2에서 호출자가 관리하는 `RdsUtilities`를 설명하며 숨겨진 종료 동작은 없다. |
| P1 | 단일 실행 기본 요소가 모호해 가상 스레드 지침을 위반할 수 있었다. | 수용 | 작업 3에서 `ReentrantLock`을 요구하고 `synchronized`를 금지한다. |
| P2 | 갱신 `DataSource` 메서드 표면과 프로퍼티 수명에 더 자세한 설명이 필요했다. | 수용 | 작업 4에 인수 없는 `getConnection()`, 드라이버 로딩, 호출별 `Properties`, 프로퍼티 전달을 명시했다. |
| P2 | 공개 API KDoc 작업이 암묵적이었다. | 수용 | 작업 1~3에서 영어 KDoc을 요구한다. |
| P2 | 예외 마스킹과 detekt/Kover 검사가 누락되었다. | 수용 | 작업 3과 6에 해당 검사를 추가했다. |

### Codex 다각도 통합 검토

| 우선순위 | 영역 | 발견 사항 | 결정 |
|---|---|---|---|
| P1 | 구현 가능성 | 계획이 모든 설계 API를 구체적인 작업에 매핑해야 한다. | 공급자 팩토리, 예외, 수명 주기 작업을 추가해 해결했다. |
| P1 | DB 수명 주기 | Hikari 래퍼에서 토큰을 주입하는 정확한 연결 경로를 정의해야 한다. | 작업 4의 메서드 표면 및 프로퍼티 수명 설명으로 해결했다. |
| P1 | Kotlin 품질 | 구현 전에 공개 API KDoc과 가상 스레드 안전 잠금을 명시해야 한다. | 작업 1~3으로 해결했다. |
| P2 | 검증 | 대상 테스트와 함께 Detekt 및 Kover 검증을 시도해야 한다. | 작업 6에서 수용했다. |

수렴 결과: 편집을 수용한 뒤 P0 = 0, P1 = 0이다.

## 검증 명령

```bash
./gradlew :bluetape4k-aws-exposed:compileKotlin --no-configuration-cache
./gradlew :bluetape4k-aws-exposed:detekt --no-configuration-cache
./gradlew :bluetape4k-aws-exposed:cleanTest :bluetape4k-aws-exposed:test :bluetape4k-aws-exposed:koverXmlReport --no-build-cache --no-configuration-cache
git diff --check
```
