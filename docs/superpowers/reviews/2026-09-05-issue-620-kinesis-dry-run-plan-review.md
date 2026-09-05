# #620 Kinesis DryRun 구현 계획 Step 3-R review

**검토 대상:** 승인된 설계
`docs/superpowers/specs/2026-09-05-issue-620-kinesis-dry-run-design.md`, 구현 계획
`docs/superpowers/plans/2026-09-05-issue-620-kinesis-dry-run.md`, 위험 ledger
`docs/superpowers/risk/2026-09-05-issue-620-kinesis-dry-run-risk.md`

**검토 기준점:** `feat/issue-620-kinesis-dry-run` worktree의 exact base
`f07015b6e9a3e6aceb4f301081b502cb88eb40c3`. production, catalog, workflow는 아직 변경하지
않았고 spec/plan/review/risk만 working tree에 있다.

**검토 범위:** Performance, Stability/lifecycle, Security, Operator/Ops, Developer/API
compatibility, User/caller 여섯 독립 관점과 Step 3-R required/conditional checks.

## 최종 verdict

**PASS — P0=0, P1=0**

초기 독립 검토는 `P0=0, P1=14, P2=2`였다. 모든 P1과 두 P2를 계획·설계에 반영했고,
첫 focused 재검토에서 Performance 관점이 Full Nightly 10분 budget이라는 새 P1 1건을
발견했다. 이를 75분 bounded budget과 동일한 capability validator/artifact 계약으로 고친 뒤
여섯 관점 모두 focused 재검토 `P0=0, P1=0`으로 수렴했다. 이 PASS는 구현 준비성에 대한
판정이며 아직 production test, Gradle, Docker, CI 성공을 의미하지 않는다.

## 여섯 관점 matrix

| 관점 | 초기 | 최종 | 계획 근거와 확인 |
| --- | --- | --- | --- |
| Developer/API compatibility | P1=2, P2=1 | PASS | exact base에서 old JAR/12 descriptor를 먼저 고정하고 Task 5는 committed fixture만 소비한다. Java legacy runner와 Kotlin external consumer fixture가 direct/`$default`, trailing/named builder와 migrated call을 검증한다. |
| Operator/Ops | P1=3, P2=1 | PASS | 네 operation row의 backend-specific report, fail-closed validator, valid non-dry-run iterator를 먼저 만드는 GetRecords probe, compatibility를 포함한 `ci-status`, README/CHANGELOG 증거가 구체화됐다. |
| Stability/lifecycle | P1=2 | PASS | `ResourceNotFoundException`만 absence로 인정하고 create-before-response ambiguity만 owned cleanup한다. closed-set 밖 오류와 정상 response는 skip하지 않고 non-zero를 유지한다. |
| Performance/test budget | P1=2 | PASS | scenario별 180초, operation/관측별 30초, polling/backoff 500ms 이하, PR CI 30분, Full Nightly 75분과 fake clock/delay 검증을 명시했다. focused 재검토의 Nightly P1도 닫혔다. |
| Security | P1=3 | PASS | endpoint allow-list/static fake credential guard, bounded allow-list sanitizer, sentinel output test, raw report 삭제, validator 성공 파일만 conditional upload하는 계약을 고정했다. |
| User/caller | P1=2 | PASS | 실제 호출 extension과 request helper KDoc 책임을 분리하고 README/KDoc/CHANGELOG의 builder-last, default false, null omission, 성공 예외, payload 전송 계약을 자동 검증한다. |

## Finding ledger

