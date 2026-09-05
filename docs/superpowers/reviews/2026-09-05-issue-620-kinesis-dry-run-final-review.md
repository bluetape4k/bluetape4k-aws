# Issue #620 Kinesis DryRun 최종 6관점 리뷰

**리뷰 일자**: 2026-09-06
**구현 기준 HEAD**: `aa91b5bb9682897f303c61f1f230df5d47526dd3`
**비교 기준**: `origin/develop`
**범위**: Kinesis DryRun 공개 API, request helper, fake/wire/emulator 검증, ABI fixture,
catalog·CI, 영어·한국어 README와 CHANGELOG
**판정**: PASS — P0=0, P1=0, P2=0. PR exact-head CI와 merge approval은 별도 gate다.

## SPW-01 — 독자·목적·근거 고정

- **독자/언어**: bluetape4k 사용자와 유지보수자, 한국어 기술 리뷰. 코드·API·명령·URL은
  원문을 유지한다.
- **목적**: Issue #620의 승인된 설계와 완료 조건이 공개 API, wire, emulator capability,
  binary compatibility, 문서와 CI에 일관되게 반영됐는지 판정한다.
- **근거**: 설계·계획·risk 문서, `origin/develop...aa91b5bb`, fake/wire/emulator JUnit,
  capability artifact, ABI fixture report, detekt/compatibility/full build 결과와 6개 독립 리뷰.
- **외부 경계**: 실제 AWS credential과 운영 계정은 사용하지 않았다. AWS 지원 의미는 공식
  Kinesis DryRun 문서에 근거하고, emulator 결과는 Floci 1.6.0과 현재 LocalStack 관찰로 제한한다.

## SPW-02 — 6관점 결과

| 관점 | P0 | P1 | P2 | 판정과 근거 |
|---|---:|---:|---:|---|
| API/ABI | 0 | 0 | 0 | 네 extension과 두 helper가 `dryRun: Boolean = false`, builder-last 계약을 제공한다. `DeprecationLevel.HIDDEN` overload, 고정된 12개 direct/`$default` descriptor와 실제 legacy consumer가 기존 binary linkage를 보존한다. |
| 안정성 | 0 | 0 | 0 | wrapper는 operation당 한 번만 SDK를 호출하며 SDK 예외와 cancellation identity를 유지한다. emulator 자원은 소유권 충돌을 거절하고 `NonCancellable`·30초 경계에서 정리하며 primary failure를 보존한다. |
| 운영/Ops | 0 | 0 | 0 | settings와 CI catalog가 같은 SHA를 사용한다. capability validator와 sanitized artifact upload는 fail closed이고, compatibility aggregate에 ABI/runtime/consumer fixture가 연결됐다. |
| 성능 | 0 | 0 | 0 | production 경로에 재시도·반복·blocking을 추가하지 않았고 `ByteArray`와 record list를 복사하지 않는다. wire/emulator client와 executor는 닫히며 polling과 cleanup은 bounded다. |
| 보안 | 0 | 0 | 0 | loopback endpoint와 fake credential guard, 폐쇄형 artifact schema, 민감 marker 선차단, JUnit 원자적 sanitize와 upload 순서가 credential·payload·원문 오류 노출을 막는다. |
| 사용자/호출자 | 0 | 0 | 0 | 영어·한국어 문서가 성공 예외, payload 전송, credential provider 서명, AWS endpoint 전용 실행, positional builder migration, backend 지원 차이와 fail-closed 예제를 같은 구조로 설명한다. |

### 공통 판정 근거

- 공개 API: `KinesisClientExtensions.kt:79-234`, `PutRecord.kt:27-51`,
  `GetShardIterator.kt:28-52`
- 호출/예외: `KinesisClientExtensionsMockTest.kt:42-152`
- wire: `KinesisDryRunWireTest.kt:120-172`
- 자원 수명: `KinesisDryRunTestSupport.kt:170-347`,
  `KinesisDryRunEmulatorTest.kt:196-235`
- artifact 보안: `validate_kinesis_dry_run_capability.py:63-194,269-284`,
  `sanitize_kinesis_dry_run_junit.py:15-39`
- ABI/CI: `build.gradle.kts:925-1064,1241-1280`, `.github/workflows/ci.yml:419-466`
- 사용자 문서: `aws-kotlin/README.md:263-345`, `aws-kotlin/README.ko.md:257-339`,
  `CHANGELOG.md:10-15`

## SPW-03 — 문체·용어·locale parity

- EN/KO README는 API 호출 4개, helper 2개, `true`·`false`·`null` builder override,
  `existingShardIterator`, capability 표와 AWS 문서 링크를 같은 구조로 제공한다.
- KDoc는 extension의 서비스 호출 계약과 helper의 request-only 계약을 분리한다.
- `kinesis_readme_contract_test.py`가 code fence, capability table, API occurrence와 핵심 경고를
  검사했고 terminology audit는 새 lesson을 포함해 findings 0이었다.

## SPW-04 — 검증과 추적성

| 주장 | 검증 결과 |
|---|---|
| targeted API/wire/emulator 계약 | 62 tests, 58 pass, capability skip 4, failure/error 0 |
| `aws-kotlin` 전체 회귀 | 820 tests, 803 pass, 명시적 skip 17, failure/error 0 |
| 전체 repository build | 3,363 tests, 3,325 pass, skip 38, failure/error 0 |
| 정적 분석 | `detekt` exit 0, merged report findings 0 |
| source/binary compatibility | `compatibilityCheck` exit 0, fixture integrity·12 descriptor·legacy runtime·Kotlin consumer 포함 |
| catalog | settings/CI SHA `9698c9d66bea6fcba373143ee8fa5bfbd9812d4b`, Kinesis `1.8.46` resolve |
| emulator capability | Floci 1.6.0의 네 operation 모두 명시적 unsupported; write는 `dry_run_ignored_write`, read는 `dry_run_ignored_response` |

## SPW-05 — 잔여 위험과 gate

- Floci 1.6.0과 현재 LocalStack은 네 DryRun operation을 지원하지 않는다. 이 결과를 AWS 지원
  증거로 해석하지 않으며, write probe는 run-scoped stream과 ownership-safe cleanup 안에서만
  실행한다.
- 실제 AWS 계정·IAM·quota에 대한 검증은 수행하지 않았다. production credential로 capability
  probe를 자동 실행하는 기능도 제공하지 않는다.
- PR 생성 뒤 hosted CI의 모든 required/expected check가 같은 exact head에서 terminal success인지
  다시 확인해야 한다.
- merge, auto-merge, branch/worktree 삭제는 fresh explicit approval 전까지 실행하지 않는다.

**최종 SPW 상태**: SPW-01 PASS, SPW-02 PASS, SPW-03 PASS, SPW-04 PASS,
SPW-05 PASS. Local implementation/review gate는 통과했으며 delivery gate는 PR exact-head CI까지
계속된다.
