# Issue #475 S3 AES·RSA client-side encryption provider lesson

## 배경과 결과

기존 `aws-spring-boot` S3 client-side encryption은 KMS data key와 byte-array
작업만 제공했다. Issue #475에서는 기존 KMS 기본 경로를 보존하면서 애플리케이션이
공급하는 `S3AesProvider`·`S3RsaProvider`, provider envelope, typed object,
ciphertext-only transfer stream/file 경계를 추가했다. 구현은
`feat/issue-475-s3-cse-providers` 브랜치의 local exact head에서 끝났으며 PR,
push, merge, tag, release는 수행하지 않았다.

## 결정과 재발 방지 규칙

### 설계·계획 단계에서 고정한 판단

- provider는 생성 시 key material을 한 번 읽어 template 수명 동안 고정한다. AES는
  encoded key 복사본만 보유하고 `close()`에서 지우며, RSA는 검증된 key 참조를
  폐기한다. caller가 소유한 `SecretKey`·`KeyPair` 객체를 지운다고 주장하지 않는다.
- provider envelope는 Bluetape4k 전용 `bt4k-cek-*` metadata version `2`를 사용한다.
  본문은 AES-GCM, AES wrapping은 AES-GCM, RSA wrapping은
  `RSA/ECB/OAEPWithSHA-1AndMGF1Padding`이다. KMS metadata는 provider 경로에서
  해석하지 않는다.
- effective encryption context는 length-prefixed canonical AAD로만 전달하고
  metadata·log·temporary file에는 기록하지 않는다. 인증이 끝나기 전에는 plaintext를
  반환하거나 destination에 기록하지 않는다.
- provider transfer의 underlying `S3OutputStream`에는 ciphertext만 전달한다.
  파일 다운로드는 HEAD의 size/ETag와 GET `If-Match`를 확인하고, ciphertext temporary
  file과 plaintext buffer를 `NonCancellable + IO` finally에서 정리한다.
- 평문 temporary file을 만들지 않기 위해 destination commit은 non-cancellable IO
  경계에서 수행하며, 기존 destination은 bounded in-memory rollback으로 보호한다.
  기존 destination이 `S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES`보다
  크면 보존 가능한 rollback을 만들 수 없어 거부한다. 이 제한은 manual에 공개했다.
- KMS는 기본 provider와 기존 `s3ClientSideEncryptionOperations` bean 이름을 유지한다.
  AES/RSA provider가 0개 또는 2개 이상이면 KMS로 대체하지 않고 명시적으로 실패한다.

### 구현 review에서 교정된 판단

독립 transfer review가 다음 실패 경계를 발견했다.

1. destination 직접 기록이 cancellation 반환 경계와 겹칠 수 있었다.
2. delegate cleanup에서 `close()`를 호출하면 실패 경로가 업로드로 바뀔 수 있었다.
3. `write`/cipher 실패가 terminal 상태와 임시 파일 폐기를 보장하지 않았다.
4. 주입 dispatcher의 `RejectedExecutionException`이 completion cleanup을 건너뛸 수
   있었다.

이에 `b0f358f0`, `d56348f2`, `2b50102d`에서 upload 없는 `discard` 경계, write/complete
terminal 상태, fallback IO cleanup, non-cancellable destination commit, rollback 실패의
suppressed 보존, dispatcher rejection cleanup을 추가했다. 다음 구현에서는 blocking
destination 기록을 설계할 때 먼저 “평문 temporary file 금지”와 “기존 파일 보존”의
동시 충족 방법을 명시하고, cancellation/rejection을 별도 테스트한다.

## TDD·환경에서 배운 점

- 처음 비대화형 Floci 실행은 `/var/run/docker.sock` 오류로 실패했다. `colima status`,
  `docker context show`, `docker info`로 healthy Colima를 확인한 뒤
  `DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock`와
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 적용해 재현 가능한
  환경으로 고정했다. Docker-backed 검증은 다른 모듈과 병렬 실행하지 않는다.
- 새 Floci test의 `ApplicationContextRunner`를 expression body로 작성했을 때 provider
  bean이 등록되지 않는 경로를 발견했다. block body로 runner를 반환하도록 고치고,
  random ciphertext에 `asUtf8String()`을 적용한 잘못된 assertion도 byte-array 비교로
  교체했다. 이후 AES/RSA acceptance가 실제 emulator에서 통과했다.
- `complete()`의 logical EOF, zero-length post-terminal write, dispatcher-entry
  cancellation/rejection, write failure, ETag/If-Match, oversized HEAD, closed provider를
  단위 테스트로 고정했다. 실제 AWS quota/credentials/HSM과 OS별 filesystem interruption은
  이 checkout에서 검증하지 않았다.

## 검증 근거

- `DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --max-workers=1 --console=plain`
  — `SUCCESS: Executed 1436 tests in 1m 33s (2 skipped)`, `BUILD SUCCESSFUL`.
  Floci/Colima와 `@Execution(SAME_THREAD)`을 사용했다.
- `./gradlew detekt --no-daemon --max-workers=1 --console=plain` — `BUILD SUCCESSFUL`.
- `./gradlew compatibilityCheck --no-daemon --no-configuration-cache --max-workers=1`
  — `BUILD SUCCESSFUL`, compatibility test 64개 실행.
- provider/transfer/object/auto-configuration targeted test와 Floci acceptance test —
  모든 재실행이 통과했다.
- `ruby scripts/manual/manual_contract_test.rb` — 9 runs, 44 assertions, 0 failures.
- `ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check`
  — manifest snapshot current.
- `node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs ...`
  — Korean terminology findings 0.
- `git diff --check` — 통과.
- 처리량 목표나 대표 payload 분포가 제공되지 않았으므로 benchmark는 N/A다. 대신
  per-object data-key allocation, RSA wrapping, bounded ciphertext, injected IO
  dispatcher, cancellation, provider close/cleanup을 source scan과 targeted test로
  확인했다.

## 후속 guard

- 새 provider를 추가할 때 provider token, wrapping algorithm, key identity/version,
  reserved metadata collision, no-plaintext acceptance를 함께 추가한다.
- provider transfer를 변경할 때 terminal state를 `Mutex`/state lock으로 재검토하고,
  normal completion과 cancellation/rejection/write failure를 각각 검증한다.
- `S3TransferAutoConfiguration`의 provider template·transfer·output provider 후보가
  정확히 하나인지와 `S3AsyncClient` single candidate 조건을 유지한다.
- EN/KO README·manual, releaseRef와 develop-only 표기를 함께 검증한다. AWS Encryption
  SDK 호환성, key store/rotation, HSM, 법적 compliance를 암시하는 문구는 추가하지 않는다.

## Superpowers writer gate

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | issue, 승인 spec, plan, risk ledger, final implementation scope와 대상 독자를 read-back했다. |
| SPW-02 | PASS | 결정, 실패 가정, 교정, 검증 명령, residual risk와 재발 방지 guard를 기록했다. |
| SPW-03 | PASS | 사용자-facing lesson은 한국어로 작성하고 API·command·URL·version token은 보존했다. |
| SPW-04 | PASS | spec→plan→source→test→manual trace와 initial review remediation을 대조했다. |
| SPW-05 | PASS | rendered Markdown, 표, code token, placeholder와 `git diff --check`를 재검토했다. |

**상태:** 구현과 local verification은 완료되었고, PR·push·merge·release는 별도 권한이
필요하다.
