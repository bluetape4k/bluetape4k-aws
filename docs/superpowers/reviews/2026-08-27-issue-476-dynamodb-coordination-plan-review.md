# #476 DynamoDB coordination 구현 계획 Step 3-R review

**검토 대상:** 승인된 설계 `docs/superpowers/specs/2026-08-27-issue-476-dynamodb-coordination-design.md`,
구현 계획 `docs/superpowers/plans/2026-08-27-issue-476-dynamodb-coordination-plan.md`,
위험 ledger `docs/superpowers/risk/2026-08-27-issue-476-dynamodb-coordination-risk.md`

**검토 범위:** Type-A 여섯 관점(정확성/동시성, 성능/테스트, 안정성/lifecycle, 보안,
Kotlin 공개 API, 운영/문서)과 workflow·Kotlin·Floci·PR evidence gate.

## 최종 verdict

**PASS — P0=0, P1=0, P2=0, P3=0**

초기 review에서 발견된 P1은 구현 전에 계획에 반영했다. Task 3 parser test command를
`DynamoDbCoordinationSupportTest`로 정렬했고, non-expiring metadata 조건, operation별
`oldItem == null` 의미, `@TestInstance(PER_CLASS)`/`@AfterAll` cleanup, README·CHANGELOG
변경 표면, malformed `get`, throttling/timeout/no-pre-read 구조 검증, EN/KO anchor·code·
manifest parity, Floci full-test 직렬화, 필수 `AllOld` 부재 시 `PENDING/BLOCKED` stop을
명시했다. 후속 plan pass에서는 Task 2 파일 책임과 Task 4 overload를 분리하고, composite
stale/request 테스트를 operation별로 쪼갰다. 또한 `UpdateTimeToLiveRequest`/`DescribeTable`
응답을 이용한 Floci capability probe, `NonCancellable` + 5초 table absence 확인, lock·metadata
양 adapter source scan, no-expiry condition map capture, 네 개 superpowers 문서의 전체
marker scan을 추가했다.

## 여섯 관점 matrix

| 관점 | verdict | 계획 근거와 확인 |
| --- | --- | --- |
| 정확성·동시성 | PASS | Task 4의 acquire fast path 1회, expired takeover 최대 2회, owner/token/expiry equality, release `UpdateItem` 보존, Task 6의 2/8 coroutine barrier와 monotonic fencing 증거가 설계와 일치한다. operation별 `oldItem == null` 정책도 분리했다. |
| 성능·테스트 | PASS | pre-read/polling/background/retry를 금지하고 `coVerify` 상한, support parser test, malformed success/exception/timeout/throttling fixture와 구조 scan을 계획했다. heap/latency/quota/실제 AWS timing은 N/A로 과장하지 않는다. |
| 안정성·lifecycle | PASS | injected `Clock`, finite integer duration/overflow, `CancellationException` 전달, `KLoggingChannel`, caller-owned client, `NonCancellable` + `withTimeout(5.seconds)`, PER_CLASS `@AfterAll` fallback을 고정했다. |
| 보안 | PASS | 고정 expression template·alias/value map, 입력 보간 금지, namespace/owner의 auth 경계 부정, least-privilege caller 책임, secret/PII·key/token/value 로그 금지와 low-cardinality logging을 계획했다. |
| Kotlin 공개 API | PASS | `DistributedLock`, `MetadataStore`, immutable options, Serializable `LockLease`와 `readObject` 검증, default-duration overload의 구체 class 배치를 승인 spec과 맞췄다. 기존 AWS Kotlin suspend/client/model helper와 dependency 경계를 재사용한다. |
| 운영·문서 | PASS | root/module README EN·KO, CHANGELOG `[미출시] 추가`, module manual EN/KO, Korean lesson, anchor/code/API token parity, manifest check, Floci-only command, PR exact-head checks와 merge 승인 hold를 계획했다. |

## Finding ledger

