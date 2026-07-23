# Issue #312 Bedrock Runtime 코드 리뷰

## 결론

Step 6-R 독립 리뷰와 수정 후 재검토는 `APPROVE`로 수렴했다.

| 심각도 | 최초 | 최종 |
|---|---:|---:|
| P0 | 0 | 0 |
| P1 | 1 | 0 |
| P2 | 3 | 0 |
| P3 | 0 | 0 |

검토 범위는 `origin/develop...d0974e6`이며 Developer/API, stability,
operator/ops, security, user/caller, performance 관점을 분리해 확인했다.

## 수정된 finding

### P1: retry publisher handoff 전 취소 누락

- 근거:
  `aws-java/src/main/kotlin/io/bluetape4k/aws/bedrock/BedrockRuntimeFlowExtensions.kt`
- 문제: retry callback coroutine이 시작된 뒤 이전 generation 종료를 기다리는
  동안 collector가 취소되면 새 publisher가 subscription이나 coordinator에
  인계되기 전에 소유권을 잃을 수 있었다.
- 수정: `handedOff` 경계를 두고 새 `StreamAttempt`에 ownership을 넘기기 전
  모든 반환·예외·취소 경로에서 `finally`가 publisher를 즉시 취소한다.
  handoff 뒤에는 기존 `StreamAttempt.cancelOnce()`가 단독 소유한다.
- 회귀 증거: latch로 이전 publisher 취소를 막아 handoff 전 구간을
  결정적으로 만든 테스트가 수정 전 실패하고 수정 후 통과했다. operation
  future, 이전 publisher, replacement publisher의 `cancelCount`는 각각 1이다.

### P2: smoke skip 사유의 일반 출력 누락

- 근거: `aws-java/build.gradle.kts`, `aws-kotlin/build.gradle.kts`
- 수정: `-PbedrockSmoke`가 요청됐지만 환경 변수가 부족하면 task logger가
  `bedrock-smoke: SKIP before client creation; missing=...`을 lifecycle level로
  출력한 뒤 task를 skip한다.
- 검증: Java와 Kotlin 모두 `BEDROCK_REGION,BEDROCK_MODEL_ID`를 명시하고
  client 생성 전에 `SKIPPED`됐다.

### P2: 루트 README의 Bedrock 지원 누락

- 근거: `README.md`, `README.ko.md`
- 수정: 서비스 목록, Java/Kotlin 모듈 표, 소비자 dependency snippet에
  Bedrock Runtime을 추가했다. 기존 service chart는 모든 core-only 서비스를
  열거하는 표가 아니라 주요 교차 모듈 통합 표라고 제목·설명을 좁혔다.
- 다이어그램 검증: SVG XML/저장소 validator 통과, PNG `3800x2080`,
  CairoSVG 2x 재렌더 hash 일치, 원본 크기 clipping/overlap 없음.

### P2: blank content 검증 범위 과장

- 근거: `aws-java/README*.md`, `aws-kotlin/README*.md`
- 수정: 임의의 네이티브 `Message` 내부까지 검사한다고 읽히던 문장을
  `contentBlockOf`와 `userMessageOf`에 전달한 빈 text만 거절한다는 실제
  계약으로 좁혔다.

## 관점별 최종 결과

### Developer/API 및 performance

- Java SDK v2 `2.47.1`과 AWS Kotlin SDK `1.8.0`의 pinned artifact를
  `javap`로 확인했으며 공개 helper signature가 생성형 SDK API와 일치한다.
- sync, future, suspend 경로는 native `ConverseResponse`를 유지하고 streaming은
  native `ConverseStreamOutput`을 순서대로 전달한다.
- `textOrEmpty`는 `buildString` 단일 순회, `firstTextOrNull`은 첫 text에서
  중단한다. 1,000 block 및 counting-list 테스트가 순서와 allocation 경계를
  고정한다.
- 새 공개 helper에는 영문 KDoc가 있으며 client ownership, cold/billable
  collection, cancellation/error, retry/exactly-once 경계를 설명한다.

