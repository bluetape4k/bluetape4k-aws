# Issue #191 구현 검토

Date: 2026-06-07
범위: `aws-spring-boot`의 선택적 DynamoDB DAX 자동 구성

## 검토 범위

다음 기준으로 구현 diff를 검토했다.

- #191 인수 조건
- 승인된 명세 및 계획 산출물
- Spring Boot 자동 구성 순서와 `compileOnly` 클래스 가드
- 로컬 `javap`으로 확인한 DAX SDK API 증거
- DAX 및 AWS SDK 버전 편차에 대한 dependencyInsight 증거
- README 언어판 동기화와 교훈/연구 자료 보존

## 검토 결과

| 심각도 | 개수 | 비고 |
|---|---:|---|
| P0 | 0 | 정확성, 빌드 또는 릴리스 차단 요인이 없다. |
| P1 | 0 | 심각한 API, 의존성 또는 자동 구성 위험이 남아 있지 않다. |
| P2 | 0 | 이전 명세/계획의 P2 항목을 해결했다. |

## 7단계 검토

| 단계 | 결과 | 근거 |
|---|---|---|
| API/호환성 | PASS | DAX는 `DynamoDbAsyncClient`로 등록되며 기존 `DynamoDbEnhancedAsyncClient`와 저장소 API는 변경되지 않는다. |
| Spring 자동 구성 | PASS | `DynamoDbDaxAutoConfiguration`은 `DynamoDbAutoConfiguration`보다 먼저 실행되고 `@ConditionalOnClass(name=...)`로 보호되며, 사용자가 `DynamoDbAsyncClient`를 제공하면 물러난다. |
| 의존성 관리 | PASS | `amazon-dax-client:2.0.9`를 명시적으로 선택하고, 전이 의존성 `software.amazon.awssdk:dynamodb:2.38.5`는 저장소가 선택한 `2.46.0`으로 올린다. |
| 테스트 범위 | PASS | 활성화, 비활성화, URL 누락, 사용자 클라이언트 backoff, filtered-classloader DAX 테스트를 추가했고 전체 `aws-spring-boot` 모듈 테스트가 통과했다. |
| 문서 | PASS | 루트 및 모듈 `README.md` / `README.ko.md`에 DAX 의존성, 속성, 에뮬레이터 경계를 설명한다. |
| 보안/자격 증명 | PASS | DAX는 기존 `AwsCredentialsProvider` 해석 경로를 사용하며, 테스트는 DAX 활성 컨텍스트를 시작하려면 명시적 정적 자격 증명이 필요함을 입증한다. |
| 운영/성능 | PASS | DAX 제한 시간, 재시도 횟수, 동시성, 엔드포인트 갱신, 호스트 이름 검증을 검증 가능한 속성으로 제어한다. |

## 검증 증거

- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency amazon-dax-client`
  - `software.amazon.dax:amazon-dax-client:2.0.9`
- `./gradlew :bluetape4k-aws-spring-boot:dependencyInsight --configuration testCompileClasspath --dependency software.amazon.awssdk:dynamodb`
  - `software.amazon.awssdk:dynamodb:2.38.5 -> 2.46.0`
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.*'`
  - `13 passing`
- `./gradlew :bluetape4k-aws-spring-boot:test`
  - `157 passing`
- `git diff --check`
  - 통과
- 연구 자료 보존:
  - `gno update`: `bluetape4k-wiki: 1 added`, `bluetape4k-docs: 1 added`
  - `gno embed --collection bluetape4k-wiki`: `Embedded 1 chunks`
  - `gno search "DynamoDB DAX Spring Boot bluetape4k" -c bluetape4k-wiki -n 5`: 새 연구 노트가 첫 결과로 반환됨

## PR 피드백 후속 조치

첫 CI 통과 후 추가된 PR 의견을 검토했다.

- 패키지 수준 속성 접두사 상수는 이제 다음을 포함한다.
  `bluetape4k.aws.dynamodb` and `bluetape4k.aws.dynamodb.dax`.
- `DynamoDbAutoConfigurationTest`는 클래스 수준 MockK 인스턴스를 재사용하고
  `@BeforeEach`에서 초기화한다.
- `aws-spring-boot` 아키텍처 다이어그램은 이제 선택적 DAX 자동 구성 경로와
  DAX 서비스 대상을 포함한다.

후속 검증:

- `xmllint --noout docs/images/readme-diagrams/aws-spring-boot-architecture-01.svg docs/images/readme-diagrams/aws-spring-boot-architecture-01-sketch.svg`
  - passed
- `rsvg-convert -w 1240 -h 1100 docs/images/readme-diagrams/aws-spring-boot-architecture-01.svg -o docs/images/readme-diagrams/aws-spring-boot-architecture-01.png`
  - 통과
- 렌더링된 PNG 검사:
  - 통과; README 배율에서 DAX 카드, DAX 경로, 카드 텍스트, 바닥글, 외부 여백을 읽을 수 있다.
- `./gradlew :bluetape4k-aws-spring-boot:test --tests 'io.bluetape4k.aws.spring.dynamodb.*'`
  - `13 passing`
- `git diff --check`
  - 통과

## 게이트 판정

PASS.

구현 검토 게이트 상태:

- `P0=0`
- `P1=0`
