# Issue #227 Spring S3 Access Grants 통합

날짜: 2026-06-08
이슈: #227

## 배경

Issue #192에서는 S3 Access Grants가 일반 S3 SDK surface에 속하지 않아 의도적으로
연기했다. 현재 AWS SDK Java v2는 S3 Control service module인
`software.amazon.awssdk:s3control`을 통해 Access Grants를 제공한다.

## 결정

- `bluetape4k.aws.s3.access-grants` 아래에 별도의 Spring Boot opt-in 기능으로 Access
  Grants를 추가한다.
- `s3control`을 선택적 service dependency로 유지한다. Production에서는 `compileOnly`,
  test에서는 `testImplementation`을 사용한다.
- 상위 S3 통합이 활성화되고 `bluetape4k.aws.s3.access-grants.enabled=true`일 때만
  `S3ControlClient`, `S3ControlAsyncClient`, `S3AccessGrantsOperations`를 등록한다.
- Service name `s3control`로 공통 AWS client 기본값과 global/service customizer를
  재사용한다.
- Coroutine operation surface는 read/data-access method에 집중한다. 관리용 create,
  update, delete method는 원본 S3 Control client에서 계속 사용할 수 있다.

## 결과

이제 `aws-spring-boot`는 선택적 S3 Access Grants 자동 구성과 `getDataAccess`,
`listCallerAccessGrants`, `listAccessGrants`, `listAccessGrantsInstances`,
`listAccessGrantsLocations`용 coroutine template을 제공한다. 영문/한글 README에 runtime
dependency, opt-in property, Spring injection example, 공통 영문 label component/flow
diagram을 문서화했다.

## 검증

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3control --configuration compileClasspath`
  에서 compile classpath의 `software.amazon.awssdk:s3control:2.46.0` 확인
- `./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1` 통과
- `./gradlew :bluetape4k-aws-spring-boot:compileTestKotlin --no-daemon --max-workers=1` 통과
- `./gradlew :bluetape4k-aws-spring-boot:test --tests '*S3AccessGrants*' --no-daemon --max-workers=1`
  에서 대상 테스트 14개 통과
- S3 Access Grants component diagram 검증:
  `nodes=10 routes=9 segments=28 badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 titleGap=54`
- S3 Access Grants flow diagram 검증:
  `nodes=12 routes=10 segments=30 badEndpointAngle=0 badBends=0 interiorCrossings=0 marginImbalance=0 titleGap=54`
- Rendering PNG를 직접 검사했다.
  `docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-components-08.png` 및
  `docs/images/readme-diagrams/bluetape4k-aws-s3-access-grants-flow-09.png`

## 향후 보호 장치

S3 Access Grants를 `S3Operations`에 합치지 않는다. 이 기능은 S3 Control에 속하며 별도의
선택적 runtime dependency가 필요하다. Access Grants method를 더 추가할 때 관리용
operation을 명시적으로 유지하고 account management workflow용 원본 client 탈출구를
보존한다.

새 README integration section을 추가할 때 review 전에 같은 PR에 관련 diagram도 포함한다.
`bluetape4k-diagram` evidence에는 geometry gate 수치, PNG 검사, README PNG embed, 대응하는
SVG/PNG/DOT/plain asset이 있어야 한다.
