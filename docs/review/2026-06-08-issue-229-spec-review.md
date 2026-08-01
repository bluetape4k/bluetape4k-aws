# Issue #229 명세 검토

## 범위

`docs/superpowers/specs/2026-06-08-issue-229-s3-vectors-design.md`를 GitHub issue #229,
공식 AWS S3 Vectors 증거, 현재 `aws-java`, `aws-spring-boot`, `aws-ktor` 패턴,
bluetape4k 워크플로 요구 사항과 대조해 검토했다.

## 입력 자료

- 2026-06-08에 갱신된 GitHub issue #229
- `S3VectorsAsyncClient`용 AWS SDK Java v2 API 참조
- Amazon S3 Vectors 사용자 가이드와 API 작업 참조
- `software.amazon.awssdk:s3vectors:2.46.0` Maven artifact 확인
- 기존 Access Grants Spring 및 Ktor 구현
- 선택적 S3 Vector 의존성 경계를 요구하는 이전 교훈

## 7단계 검토 결과

| 단계 | 범위 | P0 | P1 | P2 | P3 | 비고 |
|---|---|---:|---:|---:|---:|---|
| 1 보안 | AWS 자격 증명, IAM namespace, endpoint override, 지원하지 않는 emulator 주장 | 0 | 0 | 0 | 0 | 명세는 AWS SDK 인증 소유권을 호출자/기본 provider에 두고 로컬 emulator 지원을 주장하지 않는다. |
| 2 Ops/SRE | 시작, 종료, 재시도/제한 시간, 리소스 정리 | 0 | 0 | 0 | 0 | 부작용 없는 설치, plugin 소유 리소스 종료, 일반 AWS SDK timeout/retry 구성 지침을 요구한다. |
| 3 구조 영향 | `aws-java`, `aws-spring-boot`, `aws-ktor`, version catalog | 0 | 0 | 0 | 0 | 선택적 SDK alias 하나를 추가하고 어댑터 간에 공유 `aws-java` facade를 재사용한다. |
| 4 Kotlin/API 품질 | coroutine facade, public API, Ktor plugin surface | 0 | 0 | 0 | 0 | 첫 공개 작업 집합을 좁히고 기본적으로 Spring/Ktor facade 중복을 막는다. |
| 5 테스트 가능성/타입 | SDK double, Spring slice test, Ktor route test | 0 | 0 | 0 | 0 | missing-class, caller-owned, customizer, delegation, lifecycle, route 수준 테스트를 지정한다. |
| 6 성능/안정성 | 비동기 client 동작, dependency footprint, service 성숙도 | 0 | 0 | 0 | 0 | 자격 증명/endpoint 탐색의 blocking 위험을 설명하고 SDK 의존성을 선택적으로 유지한다. |
| 7 문서/릴리스 증거 | README 언어판, 의존성 문서, 증거 무결성 | 0 | 0 | 0 | 0 | 영어/한국어 README 갱신과 교훈을 요구하며 README 시각 자료가 바뀌지 않으면 다이어그램은 요구하지 않는다. |

## 게이트 판정

PASS.

- P0: 0
- P1: 0
- P2: 0
- P3: 0

가장 가능성 높은 경계 위험을 명세에서 해소했으므로 계획 단계로 진행할 수 있다. 구현이
실제 package 경계 문제를 입증하지 않는 한 Spring Boot와 Ktor는 공유 `aws-java`
`S3VectorsOperations` 표면을 재사용해야 한다.

## 반복 2 - 확장 함수 이름 명확화

AWS SDK Java v2 `S3VectorsAsyncClient` bytecode를 검사한 뒤 저수준 coroutine 확장 함수가
`*Suspend` 이름을 쓰도록 명세를 명확히 했다. 이는 `listVectorBuckets`처럼 이미 같은 이름을
쓰고 `CompletableFuture`를 반환하는 AWS SDK 비동기 메서드와 Kotlin member-method 해석이
충돌하는 것을 피한다.

- P0: 0
- P1: 0
- P2: 0
- P3: 0

## 증거

- `gh issue view 229 --json body`로 issue 본문에 `s3vectors` SDK 표면, 선택적 의존성 경계,
  test double 제약이 포함됨을 확인했다.
- `curl -fsSI https://repo1.maven.org/maven2/software/amazon/awssdk/s3vectors/2.46.0/s3vectors-2.46.0.pom`
  HTTP 200을 반환했다.
- `gradle/libs.versions.toml`에는 `aws2 = "2.46.0"` 줄이 있고 현재
  `aws2-s3vectors` alias는 없다.
- 기존 `S3AccessGrantsAutoConfiguration`과 `S3AccessGrantsKtorPlugin`이 선택적
  compile-only 및 lifecycle 패턴을 제공한다.
- 이전 교훈 `2026-05-26-issue-203-ktor-s3-advanced.md`와
  `2026-05-27-issue-192-spring-s3-advanced.md`는 S3 Vector를 기본 S3 API 표면에서
  제외하도록 요구한다.
