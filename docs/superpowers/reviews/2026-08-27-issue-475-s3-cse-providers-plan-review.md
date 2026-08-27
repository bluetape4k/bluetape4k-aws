# #475 S3 CSE provider 구현 계획 Step 3-R review

**검토 대상:** 승인된 설계 `docs/superpowers/specs/2026-08-27-issue-475-s3-cse-providers-design.md`,
구현 계획 `docs/superpowers/plans/2026-08-27-issue-475-s3-cse-providers.md`, 위험 ledger
`docs/superpowers/risk/2026-08-27-issue-475-s3-cse-providers-risk.md`

**검토 범위:** Type A 여섯 관점(API·Kotlin, 보안·암호, 안정성·수명, 성능·테스트,
Spring·호환성, 운영·문서)과 TDD, Floci, workflow, PR evidence 경계.

**검토 기준점:** feature worktree의 exact HEAD `69d1cc6c` (`feat/issue-475-s3-cse-providers`).
이 HEAD에는 production/test 구현이 없고 설계·계획·위험 문서만 있다. API 독립 검토는
ABI/test 명령을 교정한 `6e4c9cd1`에서, 보안·안정성 독립 검토는 최종 `69d1cc6c`에서
read-only로 수행했다. `69d1cc6c`의 위험 ledger와 계획 파일 지도 연결은 문서 변경이며,
보안·안정성 검토에서 이전 의미 변경이 없음을 확인했다.

## 최종 verdict

**PASS — P0=0, P1=0 (미해결 P2/P3 없음)**

초기 독립 검토에서 발견된 P1은 구현 전에 계획에 반영했다. 실제 `compatibilityCheck`
명령과 report 경로, provider targeted test, 기존 KMS bean 이름, provider identity helper,
zeroization·cancellation·cleanup 경계를 현재 계획에 고정했다. 위험 ledger R-01~R-08은
구현 전 stop 조건과 task별 검증 지점을 갖추며, 구현 승인 전에는 해결 증거로 간주하지
않는다.

## 여섯 관점 matrix

| 관점 | verdict | 계획 근거와 확인 |
| --- | --- | --- |
| API·Kotlin | PASS | `ClientSideEncryption` 새 field 순서와 `serialVersionUID` 처리, `S3AesProvider`/`S3RsaProvider` 공개 계약, `effectiveKeyIdentity`/`effectiveKeyVersion` helper, `compatibilityCheck`, Task 2 targeted test가 plan에 함께 정의됐다. JVM binary compatibility는 자동 보장하지 않고 실제 report와 lesson으로 확인한다. |
| 보안·암호 | PASS | metadata version/provider/algorithm/encoding을 조기 검증하고 canonical length-prefixed AAD와 GCM 인증 성공 뒤에만 평문을 반환한다. raw key/context는 metadata·로그·임시 파일에 남기지 않으며 AES 복사본, data key, nonce, AAD, bounded accumulator를 각 terminal 경로에서 zeroize한다. R-01/R-02와 negative acceptance가 연결됐다. |
| 안정성·수명 | PASS | provider는 closed-first lifecycle과 lock으로 보호하고 stream terminal owner는 `Mutex`로 단일화한다. dispatcher-entry 취소, `NonCancellable + Dispatchers.IO` cleanup, close-before-HEAD, deletion failure 시 원래 예외 보존을 계획과 테스트에 명시했다. R-02/R-03/R-04가 해당 경계를 추적한다. |
| 성능·테스트 | PASS | bounded ciphertext 상한 `67,108,864`, chunk 기반 publisher 수집, blocking filesystem의 injected IO dispatcher, RSA wrapping 비용 관찰을 고정했다. 처리량 목표와 대표 payload 분포가 없어 benchmark는 N/A로 기록하며 unsupported performance claim을 하지 않는다. Floci와 module test는 `--max-workers=1`로 순차 실행한다. |
| Spring·호환성 | PASS | provider별 명시 조건과 `ObjectProvider.getIfUnique`로 0/복수 candidate를 임의 선택하지 않는다. 기존 KMS `s3ClientSideEncryptionOperations` method/bean 이름과 `@ConditionalOnBean(KmsOperations::class)` backoff을 유지하고, transfer adapter는 provider template이 생성된 뒤 별도 configuration에서 조건부 등록한다. 새 runtime dependency는 없다. |
| 운영·문서 | PASS | EN/KO README·manual, provider bean 등록 예시, key storage/rotation/disposal caller 책임, no-wire-compatibility/HSM 제한을 Task 7에 연결했다. 위험 ledger와 workflow receipt, PR/push/merge/tag/release hold를 Task 8에 기록하며, 문서·manifest contract 실패는 완료를 차단한다. |

## Finding ledger

