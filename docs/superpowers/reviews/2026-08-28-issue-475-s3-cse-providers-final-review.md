# #475 S3 CSE provider 최종 구현 review

## 검토 대상과 결론

- 저장소: `bluetape4k/bluetape4k-aws`
- 브랜치: `feat/issue-475-s3-cse-providers`
- 기준선: `89dc7e4e`
- 초기 최종 검토 기준 HEAD: `a278accdf12e0ea047bc517490fb541a67c59fd4`
- verifier remediation 검토 HEAD: `0bfeb515` (이 문서의 후속 갱신 전 기준)
- 범위: `aws-spring-boot`의 AES/RSA client-side encryption provider, provider
  envelope와 metadata/AAD 검증, typed object, ciphertext-only stream/file transfer,
  Spring Boot 조건부 자동설정, Floci acceptance, EN/KO README·manual과 lesson

**최종 verdict: PASS — P0=0, P1=0.**

구현·테스트·문서 변경은 현재 브랜치의 exact HEAD에 반영됐다. 초기 최종 review 뒤
verifier가 발견한 S3Properties ABI와 spill output cleanup P1 두 건은 `d38f7d1c`에서
legacy descriptor shim과 실패 경로 정리로 보완했고, 원격 wrapped-key 입력 경계와
RSA null key 구성 오류는 `0bfeb515`에서 보강했다. 남은 P2/P3는 의도적으로 문서화한
bounded destination 제약과 실제 AWS production/OS 장애 환경의 검증 공백이며, 이
checkout의 P0/P1 blocker는 아니다.

## 여섯 관점 검토

| 관점 | verdict | 확인 내용 | 잔여 finding |
| --- | --- | --- | --- |
| API·Kotlin | PASS | 기존 `S3ClientSideEncryptionOperations` 계약과 KMS bean 이름을 유지하고, `S3AesProvider`·`S3RsaProvider`, provider/key version 선택 속성을 추가했다. `ClientSideEncryption`의 legacy constructor/copy/default descriptor shim과 reflection 회귀 테스트로 기존 4-인자 호출 경계를 보존했다. blocking filesystem은 injected IO dispatcher를 사용하고 `AutoCloseable` 수명을 명시했다. | 없음 |
| 보안·암호 | PASS | AES-GCM payload, AES-GCM/RSA-OAEP key wrapping, length-prefixed canonical AAD, provider/algorithm/encoding/key identity 검증과 인증 성공 후 plaintext 반환을 확인했다. key material·data key·nonce·AAD·bounded buffer는 terminal 경로에서 zeroize하고 metadata/context는 평문으로 기록하지 않는다. | 실제 HSM·key rotation·AWS Encryption SDK wire compatibility는 범위 밖이다(P3/제외). |
| 안정성·수명 | PASS | provider close race, stream state lock/`Mutex`, exactly-once completion, zero-length post-terminal write, cancellation/rejection/write failure cleanup, spill output close/write 실패 시 temp cleanup, `NonCancellable + IO` cleanup을 테스트와 source scan으로 확인했다. | process crash/OS filesystem interruption과 cleanup deletion failure의 실제 시스템 검증은 미수행(P2/P3). |
| 성능·테스트 | PASS | ciphertext 상한 `67,108,864`, chunk 기반 stream 수집, per-object data-key 생성, RSA wrapping과 dispatcher 경계를 확인했다. Floci와 module test는 공유 Docker 자원 때문에 순차 실행했다. | 처리량 목표·payload 분포가 없어 benchmark는 N/A이며, production quota/credentials는 미검증(P2). |
| Spring·호환성 | PASS | provider별 조건부 bean, 0/복수 candidate의 명시 오류, 단일 `S3AsyncClient` 조건, 기존 KMS backoff를 확인했다. `compatibilityCheck`와 64개 compatibility test가 통과했다. | 없음 |
| 운영·문서 | PASS | EN/KO README·manual에 provider 설정, metadata/AAD, typed API, ciphertext-only transfer, ETag/`If-Match`, caller-owned key storage/rotation/HSM 경계를 반영했다. lesson과 release pin 경계를 기록했다. | `validate_manuals.rb`는 기존 manifest/inventory 불일치로 실패했다(아래 baseline gap). |

## Finding ledger와 remediation

