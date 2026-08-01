# Issue #229 S3 Vectors 선택적 경계

## 배경

Issue #229에서는 `aws-java`, `aws-spring-boot`, `aws-ktor` 전반에 선택적 Amazon S3
Vectors 지원을 추가했다.

## 결정

AWS는 별도의 `s3vectors` SDK service와 IAM namespace를 통해 S3 Vectors를 제공하므로
일반 S3 object API와 분리한다. Adapter별 operation interface를 만들지 않고 Spring
Boot와 Ktor에서 `aws-java` coroutine facade를 재사용한다.

## 결과

- Library module에서 `software.amazon.awssdk:s3vectors`는 `compileOnly`와 test scope로
  유지한다.
- 소비자는 S3 Vectors 지원을 활성화하거나 설치할 때만
  `runtimeOnly("software.amazon.awssdk:s3vectors")`를 추가한다.
- Spring Boot 활성화에는 `bluetape4k.aws.s3-vectors.enabled=true`가 필요하다.
- Ktor 활성화에는 명시적인 `S3VectorsKtorPlugin` 설치가 필요하다.
- README diagram과 service coverage prose는 S3 Vectors가 선택 사항임을 나타내야 하며
  emulator 지원을 암시하면 안 된다.
- README diagram을 수정할 때 기존 pastel card/badge decoration language를 보존한다.
  Route density나 label이 빽빽하면 image를 다른 visual shape로 축소하지 말고 canvas를
  키우고 semantic route color를 사용한다.

## 검증

- Maven Central artifact probe로 `software.amazon.awssdk:s3vectors` 확인
- API wrapping 전에 `javap`로 `S3VectorsAsyncClient` operation name 확인
- `aws-java`, `aws-spring-boot`, `aws-ktor`의 S3 Vectors 경로를 대상 Gradle compile/test
  실행으로 검증
- PR review 후속 작업에서 `tools/generate-root-readme-diagrams.py`로 root README component
  map과 service coverage chart를 다시 생성했다. DOT/plain/sketch evidence, 최종 SVG/PNG
  asset, geometry gate summary, font scan, XML parsing, rendering PNG 검사를 포함했다.
- Diagram 재검토에서 component map connector를 여러 segment의 orthogonal route에서 직접
  straight route로 바꾸고, 각 matrix cell 안에 service coverage badge를 중앙 정렬했으며,
  coverage sketch PNG가 placeholder label 대신 실제 matrix를 rendering하게 했다.
- 이후 diagram review에서 이전에 보이던 runtime target을 계층화한 component map으로
  복원했다. 최종 component diagram은 application/example, framework adapter, shared
  foundation, runtime target을 분리하며 AWS 또는 emulator service, JDBC store, managed
  configuration을 포함한다.

## 향후 규칙

AWS가 새 service 전용 SDK artifact를 도입하면 가장 낮은 공통 module에 선택적 facade를
먼저 만들고 framework adapter에서 재사용한다. 모든 README 언어 파일에 runtime
dependency ownership을 명확히 문서화한다.

Root README diagram을 갱신할 때 사용자가 redesign을 명시적으로 요청하지 않았다면 기존
card/badge decoration을 유지한다. Relationship이 많은 component map은 자유 배치 또는
계층형 배치, semantic connector color, generator 수준 geometry proof가 필요하다.
Reviewer가 straight route를 요청하면 명시적인 boundary-to-boundary connector로 검증하고
endpoint가 아닌 card intersection도 계속 확인한다. 이전 runtime 또는 database target이
검토한 architecture story의 일부였다면 module-only map으로 교체하지 말고 계층형 layout에
보존한다.
