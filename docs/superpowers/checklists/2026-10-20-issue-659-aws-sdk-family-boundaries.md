# 이슈 #659 AWS SDK 계열 경계 작업 체크리스트

## 작업 기준

- 유형: Type A 전체 기능·아키텍처 변경
- 저장소: `bluetape4k/bluetape4k-aws`
- 기준: `origin/develop` at `18bacc3ce2fecdcd5bc62ba07e6a0b830d223666`
- 브랜치 / worktree: `refactor/aws-sdk-family-boundaries` / `.worktrees/refactor/aws-sdk-family-boundaries`
- 목표: `aws-ktor`는 AWS Kotlin SDK 계열만, `aws-spring-boot`는 AWS Java SDK 계열만 참조한다.
- 호환성: 사용자가 ABI 호환성 파괴를 허용했다. 기존 기능과 관찰 가능한 동작은 유지한다.
- 중단 조건: 설계·계획 검토에서 P0/P1이 남거나, 구현 후 필수 검증이 실패하면 PR을 만들지 않는다. merge는 최신 정확한 HEAD에 대한 별도 승인 전까지 보류한다.
- PR 범위: `bluetape4k/bluetape4k-aws`, base `develop`, head `refactor/aws-sdk-family-boundaries`.

## 라우터·공통 게이트

- [x] WF-00 — 사용자·워크스페이스·저장소 `AGENTS.md` 확인: 사용자 `/Users/debop/.codex/AGENTS.md`, 워크스페이스 `.github/docs/workspace/AGENTS.md`, 저장소 `AGENTS.md`.
- [x] WF-01 — Type A 분류: 두 모듈의 공개 의존 경계와 다수의 서비스 구현·예제·테스트를 함께 바꾸는 아키텍처 변경이다.
- [x] WF-02 — 첫 실행 계획 제시: worktree, 조사·설계, 승인 계획, 구현·검증·리뷰, lesson, 정확한 HEAD PR/CI를 포함했다.
- [x] WF-03 — 첫 계획 승인: 사용자가 활성 대화에서 승인했다.
- [x] WF-04 — 전체 기능·공통 게이트 및 worktree 지침을 읽었다. 문서·Kotlin·TDD 세부 게이트는 각 산출물/구현 단계에서 적용한다.
- [x] WF-04A — `bluetape-flow.py` 실행 영수증 초기화: 현재 run `20261020T030129Z-f0647513`, 상태 `running`, 구성요소 `issue-659-sdk-family-boundaries`; 저장소 worktree 전용 `.bluetape` 사용. `verify`가 sequence 7/checksum `f237bef8…`에서 정상임을 확인했다.
- [ ] WF-05 — 설계, 계획, 구현과 검증 게이트를 순서대로 수행한다.
- [ ] WF-06 — 누락 또는 실패한 게이트가 있으면 의존 검증을 다시 수행한다.

## Type A 단계