| severity | finding | disposition | evidence |
| --- | --- | --- | --- |
| P1 | destination에 직접 `Files.write`하면 transfer cancellation/실패 시 destination 보존이 불명확함 | 완화 완료. plaintext temporary file을 만들지 않는 acceptance를 우선하고, destination commit을 `NonCancellable + IO`로 감싸며 기존 bounded destination snapshot/rollback과 rollback 실패 suppressed 보존을 추가했다. | `b0f358f0`, `2b50102d`, `e869c1cc` |
| P1 | cleanup에서 `delegate.close()`를 호출하면 실패 경로가 upload로 바뀔 수 있음 | 완화 완료. `discard`/`discardBlocking`을 도입해 upload 없이 buffered/temp ciphertext를 폐기한다. | `d56348f2` |
| P1 | cipher/delegate write 실패가 stream terminal 상태와 cleanup을 보장하지 않음 | 완화 완료. 실패를 terminalize하고 delegate discard를 실행한 뒤 원래 예외를 재전파한다. | `b0f358f0` |
| P2 | 종료된 injected dispatcher의 `RejectedExecutionException`이 completion cleanup을 건너뛸 수 있음 | 완화 완료. dispatcher entry의 generic `Throwable` 경로도 terminal cleanup을 수행하고 rejection regression test를 추가했다. | `2b50102d` |
| P1 | `ClientSideEncryption`에 새 필드를 추가하면서 기존 4-인자 constructor/copy/default JVM descriptor가 사라짐 | 완화 완료. 4-인자 constructor, Kotlin default constructor descriptor, `copy`, `copy$default` shim을 추가하고 reflection/source 회귀 테스트로 고정했다. | `d38f7d1c`, `S3PropertiesCompatibilityTest` |
| P1 | spill temp path가 output 등록 또는 close 전에 실패하면 cleanup 경계가 끊김 | 완화 완료. spill staging을 성공 후 등록하고, close/upload 실패에서도 output·bytes·temp path를 finally에서 정리하며 RED→GREEN 회귀 테스트를 추가했다. | `d38f7d1c`, `S3OutputStreamTest` |
| P2 | 원격 metadata의 wrapped key가 무제한 Base64 decode로 메모리를 과도하게 사용할 수 있음 | 완화 완료. decode 전에 encoded length와 decoded bytes를 bounded guard로 검증했다. | `0bfeb515`, provider metadata regression |
| P2 | RSA `KeyPair` null 구성요소가 구성 오류 대신 NPE가 될 수 있음 | 완화 완료. public/private key를 `requireNotNull`로 명시 검증하고 회귀 테스트를 추가했다. | `0bfeb515`, provider material regression |
| P2 | 기존 destination이 `MAX_CIPHERTEXT_BYTES`보다 크면 bounded in-memory rollback을 만들 수 없음 | 의도된 제약. 평문 temporary file 금지와 기존 파일 보존을 함께 지키기 위한 bounded guard이며 manual에 공개했다. atomic plaintext replacement는 이 acceptance와 충돌해 채택하지 않았다. | `e869c1cc`, manual/README |
| P2/P3 | 실제 AWS quota/credentials/HSM, process crash, OS별 filesystem interruption, 동시 terminal race와 cleanup deletion failure를 모두 실서비스 수준으로 재현하지 않음 | 환경 공백으로 기록. 로컬 Floci·unit·source scan으로 대체 가능한 경계는 검증했고, unsupported production guarantee는 주장하지 않는다. | lesson 후속 guard, 아래 검증 목록 |

## 검증 증거

### 코드·통합 검증

```text
DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --max-workers=1 --console=plain
SUCCESS: Executed 1436 tests in 1m 33s (2 skipped)
BUILD SUCCESSFUL
```

- provider/transfer/object/auto-configuration targeted test: `SUCCESS: Executed 50 tests`.
- verifier remediation targeted test: `SUCCESS: Executed 25 tests` (provider 17, output 6,
  properties ABI 2).
- spill close failure regression은 수정 전 `DirectoryNotEmptyException`으로 실패(RED)했고,
  수정 후 `S3OutputStreamTest` 6개가 통과(GREEN)했다.
- remediation 후 전체 module test: `SUCCESS: Executed 1440 tests in 1m 31s (2 skipped)`.
- transfer + Floci acceptance 재실행: `SUCCESS: Executed 11 tests in 9.6s`.
- `./gradlew detekt --no-daemon --max-workers=1 --console=plain`: `BUILD SUCCESSFUL`.
- `./gradlew compatibilityCheck --no-daemon --no-configuration-cache --max-workers=1 --console=plain`:
  `BUILD SUCCESSFUL`, compatibility test 64개 실행.
