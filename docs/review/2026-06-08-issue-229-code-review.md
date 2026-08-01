# Issue #229 Step 6-R 코드 검토

`aws-java`, `aws-spring-boot`, `aws-ktor`, README 언어판, README 다이어그램/차트
자산에 걸친 선택적 S3 Vectors 지원을 검토했다.

## 범위

- 기준: `origin/develop`
- 변경 모듈: `aws-java`, `aws-spring-boot`, `aws-ktor`
- 지원 산출물: 명세, 계획, 명세/계획 검토 노트, 교훈, README 다이어그램, wiki 연구 노트
- CodeGraph: `detect_changes`와 `get_impact_radius`는 추적 중인 변경 파일 27개를 감지했지만
  현재 graph node mapping은 직접 code node 0개를 반환했다. 따라서 구조 검토는 최종 diff와
  집중 컴파일/테스트를 주요 증거로 사용했다.

## 모듈 구간 요약

| 구간 | 1단계 보안 | 2단계 Ops/SRE | 3단계 구조 | 4단계 Kotlin | 5단계 테스트 | 6단계 성능/안정성 | 7단계 문서/릴리스 | 게이트 |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `aws-java` | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | PASS |
| `aws-spring-boot` | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | PASS |
| `aws-ktor` | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | PASS |
| README/diagrams | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | N/A | N/A | P0=0/P1=0/P2=0/P3=0 | P0=0/P1=0/P2=0/P3=0 | PASS |

## 단계별 검토 노트

| 우선순위 | 파일:줄 | 영역 | 결과 | 처리 |
|---|---|---|---|---|
| 없음 | N/A | 보안 | 비밀, 자격 증명 기본값, 안전하지 않은 역직렬화, SQL/NoSQL injection 또는 인증 경계 확장을 찾지 못했다. S3 Vectors IAM/policy 작업은 원시 SDK client에 남는다. | PASS |
| 없음 | N/A | Ops/SRE | Spring Boot와 Ktor는 명시적으로 활성화하거나 설치할 때만 client를 생성한다. 호출자 소유 client는 framework adapter가 닫지 않는다. | PASS |
| 없음 | N/A | 구조 | `aws-java`가 공유 `S3VectorsOperations` facade를 소유하며 Spring Boot와 Ktor는 adapter별 facade를 중복하지 않고 재사용한다. `s3vectors`는 선택 사항이다. | PASS |
| 없음 | `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3vectors/S3VectorsKtorPlugin.kt` | Kotlin/lifecycle | 새 `runBlocking(Dispatchers.IO)`은 동기 Ktor `ApplicationStopping` hook에만 있으며 plugin 소유 SDK client에 대한 기존 Ktor plugin 종료 패턴과 일치한다. | 의도됨 |
| 없음 | N/A | 테스트 | 집중 테스트가 facade 위임, cancellation 전파, Spring 조건부 활성화/backoff/customizer, Ktor runtime 소유권/route 접근/customizer를 다룬다. | PASS |
| 없음 | N/A | 성능/안정성 | 새 retry loop, polling loop, 무제한 buffer, container startup 또는 hot-path allocation 우려가 없다. client close 경로는 `Dispatchers.IO`에서 `runInterruptible`을 사용한다. | PASS |
| 없음 | N/A | 문서/릴리스 | README와 README.ko를 함께 갱신했다. runtime dependency 소유권과 emulator 미지원 주장 금지 경계를 문서화했다. 구성 요소 맵과 서비스 범위 차트는 기존 pastel card/badge 장식을 유지하고 영문 label을 사용하며 geometry gate가 있는 script로 재생성된다. | PASS |

## 스캔 증거

- 운영 코드 동시성 스캔:
  `rg "GlobalScope|runBlocking\\(|Thread\\.sleep|delay\\(|synchronized\\(|@Synchronized|runCatching\\s*\\{" aws-java/src/main/kotlin aws-spring-boot/src/main/kotlin aws-ktor/src/main/kotlin`
- 이 브랜치의 새 일치 항목:
  `aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/s3vectors/S3VectorsKtorPlugin.kt`
  종료 hook에만 있다. 기존 일치 항목은 이전 lifecycle, retry, parser, observer 패턴이다.
