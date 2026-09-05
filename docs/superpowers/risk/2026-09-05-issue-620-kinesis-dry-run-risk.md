# #620 Kinesis DryRun 지원 위험 예측

**대상:** `docs/superpowers/specs/2026-09-05-issue-620-kinesis-dry-run-design.md`
및 `docs/superpowers/plans/2026-09-05-issue-620-kinesis-dry-run.md`

**범위:** AWS Kotlin SDK `1.8.46` catalog pin, Kinesis 네 operation과 두 request helper의
additive `dryRun` API, wire serialization, Floci-first capability/no-write, ABI·source
compatibility, CI artifact와 사용자 문서.

## 위험 ledger

| ID | 위험·신호 | 예방/완화 | 검증·재실행 지점 | rollback/stop |
| --- | --- | --- | --- | --- |
| R-01 | `dryRun`이 잘못된 request field에 매핑되거나 builder override 순서가 바뀌어 `true`가 전송되지 않음 | 네 extension은 named `dryRun: Boolean = false`를 builder 직전에 두고 request helper는 nullable mapping을 유지한다. builder는 항상 마지막에 적용한다. | Task 2 fake/model test에서 false/true/null, builder override, 정확한 call count를 검증한다. Task 3에서 public client wire body의 `DryRun`을 확인한다. | model 또는 wire RED가 남으면 API checkpoint commit과 emulator task로 진행하지 않는다. |
| R-02 | 기존 12개 JVM descriptor 또는 Kotlin source call shape가 깨져 downstream binary/source compatibility가 회귀함 | production/catalog 편집 전에 exact base JAR의 12개 `javap` baseline을 고정하고 additive verifier를 별도로 둔다. Java legacy runner와 Kotlin external consumer fixture를 격리된 classpath로 실행한다. | Task 0 baseline capture, Task 5 `compatibilityCheck`, legacy runtime runner, external fixture compile. | pre-change baseline을 새 JAR에서 재생성하지 않는다. descriptor 또는 consumer fixture 실패 시 다음 checkpoint와 PR을 중단한다. |
| R-03 | dry-run write가 실제 record를 남기거나 read dry-run이 정상 response를 반환하는 emulator 계약 위반 | operation별 disposable stream, baseline/marker 비교, 정상 response fail-closed, Floci-first와 closed-set LocalStack fallback을 사용한다. | Task 4 네 isolated scenario, capability row, no-write bounded read. | 정상 response·marker 관측은 unsupported skip이 아니라 실패다. 원인 해결 전 DONE/PR readiness를 선언하지 않는다. |
| R-04 | stream 생성 실패·충돌·취소 중 외부 또는 기존 자원을 삭제하거나 test resource를 누수함 | UUID owner name, `ResourceNotFoundException`만 absence로 인정, create 전 cleanup 등록, `NonCancellable` 30초 cleanup, primary exception 보존을 적용한다. | Task 4 fake collision, ambiguous create, pre-existing, cancellation, cleanup failure와 timeout test. | 소유권이 확인되지 않은 이름은 삭제하지 않는다. 다른 preflight 오류는 즉시 실패시키며 cleanup을 시도하지 않는다. |
| R-05 | capability 분류가 인증·network·timeout·정상 응답을 unsupported로 숨겨 CI가 거짓 GREEN이 됨 | HTTP/error code closed set만 reason code로 분류하고 `failed` row는 non-zero를 유지한다. top-level `ci-status`는 compatibility success와 expected job result를 요구한다. | Task 4 classifier negative test, validator, PR CI/Full Nightly job read-back. | 누락·중복·unknown field/status, unexpected skip, closed-set 밖 오류가 있으면 job과 DoD를 실패/PENDING으로 유지한다. |
| R-06 | 실제 AWS endpoint, ambient credential, payload/header 또는 예외 message가 test log/artifact에 노출됨 | test-only client factory와 모든 resource/read helper가 endpoint allow-list와 static fake credential marker를 network 전 검사한다. bounded allow-list sanitizer와 client logging disable을 적용한다. validator 성공 파일만 업로드한다. | Task 3/4 negative endpoint/credential test, sentinel output capture, JSON validator, 두 workflow contract test. | sanitizer/validator 실패 시 raw report를 삭제하고 artifact를 업로드하지 않는다. 실제 AWS 또는 default chain 경로는 즉시 중단한다. |
| R-07 | 네 emulator scenario와 기존 retry가 PR CI·Full Nightly timeout을 초과하거나 polling이 무한 대기함 | sequential 실행, scenario 180초, operation/관측 30초, poll/backoff 500ms 이하, PR CI 30분, 5회 retry Full Nightly 75분 경계를 고정한다. | Task 4 fake clock/delay test와 workflow contract, Task 7 exact workflow read-back. | budget 초과 또는 unbounded path 발견 시 timeout만 반복 상향하지 않고 helper/시나리오를 수정한 뒤 재검토한다. |
| R-08 | 126개 catalog key pin 변경이 다른 module compile을 깨뜨리거나 CI path filter가 compatibility를 건너뜀 | `settings.gradle.kts`와 CI expected ref를 같은 commit으로 고정하고 Task 1 contract에서 불일치와 다른 ref를 RED로 만든다. 최종 full build와 exact-head CI를 요구한다. | Task 1 pin contract, Task 7 `detekt`, `compatibilityCheck`, `build`, PR checks. | global catalog 불일치·full build 실패·path-filter skip이 있으면 module test만으로 완료하지 않는다. |
| R-09 | KDoc·영어/한국어 README·CHANGELOG가 성공 예외, payload 전송, builder-last, nullable omission을 서로 다르게 설명함 | extension과 helper 책임을 분리하고 두 locale 구조를 맞춘다. `[미출시] > 추가`에 `#620` 계약을 기록한다. | Task 6 documentation contract, Korean terminology audit, external consumer compile, side-by-side read-back. | 문서 contract 또는 locale parity 실패 시 final review와 PR을 보류한다. 중앙 manual은 이 issue에서 확장하지 않는다. |