| 우선순위 | 관점 | 발견 | disposition |
| --- | --- | --- | --- |
| P1 | API | ABI baseline을 production/catalog 변경 뒤 만들 수 있어 새 descriptor를 과거 기준으로 오인할 수 있음 | Task 0에서 exact base SHA와 old catalog ref를 assert하고 source 변경 전에 세 `javap` fixture를 commit한다. |
| P1 | API | Kotlin source compatibility가 추상적인 compile 언급뿐이고 호출 형태별 fixture가 없음 | 기존 external consumer fixture에 trailing lambda, named builder, positional migration, 여섯 새 API 호출을 추가하고 `compatibilityCheck`에 연결한다. |
| P2 | API | README 예제 의미가 token scan에만 의존함 | documentation contract와 external consumer compile을 함께 요구하도록 보강했다. |
| P1 | Ops | capability evidence의 path/schema/upload/failure 처리가 불명확함 | backend별 raw/validated path, 네 row schema, fail-closed validator, conditional upload와 PR read-back을 고정했다. |
| P1 | Ops | GetRecords dry-run 전에 유효한 iterator 생성 절차가 없음 | 별도 정상 `getShardIterator`에서 non-blank iterator를 만든 뒤 GetRecords만 dry-run probe한다. |
| P1 | Ops | `ci-status`가 compatibility와 unexpected skip을 통제하지 않음 | compatibility path filter와 success를 집계하고 expected skip 외 skip은 실패하도록 계획했다. |
| P2 | Ops | `[미출시]` CHANGELOG 갱신이 없음 | Task 6 `[미출시] > 추가`에 `#620`, named API, `DryRunOperationException` 계약을 추가한다. |
| P1 | Stability | closed-set 밖 실패가 delivery `PENDING`으로만 표현돼 original non-zero가 흐려짐 | 인증/network/Docker/timeout/assertion/정상 response는 test command를 non-zero로 유지하고 delivery 상태가 이를 pass로 바꾸지 못하게 했다. |
| P1 | Stability | preflight 오류별 ownership 경계가 불충분함 | `ResourceNotFoundException`만 absence이며 다른 오류·기존 stream은 미소유로 남기고 삭제하지 않는다. |
| P1 | Performance | 네 scenario의 최악 12분이 PR CI 10분 timeout을 초과함 | PR `test-aws-kotlin` timeout을 setup/teardown 포함 30분으로 변경한다. |
| P1 | Performance | spec의 30초 operation deadline과 500ms polling cap이 concrete test에 없음 | `withinOperationDeadline`, clamp, fake clock/delay 음성 테스트를 Task 4에 추가했다. |
| P1 | Performance focused | Full Nightly가 같은 전체 module test를 5회 재시도하면서 10분 timeout을 유지함 | 최악 12분 x 5, backoff와 setup/teardown을 포함해 Nightly를 75분으로 제한하고 동일 validator/artifact gate를 적용한다. |
| P1 | Security | validator 실패 report도 `if: always()` upload될 수 있어 sentinel/secret 노출 가능 | validator가 allow-list normalized file을 성공 시에만 만들고 raw는 실패 시 삭제한다. workflow는 validated file만 conditional upload한다. |
| P1 | Security | sanitizer가 arbitrary exception message와 길이를 허용함 | backend/version/operation/reason-code/stream-token allow-list와 길이 제한, exception/cleanup sentinel output 검증을 계획했다. |
| P1 | Security | emulator client가 실제 AWS endpoint나 ambient credential을 사용할 수 있음 | client factory와 create/describe/delete/read helper가 network 전에 endpoint/credential을 재검사하며 fake call count 0 음성 테스트를 둔다. |
| P1 | Caller | request helper가 서비스 예외를 던지는 것처럼 KDoc 책임이 섞임 | extension에는 서비스 성공/오류/cancellation을, helper에는 mapping/builder-last/null omission만 문서화한다. |
| P1 | Caller | README 일부 token 외 여섯 KDoc 및 CHANGELOG 배치 검증이 없음 | documentation contract가 두 README, 함수별 KDoc 책임, `[미출시] > 추가` 배치를 검사한다. |

## Step 3-R required checks

