# Issue 228 Ktor S3 Access Grants 통합

## 배경

#227에서는 AWS SDK v2 S3 Control을 통해 Spring Boot S3 Access Grants를 지원했다. Ktor
module에도 같은 boundary가 필요했다. Object REST operation에 집중해야 하는
`S3KtorClient`로 Access Grants를 옮기지는 않는다.

## 결정

`S3ControlAsyncClient` 기반 선택적 `S3AccessGrantsKtorPlugin`을 추가한다. Suspend
read/data-access 및 discovery operation을 제공하고 `AwsKtorCore` 기본값/customizer를
상속한다. 관리용 create/update/delete operation은 원본 S3 Control client에 유지한다.

## 결과

이제 module은 다음 기능을 제공한다.

- `S3AccessGrantsKtorOperations`와 `S3AccessGrantsKtorTemplate`
- 호출자가 소유한 operation, 호출자가 소유한 client, plugin이 소유한 client, disabled
  mode, customizer ordering을 다루는 plugin config/runtime 수명 주기
- `AwsKtorCore.ktorCore()`와 `bluetape4k-ktor-testing` response assertion을 결합한
  route-level 검증
- 새 Access Grants flow diagram이 있는 영문/한글 README 갱신

## 검증

- `./gradlew :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1`
  성공
- `./gradlew :bluetape4k-aws-ktor:test --tests '*AwsKtorCoreTest' --tests '*S3AccessGrants*' --rerun-tasks --no-daemon --max-workers=1`
  에서 테스트 18개 통과
- Diagram gate 결과: `badEndpointAngle=0`, `badBends=0`, `interiorCrossings=0`,
  `marginImbalance=0`, `titleGap=54`
- SVG와 sketch SVG에서 `xmllint --noout` 통과
- Diagram grep에서 `/Users/debop`, `Inter`, `Arial`, `Helvetica`가 발견되지 않음
- Rendering PNG `docs/images/readme-diagrams/aws-ktor-s3-access-grants-flow-01.png` 검사
- `git diff --check` 통과

## 향후 보호 장치

AWS service-level Ktor plugin을 추가할 때 request 처리 code에 필요하지 않은 원본 AWS SDK
관리 API는 Ktor facade 밖에 둔다. 먼저 `bluetape4k-projects` Ktor module도 확인한다.
적용 가능하면 example에 `bluetape4k-ktor-core` baseline을 설치하고 route test에서는 원시
status 검사 대신 `bluetape4k-ktor-testing` assertion을 재사용한다.