| 초기 심각도 | 발견 | 계획의 수선 |
| --- | --- | --- |
| P1 | Task 2에서 아직 생성하지 않은 adapter convenience overload를 검증함 | overload test를 Task 4 `DynamoDbDistributedLockUnitTest`로 이동하고 구체 API를 명시했다. |
| P1 | Task 3 test file과 실행 명령이 달랐음 | parser-only `DynamoDbCoordinationSupportTest`와 두 실행 명령을 정렬했다. |
| P1 | Floci order/cleanup과 non-expiring metadata branch가 불명확했음 | `PER_CLASS`, `@TestMethodOrder`, `@AfterAll`, unique table owner, `attribute_not_exists(#expiresAt/#ttl)`를 추가했다. |
| P1 | public API 전달 surface에서 README/CHANGELOG가 빠졌음 | root/module README 네 파일과 Korean `[미출시] 추가` 항목을 파일 지도·검증 loop에 추가했다. |
| P1 | malformed metadata `get`, throttling/timeout/no-pre-read와 성공 응답 검증이 약했음 | named tests, `coVerify` 호출 상한/0회, source scan, malformed `AllNew`/`AllOld` test를 추가했다. |
| P1 | 필수 `AllOld` capability 부재를 단순 N/A로 허용할 위험 | pre-read/fallback 없이 `PENDING/BLOCKED` stop 및 PR 금지를 plan/risk에 명시했다. |
| P2 | scopeId가 metadata attribute까지 포함해 spec과 drift | lock scope에는 table/partition/namespace와 owner/expires/fencing만 포함하도록 고정했다. |
| P2 | sort-key를 schema constructor가 판별한다고 기술 | DescribeTable 없이 caller PK-only precondition과 Floci table assertion으로 변경했다. |
| P2 | manual parity가 anchor만 비교했음 | anchor diff, code-fence count, 필수 API token 양 locale 검사, manifest export check를 추가했다. |
| P2 | Floci TTL/backend capability와 table deletion completion이 서술 수준이었음 | `UpdateTimeToLiveRequest`/`DescribeTable` 응답 캡처, 5초 bounded absence polling, capability별 `PENDING/BLOCKED` 판정을 추가했다. |
| P2 | metadata adapter의 pre-read/delete 구조 scan과 no-expiry condition request가 부분적이었음 | lock·metadata 두 source scan, no-expiry `conditionExpression`/alias/value capture를 Task 5/8 검증에 추가했다. |
| P3 | placeholder scan이 plan 한 파일에 한정됐음 | plan/risk/review/checklist 네 파일을 shell 인접 marker로 검사하도록 확장했다. |

## Evidence

검토 시점에는 production source를 작성하지 않았으며, 다음 계획/위험 read-back 검사를
실행했다.

```bash
git diff --check
marker="T""B""D|TO""DO|FIX""ME"
for file in \
  docs/superpowers/plans/2026-08-27-issue-476-dynamodb-coordination-plan.md \
  docs/superpowers/risk/2026-08-27-issue-476-dynamodb-coordination-risk.md \
  docs/superpowers/reviews/2026-08-27-issue-476-dynamodb-coordination-plan-review.md \
  docs/superpowers/checklists/2026-08-27-issue-476-dynamodb-coordination.md; do
  if rg -n "$marker" "$file"; then exit 1; fi
done
rg -n -- "DynamoDbCoordinationSupportTest|@TestInstance|UpdateTimeToLiveRequest|DescribeTable|attribute_not_exists\(#expiresAt\)|PENDING/BLOCKED|KLoggingChannel|export_manifest|gh pr view|--max-workers=1" \
  docs/superpowers/plans/2026-08-27-issue-476-dynamodb-coordination-plan.md
```

결과: `git diff --check`와 네 문서의 placeholder marker 검사가 모두 exit 0이며, plan에 수정된 파일·
테스트·capability probe·stop condition·PR evidence 토큰이 존재한다. Gradle/Floci/문서 contract 실행은
implementation gate의 fresh evidence로 남겨두며 이 review에서 PASS로 주장하지 않는다.

## Gate disposition

- **A-04 구현 plan 승인·review:** PASS. 위 plan/risk와 본 review를 commit한 뒤 RED 단계로 이동한다.
- **A-05 위험 예측:** PASS. 필수 capability 부재는 blocker로, 실제 AWS는 사용자 제약상 N/A로 고정한다.
- **A-06 test-first 구현:** 다음 gate. production code보다 각 task의 RED test를 먼저 작성한다.
- **PR/merge:** 아직 수행하지 않는다. exact-head CI·review·merge는 구현 후 별도 evidence와 fresh 사용자 승인이 필요하다.
