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
- 현재 작업: Task 1 완료. Task 2 steps 1–8 완료: 최종 DynamoDB builder endpoint 검사와 literal loopback HTTP opt-in, caller-owned closeable provider/engine 검증, 공통/Spring 회귀 실행을 마쳤다. Ktor 테스트 302개가 통과했고 Spring 테스트 task는 Gradle cache에서 복원됐다. endpoint/core/DynamoDB 타깃 테스트와 전체 공통/Spring task가 모두 `BUILD SUCCESSFUL`이다. 공통 레이어를 한국어 Lore commit으로 기록했다. 공용-layer 독립 리뷰 P0/P1=0이며 P2 등록 순서 테스트 지적을 보완했다. AWS Kotlin/Smithy 고정 source 및 공식 문서 lifecycle 조사를 완료했다.
- 다음 게이트: 승인된 계획 순서대로 회귀 테스트 우선 구현을 계속한다. 모든 로컬 검증과 independent code review에서 P0/P1=0을 확인한 다음 lesson을 작성한다. PR 생성·merge·동기화·정리는 별도 승인 절차를 따른다.