- [x] A-01 — 승인된 범위와 요구사항 확인: 이슈 #659는 OPEN이며 본문은 Ktor의 Kotlin SDK 계열 사용과 Spring Boot의 Java SDK 사용을 요구한다. 현재 develop은 깨끗했고 기존 worktree는 보존했다.
- [x] A-02 — 현재 소스·의존성·선행 결정·공식 SDK 근거를 설계 문서와 대조했다. Ktor Java SDK 참조 소스 50개, 서비스·예제·fixture 경계, AWS Kotlin 서비스 API와 signer 근거를 확인했다.
- [x] A-03 — 승인된 SDK 경계 방향과 IMDS 원격 HTTP 차단을 반영했다. 초기 통합 검토는 P0=0/P1=3으로 `REJECT`했으며 dependency version 공급·공개 presign 경로·IMDS profile resolver를 수정했다. 이후 독립 계획 검토가 Task 6에서 공통 Java API를 조기 삭제하는 P1을 찾아 Task 7의 공통 테스트·consumer fixture 이전 뒤로 삭제를 이동했다. 재검토는 `APPROVE`, P0=0/P1=0/P2=0이다. 검토 기준 HEAD는 `18bacc3ce2fecdcd5bc62ba07e6a0b830d223666`이며 spec `282019f6371342b391f27126b96a5a20156bbf0d0f7f7451349941dde3165704`, plan `08b134c16f6dea53c90083ba43662cd3f89a6f223216add21e3e079a45df0990`, checklist `e7117f8e8ea419379d0162cfe02f9c6c94ebcfe9286208fd36f5c19fa861bc07`의 SHA-256를 검토했다. PR 전 branch 검증과 독립 리뷰만 이번 단계에서 완료하고 exact PR HEAD CI는 A-10에서 확인한다.
- [x] A-04 — [`docs/superpowers/plans/2026-10-20-issue-659-aws-sdk-family-boundaries.md`](../plans/2026-10-20-issue-659-aws-sdk-family-boundaries.md)에 파일 지도, 테스트 우선 순서, 구현 단위, 검증 명령 및 19개 인수 조건 추적표를 작성했다. 사용자는 2026-10-20 계획을 승인했고 ABI 호환성 파괴를 허용했다. 독립 재검토 P1을 반영해 공통 Java API 삭제를 공통 테스트·fixture 이전 뒤로 옮겼으며, 보정 계획은 고정한 문서 해시에서 `APPROVE`, P0=0/P1=0/P2=0으로 승인됐다.
- [x] A-05 — SDK 매핑, 서명 변환, 서비스 의미 차이, 선택 의존성, 소비자 마이그레이션의 위험·완화·롤백을 명세와 계획에 기록했다.
- [ ] A-06 — 기능별 테스트를 먼저 작성하고 구현한다.
- [ ] A-07 — 승인 명세·계획과 일치하는지 확인하고 영향 모듈·예제·정적 분석을 검증한다.
- [ ] A-08 — 6개 코드 리뷰 관점과 통합 리뷰를 통과한다. P0=0/P1=0 필수.
- [ ] A-09 — 한국어 lesson을 작성·검토·커밋한다.
- [ ] A-10 — 승인된 저장소·base·head로 PR을 만들고 읽어온 뒤, 리뷰·대화 스레드·정확한 HEAD CI를 확인한다.
- [ ] A-11 — merge-ready 상태와 미해결 게이트를 보고한다. fresh merge approval 전에는 대기한다.
- [ ] A-12 — 현재 PR HEAD에 대한 별도 승인이 있어야 merge·동기화·승인된 cleanup·GNO 인덱싱을 수행한다.

## 설계 명세 문서 게이트