## 실행 규칙

- R-01~R-06은 기능·호환성·보안 P1 위험이며 계획된 음성/경계 테스트가 GREEN이기 전에는
  emulator 결과나 문서만으로 닫지 않는다.
- R-03~R-07의 Docker/emulator 검증은 공유 자원 때문에 순차 실행한다. closed set 이외의
  실패를 JUnit assumption 또는 artifact-only 성공으로 바꾸지 않는다.
- R-08의 catalog pin은 전역 blast radius가 있으므로 targeted module test 뒤에도 exact
  `compatibilityCheck`와 full `build`가 필요하다.
- task별 Lore commit에는 관련 risk, exact 명령, 실행 결과, 미실행 gap을 기록한다.
- PR 생성은 승인된 repo/base/head 범위에서만 수행한다. merge, tag, release, branch 삭제는
  별도 권한 없이는 수행하지 않는다.

## Plan gate

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | Issue #620, 승인된 A안 spec, implementation plan, exact base와 대상 API를 고정했다. |
| SPW-02 | PASS | 각 위험에 신호, 예방/완화, 검증 task와 rollback/stop 조건을 연결했다. |
| SPW-03 | PASS | 한국어 reader-facing prose와 AWS/Kotlin/API/command token 보존을 적용했다. |
| SPW-04 | PASS | model → wire → emulator → ABI/source → CI/docs 경계를 R-01~R-09로 trace했다. |
| SPW-05 | PASS | plan review artifact 확정 뒤 terminology audit와 `git diff --check`를 fresh 실행하고 heading/table을 read-back했다. |

**현재 상태:** 구현 전 위험 예측 완료, 검증 evidence는 PENDING. 이 ledger는 실제 test,
compatibility, full build, exact-head CI를 대신하지 않는다.
