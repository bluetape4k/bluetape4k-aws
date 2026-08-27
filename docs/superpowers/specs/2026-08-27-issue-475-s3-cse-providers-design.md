# #475 S3 AES·RSA client-side encryption provider 설계

<!-- 이슈 #475 | bluetape4k/bluetape4k-aws -->

**상태**: 설계 승인됨 — 사용자 승인 메시지 `승인` 확인, 작성된 spec 검토 대기
**작성일**: 2026-08-27
**이슈**: [#475](https://github.com/bluetape4k/bluetape4k-aws/issues/475)
**대상 모듈**: `aws-spring-boot`
**검증 원칙**: 실제 AWS 계정은 사용하지 않고 기존 Floci 기반 테스트를 사용한다.

## 1. 문제와 목표

현재 `aws-spring-boot`의 S3 client-side encryption(CSE)은 KMS가 만든 AES-256
데이터 키를 로컬 AES-GCM으로 사용하는 바이트 배열 전용 계약이다. 따라서 KMS를
사용하지 않는 애플리케이션은 안정적으로 보관하는 AES secret key 또는 RSA key pair를
S3 CSE에 연결할 수 없고, TransferManager streaming/typed object 경로도 같은 암호화
계약을 공유하지 못한다.

이번 변경은 기존 KMS 경로를 유지하면서 다음을 추가한다.

- Spring Cloud AWS와 같은 최소 provider 계약인 `S3AesProvider`와 `S3RsaProvider`
- provider가 공급한 안정적인 키를 사용해 객체별 AES-256 data key를 봉투 암호화하는
  `S3ClientSideEncryptionProviderTemplate`
- provider 선택, key ID/version, algorithm/encoding 검증이 포함된 S3 user metadata
- 암호화된 `S3OutputStream`과 converter 기반 typed object 확장
- provider 수명 종료와 보유 바이트 배열의 best-effort zeroization

다음은 범위에서 제외한다.

- KMS CSE의 기존 metadata/constructor/byte-array 동작 변경
- AWS S3 Encryption Client 전체 wire format 또는 다른 클라이언트와의 완전한
  상호운용성 보장
- 키 저장소, rotation service, HSM 또는 법적 인증 보장
- S3 Access Grants/Vectors, 새로운 AWS SDK 또는 awspring 의존성
- KMS 경로의 multipart/streaming 재작성
- 실제 AWS 계정 검증, release·publish·tag·PR merge

## 2. 현재 근거와 외부 근거

### 2.1 저장소 근거

| 근거 | 확인된 사실 | 설계 영향 |
| --- | --- | --- |
| `aws-spring-boot/.../s3/S3ClientSideEncryptionOperations.kt` | KMS data key, AES-GCM 본문, `bt4k-cek-*` metadata, bounded read를 구현하며 byte-array 전용이라고 명시한다. | 기존 KMS 구현은 보존하고 provider 구현을 별도 delegate로 둔다. |
| `aws-spring-boot/.../s3/S3ExtendedCapabilities.kt` | `S3BoundedEncryptedReadOperations`와 `S3ClientSideEncryptionIdentity`가 SQS extended client와 연결된다. | provider template도 두 capability를 구현하고 안정적인 identity를 제공한다. |
| `aws-spring-boot/.../s3/S3AutoConfiguration.kt` | CSE bean은 `enabled=true`, `KmsOperations` 존재, missing custom bean 조건에서만 생성된다. | KMS를 기본값으로 보존하고 provider 선택 조건을 별도 분기한다. |
| `aws-spring-boot/.../s3/S3Properties.kt` | CSE 속성은 `enabled`, `keyId`, `encryptionContext`, `useDataKeyCache`뿐이다. | `provider`와 `keyVersion`을 additive하게 추가하고 KMS 기본값을 유지한다. |
| `S3TransferOperations.kt`, `S3OutputStream.kt` | TransferManager output stream은 누적량 초과 시 파일로 전환한다. | 암호화 cipher를 먼저 적용해 임시 파일에는 ciphertext만 기록한다. |
| `S3ObjectOperations.kt`, `S3ObjectConverter.kt` | converter 기반 typed API는 바이트로 직렬화한다. | CSE typed 확장은 기존 converter를 재사용하고 새 serializer를 만들지 않는다. |
| `docs/lessons/2026-05-27-issue-192-spring-s3-advanced.md` | KMS CSE의 byte-array 경계와 multipart 보류 이유가 기록되어 있다. | KMS와 provider의 streaming 지원 범위를 문서에서 구분한다. |

기준선 검증은 다음과 같다.

~~~text
./gradlew :bluetape4k-aws-spring-boot:test \\
  --tests 'io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest' \\
  --tests 'io.bluetape4k.aws.spring.s3.S3CoroutinesTemplateAwsEmulatorTest' \\
  --no-daemon --max-workers=1 --console=plain
~~~

비대화형 프로세스에 Colima socket을 명시한 재실행에서 18/18 테스트가 통과했다.
첫 실행의 5건 실패는 `/var/run/docker.sock`를 찾지 못한 Testcontainers 환경 오류였고,
`colima status`, `docker context show`, `docker info`로 실행 중인 Colima를 확인한 뒤
`DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock`와
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 적용해 해소했다.

### 2.2 외부 근거

- [Spring Cloud AWS S3 client-side encryption](https://docs.awspring.io/spring-cloud-aws/docs/4.1.0/reference/html/index.html#s3-client-side-encryption)은 AES secret key provider와 RSA key pair provider를 애플리케이션이 공급하는 구성을 설명한다.
- [Spring Cloud AWS `S3AesProvider`](https://github.com/awspring/spring-cloud-aws/blob/1453a46726a3f06150e9d00274a3a405e69d591d/spring-cloud-aws-autoconfigure/src/main/java/io/awspring/cloud/autoconfigure/s3/S3AesProvider.java)는 `SecretKey generateSecretKey()` 계약을 정의한다.
- [Spring Cloud AWS `S3RsaProvider`](https://github.com/awspring/spring-cloud-aws/blob/1453a46726a3f06150e9d00274a3a405e69d591d/spring-cloud-aws-autoconfigure/src/main/java/io/awspring/cloud/autoconfigure/s3/S3RsaProvider.java)는 `KeyPair generateKeyPair()` 계약을 정의한다.
- [AWS S3 Encryption Client 알고리즘](https://docs.aws.amazon.com/amazon-s3-encryption-client/latest/developerguide/encryption-algorithms.html)은 객체별 대칭 data key와 AES-GCM 본문 암호화를 설명하며, RSA wrapping에는 AWS 호환 OAEP 조합이 필요함을 보여 준다.
- [Amazon S3 user metadata](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html)는 user metadata가 객체에 고정되고 크기 제한을 받는다는 근거다.
- [JDK `GCMParameterSpec`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html)과 [`Cipher`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)는 본문·AES wrapping 구현의 JDK 경계다.

외부 문서는 provider shape와 암호 알고리즘 선택의 근거로만 사용한다. AWS S3
Encryption Client의 전체 metadata schema를 복사하거나 새 dependency를 추가하지 않는다.

## 3. 대안과 선택

### 대안 A — JDK provider adapter와 Bluetape metadata (선택)

`S3AesProvider`/`S3RsaProvider`는 안정적인 key material만 공급하고, Bluetape 내부
adapter가 객체별 AES-256 data key 생성·wrapping·본문 GCM을 담당한다. KMS 구현과 새
provider 구현을 분리하므로 기존 consumer를 깨지 않고, 암호화 cipher를
`S3OutputStream` 앞에 둘 수 있다. metadata는 Bluetape 전용이므로 AWS Encryption
Client와 wire-compatible하다고 주장하지 않는다.

### 대안 B — AWS S3 Encryption Client 직접 위임

AWS 표준 multipart와 metadata를 얻을 수 있지만 새 runtime dependency, client lifecycle,
Spring 조건부 wiring, KMS 계약 병합이 필요하다. compileOnly AWS service SDK와 새
dependency를 피하는 저장소 경계에 맞지 않아 선택하지 않는다.

### 대안 C — provider interface만 추가하고 바이트 API 유지

구현량은 가장 작지만 TransferManager streaming에서 평문 임시 파일을 만들 수 있고
typed object와 공통 envelope 검증이 분리된다. 이슈의 acceptance를 충족하지 못해
선택하지 않는다.

대안 A를 선택한다. provider가 반환한 키는 template 생성 시 한 번 고정해 복호화에도
같은 키를 사용한다. provider가 호출마다 새 키를 반환하는 구현은 지원하지 않으며,
rotation은 별도 keyVersion과 caller가 관리하는 provider instance로 구분한다.

## 4. 선택한 설계

### 4.1 Provider 공개 계약

패키지는 `io.bluetape4k.aws.spring.s3`로 통일한다. 두 fun interface는 Spring Bean으로
등록하기 쉽고 lambda·secured storage adapter를 모두 허용한다.

~~~kotlin
fun interface S3AesProvider {
    fun generateSecretKey(): SecretKey

    companion object {
        fun of(key: SecretKey): S3AesProvider = S3AesProvider { key }
    }
}

fun interface S3RsaProvider {
    fun generateKeyPair(): KeyPair

    companion object {
        fun of(keyPair: KeyPair): S3RsaProvider = S3RsaProvider { keyPair }
    }
}
~~~

template 내부에는 public contract를 암호 primitive와 분리하는
`S3ClientSideEncryptionKeyMaterial` delegate를 둔다.

- AES adapter는 `SecretKey.getEncoded()`가 16/24/32 bytes인지, algorithm이 AES인지
  확인하고 복사본을 보유한다.
- RSA adapter는 public/private key가 모두 존재하고 algorithm이 RSA인지 확인한다.
  RSA modulus는 2048 bits 이상이어야 하며, provider가 반환한 key pair는 template
  수명 동안 고정한다.
- null·blank·control character·지원하지 않는 key algorithm은 생성 시
  `IllegalArgumentException`으로 거부한다.
- `close()`는 AES 복사본을 `fill(0)`으로 지우고 RSA 참조를 폐기한다. JVM/JCE가
  외부 객체 메모리를 지운다고 주장하지 않는다.

### 4.2 Properties와 auto-configuration

`S3Properties.ClientSideEncryption`에 다음을 additive하게 추가한다.

~~~kotlin
enum class ClientSideEncryptionProvider { KMS, AES, RSA }

data class ClientSideEncryption(
    val enabled: Boolean = false,
    val provider: ClientSideEncryptionProvider = ClientSideEncryptionProvider.KMS,
    val keyId: String? = null,
    val keyVersion: String? = null,
    val encryptionContext: Map<String, String> = emptyMap(),
    val useDataKeyCache: Boolean = true,
)
~~~

`keyId`/`keyVersion`는 non-blank·control character를 거부한다. KMS에서는 기존
`keyId` 의미와 canonical ARN 검증을 그대로 사용한다. AES/RSA에서는 keyId가 없을 때
provider key material의 SHA-256 fingerprint에서 `sha256:<base64url>` identity를 만들고,
설정된 keyId가 있으면 그 값을 metadata에 저장한다. keyVersion은 선택적이지만 설정하면
암호화 metadata와 복호화 설정이 정확히 일치해야 한다.

auto-configuration은 CSE `enabled=true`일 때 다음 우선순위를 사용한다.

| `provider` | 필요한 bean | 결과 |
| --- | --- | --- |
| `KMS` (기본값) | `KmsOperations` | 기존 `S3ClientSideEncryptionTemplate` |
| `AES` | `S3AesProvider` | `S3ClientSideEncryptionProviderTemplate`의 AES delegate |
| `RSA` | `S3RsaProvider` | `S3ClientSideEncryptionProviderTemplate`의 RSA delegate |

선택한 provider bean이 없으면 컨텍스트 시작을 실패시키고 필요한 bean과
`bluetape4k.aws.s3.client-side-encryption.provider`를 메시지에 포함한다. KMS가
존재해도 `provider=AES/RSA`를 명시하면 명시 provider를 사용한다. 기본값 KMS에서
provider bean만 있는 경우에는 KMS를 자동 대체하지 않아 기존 설정의 의미를 보존한다.
선택한 provider에 여러 candidate bean이 있으면 template을 임의로 선택하지 않고 명시적
구성 오류로 거부한다. `provider=KMS`에서는 AES/RSA bean을 무시하므로 기존 KMS 구성이
영향받지 않는다.

모든 template bean은 `@ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)`
backoff을 유지한다. provider template은 `S3BoundedEncryptedReadOperations`와
`S3ClientSideEncryptionIdentity`를 함께 구현한다. Spring이 관리하는 provider template은
`close()`를 destroy method로 호출하고, `S3AsyncClient`는 기존 auto-configuration의
소유권을 유지한다.

### 4.3 Envelope metadata와 암호 primitive

provider 객체는 매 업로드마다 32-byte random AES data key와 12-byte random 본문 nonce를
만든다. AES provider는 provider key로 data key를 AES-GCM wrapping하고, RSA provider는
`RSA/ECB/OAEPWithSHA-1AndMGF1Padding`으로 data key를 wrapping한다. 본문은 기존과 같은
`AES/GCM/NoPadding`, 128-bit tag를 사용한다.

다음 reserved user metadata를 사용한다. 값은 표준 Base64 또는 고정 ASCII token이며,
사용자 metadata가 같은 이름(대소문자 무시)을 제공하면 업로드 전에 거부한다.

| metadata | 값 |
| --- | --- |
| `bt4k-cek-version` | `2` |
| `bt4k-cek-provider` | `aes` 또는 `rsa` |
| `bt4k-cek-alg` | `AES/GCM/NoPadding` |
| `bt4k-cek-wrap-alg` | `AES/GCM/NoPadding` 또는 `RSA/ECB/OAEPWithSHA-1AndMGF1Padding` |
| `bt4k-cek-encoding` | `base64` |
| `bt4k-cek` | wrapping된 data key |
| `bt4k-cek-nonce` | 본문 GCM nonce |
| `bt4k-cek-wrap-nonce` | AES wrapping nonce (RSA에서는 없음) |
| `bt4k-cek-key-id` | 유효 key ID 또는 material fingerprint |
| `bt4k-cek-key-version` | 설정된 경우에만 기록 |

복호화는 metadata를 case-insensitive하게 찾되 다음 순서로 조기 검증한다.

1. version, provider, content algorithm, wrapping algorithm, encoding을 정확히 비교한다.
2. Base64를 decode하고 nonce 길이(12), data-key 길이(32), RSA wrapped key 길이를
   검증한다. malformed metadata는 secret·ciphertext를 메시지에 넣지 않고
   `IllegalArgumentException`으로 보고한다.
3. 설정된 keyId/keyVersion와 metadata가 다르면 명시적인 key identity/version mismatch
   오류를 반환한다.
4. data key를 unwrap한 뒤 GCM `doFinal`을 호출한다. 다른 key/provider, 변조된
   ciphertext, 잘못된 tag는 인증 실패로 전파하며 부분 plaintext를 반환하지 않는다.

KMS metadata(`bt4k-cek-alg`, `bt4k-cek`, `bt4k-cek-key-id`, `bt4k-cek-nonce`)는 provider
template에서 version/provider 검증에 실패하므로 KMS template으로만 읽는다. 반대로
기존 KMS template을 수정해 provider metadata를 해석하지 않는다.

### 4.4 Byte, typed, streaming/transfer API

`S3ClientSideEncryptionProviderTemplate`은 기존 byte API와 bounded read를 구현한다.
plaintext byte array는 encryption call 동안 메모리에만 있고, 실패 시 ciphertext와
복사된 data key를 즉시 폐기한다.

기존 converter를 재사용하는 extension을 추가한다.

~~~kotlin
suspend fun <T : Any> S3ClientSideEncryptionOperations.uploadEncryptedObject(
    bucket: String,
    key: String,
    value: T,
    converter: S3ObjectConverter<T>,
    contentType: String? = null,
    metadata: Map<String, String> = emptyMap(),
    encryptionContext: Map<String, String> = emptyMap(),
): PutObjectResponse

suspend fun <T : Any> S3ClientSideEncryptionOperations.downloadEncryptedObject(
    bucket: String,
    key: String,
    targetType: Class<T>,
    converter: S3ObjectConverter<T>,
    encryptionContext: Map<String, String> = emptyMap(),
): T
~~~

provider template은 transfer capability가 주입된 경우
`S3ClientSideEncryptionTransferOperations`를 노출한다.

~~~kotlin
interface S3ClientSideEncryptionTransferOperations {
    fun encryptedOutputStream(
        bucket: String,
        key: String,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        encryptionContext: Map<String, String> = emptyMap(),
    ): S3EncryptedOutputStream

    suspend fun downloadEncryptedFile(
        bucket: String,
        key: String,
        destination: Path,
        encryptionContext: Map<String, String> = emptyMap(),
    )
}
~~~

`S3EncryptedOutputStream`은 provider envelope를 먼저 만든 뒤
`S3OutputStreamProvider.outputStream`을 ciphertext destination으로 연다. `write`는
`Cipher.update` 결과만 delegate에 전달하고 `close/complete`에서 `doFinal`을 호출해 GCM
tag를 기록한다. 따라서 delegate가 threshold를 넘겨 OS temporary file을 만들더라도
그 파일에는 ciphertext만 존재한다. 암호화 실패·close 실패·취소 시 delegate를 닫고
임시 파일을 삭제한다.

`downloadEncryptedFile`은 TransferManager로 ciphertext를 임시 경로에 내려받을 수
있지만, 인증이 끝날 때까지 평문을 생성하지 않는다. 전체 ciphertext를 unwrap/GCM
검증한 뒤 최종 `destination`에만 평문을 쓰고, 임시 ciphertext 경로는 `finally`에서
삭제한다. destination을 덮어쓰는 동작은 caller가 지정한 경로에 한정하며, 로그에는
내용·key material·credentials를 기록하지 않는다. KMS template은 이 transfer capability를
노출하지 않는다.

### 4.5 Identity, 오류, 수명

provider identity는 `provider`, effective keyId, keyVersion, 정렬된 encryption context를
canonical 문자열로 묶고 SHA-256 fingerprint를 계산한다. `S3ClientSideEncryptionIdentity`
계약을 사용하는 SQS extended client가 KMS와 provider를 서로 다른 암호 경계로 인식할
수 있어야 한다.

다음 오류는 호출자에게 의미가 드러나는 안정적인 메시지를 제공한다.

- unsupported provider/algorithm/version/encoding: `IllegalArgumentException`
- key material이 없거나 형식이 잘못됨: `IllegalArgumentException`
- metadata key ID/version/provider mismatch: `IllegalStateException`
- GCM tag 또는 RSA unwrap 인증 실패: 원인 예외를 보존한
  `S3ClientSideEncryptionException`(새 public hierarchy가 필요하면 provider 경계에만
  한정)
- S3 network/permission 오류: AWS SDK 원인과 request 위치를 보존하고 secret은 제외

어떤 오류도 부분 plaintext를 반환하지 않는다. provider template은 Spring context가
종료될 때 한 번만 close되며, close 이후 모든 operation은 `IllegalStateException`으로
거부한다. provider interface의 구현자가 반환한 외부 `SecretKey`/`KeyPair`는 caller 소유로
남기고, template이 만든 복사본만 zeroize한다.

## 5. 파일 경계와 구현 책임

| 파일 | 책임 |
| --- | --- |
| `aws-spring-boot/.../s3/S3ClientSideEncryptionProviders.kt` | `S3AesProvider`, `S3RsaProvider`, key material adapter, validation, close |
| `aws-spring-boot/.../s3/S3ClientSideEncryptionProviderTemplate.kt` | provider envelope, byte/bounded decrypt, identity, lifecycle |
| `aws-spring-boot/.../s3/S3ClientSideEncryptionTransferOperations.kt` | encrypted output stream/download file contract와 구현 |
| `aws-spring-boot/.../s3/S3ClientSideEncryptionOperations.kt` | 기존 KMS 구현 보존, 공통 reserved metadata/helper만 중복 없이 재사용 |
| `aws-spring-boot/.../s3/S3Properties.kt` | provider enum, keyVersion, 입력 검증 |
| `aws-spring-boot/.../s3/S3AutoConfiguration.kt` | KMS/AES/RSA 선택, missing bean/ambiguity 조건, destroy method |
| `aws-spring-boot/src/test/.../S3ClientSideEncryptionProviderTest.kt` | JDK crypto round-trip, metadata/algorithm/key validation, zeroization/lifecycle |
| `aws-spring-boot/src/test/.../S3ClientSideEncryptionProviderAwsEmulatorTest.kt` | Floci S3 round-trip, transfer/streaming, typed object, no plaintext temp |
| `aws-spring-boot/src/test/.../S3AutoConfigurationTest.kt` | provider property/bean 조건, KMS backoff, ambiguity/startup failure |
| `aws-spring-boot/README.md`, `README.ko.md`, `docs/manual/en/...`, `docs/manual/ko/...` | provider 선택, 키 수명, metadata/wire 경계, streaming 제한 문서 |
| `docs/lessons/2026-08-27-issue-475-s3-cse-providers.md` | 결정·검증·실패·향후 guard 기록 |

새 dependency는 추가하지 않는다. 테스트는 이미 module에 있는 AWS SDK, MockK,
Testcontainers/Floci, converter를 사용한다.

## 6. 검증 매트릭스와 acceptance

| 요구 | 검증 |
| --- | --- |
| AES secret key round-trip | 고정 `SecretKey` provider로 byte·typed·stream upload/download가 원문과 일치하고 저장 ciphertext가 다름 |
| RSA key pair round-trip | 고정 2048-bit RSA pair로 같은 경로를 검증 |
| invalid key/metadata | AES 길이, RSA algorithm/size, missing field, malformed Base64, nonce/tag length를 네트워크 호출 전에 거부 |
| algorithm/provider mismatch | AES object를 RSA/AES wrong key와 읽을 때 명시 mismatch 또는 인증 오류; plaintext 미반환 |
| key ID/version mismatch | 설정과 metadata가 다를 때 조기 실패 |
| no plaintext temporary file/log | threshold를 낮춘 encrypted stream에서 temporary directory를 감시하고 ciphertext만 존재하는지, 실패 로그에 plaintext/key material이 없는지 확인 |
| typed object combination | `S3ObjectConverter` extension round-trip |
| KMS backward compatibility | 기존 `S3AutoConfigurationTest`와 KMS Floci test의 bean/backoff/round-trip 유지 |
| provider lifecycle | close 후 operation 거부, AES copied key zeroization best-effort, Spring destroy method 호출 |
| bounded read/SQS identity | provider template이 `S3BoundedEncryptedReadOperations`와 `S3ClientSideEncryptionIdentity`를 구현하고 기존 SQS capability test가 통과 |

검증 순서는 RED 테스트 → 최소 구현 → GREEN targeted test → module test → `detekt`와
문서 diff/link 검증이다. Docker-backed 테스트는 Colima socket을 명시하고 공유 emulator를
동시에 실행하지 않는다.

## 7. DoD와 승인 게이트

- 설계 문서의 SPW-01~05 writer gate가 모두 통과한다.
- 독립 perspective review에서 보안·API·Spring 조건·stream lifecycle·테스트 관점의
  P0/P1 미해결 항목이 없다.
- 사용자 spec 검토 승인을 받은 뒤에만 `writing-plans`로 구현 계획을 만든다.
- 구현 계획은 각 public API와 테스트를 TDD RED/GREEN 순서로 추적한다.
- 구현 후 `git diff --check`, provider targeted tests, Floci emulator tests, module
  `test`, `detekt` 결과를 새로 읽고 기록한다.
- README/manual 영어·한국어 구조와 API/경계 설명이 일치한다.
- lesson을 Korean으로 작성하고 Type A pre-PR review를 수행한다. PR 생성·merge·push는
  별도 권한이 없으므로 이 작업의 DoD에서 실행하지 않는다.

## 8. Spec writer gate

- **SPW-01**: 독자(`aws-spring-boot` 소비자), 목적(issue #475 CSE provider), source
  ledger, 외부 URL, baseline 명령과 미지원 wire-format 경계를 고정했다.
- **SPW-02**: 문제·대안·선택·공개 계약·metadata·오류·수명·파일·테스트·DoD를 포함했다.
- **SPW-03**: 한국어 자연스러움 checklist(KO-01~KO-07)를 적용하고 API/명령/URL/숫자는
  원문 token으로 보존한다.
- **SPW-04**: 현재 소스, issue body, GNO backlog, Spring/AWS/JDK primary source와
  각 결정의 traceability를 확인했다.
- **SPW-05**: 작성 후 Markdown headings/table/code fence와 scope를 read-back하고,
  spec 검토 승인 전 상태를 명시했다.

이 문서는 구현 코드가 아니라 승인된 설계 기록이다. spec 검토에서 변경이 생기면
SPW-01~05와 영향받는 후속 승인 게이트를 다시 수행한다.
