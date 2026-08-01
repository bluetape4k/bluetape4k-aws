# Issue #312 Bedrock Runtime 최소 facade

## 배경

Issue #312는 AWS Java SDK v2와 AWS Kotlin SDK의 Bedrock Runtime `Converse` 및
`ConverseStream`을 bluetape4k 방식으로 제공하는 Type A 작업이었다. 새 SDK
추상화를 만드는 대신 네이티브 요청·응답을 유지하면서 빌더, coroutine,
`Flow`, 클라이언트 수명 주기만 얇게 보완해야 했다.

작업 시작 시 기능 worktree가 저장소 workflow 초기화보다 먼저 만들어져
승인된 spec/plan 상태가 현재 worktree와 어긋나 있었다. 또한 초기 emulator
검증 실패는 구현 결함이 아니라 Colima Docker socket과 JDK self-attach
환경의 차이에서 발생했다.

## 결정 및 발견

### workflow 상태는 저장소 증거로 복구한다

worktree가 먼저 만들어졌더라도 새로 계획을 작성하거나 구현을 다시 시작하지
않았다. 현재 브랜치, 승인된 spec/plan 커밋, GitHub issue 상태, worktree
차이를 다시 읽고 실제 중단 지점을 복구했다. `.omx` 상태는 실행 보조 정보일
뿐 승인 이력과 Git 커밋을 대체하지 않는다.

### 환경 실패와 코드 회귀를 분리한다

Colima 기반 테스트는 다음 환경을 명시했을 때 재현됐다.

```bash
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :bluetape4k-aws-java:test :bluetape4k-aws-kotlin:test \
  --no-daemon --no-configuration-cache
```

socket 연결, 컨테이너 기동, JDK attach가 실패하면 먼저 인프라 전제부터
확인해야 한다. 동일 조건에서 대상 테스트와 전체 모듈 테스트가 통과한 뒤에야
코드 회귀 여부를 판단했다.

### 생성형 SDK union은 고정된 소스에서 확인한다

Java SDK의 스트림 이벤트는 공통 응답 타입 아래 구체 이벤트가 배치되고,
Kotlin SDK는 sealed union의 `asXxxOrNull()` 접근자를 제공한다. 이름을
기억에 의존해 추정하지 않고 version catalog가 고정한 SDK 소스와 컴파일
fixture로 실제 타입 모양을 확인했다. 공개 helper는 네이티브 응답을 그대로
반환하고, 텍스트 선택기는 알 수 없는 미래 variant를 버리더라도 원본 응답을
호출자가 계속 검사할 수 있게 했다.

### SDK retry와 exactly-once는 다른 계약이다

Java SDK의 `ConverseStream` handler는 SDK retry 과정에서 새 publisher를
전달할 수 있다. adapter는 가장 최신 세대만 활성화하고 이전 subscription을
한 번만 취소하며 늦게 도착한 signal을 버린다. 이는 SDK retry를 보존하는
것이지 의미상 중복을 제거하는 것이 아니다. 따라서 API와 문서는
exactly-once, deduplication, replay를 약속하지 않는다.

### bluetape4k 기능은 의미가 맞을 때만 재사용한다

- 입력 검증은 `requireNotBlank`와 `requireNotEmpty`를 사용했다.
- Kotlin SDK 클라이언트의 scoped 수명은 `useSafe`로 보장했다.
- 텍스트 delta 선택은 `map(...).castNotNull()`로 순서와 빈 문자열을
  보존했다.
- README의 협력적 중단 예제는 `takeUntil`을 사용하되, upstream이 다음
  이벤트를 방출해야 중단 상태를 관찰한다는 한계를 함께 설명했다.
- `Flow.log`와 `KLoggingChannel`은 prompt/output 유출 위험 때문에
  Bedrock 경로에서 사용하지 않았다.
- `FlowEvent`는 네이티브 SDK 이벤트와 오류 계약을 가릴 수 있어 추가하지
  않았다.
- 병렬 mapping은 이벤트 순서를 흐리고 이 작업의 얇은 adapter 범위를
  벗어나므로 사용하지 않았다.

### SVG와 PNG는 한 쌍의 계약이다

영문·한글 다이어그램은 각각 편집 가능한 SVG와 정확히 2배 크기의 authoritative
PNG를 함께 저장했다. SVG 구조 검사, connector/geometry/style 검사, 2x
치수 확인 뒤 PNG를 원본 크기로 열어 텍스트 겹침과 clipping을 확인했다.
축소 renderer의 캐시 때문에 발생한 오탐은 현재 PNG hash와 새 crop을
대조해 해소했다.

## 결과

- Java sync, `CompletableFuture`, coroutine, streaming facade와 Kotlin native
  suspend/streaming facade를 네이티브 SDK 응답 계약 위에 추가했다.
- Bedrock SDK는 두 published module 모두 `compileOnly`로 유지했고,
  소비자 fixture가 런타임 SDK를 직접 추가해야만 컴파일되도록 고정했다.
- 각 `Flow` collection이 새 과금 가능 요청임을 문서화하고 cancellation,
  timeout, retry 중복, 부분 출력, 클라이언트 종료 책임을 명시했다.
- raw prompt/output logging을 추가하지 않았고 endpoint는 HTTPS 또는 literal
  loopback HTTP만 허용했다.

## 검증

- Java/Kotlin 전체 모듈 테스트: Java 350건 통과·14건 pending, Kotlin
  562건 통과·12건 pending
- Java/Kotlin Bedrock 대상 테스트: Java 44건, Kotlin 29건 통과, 실패 0
- `./gradlew detekt --no-daemon --no-configuration-cache`: 성공
- `./gradlew build -x test --parallel --no-daemon --no-configuration-cache`:
  성공
- Java/Kotlin 소비자 fixture와 publication metadata 생성: 성공
- publication POM 검사: 2개 파일, 195개 dependency, 실패 0
- published runtime Bedrock dependency: Java 0건, Kotlin 0건
- Gradle module metadata의 `bedrockruntime`: 0건
- release-bound manual: 14개 project, 248개 link, 누락 0
- 영문 PNG SHA-256:
  `c18c47139d10cbc9f7c461e21d158686c7061162b767034e405b0e2390afba2d`
- 한글 PNG SHA-256:
  `4e88bf0e1a113b3ff8171fd1705a7f137788c9fbf942fa478ade835921694cf1`

credentialed Bedrock smoke는 기본 검증에서 실행하지 않았다. `-PbedrockSmoke`,
`BEDROCK_REGION`, `BEDROCK_MODEL_ID`가 모두 있을 때만 client를 만들도록
gate를 두었고, 입력이 없으면 client 생성 전에 skip되는 것을 확인했다.

## 향후 지침

- AWS SDK를 올릴 때 생성형 union과 stream callback 순서를 pinned source에서
  다시 확인하고 공개 helper를 컴파일 fixture로 검증한다.
- streaming adapter가 SDK retry를 수용해도 exactly-once를 문서화하지 않는다.
- Flow 중단 정책은 협력적 `takeUntil`과 즉시 취소하는 `withTimeout`을
  구분해 설명한다.
- release manual 검증에는 현재 authoring inventory가 아니라 release tag에서
  필터링한 inventory를 사용한다. 새 unreleased example이 현재 inventory에
  포함되면 안정 release의 manual contract와 직접 비교할 수 없다.
- 다이어그램을 고치면 SVG와 2x PNG를 같은 변경에서 다시 생성하고, 자동
  검사와 원본 크기 시각 검사를 모두 수행한다.