- [x] SPW-01 — 독자(저장소 설계·구현 검토자), 목적(이슈 #659의 SDK 경계와 동작 계약 결정), 한국어 문서 범위, 저장소 소스와 고정한 AWS/Smithy API 근거를 명세와 [조사 노트](../../../../../../../bluetape4k-wiki/research/2026-10-20-aws-kotlin-imds-smithy-signing.md)에 기록했다. 저장소 기본 catalog ref `198147fd6ca2992fae32e175bd5f3d396c497096`이 지정한 AWS Kotlin `1.8.46`·Smithy Kotlin `1.7.9`와 upstream 소스 고정점을 구분했다.
- [x] SPW-02 — 문제, 선택지, 책임 경계, 실패 모드, ABI/동작 호환성, 인수 조건, DoD와 승인 경계를 포함했다.
- [x] SPW-03 — facts lock 이후 한국어 문장과 용어를 최종 명세·체크리스트에서 다시 읽고 모호한 API/type 구분을 수정했다. 명세/체크리스트 용어 감사는 각각 findings=0이고 최종 Markdown 공백·링크 검사가 통과했다.
- [x] SPW-04 — 초기 API·architecture·caller 리뷰의 변경 요청과 통합 P1 세 항목을 각각 근거로 확인해 수정했다. 최신 독립 검토는 고정한 spec·plan·checklist 해시에서 P0=0/P1=0/P2=0으로 승인했다. pinned AWS Java/Smithy 소스, Smithy KMP metadata, spec-to-plan AC 추적표와 AC19 상태를 다시 대조했다.
- [x] SPW-05 — 수정된 명세·계획·체크리스트를 최종 read-back했다. 독립 검토는 spec `282019f6371342b391f27126b96a5a20156bbf0d0f7f7451349941dde3165704`, plan `08b134c16f6dea53c90083ba43662cd3f89a6f223216add21e3e079a45df0990`, checklist `e7117f8e8ea419379d0162cfe02f9c6c94ebcfe9286208fd36f5c19fa861bc07`을 확인했다. 기록 갱신 후 상대 링크·공백·개행을 다시 검사하고 한국어 용어 감사 findings=0을 확인했다. exact PR HEAD CI는 후속 A-10에서 확인한다.

## 현재 결정과 다음 작업

- 승인된 방향: `aws-ktor`의 Java SDK 호출을 AWS Kotlin SDK 또는 Smithy 기반 구현으로 옮기고, Ktor HTTP 요청과 Smithy 서명 요청 사이에 필요한 경계 변환을 둔다. `aws-spring-boot`는 Java SDK 경계를 유지하며 stale Kotlin 의존 선언을 제거한다.
- 검증할 예외: Ktor의 `aws-exposed` 선택 통합이 Java SDK를 재유입하지 않는지 Gradle 발행 메타데이터로 확인한다.
- 조사 산출물: `bluetape4k-wiki/research/2026-10-20-aws-kotlin-imds-smithy-signing.md`에 원문 source 링크, 고정점, 미확인 항목과 적용 조건을 보존했다. IMDS 추가 내용을 반영해 `gno update`, `gno embed --collection bluetape4k-wiki`를 다시 실행했다. 초기 검색은 결과가 없었으나 `gno search "IMDS RetryableException" -c bluetape4k-wiki`에서 수정 노트와 새 계약 발췌를 확인했다.
- workflow recovery: 손상된 원 receipt를 helper가 quarantine했고, 현재 세션 연결 run `20261020T030129Z-f0647513`는 sequence 3까지 `running`으로 검증했다. 문서 변경 전 mutation-check가 대상 3개를 통과했다.
- 수정 산출물 SHA-256: 독립 검토가 확인한 spec·plan·checklist는 위에 기록했다. checklist는 검토 결과를 기록하기 위해 후속 갱신했으며 최종 checksum은 다시 계산한다.
- 기준 검증: Task 1 test-only Lore commit `1995b6a1` 전후 전체 Ktor suite가 291개 통과했고 `SerializableContractTest` 대상 재실행은 5개 통과했다. Task 2 common-layer 변경 후 Ktor/Spring suite는 `BUILD SUCCESSFUL`, Ktor 292개 통과했다. dependency insight에서 AWS Kotlin `1.8.46`, Smithy `1.7.9`, `http-client-jvm`, `http-client-engine-okhttp-jvm`을 확인했다.
- 현재 작업: Task 1 완료. Task 2 공통 설정 구현과 기존 검증은 `c9320c61`에 기록했다. 당시 Ktor 테스트 302개가 통과했고 Spring 테스트 task는 Gradle cache에서 복원됐다. 이 결과는 아래 endpoint provider 수정 전 기준이다.
- 2026-10-20 독립 재리뷰 및 수정: 첫 후속 리뷰는 P0=0/P1=1, `REQUEST_CHANGES`였다. `DynamoDbClient.Config.Builder.endpointProvider`가 `endpointUrl`과 별도로 최종 `Endpoint.uri`를 반환할 수 있어, customizer 뒤 provider를 래핑하고 매 요청 URI를 HTTPS/opt-in loopback 정책으로 검사하도록 고쳤다. 후속 P2-1은 서비스 customizer가 공유 customizer의 provider를 덮는 경로를 지적했다. 회귀 테스트를 서비스 customizer 경로로 옮겨 우선순위, 공유 provider 미호출, 원격 HTTP 거부, interceptor 등록을 검증한다.
- 후속 P2-2 수정: AWS Kotlin `1.8.46` endpoint discovery가 provider의 host를 바꿀 수 있고 Smithy `1.7.9`는 전송 직전 interceptor를 실행한다. 생성 client에 `modifyBeforeTransmit` 정책 interceptor를 추가해 실제 request URL을 재검사한다. 회귀 테스트는 `localhost`에서 `169.254.169.254`로 바뀐 discovery URL이 차단되는지 확인한다. 주입 client는 호출자 소유이며 별도 보안 책임임을 구성 KDoc에 명시한다.
- Task 2 증거: endpoint 정책/DynamoDB 집중 테스트 25개 및 전체 Ktor 테스트 304개가 `BUILD SUCCESSFUL`로 통과했다. Spring 테스트는 P2-2 Ktor 전용 수정 전 실행한 최신 전체 검증에서 1778개 통과, 6개 skip, 실패 0으로 `BUILD SUCCESSFUL`이었다. 독립 최종 재리뷰는 P0=0/P1=0/P2=0, `APPROVE`; P3 한 건은 fake discoverer/HTTP engine을 연결한 통합 테스트 권고로, 현재 URL 변환·interceptor 등록 단위 근거가 있어 비차단 후속이다.
- Task 3 CloudWatch/Logs 정리 계획: 첫 변경 범위를 두 서비스에 한정하고 Java SDK v2 client, model, customizer 사용부를 AWS Kotlin SDK 경계로 옮긴다. 이미 `aws-kotlin`에 있는 CloudWatch/Logs helper를 재사용하고 새 변환 계층은 만들지 않는다. 주입 `Operations` facade는 호출자 확장점으로 유지하고, Java client 주입·customizer 병행 경로는 제거한다. 공유 코어의 이 두 서비스 설정도 함께 바꾸되 공통 Java wrapper bridge 삭제는 계획된 Task 7까지 보류한다.
- Task 3 CloudWatch/Logs fallback 인벤토리: 두 Ktor 서비스 구현은 AWS Java SDK v2 비동기 client/model 경로만 사용한다. 대체 Kotlin 구현은 기존 `aws-kotlin` 모듈의 service extensions와 client support이며, 중복 호환 fallback은 없다. 운영자가 주입한 `Operations` facade와 caller-owned client lifecycle은 의도된 확장·소유권 경계이므로 보존한다.
- Task 3 EventBridge/Access Grants 이전: `EventBridgeKtorOperations`의 Future/Java model API를 suspend/Kotlin model API로 바꿨다. EventBridge 테스트는 부분 실패 응답 순서·오류 필드, target 선택 필드, 페이지 토큰, 미지 규칙 상태를 잠근다. Access Grants는 S3 Control Kotlin client와 Kotlin request/response를 사용해 임시 자격 증명, nullable 응답, unknown permission, 페이지 토큰을 보존한다. 두 서비스 모두 주입 client 소유권과 플러그인 종료를 유지한다.
- Task 3 S3 Vectors 경계 보정: 현재 Ktor config가 `aws-java`의 `S3VectorsCoroutinesTemplate`를 생성해 Java request/response facade를 노출하므로 Kotlin client만 교체할 수 없다. Ktor 전용 Kotlin model `S3VectorsKtorOperations`/`Template`을 추가하고 Ktor plugin 접근자를 이 facade로 바꾼다. `aws-java` 모듈의 별도 Java SDK API는 유지해 모듈 책임을 섞지 않는다.
- CloudWatch/Logs 동작 잠금: 메트릭 빈 입력 미호출, chunk 순서·기본 namespace 검증, datum 선택 필드와 nullable filter, dimension·pagination token·응답 객체 보존, Micrometer finite measurement 변환을 유지한다. 로그는 group/stream 검증, batch 순서, timestamp 정렬, 실패·취소 drain 복원, 앞 chunk 성공 뒤 재전송될 수 있는 at-least-once 의미, 종료 timeout·dropped count·observer·client close 순서를 유지한다. 기존 테스트 31개는 위 세 test class에서 추가한 선택 필드·nullable filter·next token·부분 성공 뒤 전체 buffer 복원을 포함해 변경 전 `BUILD SUCCESSFUL`로 통과했다.
- Task 3 완료: CloudWatch/Logs, EventBridge, S3 Access Grants/Vectors, SES/SNS/STS를 AWS Kotlin SDK 기반으로 이전했다. S3 Vectors에는 Ktor 전용 Kotlin `Operations`/`Template`을 추가하고 `aws-java`의 독립 API는 유지했다. SES raw/attachment bytes와 attachment enum, SNS 전체 message attribute와 SMS 표준 옵션 덮어쓰기, STS 임시 자격 증명 반환 및 diagnostics redaction 범위를 보존했다.
- Task 3 검증: `./gradlew :bluetape4k-aws-ktor:test --tests 'io.bluetape4k.aws.ktor.cloudwatch.*' --tests 'io.bluetape4k.aws.ktor.eventbridge.*' --tests 'io.bluetape4k.aws.ktor.s3.accessgrants.*' --tests 'io.bluetape4k.aws.ktor.s3vectors.*' --tests 'io.bluetape4k.aws.ktor.ses.*' --tests 'io.bluetape4k.aws.ktor.sns.*' --tests 'io.bluetape4k.aws.ktor.sts.*' --tests 'io.bluetape4k.aws.ktor.AwsKtorApplicationResourceLifecycleTest' --tests 'io.bluetape4k.aws.ktor.AwsKtorLifecycleEdgeCasesTest' --no-daemon` 성공. 최신 JUnit XML: 23 suites, 148 tests, 0 failures, 0 skipped. `git diff --check` 성공; CloudWatch/Logs, EventBridge, Access Grants/Vectors, SES/SNS/STS의 `aws-ktor` source/test에서 AWS SDK v2 service package import 0건.
- 제출 전 독립 리뷰: `$code-review`가 요구하는 `code-reviewer` 및 `architect` lanes를 usage limit 때문에 현재 실행할 수 없다. human review는 N/A (single-developer lane)이지만 독립 코드 리뷰는 대체하지 않았으며 A-08은 `PENDING`; merge-ready 또는 review approval로 표기하지 않는다.
- Task 3 implementation과 검증 결과는 로컬 한국어 Lore commit으로 기록했다. 독립 최종 리뷰 A-08은 미완료이므로 merge-ready verdict가 아니다.
- 다음 작업: Task 5 IMDS Kotlin adapter와 전송 정책을 검증·커밋하고 Task 6 Kinesis/SQS 이전으로 진행한다. 전체 영향 모듈 검증과 독립 최종 리뷰는 A-07/A-08에서 계속한다. PR 생성·merge·동기화·정리는 별도 승인 절차를 따른다.

### Task 4 실행 증거

- Task 4 승인 계획의 범위에서 `AwsSigV4Plugin`과 S3 REST/presign 구현을 Smithy/AWS Kotlin 자격 증명 provider로 옮겼다. ABI 호환성은 유지하지 않는다. 요청마다 provider를 resolve하고, endpoint 정책을 resolve보다 먼저 적용한다. replay 가능한 byte array만 payload 서명하며, unsigned streaming body는 버퍼링하지 않는다.
- 고정 presign vector 서명은 `3db75a0ad67e1d88633145f9c3ffca7556f32c68680f5071e6e2933738adfcbd`이다. 실제 MockEngine 캡처가 signed URL, headers, body와 일치한다. presigned URL round-trip에서 표현할 수 없는 URI는 전송 전에 거부한다.
- 새 suspend presign과 AWS Kotlin credentials provider에 맞게 `aws-ktor` README/KDoc 및 S3 examples source, tests, bilingual README를 갱신했다. 예제 모듈의 첫 컴파일에서 두 소비 경계 오류가 확인되어 이 파일들을 Task 4 구현 범위에 추가한 뒤 `:aws-ktor-s3-examples:test`를 통과시켰다.
- SigV4/S3 focused Gradle 실행: 55 tests, 0 failures, 0 skipped; Floci S3 및 owned default provider rollback-close 테스트 포함. Testcontainers가 Docker CLI context를 읽지 못해 확인된 Colima endpoint를 `DOCKER_HOST`와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`로 해당 테스트 프로세스에만 지정했다. `colima status`, `docker context show`, `docker info` 모두 정상이다.
- `detekt` 실행은 실패했다: 플러그인은 Kotlin `2.4.10`으로 컴파일됐으나 실행 중인 Kotlin Gradle plugin은 `2.4.20`이다. 정적 분석 결과는 미확인으로 남긴다. `git diff --check`는 통과했다.
- 한국어 문서 작성자 게이트: plan, checklist, AWS Ktor README와 S3 examples README의 독자·목적·근거를 고정했고 코드/API 토큰과 명령을 보존했다. 한국어 자연스러움 및 용어 검토를 거쳐 각 artifact를 다시 읽었다. 용어 감사는 4개 대상 파일에서 `series=clinic-appointment`, `findings=0`을 반환했다. 해당 ruleset은 clinic 경로 전용이라 AWS prose에 적용되는 용어 규칙은 없다.
- Task 4 구현·테스트·문서 변경은 한국어 Lore 커밋에 포함한다. A-08 독립 최종 리뷰와 A-07 전체 영향 모듈 검증은 PENDING이며, 이 작업을 merge-ready로 간주하지 않는다.

### Task 4 Superpowers 작성자 게이트

- 승인 계획 SPW-01 — 독자(이슈 구현자·검토자), 목적(SigV4/S3 경계 및 인수 조건 계획), 근거(승인 Task 4 요구사항, 구현 소스·테스트, Gradle 결과), 보존 토큰(`AwsSigner`, Smithy 버전, API·명령)을 고정했다.
- 승인 계획 SPW-02 — 영향 파일에 downstream README/example/test를 추가했다. API 변경 후 예제 테스트 compile error 두 건을 근거로 소비자 적응 파일을 포함하고, 검증 결과와 정적 분석 실패를 별도로 기록했다.
- 승인 계획 SPW-03 — 한국어 기술 문장을 자연스러움 체크리스트로 다시 읽었다. 4개 대상 파일 용어 audit은 `series=clinic-appointment`, `findings=0`이며, 해당 경로 전용 규칙은 AWS 문서에 적용되지 않는다.
- 승인 계획 SPW-04 — 승인 요구사항과 구현·테스트 결과를 대조했고 최신 55개 집중 테스트와 examples 테스트 결과를 확인했다. detekt 버전 불일치는 PASS로 치환하지 않았다.
- 승인 계획 SPW-05 — 계획의 Task 4 파일·명령·상태를 최종 read-back했다. 이 커밋으로 Task 4를 기록하며 A-07, A-08은 미완료로 남긴다.
- 실행 체크리스트 SPW-01 — 독자(저장소 구현자), 목적(Task 4 진행 상태·검증·미결 게이트 기록), 근거(작업 계획, 소스, JUnit 및 Gradle 출력)를 고정했다.
- 실행 체크리스트 SPW-02 — 테스트·구현·검증·문서화·커밋 단계를 의존 순서대로 두고, 정적 분석 실패와 독립 리뷰 보류를 분리했다.
- 실행 체크리스트 SPW-03 — 한국어 기술 문장과 같은 표현의 일관성을 확인했고, 4개 대상 파일 용어 audit 결과 findings=0을 기록했다. clinic 전용 규칙은 AWS prose에 적용하지 않았다.
- 실행 체크리스트 SPW-04 — 상태 및 55개 테스트 수치를 최신 Gradle 출력과 대조했다. 코드 리뷰나 전체 A-07 완료를 주장하지 않는다.
- 실행 체크리스트 SPW-05 — 수정된 plan/checklist Markdown을 다시 읽고 상대 링크·공백·개행을 검사했다. 한국어 용어 감사 findings=0과 최종 `git diff --check`를 확인했다.

### Task 5 실행 증거

- Smithy Kotlin `1.7.9`의 public OkHttp engine config에는 connection retry setter가 없다. 명시적인 `retryOnConnectionFailure(false)` 적용을 위해 사용자가 승인한 대로 `aws-ktor/build.gradle.kts`에 OkHttp `5.5.0`을 `compileOnly`로 추가했다. 해당 artifact는 이미 runtime에 전이되어 runtime dependency footprint는 늘지 않는다.
- IMDS 구현은 endpoint allowlist/profile precedence, token cache와 TTL, 3xx/401 terminal 처리, cancellation과 cleanup, retry matrix, plugin-owned engine lifecycle을 포함한다. `HttpCall.complete()`의 `@InternalApi` 사용은 내부 adapter 한 경계로 제한했다.
- `:bluetape4k-aws-ktor:compileKotlin` 성공, IMDS 집중 테스트 41개 통과, 전체 Ktor suite 343개 통과(실패 0, 오류 0, skip 0), `git diff --check` 통과. 독립 Task 5 리뷰는 `APPROVE`, P0/P1/P2=0이다.
- `detekt`는 플러그인 Kotlin `2.4.10`과 실행 Kotlin Gradle plugin `2.4.20` 불일치로 실패했다. 정적 분석은 미검증으로 남기며 A-07 전체 검증 게이트를 통과한 것으로 간주하지 않는다.
- workflow receipt는 helper를 통해 probe 복구했고 `verify`가 run `20261020T030129Z-f0647513`, sequence 14, checksum `9870289c34039e8ff38333d818a2cd5e9e7e16831e744b3f8b4a6955672ca2d8`을 확인했다. `resume-check`에는 topology gap과 incomplete replacement가 없으며 실행 중인 main lane은 다음 작업을 수행한다.
- Task 5 코드·테스트·계획·체크리스트를 한국어 Lore commit으로 기록한다. A-07 전체 영향 모듈/정적 분석과 A-08 최종 6관점 통합 리뷰는 계속 진행한다.
