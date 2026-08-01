# 이슈 #269 RDS IAM core helper 계획

작성일: 2026-06-30
명세: `docs/superpowers/specs/2026-06-30-issue-269-rds-iam-core-design.md`

## 작업 1: 실패하는 core API test 추가

복잡도: 중간

- production code 전에 예정된 `io.bluetape4k.aws.rds` API의 `:bluetape4k-aws-java` unit test를 추가한다.
- 검증 범위:
  - redaction된 token factory와 `toString()`
  - blank token 검증
  - blank region/hostname/username 및 잘못된 port에 대한 request validation
  - 가능한 경우 fake 또는 caller가 제공한 `RdsUtilities` 경계를 통한 request-to-AWS-SDK mapping
  - token 유출 없는 generator failure wrapping
  - 저장소 AWS exception base를 확장하는 exception 타입
- production code 추가 전에 targeted test task를 실행하고 예상 RED failure를 기록한다.
- 검증 evidence:
  `./gradlew :bluetape4k-aws-java:test --tests '*AwsRdsIam*' --no-configuration-cache`
  새 core API가 아직 없으므로 실패한다.

## 작업 2: core RDS IAM token API 구현

복잡도: 중간

- `aws-java/src/main/kotlin/io/bluetape4k/aws/rds/` package를 추가한다.
- 구현 항목:
  - `AwsRdsIamAuthToken`;
  - `awsRdsIamAuthTokenOf`;
  - `AwsRdsIamAuthTokenRequest`;
  - `AwsRdsIamAuthTokenGenerator`;
  - `AwsSdkRdsIamAuthTokenGenerator`;
  - `AwsRdsIamAuthTokenException`.
- `aws-java`에 `compileOnly(libs.aws2.rds)`와 `testImplementation(libs.aws2.rds)`를 추가한다.
- 주입한 `RdsUtilities`는 caller-managed로 유지한다.
- message를 redaction-safe하게 유지하고 failure message에는 endpoint host/port만 request context로 사용한다.
- public API에 영문 KDoc을 추가한다.
- 검증 evidence: targeted core test 통과.

## 작업 3: 실패하는 Exposed 재사용 regression 추가

복잡도: 중간

- Exposed SDK-backed generator가 core generator 경로에 위임하거나 core token 동작을 adapt함을 입증하도록 `aws-exposed` test를 추가하거나 갱신한다.
- 관찰 가능한 adapter assertion을 우선한다. 실패하는 `RdsUtilities`를 주입하고 노출된 exception이 redaction-safe한지, cause chain에 core `io.bluetape4k.aws.rds.AwsRdsIamAuthTokenException`이 포함되는지 검증한다.
- 모호한 overload 없이 기존 public Exposed provider factory lambda 호출 위치를 보존한다.
- 위임 후에도 Exposed generator failure message가 redaction-safe함을 확인하는 regression assertion을 추가한다.
- Exposed production code를 변경하기 전에 targeted Exposed test task를 실행하고 예상 RED failure를 기록한다.
- 검증 evidence:
  `./gradlew :bluetape4k-aws-exposed:test --tests '*AwsRdsIam*' --no-configuration-cache`
  새 재사용 기대 조건으로 실패한다.

## 작업 4: core generator를 재사용하도록 Exposed refactor

복잡도: 높음

- `aws-exposed`에 `implementation(project(":bluetape4k-aws-java"))`를 추가한다.
- `AwsRdsIamAuthenticationProperties`,
  `AwsDatabasePasswordProvider`, `AwsDatabasePasswordProviders`, and
  `RdsIamRefreshingDataSource`는 `aws-exposed`에 유지한다.
- typealias가 Kotlin/JVM 사용에 안전하다고 입증되지 않는 한 Exposed public generator/request/exception 이름을 compatibility wrapper 또는 adapter로 유지한다.
- `aws-exposed`의 `AwsSdkRdsIamAuthTokenGenerator`가 `io.bluetape4k.aws.rds.AwsSdkRdsIamAuthTokenGenerator`에 위임하도록 갱신한다.
- JDBC password provider 경계에서만 core `AwsRdsIamAuthToken`을 Exposed `AwsSecretString`으로 adapt한다.
- `AwsDatabasePasswordProviders.rdsIam(...)` lambda 호출 위치를 모호하게 만드는 overload를 피한다.
- 검증 evidence: 기존 및 신규 Exposed RDS IAM test 통과.