- `S3PropertiesCompatibilityTest`는 compatibility fixture에 포함되지 않는 S3 설정 속성의
  legacy JVM descriptor를 별도 reflection 테스트로 검증했다.
- `colima status`, `docker context show`, `docker info`로 healthy Colima와 Docker
  `29.2.1`을 확인했고 emulator lane은 순차 실행했다.
- 성능·안정성 source scan에서 `GlobalScope`·`Thread.sleep`은 없었고,
  `runBlocking`은 close/discard 경로, `withContext`는 IO/cleanup 경로에 한정됐다.
  `Files.readAllBytes`는 bounded ciphertext 또는 bounded rollback 범위에만 사용된다.

### 문서·계약 검증

- `./gradlew exportManualModuleInventory --no-daemon --max-workers=1 --console=plain`:
  `BUILD SUCCESSFUL`.
- `ruby scripts/manual/manual_contract_test.rb`: `9 runs, 44 assertions, 0 failures`.
- `ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check`:
  `Manual manifest snapshot is current.`
- Korean terminology audit: findings `0`.
- EN/KO manual parity: headings 18개, anchors 20개, code fence 12개 일치.
- `git diff --check`: 통과.

### 알려진 baseline gap

다음 명령은 현재 checkout의 기존 inventory/manifest 불일치 때문에 실패했다.

```text
ruby scripts/manual/validate_manuals.rb build/manual/module-inventory.json docs/manual/manifest.yaml
aws-ktor-service-coverage-examples: missing from manifest
```

이는 Issue #475 변경이 추가한 모듈이 아니며, manifest를 범위 밖에서 수정하지 않았다.
manifest snapshot/contract test와 inventory export는 통과했으므로 이 결과는 숨기지 않은
검증 공백으로 남긴다.

## SPW-01~05 read-back

| gate | result | evidence |
| --- | --- | --- |
| SPW-01 | PASS | issue, 승인 spec/plan/risk, exact HEAD, 구현·테스트·문서 scope를 대조했다. |
| SPW-02 | PASS | 여섯 관점, severity별 finding, remediation commit, residual gap과 stop 경계를 기록했다. |
| SPW-03 | PASS | reader-facing prose는 자연스러운 한국어로 작성하고 API·command·URL·version·identifier는 원문 token을 보존했다. |
| SPW-04 | PASS | spec→plan→source→test→manual→lesson trace와 초기 review remediation을 read-back했다. |
| SPW-05 | PASS | 이 문서를 다시 읽고 표·code block·placeholder·`git diff --check`를 확인했다. |

## Workflow·전달 상태

- workflow run `20260827T141950Z-e6cc6c9b`의 sequence 21 completion은 초기 final review
  기준으로 이미 종료됐다. 그 뒤 verifier P1 remediation은 완료된 run을 변조하지 않고
  `d38f7d1c`, `0bfeb515`와 fresh local test/detekt/compatibility evidence로 별도
  추적했다. 기존 receipt는 remediation 이후 exact HEAD의 증거로 소급하지 않는다.
- PR 생성, push, merge, tag, release는 수행하지 않았다. 해당 작업은 별도 exact-head
  CI/review와 새 사용 권한이 필요한 전달 단계다.
- 현재 결과: **로컬 구현·검증 DONE**. **외부 전달(Push/PR/Merge/Release)은 PENDING
  (요청 범위 밖)**.

## 후속 guard

1. 새 provider는 token, wrapping algorithm, key identity/version, metadata collision,
   no-plaintext acceptance를 한 묶음으로 추가한다.
2. transfer 변경은 normal completion, cancellation, dispatcher rejection, write failure,
   ETag mismatch와 destination rollback을 각각 회귀 검증한다.
3. bounded destination 제한을 완화하려면 평문 temporary file 금지 계약과 crash-safe
   replacement 설계를 먼저 재승인한다.
4. release source가 갱신될 때 `releaseRef`와 develop-only Issue #475 문서 표기를 다시
   대조한다.

**상태:** P0/P1 blocker 없음. 로컬 DoD 완료. baseline manifest gap과 실서비스 환경
검증 공백은 명시된 P2/P3로 보존한다.