- 금지된 README 다이어그램 글꼴 스캔:
  `rg -n "Arial|Helvetica|Inter" docs/images/readme-diagrams/bluetape4k-aws-service-coverage-chart-05.svg docs/images/readme-diagrams/bluetape4k-aws-components-04.svg`
  일치 항목이 없었다.
- 글꼴 탐색:
  `fc-list`에서 `Architects Daughter`와 `Comic Mono`를 찾았다.
- 다이어그램 생성기:
  `python3 tools/generate-root-readme-diagrams.py`
  최종 PNG 렌더링 전에 다음 geometry 요약을 출력했다.
  - `bluetape4k-aws-components-04`: `nodes=10`, `routes=12`, `segments=12`,
    `badEndpointAngle=0`, `badBends=0`, `interiorCrossings=0`,
    `laneClearance=0`, `titleGap=55`.
  - `bluetape4k-aws-service-coverage-chart-05`: `nodes=3`, `routes=0`,
    `segments=0`, `badEndpointAngle=0`, `badBends=0`,
    `interiorCrossings=0`, `laneClearance=0`, `titleGap=40`.
- SVG/XML 및 PNG 검사:
  최종/스케치 SVG 자산에 대해 `xmllint --noout`이 통과했고, `file`은 최종 PNG 크기가
  `1600x940` 및 `1900x820`임을 확인했다.
- GNO 다이어그램 증거:
  `gno query "bluetape4k-aws README diagram component map service coverage routing font"`
  `bluetape4k-docs`와 `bluetape4k-github`에서 모두 확인했으며 관련 이전 지침은 PR #266,
  PR #236 및 루트 README 다이어그램 교훈이었다.
- 시각 검사:
  기존 badge-matrix 형태를 복원하고 S3 Vectors 열을 위해 canvas를 넓힌 뒤
  `bluetape4k-aws-service-coverage-chart-05.png`를 검사했다. `yes`/`-`/`opt-in` badge는
  각 행 cell 중앙에 있다. placeholder sketch를 읽을 수 있는 Graphviz matrix preview로
  교체한 뒤 `bluetape4k-aws-service-coverage-chart-05-sketch.png`를 검사했다.
  core, config, verification, runtime 흐름에 직선 boundary-to-boundary route와 의미 기반
  route color를 사용하는 계층형 구성 요소 맵으로 바꾼 뒤 `bluetape4k-aws-components-04.png`와
  `bluetape4k-aws-components-04-sketch.png`를 검사했다. 다이어그램은 runtime 계층에 AWS 또는
  emulator 서비스, JDBC store, managed configuration을 복원한다.

## 통합 증거

- `./gradlew :bluetape4k-aws-java:dependencyInsight --dependency s3vectors --configuration compileClasspath :bluetape4k-aws-spring-boot:dependencyInsight --dependency s3vectors --configuration compileClasspath :bluetape4k-aws-ktor:dependencyInsight --dependency s3vectors --configuration compileClasspath --no-daemon --max-workers=1`
  - PASS; 세 모듈 모두 `software.amazon.awssdk:s3vectors:2.46.0`으로 해석된다.
- `./gradlew :bluetape4k-aws-java:compileKotlin :bluetape4k-aws-java:compileTestKotlin :bluetape4k-aws-spring-boot:compileKotlin :bluetape4k-aws-spring-boot:compileTestKotlin :bluetape4k-aws-ktor:compileKotlin :bluetape4k-aws-ktor:compileTestKotlin --no-daemon --max-workers=1`
  - PASS.
- `./gradlew :bluetape4k-aws-java:test --tests '*S3Vectors*' :bluetape4k-aws-spring-boot:test --tests '*S3Vectors*' :bluetape4k-aws-ktor:test --tests '*S3Vectors*' --tests '*AwsKtorCoreTest' --rerun-tasks --no-daemon --max-workers=1`
  - PASS; `aws-java` 10개, `aws-spring-boot` 8개, `aws-ktor` 13개가 통과했다.
- `git diff --check`
  - PASS.
- Wiki 보존:
  `gno update`, `gno embed --collection bluetape4k-wiki`, and
  `gno search "S3 Vectors s3vectors bluetape4k" -c bluetape4k-wiki` returned
  `research/2026-06-08-aws-s3-vectors.md`를 반환했다.

## 최종 게이트

- P0 = 0
- P1 = 0
- 결정: PASS. PR 생성을 진행할 수 있다.
