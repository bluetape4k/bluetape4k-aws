# Issue #637 S3 암호화 전송 cleanup 최종 7관점 리뷰

**리뷰 일자**: 2026-09-06  
**비교 기준**: `origin/develop` (`216c95203ff63e0d8c7f822c77ac4386c6c3b6c1`)  
**범위**: S3 encrypted stream/file cleanup failure precedence, bounded retry, residue ownership,
fake-first 회귀 테스트, 영어·한국어 운영 문서  
**리뷰 출처**: 현재 세션 정책에 따라 독립 subagent를 사용하지 않은 main-session exact-diff fallback  
**판정**: PASS — P0=0, P1=0, P2=0. PR exact-head CI와 merge approval은 별도 gate다.

## 7관점 결과

| 관점 | P0 | P1 | P2 | 판정과 근거 |
|---|---:|---:|---:|---|
| 정확성 | 0 | 0 | 0 | primary/cancellation instance를 그대로 다시 던지고, 최종 cleanup 실패만 sanitized suppressed signal로 붙인다. primary가 없는 cleanup-only 실패는 직접 던진다. |
| API/ABI | 0 | 0 | 0 | 기존 public constructor, interface, transfer method와 wire format은 바꾸지 않았다. 새 failure model과 fake seam은 module-internal이며 `compatibilityCheck`가 통과했다. |
| 동시성 | 0 | 0 | 0 | cleanup은 `NonCancellable`에서 configured dispatcher와 `Dispatchers.IO`를 순서대로 최대 두 번만 사용한다. encrypted completion의 기존 mutex/state precedence를 유지한다. |
| 실패/보안 | 0 | 0 | 0 | signal은 고정 operation, 시도 횟수, failure class 이름만 담고 raw cause/message/path/bucket/key/credential을 보존하지 않는다. 실패한 owned reference는 성공 전까지 유지한다. |
| 성능 | 0 | 0 | 0 | 추가 작업은 failure cleanup 경로의 최대 2회 dispatcher 전환과 작은 failure-type list뿐이다. background worker, unbounded retry, 정상 전송 경로의 추가 hash/I/O는 없다. |
| 테스트/CI | 0 | 0 | 0 | cancellation, dispatcher rejection, authentication/upload/setup failure, delete/discard 이중 실패, fallback 성공, cleanup-only `close()` 재시도를 deterministic fake로 검증했다. |
| 생태계/유지보수 | 0 | 0 | 0 | `S3OutputStream` ownership을 공통 경계로 유지하고 caller-owned client/provider/object는 건드리지 않는다. EN/KO README와 lesson이 같은 운영 계약을 기록한다. |

## 검증 근거

| 주장 | 결과 |
|---|---|
| TDD RED | missing cleanup type/seam compile failure, cleanup-only second `close()`의 `deleteAttempts 1 != 2`를 각각 확인 |
| targeted S3 cleanup | `S3OutputStreamTest` 9개 + `S3ClientSideEncryptionTransferTest` 12개, failure/error 0 |
| affected full tests | Spring Boot 1629 tests: 1623 pass, 6 skip, failure/error 0 |
| 정적 분석 | `:bluetape4k-aws-spring-boot:detekt` exit 0 |
| 호환성 | root `compatibilityCheck --no-configuration-cache`, 64 passing |
| publication | Gradle metadata/POM 생성 성공, `validate_poms.rb` failures=0, files=1, dependencies=104, maven_models=1 |
| 문서/patch | EN/KO 구조 대조, terminology audit 9 files/findings=0, placeholder scan과 `git diff --check` 통과 |

## 잔여 위험과 gate

- 실제 AWS multipart object 삭제를 새로 소유하지 않으며 LocalStack/Floci 실서비스 경로는 실행하지 않았다.
- suppression을 명시적으로 비활성화한 비표준 `Throwable`은 Java 계약상 suppressed signal을 보존할 수 없다.
- Kotlin plugin의 `checkPomFileForBluetapeAwsPublication`은 변경하지 않은 기존 developer
  `organization`/`organizationUrl` 누락으로 실패한다. 이 PR은 repository publication audit와 Maven
  effective-model 검증을 통과했으며 publication 설정 자체는 수정하지 않는다.
- PR 생성 뒤 모든 expected check가 같은 exact head에서 terminal success인지 다시 확인해야 한다.
- merge, auto-merge, branch/worktree 삭제는 fresh explicit approval 전까지 실행하지 않는다.

**최종 상태**: local implementation/review gate PASS. Delivery gate는 PR exact-head CI 확인까지 PENDING.