| 초기 심각도 | 발견 | 계획의 수선과 증거 |
| --- | --- | --- |
| P1 | ABI 단계에서 이 checkout에 없는 `checkBinaryCompatibility`를 사용함 | `6e4c9cd1`에서 실제 `./gradlew compatibilityCheck --no-daemon --no-configuration-cache`와 `build/reports/compatibility/compatibility-check.json`을 명시했다. |
| P1 | Task 2 GREEN이 compile만 실행하고 provider regression test를 빠뜨림 | `6e4c9cd1`에서 `S3ClientSideEncryptionProviderTest` targeted test를 compile 이후 실행하도록 추가했다. |
| P1 | 기존 KMS auto-configuration method를 rename하면 bean name이 변함 | `ff7e0b18`에서 `s3ClientSideEncryptionOperations` 이름을 유지하고 KMS 조건을 보존했다. |
| P1 | identity/cleanup helper와 RSA fingerprint가 계획에서 암시적으로만 존재함 | `399abbae` 이후 `effectiveKeyIdentity`, `effectiveKeyVersion`, SHA-256 fingerprint bytes 즉시 zeroization을 구체 코드로 고정했다. |
| P1 | transfer cancellation·temporary path·cleanup exception 경계가 모호함 | `f773ce0a`, `188a990c`, `0994525a`에서 `NonCancellable + IO`, dispatcher-entry 취소, `ifMatch`, bounded precondition, 원래 예외 보존을 각각 명시했다. |
| P1 | Type A 위험 예측이 별도 artifact로 남지 않음 | `69d1cc6c`에서 R-01~R-08 ledger와 rollback/stop 규칙을 `docs/superpowers/risk/...`에 추가하고 Task 8 gate에 연결했다. |

## Writer gate (SPW-01~05)

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | 승인 spec, plan, risk ledger, exact HEAD, 대상 독자(구현자·reviewer), 외부 API 경계와 unsupported claim을 고정했다. |
| SPW-02 | PASS | review 범위·근거, 여섯 관점 verdict, finding severity/disposition, gaps, gate disposition을 이 artifact에 기록했다. |
| SPW-03 | PASS | 한국어 technical register를 적용하고 API·identifier·command·URL·숫자는 원문 token을 보존했다. `references/korean-naturalness-checklist.md`의 KO-01~KO-07을 read-back에 적용했다. |
| SPW-04 | PASS | spec→plan→risk→review를 대조해 provider, metadata, lifecycle, transfer, Spring, emulator, docs acceptance를 trace했고 초기 P1 disposition을 명시했다. |
| SPW-05 | PASS | 이 문서를 rendered Markdown으로 다시 읽어 heading/table/code token과 concise flow를 확인했고, placeholder scan과 `git diff --check`가 exit 0이었다. |

## Evidence

검토 시점에는 production source와 test source를 작성하지 않았다. 다음 read-back 검사로
계획·위험·review의 구조와 핵심 token을 확인한다.

```bash
git diff --check
marker="T""B""D|TO""DO|FIX""ME"
for file in \
  docs/superpowers/plans/2026-08-27-issue-475-s3-cse-providers.md \
  docs/superpowers/risk/2026-08-27-issue-475-s3-cse-providers-risk.md \
  docs/superpowers/reviews/2026-08-27-issue-475-s3-cse-providers-plan-review.md; do
  if rg -n "$marker" "$file"; then exit 1; fi
done
rg -n -- "compatibilityCheck|S3ClientSideEncryptionProviderTest|s3ClientSideEncryptionOperations|ifMatch|NonCancellable|R-01|R-08" \
  docs/superpowers/plans/2026-08-27-issue-475-s3-cse-providers.md \
  docs/superpowers/risk/2026-08-27-issue-475-s3-cse-providers-risk.md
```

실행 결과는 `git diff --check` 무출력, 세 문서의 placeholder marker 부재, 위 핵심 token의
plan/risk 존재였다. implementation Gradle, Floci, detekt, compatibility report는
이 review의 PASS 근거가 아니며, 사용자 계획 승인 뒤 각 task와 Task 8에서 fresh evidence로
수집한다. 기준선에서 실행한 기존 KMS/Floci test pass는 baseline evidence일 뿐 새 provider
구현의 통과를 의미하지 않는다.

## Gate disposition

- **A-04 구현 계획 승인·review:** PASS. plan/risk/review가 commit됐고 P0/P1 blocker가 없다. 다음 상태는 사용자 계획 승인 대기다.
- **A-05 위험 예측:** PASS. R-01~R-08에 신호·완화·검증·rollback/stop이 있으며, 실제 구현 증거는 아직 없다.
- **A-06 test-first 구현:** PENDING. 사용자 계획 승인 후 `$executing-plans`와 TDD 순서로 각 task의 RED부터 시작한다.
- **PR/merge:** PENDING/미수행. exact-head CI·review·merge는 구현 후 별도 evidence와 fresh 사용자 승인이 필요하다.

사용자에게 필요한 현재 조치는 구현 계획 승인 한 가지다. 계획 승인이 있기 전에는
production/test code, PR, push, merge, tag, release를 실행하지 않는다.
