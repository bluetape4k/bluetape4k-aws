# 변경 이력

`bluetape4k-aws`의 주요 변경 사항을 이 문서에 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)를 따릅니다.
이 프로젝트는 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)을 따릅니다.

## [미출시]

### 추가

- Java SDK v2와 AWS SDK for Kotlin에 Lambda `Invoke` helper를 추가했습니다. 동기,
  async/coroutine 또는 native suspend 호출, typed payload codec, raw response와
  `FunctionError` 보존, 명시적 client 수명과 Floci-first smoke 경계를 제공합니다
  ([#314](https://github.com/bluetape4k/bluetape4k-aws/issues/314)).
- Java SDK v2와 AWS SDK for Kotlin에 Step Functions 실행 시작·중지·조회·목록 및
  coroutine `Flow` polling helper를 추가했습니다. compileOnly SDK, caller-owned
  client 수명, 명시적 cancellation, Floci/LocalStack 검증 경계를 포함합니다
  ([#313](https://github.com/bluetape4k/bluetape4k-aws/issues/313)).

## [0.5.0] - 2026-08-06

### 추가

- Java 및 Kotlin SDK 모듈에 모델 중립적인 Amazon Bedrock Runtime `Converse`와
  `ConverseStream` facade를 추가했습니다. 코루틴 우선 cold Flow adapter, 범위가
  지정된 lifecycle helper, 이중 언어 다이어그램, 자격 증명을 사용하는 opt-in smoke
  test를 포함합니다
  ([#312](https://github.com/bluetape4k/bluetape4k-aws/issues/312)).
- Java 및 Kotlin AWS facade에 EventBridge core wrapper와 coroutine DSL을 추가하고,
  Spring Boot auto-configuration과 Ktor plugin으로 제공했습니다
  ([#308](https://github.com/bluetape4k/bluetape4k-aws/issues/308),
  [#309](https://github.com/bluetape4k/bluetape4k-aws/issues/309)).
- 재사용 가능한 구성 및 secret 조회를 위해 core Java/Kotlin 모듈에 Secrets Manager와
  Parameter Store wrapper를 추가했습니다
  ([#268](https://github.com/bluetape4k/bluetape4k-aws/issues/268)).
- RDS IAM 인증 token helper를 core AWS 모듈로 승격하고 JDBC connection refresh를
  공통 bluetape4k JDBC helper 경계에 위임했습니다
  ([#269](https://github.com/bluetape4k/bluetape4k-aws/issues/269),
  [#295](https://github.com/bluetape4k/bluetape4k-aws/issues/295)).
- Spring Boot Kinesis auto-configuration과 coroutine operation을 추가했습니다
  ([#270](https://github.com/bluetape4k/bluetape4k-aws/issues/270)).
- Ktor SES v2, SNS, Kinesis, STS integration helper를 추가했습니다
  ([#271](https://github.com/bluetape4k/bluetape4k-aws/issues/271),
  [#272](https://github.com/bluetape4k/bluetape4k-aws/issues/272)).
- Secrets Manager, Parameter Store, Ktor database settings plugin coverage를
  기반으로 하는 Spring Boot 및 Ktor `aws-exposed` settings integration을 추가했습니다
  ([#180](https://github.com/bluetape4k/bluetape4k-aws/issues/180),
  [#181](https://github.com/bluetape4k/bluetape4k-aws/issues/181)).
- 0.5.0 service 확장 후 남은 service coverage gap을 다루는 Ktor example 모듈을
  추가했습니다
  ([#273](https://github.com/bluetape4k/bluetape4k-aws/issues/273)).

### 변경

- `0.4.0` 출시 후 `0.5.0` snapshot 개발선을 열고 local bluetape4k BOM ref를
  `bluetape4k-bom:1.11.1-SNAPSHOT` 및
  `bluetape4k-exposed-bom:1.12.0-SNAPSHOT`에 맞췄습니다.
- service matrix, integration view, class diagram routing을 포함하도록 EventBridge
  coverage 관련 README 다이어그램을 갱신했습니다
  ([#326](https://github.com/bluetape4k/bluetape4k-aws/pull/326)).

### 버그 수정

- CI가 불안정한 URL 형식에 의존하지 않고 metadata에서 release artifact를 찾도록
  gitleaks release asset 조회를 강화했습니다
  ([#275](https://github.com/bluetape4k/bluetape4k-aws/issues/275)).
- 최근 문서 형식, validation helper 사용, public KDoc 언어 일관성과 관련된 0.5.0
  hygiene gap을 해소했습니다
  ([#284](https://github.com/bluetape4k/bluetape4k-aws/issues/284),
  [#285](https://github.com/bluetape4k/bluetape4k-aws/issues/285),
  [#286](https://github.com/bluetape4k/bluetape4k-aws/issues/286)).

## [0.4.0] - 2026-06-27

### 추가

- Ktor DynamoDB integration과 emulator 기반 Ktor DynamoDB example을 추가했습니다
  ([#179](https://github.com/bluetape4k/bluetape4k-aws/issues/179)).
- 선택 가능한 Spring Boot DynamoDB Accelerator(DAX) client integration을 추가하되,
  emulator test는 일반 DynamoDB client 경로를 유지했습니다
  ([#191](https://github.com/bluetape4k/bluetape4k-aws/issues/191)).
- Spring Boot CloudWatch 및 CloudWatch Logs auto-configuration, coroutine operation
  template, Micrometer snapshot publishing helper를 추가했습니다
  ([#194](https://github.com/bluetape4k/bluetape4k-aws/issues/194)).
- 명시적인 metric/log 발행과 buffered shutdown flush를 지원하는 Ktor CloudWatch 및
  CloudWatch Logs plugin을 추가했습니다
  ([#201](https://github.com/bluetape4k/bluetape4k-aws/issues/201)).
- IMDS를 credential strategy로 사용하지 않는 Spring Boot 및 Ktor용 EC2 Instance
  Metadata Service helper를 추가했습니다
  ([#196](https://github.com/bluetape4k/bluetape4k-aws/issues/196),
  [#200](https://github.com/bluetape4k/bluetape4k-aws/issues/200)).
- AWS SDK v2 S3 Control 경계를 통해 Spring Boot와 Ktor에서 선택적으로 사용할 수
  있는 S3 Access Grants 지원을 추가했습니다
  ([#227](https://github.com/bluetape4k/bluetape4k-aws/issues/227),
  [#228](https://github.com/bluetape4k/bluetape4k-aws/issues/228)).
- Java SDK facade, Spring Boot auto-configuration, Ktor plugin integration 전반에
  선택 가능한 S3 Vectors 지원을 추가했습니다
  ([#229](https://github.com/bluetape4k/bluetape4k-aws/issues/229)).
- 모든 consumer에 metric을 강제하지 않으면서 SQS와 S3 operation 시간을 측정하는
  선택 가능한 Micrometer observability adapter를 추가했습니다
  ([#230](https://github.com/bluetape4k/bluetape4k-aws/issues/230)).

### 변경

- `0.3.1` stable release 후 `0.4.0` 개발선을 열고 local bluetape4k BOM ref를
  `bluetape4k-bom:1.11.0-SNAPSHOT` 및
  `bluetape4k-exposed-bom:1.11.0-SNAPSHOT`에 맞췄습니다.
- 중복된 local Ktor helper를 유지하는 대신 AWS Ktor integration과 example에서 공통
  `bluetape4k-ktor-*` 모듈을 사용하도록 변경했습니다
  ([#244](https://github.com/bluetape4k/bluetape4k-aws/issues/244),
  [#245](https://github.com/bluetape4k/bluetape4k-aws/issues/245)).
- AWS emulator-aware test와 example의 기본값을 Floci 우선으로 변경하고, API coverage
  gap에는 명시적인 LocalStack fallback 실행을 유지했습니다
  ([#239](https://github.com/bluetape4k/bluetape4k-aws/issues/239),
  [#241](https://github.com/bluetape4k/bluetape4k-aws/issues/241)).
- 0.4.0 service surface에 맞춰 root README의 architecture, component, service
  coverage 다이어그램을 갱신했습니다
  ([#266](https://github.com/bluetape4k/bluetape4k-aws/pull/266),
  [#291](https://github.com/bluetape4k/bluetape4k-aws/pull/291)).
- 조율된 dependency train을 위한 0.4.0 release 문서와 code-pattern preflight 근거를
  준비했습니다
  ([#292](https://github.com/bluetape4k/bluetape4k-aws/issues/292),
  [#294](https://github.com/bluetape4k/bluetape4k-aws/issues/294)).

### 버그 수정

- injected-operation validation과 DAX concurrency validation을 포함한 최종 IMDS 및
  DAX review gap을 해소했습니다
  ([#281](https://github.com/bluetape4k/bluetape4k-aws/issues/281),
  [#282](https://github.com/bluetape4k/bluetape4k-aws/issues/282),
  [#283](https://github.com/bluetape4k/bluetape4k-aws/issues/283)).
- Central snapshot metadata 및 cache refresh 실패에 대응하도록 CI와 Nightly snapshot
  dependency refresh 동작을 강화했습니다
  ([#251](https://github.com/bluetape4k/bluetape4k-aws/issues/251),
  [#253](https://github.com/bluetape4k/bluetape4k-aws/issues/253),
  [#255](https://github.com/bluetape4k/bluetape4k-aws/issues/255),
  [#257](https://github.com/bluetape4k/bluetape4k-aws/issues/257),
  [#259](https://github.com/bluetape4k/bluetape4k-aws/issues/259),
  [#261](https://github.com/bluetape4k/bluetape4k-aws/issues/261),
  [#263](https://github.com/bluetape4k/bluetape4k-aws/issues/263)).

## [0.3.1] - 2026-06-01

### 변경

- `aws-exposed`가
  `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.10.0`을 사용하도록
  갱신했습니다.
- AWS Exposed 모듈이 consumer에 BOM platform을 노출하지 않도록
  `bluetape4k-exposed-bom` platform import를 API scope에서 implementation scope로
  변경했습니다.
- 하위 모듈이 API scope의 BOM 전파에 의존하지 않도록 구체적인
  `bluetape4k-exposed-jdbc` API dependency는 같은 catalog line에서 직접 버전을
  지정하도록 유지했습니다.
- release workflow와 맞추기 위해 기본 bluetape4k dependencies catalog ref를
  `catalog/2026-06-01-00`으로 갱신했습니다.

## [0.3.0] - 2026-05-27

### 추가

- S3 및 SQS production integration을 위한 공통 Spring Boot AWS core property와 client
  customizer 기반을 추가했습니다 ([#190](https://github.com/bluetape4k/bluetape4k-aws/issues/190)).
- 암호화, config reload, access grant, vector operation, content helper를 지원하는
  고급 Spring Boot S3 기능을 추가했습니다 ([#192](https://github.com/bluetape4k/bluetape4k-aws/issues/192)).
- 타입 변환, 수동 승인, 재시도 정책, listener interceptor,
  observability hook을 지원하는 고급 Spring Boot SQS 기능을 추가했습니다
  ([#193](https://github.com/bluetape4k/bluetape4k-aws/issues/193)).
- 공통 Ktor AWS 기본값과 client customizer hook을 추가했습니다
  ([#197](https://github.com/bluetape4k/bluetape4k-aws/issues/197)).
- 고급 Ktor S3 및 SQS integration과 실행 가능한 고급 example을 추가했습니다
  ([#199](https://github.com/bluetape4k/bluetape4k-aws/issues/199),
  [#203](https://github.com/bluetape4k/bluetape4k-aws/issues/203),
  [#207](https://github.com/bluetape4k/bluetape4k-aws/issues/207)).
- Spring Boot S3 및 SQS용 AWSpring 동등성 example scenario를 추가했습니다
  ([#206](https://github.com/bluetape4k/bluetape4k-aws/issues/206)).

### 변경

- 0.3.0 release line에서 다음 BOM을 사용하도록 준비했습니다
  `io.github.bluetape4k:bluetape4k-bom:1.9.2` and
  `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.9.2`.
- AWS Java, AWS Kotlin, AWS Ktor, AWS Spring Boot, AWS Exposed 및 example 전반의
  README architecture, flow, sequence 다이어그램을 갱신했습니다.

### 버그 수정

- SNS-to-SQS fanout의 LocalStack coverage를 안정화했습니다
  ([#182](https://github.com/bluetape4k/bluetape4k-aws/issues/182)).

## [0.2.0] - 2026-05-22

### 추가

- 공통 Exposed database registry, settings, AWS 기반 credential/property resolution을
  제공하는 `aws-exposed` 기반 모듈을 추가했습니다 ([#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74)).
- AWS 기반 JDBC integration을 위한 Spring Boot Exposed database auto-configuration을
  추가했습니다 ([#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75)).
- AWS 기반 Exposed database를 위한 Ktor `AwsExposedPlugin` lifecycle integration을
  추가했습니다 ([#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76)).
- Hikari 기반 Exposed database를 위한 `aws-exposed` RDS IAM authentication token
  provider를 추가했습니다. refresh-aware token caching과 AWS SDK Java v2
  `RdsUtilities` integration을 포함합니다 ([#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77)).
- Spring Boot 및 Ktor Exposed AWS database example 모듈을 추가했습니다 ([#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82)).
- Spring Boot SES email sender 지원을 추가했습니다 ([#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7)).

### 변경

- 0.2.0 release line에서 `io.github.bluetape4k:bluetape4k-bom:1.9.0`과 `io.github.bluetape4k.exposed:bluetape4k-exposed-bom:1.9.0`을 사용하도록 준비했습니다.
- Exposed helper artifact 버전을 직접 고정하는 대신 `bluetape4k-exposed-bom`을 가져오도록 변경했습니다.

### 버그 수정

- AWS Exposed serialization 경로 전반에서 secret redaction을 강화했습니다.

## [0.1.1] - 2026-05-22

### 추가

- `ListObjectsV2` pagination을 자동으로 순회하는 S3 async 및 AWS Kotlin SDK object listing Flow helper를 추가했습니다 ([#145](https://github.com/bluetape4k/bluetape4k-aws/issues/145)).

### 변경

- 0.1.1 patch release line에서 `io.github.bluetape4k:bluetape4k-bom:1.9.0`을 사용하도록 준비했습니다.

### 버그 수정

- versioning을 사용하는 S3 bucket이 bucket 삭제 전에 object version과 delete marker를 제거하도록 `forceDeleteBucket`을 수정했습니다 ([#147](https://github.com/bluetape4k/bluetape4k-aws/issues/147)).

## [0.1.0] - 2026-05-16

### 추가

- Spring Boot SNS direct SMS 발행, HTTP(S) endpoint payload parsing, token 기반
  subscription confirmation을 추가했습니다 ([PR #95](https://github.com/bluetape4k/bluetape4k-aws/pull/95)).
- 기본 S3 사용자에게 CRT dependency를 강제하지 않으면서 AWS SDK v2
  `S3TransferManager` 기반의 선택 가능한 Spring Boot S3 transfer operation을
  추가했습니다
  ([PR #94](https://github.com/bluetape4k/bluetape4k-aws/pull/94)).
- 기존 Spring Boot S3 example과 함께 Spring Boot SQS/SNS fanout example도 Spring
  AOT processing에 연결했습니다 ([PR #93](https://github.com/bluetape4k/bluetape4k-aws/pull/93)).
- `:aws-kotlin`과 공식 AWS SDK for Kotlin을 기반으로 하는 Ktor DynamoDB server
  plugin 및 repository facade를 추가했습니다 ([PR #87](https://github.com/bluetape4k/bluetape4k-aws/pull/87)).
- remote Environment source에 Secrets Manager 및 Parameter Store refresh 지원을
  추가했습니다 ([PR #84](https://github.com/bluetape4k/bluetape4k-aws/pull/84)).
- KMS field-level encryption과 Spring Boot SQS/SNS fanout example을 추가했습니다 ([PR #73](https://github.com/bluetape4k/bluetape4k-aws/pull/73)).
- root README hero image를 추가하고 프로젝트 목적, 기능, architecture 진입점 문서를
  갱신했습니다 ([PR #68](https://github.com/bluetape4k/bluetape4k-aws/pull/68)).
- Ktor SQS consumer runtime과 server integration을 추가했습니다 ([PR #60](https://github.com/bluetape4k/bluetape4k-aws/pull/60)).
- Spring Boot 4 SNS coroutine publisher를 추가했습니다 ([PR #55](https://github.com/bluetape4k/bluetape4k-aws/pull/55)).
- Spring Boot 4 Secrets Manager 및 Parameter Store property loading을 추가했습니다 ([PR #57](https://github.com/bluetape4k/bluetape4k-aws/pull/57)).
- Spring Boot 4 KMS encryption 지원을 추가하고 disabled contract를 수정했습니다 ([PR #58](https://github.com/bluetape4k/bluetape4k-aws/pull/58), [PR #62](https://github.com/bluetape4k/bluetape4k-aws/pull/62)).
- AWS request signing을 위한 Ktor SigV4 client plugin을 추가했습니다 ([PR #27](https://github.com/bluetape4k/bluetape4k-aws/pull/27)).
- Ktor S3 coroutine client와 LocalStack 중심 example coverage를 추가하고 Nightly에
  포함했습니다 ([PR #28](https://github.com/bluetape4k/bluetape4k-aws/pull/28)).
- Spring Boot 4 S3 auto-configuration과 coroutine operation template을 추가했습니다 ([PR #29](https://github.com/bluetape4k/bluetape4k-aws/pull/29)).
- Spring Boot 4 SQS coroutine operation template, listener annotation, polling 기능을 갖춘
  container를 추가했습니다 ([PR #30](https://github.com/bluetape4k/bluetape4k-aws/pull/30)).
- Spring Boot 4 DynamoDB enhanced async client auto-configuration과 coroutine
  repository 기반을 추가했습니다 ([PR #31](https://github.com/bluetape4k/bluetape4k-aws/pull/31)).
- AWS library consumer를 위한 `bluetape4k-aws-bom` BOM 모듈을 추가했습니다 ([PR #24](https://github.com/bluetape4k/bluetape4k-aws/pull/24)).
- AWS BOM 모듈의 영문 및 한국어 README를 추가했습니다 ([PR #25](https://github.com/bluetape4k/bluetape4k-aws/pull/25)).
- CI, nightly, snapshot, release, code-quality 검사를 위한 GitHub Actions workflow를
  추가했습니다 ([PR #19](https://github.com/bluetape4k/bluetape4k-aws/pull/19)).

### 변경

- Spring Boot SQS listener/template 동등성 범위에 FIFO metadata 노출, 명시적인 send
  request field, AOT-safe example coverage를 포함했습니다 ([PR #93](https://github.com/bluetape4k/bluetape4k-aws/pull/93)).
- AWS SDK, Ktor 3.5, Gradle 9.5.1, SLF4J 2.0.18 버전의 dependency baseline을
  갱신했습니다 ([PR #89](https://github.com/bluetape4k/bluetape4k-aws/pull/89),
  [PR #90](https://github.com/bluetape4k/bluetape4k-aws/pull/90),
  [PR #91](https://github.com/bluetape4k/bluetape4k-aws/pull/91),
  [PR #92](https://github.com/bluetape4k/bluetape4k-aws/pull/92)).
- GitHub Actions 및 Gradle Actions caching을 갱신하고, dependency update 이후 CI
  secret scan installer를 안정화했습니다
  ([PR #88](https://github.com/bluetape4k/bluetape4k-aws/pull/88)).
- AWS 모듈이 `bluetape4k-jackson3`을 공통으로 사용하도록 하고, 직접적인 Jackson
  helper 사용을 `tools.jackson`으로 옮겼습니다.
- README workbench image를 갱신하고 license 문구를 MIT에 맞췄습니다 ([PR #72](https://github.com/bluetape4k/bluetape4k-aws/pull/72), [PR #70](https://github.com/bluetape4k/bluetape4k-aws/pull/70)).
- PR review gate metrics 문서를 통합했습니다 ([PR #69](https://github.com/bluetape4k/bluetape4k-aws/pull/69)).
- `aws`, `aws-kotlin`, `aws-spring-boot`, `aws-ktor` test 전반의 review finding을
  보완했습니다 ([PR #64](https://github.com/bluetape4k/bluetape4k-aws/pull/64), [PR #65](https://github.com/bluetape4k/bluetape4k-aws/pull/65), [PR #66](https://github.com/bluetape4k/bluetape4k-aws/pull/66), [PR #67](https://github.com/bluetape4k/bluetape4k-aws/pull/67)).
- `aws-spring-boot` test의 assertion library를 `bluetape4k-assertions`로
  통일했습니다 ([PR #63](https://github.com/bluetape4k/bluetape4k-aws/pull/63)).
- WIP queue와 review-gate metrics 문서를 갱신했습니다 ([PR #56](https://github.com/bluetape4k/bluetape4k-aws/pull/56), [PR #61](https://github.com/bluetape4k/bluetape4k-aws/pull/61)).
- 불필요한 test 작업을 줄이고 일시적 실패 처리를 개선하도록 CI에 path filtering과
  retry 구성을 적용했습니다 ([PR #23](https://github.com/bluetape4k/bluetape4k-aws/pull/23)).
- test code를 Kluent에서 `bluetape4k-assertions`로 이전했습니다 ([PR #22](https://github.com/bluetape4k/bluetape4k-aws/pull/22)).

### 버그 수정

- remote Environment refresh가 reload 중 안정적인 snapshot을 유지하고 refresh race
  regression을 방지하도록 수정했습니다 ([PR #86](https://github.com/bluetape4k/bluetape4k-aws/pull/86)).
- 첫 public release 전에 deprecated `S3Factory`, `SesFactory`, `SnsFactory`,
  `SqsFactory` object를 제거했습니다. 각각 `S3ClientFactory`, `SesClientFactory`,
  `SnsClientFactory`, `SqsClientFactory`를 사용합니다 ([#98](https://github.com/bluetape4k/bluetape4k-aws/issues/98), [PR #113](https://github.com/bluetape4k/bluetape4k-aws/pull/113)).
- emulator 제약 및 out-of-band-protocol test의 `@Disabled` annotation에 issue ref와
  영문 사유를 포함하도록 변경했습니다 ([#99](https://github.com/bluetape4k/bluetape4k-aws/issues/99), [#100](https://github.com/bluetape4k/bluetape4k-aws/issues/100), [PR #114](https://github.com/bluetape4k/bluetape4k-aws/pull/114)).

### 0.2.0 로드맵

0.1.0 출시 시점에 다음 항목을 0.2.0 개발선으로 연기했습니다.

- Exposed 우선 AWS database integration ([#74](https://github.com/bluetape4k/bluetape4k-aws/issues/74)).
- Spring Boot 및 Ktor Exposed auto-configuration과 `AwsExposedPlugin`
  ([#75](https://github.com/bluetape4k/bluetape4k-aws/issues/75),
  [#76](https://github.com/bluetape4k/bluetape4k-aws/issues/76)).
- RDS IAM auth token provider와 Exposed database example
  ([#77](https://github.com/bluetape4k/bluetape4k-aws/issues/77),
  [#82](https://github.com/bluetape4k/bluetape4k-aws/issues/82)).
- Kinesis 및 DynamoDB Streams coroutine `Flow` 지원 ([#81](https://github.com/bluetape4k/bluetape4k-aws/issues/81)).
- Spring Boot SES sender 지원 ([#7](https://github.com/bluetape4k/bluetape4k-aws/issues/7)).
- Ktor integration을 `:aws-kotlin`으로 이전 ([#85](https://github.com/bluetape4k/bluetape4k-aws/issues/85)).
- SES V2 및 SNS token flow를 위한 LocalStack 호환 test strategy ([#105](https://github.com/bluetape4k/bluetape4k-aws/issues/105)).
- disabled-test registry 및 CI release gate ([#106](https://github.com/bluetape4k/bluetape4k-aws/issues/106)).