## 작업 5: 공개 문서와 차트 갱신

복잡도: 중간

- 루트 `README.md`와 `README.ko.md`를 갱신한다.
  - `bluetape4k-aws-java` 모듈 행에 Java SDK 기반 RDS IAM token helper를 포함한다.
  - Java SDK 설치 snippet에 선택 사항인 `software.amazon.awssdk:rds`를 포함한다.
  - `bluetape4k-aws-kotlin`에는 native RDS IAM facade가 추가되지 않았음을 명확히 유지한다.
- JDBC refresh 지침을 유지하면서 shared Java SDK 기반 generator를 가리키도록 `aws-exposed/README.md`와 `aws-exposed/README.ko.md`를 갱신한다.
- `docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg`를 검사한다. RDS IAM을 Exposed 전용으로 표시하거나 Java 지원을 누락하면 SVG를 갱신하고 대응하는 PNG를 다시 생성한다.
- 변경한 diagram은 XML parse, PNG render, full-size visual inspection을 수행한다.
- 검증 evidence: `git diff --check`, README image link 검토, 변경 asset의 diagram render/inspection evidence.

## 작업 6: compile, test, review 수행

복잡도: 중간

- 다음 명령을 실행한다.
  - `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin --no-configuration-cache`
  - `./gradlew :bluetape4k-aws-java:test --tests '*AwsRdsIam*' --no-configuration-cache`
  - `./gradlew :bluetape4k-aws-exposed:compileKotlin :bluetape4k-aws-exposed:compileTestKotlin --no-configuration-cache`
  - `./gradlew :bluetape4k-aws-exposed:test --tests '*AwsRdsIam*' --no-configuration-cache`
  - `git diff --check`
- project가 대상 module의 task를 제공할 때만 detekt를 시도한다.
- 최종 diff를 spec과 이슈 #269 acceptance criteria에 맞춰 검토한다.
- 검증 evidence: 명령 exit code, test count/failure, 명시한 검증 gap.

## 작업 7: commit, PR, metadata parity 확인

복잡도: 중간

- 영문 Lore trailer를 포함해 commit한다.
- `feat/aws-rds-iam-core`를 push한다.
- 이슈 #269에 연결하고 `debop`에게 할당한 PR을 생성한다.
- GitHub가 지원하면 이슈 milestone `0.5.0`과 label을 동일하게 설정한다.
- PR 본문의 마지막 `##` section이 `## DoD Status`인지 확인한다.
- `gh issue view`와 `gh pr view`로 live issue와 PR metadata를 검증한다.
- merge 준비 상태를 판단하기 전에 required CI를 관찰한다.

## 단계 3-R 검토 기록

### Codex 계획 검토

| 우선순위 | 발견 사항 | 결정 |
|---|---|---|
| P0 | 기존 Exposed 재사용 test 아이디어는 core 재사용을 입증하지 않고도 통과할 수 있다. | 수용. 작업 3은 이제 실패하는 `RdsUtilities`와 cause chain의 core exception을 사용하는 관찰 가능한 adapter assertion을 요구한다. |
| P1 | provider factory overload는 모호한 Kotlin lambda 호출을 만들 수 있다. | 수용. 작업 4는 기존 Exposed generator signature를 유지하고, 구현으로 모호하지 않음이 입증되지 않으면 overload를 피한다. |
| P1 | chart 작업이 #269보다 넓어질 수 있다. | 수용. 작업 5는 현재 의미가 새 `aws-java` RDS IAM 지원과 충돌할 때만 시각적 변경을 service coverage chart로 제한한다. |
| P1 | TDD RED evidence는 오타나 설정 실패가 아니라 누락된 동작과 연결돼야 한다. | 수용. 작업 1과 3은 production edit 전에 예상 RED failure를 기록하도록 요구한다. |

수용한 수정 후 수렴 상태: P0 = 0, P1 = 0.