| # | Result | Evidence |
| --- | --- | --- |
| 1 | PASS | spec 수용 기준 20개가 Task 1~7과 전체 DoD에 매핑됐다. |
| 2 | PASS | immutable catalog pin → model/fake → wire → emulator → ABI/source → docs → full validation 순서다. |
| 3 | PASS | exact old ABI fixture는 production/catalog 변경 전 Task 0에서 생성하며 이후 task가 미래 artifact에 의존하지 않는다. |
| 4 | PASS | success/failure/edge/cancellation/lifecycle/backend capability와 false/true/null/builder override 경로가 명시됐다. |
| 5 | PASS | targeted class, module, validator, detekt, compatibility, full build와 workflow read-back 명령이 구체적이다. |
| 6 | PASS | `aws-kotlin/README.md`와 `README.ko.md` parity와 contract test가 Task 6에 있다. |
| 7 | PASS | Korean KDoc/CHANGELOG/PR/lesson과 terminology audit가 Task 6/7에 있다. |
| 8 | N/A | 새 module이 없다. 기존 aws-kotlin의 CI/Nightly scope와 capability artifact는 별도 검증한다. |
| 9 | N/A | Spring Boot auto-configuration 변경이 아니다. |
| 10 | N/A | Exposed 변경이 아니다. |
| 11 | PASS | suspend extension은 SDK exception/cancellation identity와 cleanup `NonCancellable` 경계를 테스트한다. |
| 12 | PASS | no-copy/single-call, bounded timeout/polling, ownership cleanup, sequential Testcontainers, CI/Nightly budget을 포함한다. |
| 13 | PASS | 새 production abstraction 없이 기존 request helper/extension 패턴을 재사용하고 공통 로직은 test-only support로 제한한다. |
| 14 | PASS | old descriptor, source migration, catalog blast radius, rollback/stop이 plan과 R-01~R-09에 명시됐다. |

## Conditional checks

| Check | Result | Evidence |
| --- | --- | --- |
| Domain-constrained fields | N/A | 새 domain value object가 없고 SDK Boolean field를 그대로 노출한다. |
| Client/resource work | PASS | disposable name 생성, absence/ownership, ambiguous create, bounded cleanup, client close 위치를 Task 4에 고정했다. |
| Streaming APIs | N/A | 새 streaming API가 없다. |
| Suspend APIs | PASS | 네 suspend extension의 cancellation identity와 cleanup 중 cancellation을 음성 테스트한다. |
| JDK preview APIs | N/A | JDK `HttpServer`는 stable test surface이며 preview API를 사용하지 않는다. |

## Writer gate와 evidence

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | Issue #620, 승인 A안, exact base, 구현자/reviewer 독자와 stop condition을 고정했다. |
| SPW-02 | PASS | 여섯 관점 초기/final verdict, finding severity와 disposition, required/conditional checks를 기록했다. |
| SPW-03 | PASS | 한국어 technical register를 적용하고 API·identifier·command·SHA는 원문 token으로 유지했다. |
| SPW-04 | PASS | spec → plan → risk → review 및 initial → focused finding을 대조했다. |
| SPW-05 | PASS | heading/table/link read-back, terminology audit와 `git diff --check`를 실행했다. |

검토 시점에는 production/Kotlin/Gradle/workflow 구현을 시작하지 않았다. terminology audit는
spec/plan/risk에서 findings 0, `git diff --check`는 무출력이었다. 이 문서를 포함한 최종
placeholder/terminology/diff 검증은 Task 0 commit 전에 다시 실행한다.

## Gate disposition

- **A-04 구현 계획 review:** PASS. P0=0, P1=0이며 여섯 관점 focused 재검토가 끝났다.
- **A-05 위험 예측:** PASS. R-01~R-09에 신호·완화·검증·rollback/stop이 있다.
- **A-06 test-first 구현:** PENDING. Task 0 exact old ABI baseline을 먼저 고정한 뒤 Task 1 RED부터 시작한다.
- **PR:** PENDING. 구현과 exact-head 검증 뒤 승인된 repo/base/head로 생성한다.
- **Merge:** PENDING. PR exact-head evidence를 다시 읽고 fresh explicit approval을 받기 전에는 실행하지 않는다.

**Step DoD:** `PASS` — 구현 계획과 위험 예측이 P0=0, P1=0으로 수렴했다.