### Stability 및 operator/ops

- Java adapter는 `request(1)`, active generation 하나, 이전 generation
  cancel-once, late signal 폐기, first-terminal-wins를 유지한다.
- 새 handoff 회귀 테스트를 포함한 Java Bedrock 테스트 44건이 통과했다.
- Kotlin native Flow는 response scope 안에서 수집되고 structured cancellation
  뒤 client가 한 번 닫힌다.
- credentialed smoke는 기본 test에서 제외되며 property와 두 환경 변수가 모두
  있을 때만 실행된다.

### Security

- endpoint는 HTTPS 또는 literal loopback HTTP만 허용하고 builder-only
  override도 최종 설정에서 다시 검사한다.
- Bedrock 경로에는 raw prompt/output logging, `Flow.log`,
  `KLoggingChannel`이 없다.
- native SDK 오류는 호출자에게 유지하되 README는 원문 예외·prompt·output을
  애플리케이션 경계 밖에 그대로 노출하지 말라고 경고한다.

### User/caller

- 영문·한글 README는 cold Flow 재수집 비용, `takeUntil`의 협력적 중단,
  `withTimeout`의 즉시 취소, partial output, SDK retry 의미 중복,
  exactly-once 부재, non-streaming 대안을 같은 구조로 설명한다.
- Bedrock sequence SVG/2x PNG는 retry generation, 이전 subscription 취소,
  late signal 폐기, normal/error terminal, client close 순서를 구현과 같게
  표현한다.
- `CHANGELOG.md`와 PR 전 `WIP.md`는 live issue/milestone 상태와 일치한다.

## bluetape4k 재사용 및 금지 패턴

- 입력 검증: `requireNotBlank`, `requireNotEmpty`
- client 수명: `useSafe`
- text delta 선별: `map(...).castNotNull()`
- reader 예제의 협력적 종료: `takeUntil`
- 사용하지 않음: production `!!`, `GlobalScope`, suspend `runCatching`,
  cancellation swallow, parallel mapping, Bedrock raw logging

## 검증 증거

| 검증 | 결과 |
|---|---|
| Java Bedrock 대상 테스트 | 44 passing, 실패 0 |
| Kotlin Bedrock 대상 테스트 | 29 passing, 실패 0 |
| Java 전체 모듈 | 350 passing, 14 pending |
| Kotlin 전체 모듈 | 562 passing, 12 pending |
| `detekt` 및 `build -x test` | 성공 |
| Java/Kotlin 소비자 fixture | 성공 |
| publication POM 검사 | 2 files, 195 dependencies, failures 0 |
| published runtime Bedrock dependency | Java 0, Kotlin 0 |
| Gradle module metadata Bedrock entry | Java 0, Kotlin 0 |
| manual contract | 9 runs, 44 assertions, failures 0 |
| release-bound manual links | 248 links, missing 0 |
| Bedrock sequence diagrams | SVG audits 통과, `1960x1320` → `3920x2640` |
| root selected-service chart | SVG validator 통과, `1900x1040` → `3800x2080` |
| `git diff --check` | 성공 |

Gradle 재현 환경은 다음과 같다.

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :bluetape4k-aws-java:test :bluetape4k-aws-kotlin:test \
  --no-daemon --no-configuration-cache
```

publication task는 저장소의 기존 configuration-cache 비호환 때문에
`--no-configuration-cache`로 실행했다. POM의 `dependencyManagement`에는
version authority를 위한 Bedrock catalog constraint가 존재하므로 raw 문자열
0건 검사가 아니라 runtime dependency 0건과 module metadata 0건으로 출판
경계를 판정했다.

## 잔여 위험

- 실제 AWS credential과 과금 가능한 model을 사용하는 smoke는 opt-in이므로
  이번 기본 완료 조건에서 실행하지 않았다.
- SDK upgrade 시 생성형 union과 retry callback 순서가 달라질 수 있으므로
  pinned source 대조와 handoff race 테스트를 다시 수행해야 한다.
