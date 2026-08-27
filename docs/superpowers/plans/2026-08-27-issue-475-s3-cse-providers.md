# #475 S3 AES·RSA client-side encryption provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 기존 KMS S3 client-side encryption을 보존하면서 고정 AES secret key와 RSA key pair provider, 검증 가능한 envelope metadata, typed object, ciphertext-only streaming/transfer 경로를 aws-spring-boot에 추가한다.

**Architecture:** 공개 provider는 S3AesProvider와 S3RsaProvider 두 fun interface로 제한하고, 내부 key-material adapter와 JDK Cipher envelope이 실제 암호 primitive를 담당한다. KMS/AES/RSA 선택은 S3AutoConfiguration의 provider 조건과 명시적인 단일 candidate 조회로 분리하며 provider 구현은 기존 KMS template과 별도 클래스로 둔다. provider template은 byte/bounded read와 identity를 제공하고 transfer가 구성된 경우 별도 adapter가 encrypted output stream과 authenticated file download를 노출한다.

**Tech Stack:** Kotlin, Spring Boot 4 auto-configuration, AWS SDK v2 S3AsyncClient/S3TransferManager, Kotlin coroutines, JDK AES/GCM/NoPadding 및 RSA/ECB/OAEPWithSHA-1AndMGF1Padding, JUnit 5, MockK, Floci Testcontainers.

---

## 작업 전 고정 사항

- 대상 worktree는 feat/issue-475-s3-cse-providers이며 기준 commit은 9033ac17의 승인된 설계 문서다.
- 새 runtime dependency는 추가하지 않는다. AWS KMS/S3/Transfer SDK의 현재 compileOnly/test 경계를 유지한다.
- 공개 API, KDoc, README/manual, lesson은 한국어로 작성하고 코드·명령·식별자·URL은 원문 token을 유지한다.
- 각 구현 task는 RED 테스트 작성 → 실패 확인 → 최소 구현 → GREEN 확인 → Lore commit 순서로 진행한다.
- 사용자 계획 승인을 받기 전에는 production/test 코드를 수정하지 않는다.

## 파일 경계와 책임

| 파일 | 변경 책임 |
| --- | --- |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Properties.kt | ClientSideEncryptionProvider, provider, keyVersion, 안전한 token 검증 |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviders.kt | S3AesProvider, S3RsaProvider, AES/RSA key-material adapter, fingerprint, 오류 type |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTemplate.kt | provider envelope metadata, byte/bounded decrypt, identity, lifecycle |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionTransferOperations.kt | transfer capability 계약, encrypted stream, authenticated file download |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionObjectExtensions.kt | 기존 converter를 재사용하는 typed encrypted object extension |
| aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt | KMS/AES/RSA 선택, missing/ambiguous bean 실패, transfer adapter destroy/backoff |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt | JDK crypto, metadata, mismatch, lifecycle, zeroization 단위 검증 |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderAwsEmulatorTest.kt | Floci S3 AES/RSA byte·typed·stream·file acceptance |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfigurationTest.kt | provider property/조건, KMS 회귀, backoff/ambiguity/startup 오류 |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionObjectExtensionsTest.kt | converter extension의 byte/typed round-trip 단위 검증 |
| aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionTransferTest.kt | stream delegate와 ciphertext-only 임시 파일 검증 |
| aws-spring-boot/README.md, aws-spring-boot/README.ko.md | provider 설정과 KMS/provider streaming 경계 요약 |
| docs/manual/en/modules/bluetape4k-aws-spring-boot.md, docs/manual/ko/modules/bluetape4k-aws-spring-boot.md | 영어/한국어 상세 사용법과 제한사항 |
| docs/lessons/2026-08-27-issue-475-s3-cse-providers.md | 결정, 검증 결과, 실패 원인, 후속 guard 기록 |

---

### Task 1: Properties와 공개 provider 계약

Files:
- Modify: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Properties.kt
- Create: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviders.kt
- Create: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt

- [ ] Step 1: 공개 계약과 속성의 실패 테스트를 먼저 작성한다.

    @Test
    fun defaults_to_kms_and_accepts_key_version() {
        val defaults = S3Properties().clientSideEncryption
        defaults.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        defaults.keyVersion shouldBeNull()

        val configured = S3Properties.ClientSideEncryption(
            enabled = true,
            provider = ClientSideEncryptionProvider.AES,
            keyId = "orders-key",
            keyVersion = "2026-08",
        )
        configured.provider shouldBeEqualTo ClientSideEncryptionProvider.AES
        configured.keyVersion shouldBeEqualTo "2026-08"
    }

    @Test
    fun provider_factories_return_caller_key_material() {
        val aesKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

        S3AesProvider.of(aesKey).generateSecretKey() shouldBeSameInstanceAs aesKey
        S3RsaProvider.of(pair).generateKeyPair() shouldBeSameInstanceAs pair
    }

    @Test
    fun key_id_and_version_reject_blank_or_control_character() {
        assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyId = "  ")
        }
        assertFailsWith<IllegalArgumentException> {
            S3Properties.ClientSideEncryption(keyVersion = "v" + '\n' + "1")
        }
    }

- [ ] Step 2: 테스트가 아직 없는 type을 가리켜 실패하는지 확인한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: FAIL with unresolved ClientSideEncryptionProvider, S3AesProvider, or S3RsaProvider.

- [ ] Step 3: 최소 공개 type과 입력 검증을 구현한다.

    enum class ClientSideEncryptionProvider { KMS, AES, RSA }

    data class ClientSideEncryption(
        val enabled: Boolean = false,
        val provider: ClientSideEncryptionProvider = ClientSideEncryptionProvider.KMS,
        val keyId: String? = null,
        val keyVersion: String? = null,
        val encryptionContext: Map<String, String> = emptyMap(),
        val useDataKeyCache: Boolean = true,
    ) : Serializable {
        init {
            require(keyId == null || keyId.isSafeCseToken("keyId")) {
                "bluetape4k.aws.s3.client-side-encryption.keyId must not be blank or contain control characters."
            }
            require(keyVersion == null || keyVersion.isSafeCseToken("keyVersion")) {
                "bluetape4k.aws.s3.client-side-encryption.keyVersion must not be blank or contain control characters."
            }
            require(encryptionContext.keys.none { it.isBlank() || it.any(Char::isISOControl) }) {
                "bluetape4k.aws.s3.client-side-encryption.encryptionContext keys must not be blank or contain control characters."
            }
            require(encryptionContext.values.none { it.any(Char::isISOControl) }) {
                "bluetape4k.aws.s3.client-side-encryption.encryptionContext values must not contain control characters."
            }
        }
    }

    private fun String.isSafeCseToken(name: String): Boolean {
        require(isNotBlank()) { "client-side-encryption." + name + " must not be blank." }
        require(none(Char::isISOControl)) {
            "client-side-encryption." + name + " must not contain control characters."
        }
        return true
    }

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

- [ ] Step 4: 계약/속성 단위 테스트가 통과하는지 확인한다.

Run the Step 1 Gradle command again.

Expected: BUILD SUCCESSFUL and all S3ClientSideEncryptionProviderTest tests pass.

- [ ] Step 5: Lore commit을 만든다.

    git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3Properties.kt \
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviders.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt
    git commit -m "#475 S3 provider 공개 계약과 선택 속성을 고정한다" \
      -m "Constraint: 기존 KMS 기본값과 compileOnly SDK 경계를 유지한다.
    Rejected: awspring provider type 직접 의존 | 새 runtime dependency를 피한다.
    Confidence: high
    Scope-risk: narrow
    Directive: provider key material은 다음 task에서 복사본과 수명 경계를 추가한다.
    Tested: S3ClientSideEncryptionProviderTest
    Not-tested: 실제 S3 호출과 Spring auto-configuration"


---

### Task 2: Key material adapter와 provider envelope primitive

Files:
- Modify: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviders.kt
- Modify: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt

- [ ] Step 1: AES/RSA key 검증, envelope round-trip, metadata 검증의 RED 테스트를 추가한다.

    @Test
    fun aes_material_close_rejects_use_and_does_not_mutate_caller_key() {
        val source = SecretKeySpec(ByteArray(32) { 3 }, "AES")
        val material = AesClientSideEncryptionKeyMaterial.from(S3AesProvider.of(source))

        material.keyIdentityMaterial.isNotEmpty().shouldBeTrue()
        material.close()
        source.encoded.all { it == 3.toByte() }.shouldBeTrue()
        assertFailsWith<IllegalStateException> {
            material.wrap(ByteArray(32), SecureRandom())
        }
    }

    @Test
    fun rsa_material_rejects_small_key() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
        assertFailsWith<IllegalArgumentException> {
            RsaClientSideEncryptionKeyMaterial.from(
                S3RsaProvider.of(generator.generateKeyPair()),
            )
        }
    }

    @Test
    fun provider_envelope_round_trips_aes_and_rsa() {
        val aes = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider.of(SecretKeySpec(ByteArray(32) { 9 }, "AES")),
        )
        val rsa = RsaClientSideEncryptionKeyMaterial.from(
            S3RsaProvider.of(
                KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(2048) }
                    .generateKeyPair(),
            ),
        )
        val plaintext = "provider envelope".encodeToByteArray()

        val aesEnvelope = ProviderEnvelope.encrypt(
            plaintext, aes, "orders", "v1", SecureRandom(), mapOf("service" to "orders"),
        )
        val rsaEnvelope = ProviderEnvelope.encrypt(
            plaintext, rsa, "orders", "v1", SecureRandom(), mapOf("service" to "orders"),
        )

        ProviderEnvelope.decrypt(
            aesEnvelope.ciphertext, aes, aesEnvelope.metadata, "orders", "v1",
            mapOf("service" to "orders"),
        ).contentEquals(plaintext).shouldBeTrue()
        ProviderEnvelope.decrypt(
            rsaEnvelope.ciphertext, rsa, rsaEnvelope.metadata, "orders", "v1",
            mapOf("service" to "orders"),
        ).contentEquals(plaintext).shouldBeTrue()
        aesEnvelope.metadata["bt4k-cek-provider"] shouldBeEqualTo "aes"
        rsaEnvelope.metadata["bt4k-cek-provider"] shouldBeEqualTo "rsa"
        aesEnvelope.metadata["bt4k-cek-wrap-nonce"].shouldNotBeNull()
        rsaEnvelope.metadata["bt4k-cek-wrap-nonce"] shouldBeNull()
    }

    @Test
    fun provider_envelope_rejects_context_mismatch_without_plaintext() {
        val material = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider.of(SecretKeySpec(ByteArray(32) { 10 }, "AES")),
        )
        val envelope = ProviderEnvelope.encrypt(
            "context-bound".encodeToByteArray(),
            material,
            "orders",
            "v1",
            SecureRandom(),
            mapOf("service" to "orders"),
        )

        assertFailsWith<S3ClientSideEncryptionException> {
            ProviderEnvelope.decrypt(
                envelope.ciphertext,
                material,
                envelope.metadata,
                "orders",
                "v1",
                mapOf("service" to "billing"),
            )
        }
    }

    @Test
    fun provider_metadata_validation_rejects_missing_malformed_and_colliding_fields() {
        val material = AesClientSideEncryptionKeyMaterial.from(
            S3AesProvider.of(SecretKeySpec(ByteArray(32) { 11 }, "AES")),
        )
        val envelope = ProviderEnvelope.encrypt(
            byteArrayOf(1, 2, 3), material, "orders", "v1", SecureRandom(), emptyMap(),
        )

        listOf(
            envelope.metadata - "bt4k-cek-version",
            envelope.metadata + ("bt4k-cek-nonce" to "not-base64"),
            envelope.metadata + ("bt4k-cek-nonce" to Base64.getEncoder().encodeToString(byteArrayOf(1))),
            envelope.metadata + ("bt4k-cek-wrap-alg" to "wrong"),
        ).forEach { metadata ->
            assertFailsWith<IllegalArgumentException> {
                ProviderEnvelope.decrypt(byteArrayOf(1), material, metadata, "orders", "v1", emptyMap())
            }
        }

        assertFailsWith<IllegalArgumentException> {
            ProviderEnvelope.mergeMetadata(
                mapOf("BT4K-CEK" to "caller-value"),
                envelope.metadata,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProviderEnvelope.mergeMetadata(
                mapOf("bt4k-cek" to "first", "BT4K-CEK" to "duplicate"),
                emptyMap(),
            )
        }
    }

    @Test
    fun rsa_material_rejects_mismatched_public_and_private_keys() {
        val publicPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val privatePair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

        assertFailsWith<IllegalArgumentException> {
            RsaClientSideEncryptionKeyMaterial.from(
                S3RsaProvider.of(KeyPair(publicPair.public, privatePair.private)),
            )
        }
    }

각 metadata 음성 fixture는 version/provider/content algorithm/wrap algorithm/encoding/key-id/
key-version/nonce/wrap-nonce/wrapped-key 중 하나를 제거·변조한 값을 parameterized case로
실행한다. upload reserved-key collision과 duplicate-key case는 MockK `putObject` 호출 횟수를
0으로 검증해 네트워크 호출 전에 실패하는지 확인하고, 오류 메시지에는 plaintext·raw key·Base64
wrapped key를 포함하지 않는다.

- [ ] Step 2: primitive 테스트가 구현 부재로 실패하는지 확인한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: FAIL with missing AesClientSideEncryptionKeyMaterial, RsaClientSideEncryptionKeyMaterial, or ProviderEnvelope.

- [ ] Step 3: 내부 key material과 오류 type을 구현한다.

    open class S3ClientSideEncryptionException(
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)

    internal sealed interface ClientSideEncryptionKeyMaterial : AutoCloseable {
        val providerToken: String
        val wrappingAlgorithm: String
        val keyIdentityMaterial: ByteArray
        fun wrap(dataKey: ByteArray, random: SecureRandom): WrappedDataKey
        fun unwrap(wrapped: ByteArray, nonce: ByteArray?): ByteArray
    }

    internal data class WrappedDataKey(
        val ciphertext: ByteArray,
        val nonce: ByteArray?,
    )

    internal data class ProviderEncryptedPayload(
        val ciphertext: ByteArray,
        val metadata: Map<String, String>,
    )

    internal data class ProviderStreamingEnvelope(
        val dataKey: ByteArray,
        val nonce: ByteArray,
        val aad: ByteArray,
        val metadata: Map<String, String>,
    )

    internal object ProviderEnvelope {
        fun encrypt(
            plaintext: ByteArray,
            material: ClientSideEncryptionKeyMaterial,
            keyId: String,
            keyVersion: String,
            random: SecureRandom,
            encryptionContext: Map<String, String>,
        ): ProviderEncryptedPayload

        fun decrypt(
            ciphertext: ByteArray,
            material: ClientSideEncryptionKeyMaterial,
            metadata: Map<String, String>,
            expectedKeyId: String,
            expectedKeyVersion: String,
            encryptionContext: Map<String, String>,
        ): ByteArray

        fun mergeMetadata(
            userMetadata: Map<String, String>,
            reservedMetadata: Map<String, String>,
        ): Map<String, String>

        fun newStreamingEnvelope(
            material: ClientSideEncryptionKeyMaterial,
            keyId: String,
            keyVersion: String,
            random: SecureRandom,
            encryptionContext: Map<String, String>,
        ): ProviderStreamingEnvelope

        fun canonicalContextAad(encryptionContext: Map<String, String>): ByteArray
    }

    internal class AesClientSideEncryptionKeyMaterial private constructor(
        private val keyBytes: ByteArray,
    ) : ClientSideEncryptionKeyMaterial {
        override val providerToken: String = "aes"
        override val wrappingAlgorithm: String = "AES/GCM/NoPadding"
        override val keyIdentityMaterial: ByteArray
            get() = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        override fun wrap(dataKey: ByteArray, random: SecureRandom): WrappedDataKey =
            aesGcmWrap(dataKey, keyBytes, random)
        override fun unwrap(wrapped: ByteArray, nonce: ByteArray?): ByteArray =
            aesGcmUnwrap(wrapped, keyBytes, requireNotNull(nonce))
        override fun close() = keyBytes.fill(0)

        companion object {
            fun from(provider: S3AesProvider): AesClientSideEncryptionKeyMaterial {
                val key = requireNotNull(provider.generateSecretKey()) {
                    "S3AesProvider returned null key."
                }
                require(key.algorithm.equals("AES", ignoreCase = true)) {
                    "AES provider key algorithm must be AES."
                }
                val encoded = requireNotNull(key.encoded) {
                    "AES provider key must expose encoded bytes."
                }
                require(encoded.size in setOf(16, 24, 32)) {
                    "AES provider key must be 16, 24, or 32 bytes. size=" + encoded.size
                }
                return AesClientSideEncryptionKeyMaterial(encoded.copyOf())
            }
        }
    }

    internal class RsaClientSideEncryptionKeyMaterial private constructor(
        private var publicKey: RSAPublicKey?,
        private var privateKey: RSAPrivateKey?,
    ) : ClientSideEncryptionKeyMaterial {
        override val providerToken: String = "rsa"
        override val wrappingAlgorithm: String =
            "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
        override val keyIdentityMaterial: ByteArray
            get() = requireNotNull(publicKey) { "RSA provider material is closed." }.encoded.copyOf()
        override fun wrap(dataKey: ByteArray, random: SecureRandom): WrappedDataKey =
            rsaOaepWrap(dataKey, requireNotNull(publicKey) { "RSA provider material is closed." })
        override fun unwrap(wrapped: ByteArray, nonce: ByteArray?): ByteArray =
            rsaOaepUnwrap(wrapped, requireNotNull(privateKey) { "RSA provider material is closed." })
        override fun close() {
            publicKey = null
            privateKey = null
        }

        companion object {
            fun from(provider: S3RsaProvider): RsaClientSideEncryptionKeyMaterial {
                val pair = requireNotNull(provider.generateKeyPair()) {
                    "S3RsaProvider returned null key pair."
                }
                require(pair.public.algorithm.equals("RSA", ignoreCase = true)) {
                    "RSA public key algorithm must be RSA."
                }
                require(pair.private.algorithm.equals("RSA", ignoreCase = true)) {
                    "RSA private key algorithm must be RSA."
                }
                val public = pair.public as? RSAPublicKey
                val private = pair.private as? RSAPrivateKey
                require(public != null && private != null && public.modulus.bitLength() >= 2048) {
                    "RSA provider public key must be at least 2048 bits."
                }
                require(public.modulus == private.modulus) {
                    "RSA provider public and private key modulus must match."
                }
                require(private.modulus.bitLength() >= 2048) {
                    "RSA provider private key must be at least 2048 bits."
                }
                return RsaClientSideEncryptionKeyMaterial(public, private)
            }
        }
    }

aesGcmWrap/aesGcmUnwrap는 12-byte nonce와 128-bit tag를 사용하고 RSA는 OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)로 초기화한다. ProviderEnvelope는 metadata를 소문자 key로 정규화하고 bt4k-cek-version=2, bt4k-cek-encoding=base64, payload/wrap nonce 길이와 wrapped key 길이를 검증한다. reserved metadata 이름은 ASCII와 `Locale.ROOT` 소문자 비교로 관리하고 사용자 metadata와 대소문자 무시 충돌·중복을 업로드 전에 거부한다. context AAD는 정렬된 key/value 각각의 UTF-8 길이를 4-byte big-endian prefix로 붙여 모호한 연결을 막는다. Cipher.doFinal의 GeneralSecurityException은 secret 값을 메시지에 넣지 않고 S3ClientSideEncryptionException으로 감싼다.

암호 primitive는 raw key/data-key를 호출자에게 반환하지 않는다. AES adapter는 key byte 복사본만 보유하고 `close()`에서 지우며, RSA adapter는 검증된 `RSAPublicKey`/`RSAPrivateKey` 참조를 nullable 상태로 폐기한다. `ProviderEnvelope.encrypt/decrypt`와 streaming cipher 생성은 data key, decoded wrapped key, nonce, AAD의 임시 배열을 각 성공·실패·취소 경로의 `finally`에서 `fill(0)`한다. zeroization은 JVM/JCE 내부 복사본이나 caller 소유 key 객체까지 지운다고 주장하지 않는다.

- [ ] Step 4: primitive GREEN 테스트와 정적 검증을 실행한다.

    git diff --check
    ./gradlew :bluetape4k-aws-spring-boot:compileKotlin --no-daemon --max-workers=1 --console=plain

Expected: targeted tests pass, git diff --check emits no output, and compileKotlin ends with BUILD SUCCESSFUL.

- [ ] Step 5: Lore commit을 만든다.

    git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviders.kt \
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTemplate.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt
    git commit -m "#475 provider key material과 envelope primitive를 고정한다" \
      -m "Constraint: JDK Cipher와 Bluetape 전용 metadata만 사용하고 AWS Encryption Client wire format은 복제하지 않는다.
    Rejected: 외부 key 객체 zeroization | caller 소유 메모리를 변경할 수 없다.
    Confidence: high
    Scope-risk: moderate
    Directive: provider template은 이 primitive를 통해서만 ciphertext를 생성한다.
    Tested: S3ClientSideEncryptionProviderTest, compileKotlin, git diff --check
    Not-tested: Spring 조건부 wiring과 emulator"

---

### Task 3: Provider template의 byte/bounded API, identity와 lifecycle

Files:
- Create: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTemplate.kt
- Modify: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt

- [ ] Step 1: MockK 기반 byte/bounded/identity/lifecycle RED 테스트를 추가한다.

    @Test
    fun provider_template_uploads_ciphertext_and_exposes_identity() = runSuspendIO {
        val client = mockk<S3AsyncClient>()
        every { client.putObject(any(), any()) } returns
            CompletableFuture.completedFuture(PutObjectResponse.builder().build())
        val template = S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = client,
            properties = S3Properties(
                clientSideEncryption = S3Properties.ClientSideEncryption(
                    enabled = true,
                    provider = ClientSideEncryptionProvider.AES,
                    keyId = "orders-key",
                    keyVersion = "v1",
                ),
            ),
            aesProvider = S3AesProvider.of(SecretKeySpec(ByteArray(32) { 8 }, "AES")),
        )

        template.uploadEncrypted("bucket", "object", "plain".encodeToByteArray())
        template.canonicalKeyIdentity shouldContain "orders-key"
        template.keyFingerprint.isNotBlank().shouldBeTrue()
    }

    @Test
    fun provider_template_rejects_provider_mismatch_without_plaintext() = runSuspendIO {
        val template = testProviderTemplate()
        val metadata = mapOf(
            "bt4k-cek-version" to "2",
            "bt4k-cek-provider" to "rsa",
            "bt4k-cek-alg" to "AES/GCM/NoPadding",
            "bt4k-cek-wrap-alg" to "RSA/ECB/OAEPWithSHA-1AndMGF1Padding",
            "bt4k-cek-encoding" to "base64",
        )
        assertFailsWith<IllegalArgumentException> {
            template.decryptProviderPayload(byteArrayOf(1), metadata, emptyMap())
        }
    }

    @Test
    fun provider_template_rejects_operations_after_close() = runSuspendIO {
        val template = testProviderTemplate()
        template.close()
        assertFailsWith<IllegalStateException> {
            template.uploadEncrypted("bucket", "key", byteArrayOf(1))
        }
    }

- [ ] Step 2: RED 결과를 확인한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: FAIL because provider template methods and metadata-backed S3 response fixture are not implemented.

- [ ] Step 3: S3 provider template을 구현한다.

    class S3ClientSideEncryptionProviderTemplate(
        private val s3AsyncClient: S3AsyncClient,
        private val properties: S3Properties,
        aesProvider: S3AesProvider? = null,
        rsaProvider: S3RsaProvider? = null,
        private val random: SecureRandom = SecureRandom(),
    ) : S3BoundedEncryptedReadOperations,
        S3ClientSideEncryptionIdentity,
        AutoCloseable {
        private val material = when (properties.clientSideEncryption.provider) {
            ClientSideEncryptionProvider.AES ->
                AesClientSideEncryptionKeyMaterial.from(
                    requireNotNull(aesProvider) {
                        "S3AesProvider is required when provider=AES."
                    },
                )
            ClientSideEncryptionProvider.RSA ->
                RsaClientSideEncryptionKeyMaterial.from(
                    requireNotNull(rsaProvider) {
                        "S3RsaProvider is required when provider=RSA."
                    },
                )
            ClientSideEncryptionProvider.KMS ->
                error("KMS provider must use S3ClientSideEncryptionTemplate.")
        }
        private var closed = false

        override suspend fun uploadEncrypted(
            bucket: String,
            key: String,
            bytes: ByteArray,
            contentType: String?,
            metadata: Map<String, String>,
            encryptionContext: Map<String, String>,
        ): PutObjectResponse {
            checkOpen()
            bucket.requireNotBlank("bucket")
            key.requireNotBlank("key")
            val envelope = newEncryptionEnvelope(bytes, encryptionContext)
            return s3AsyncClient.putObject(
                { builder ->
                    builder.bucket(bucket).key(key)
                    contentType?.let(builder::contentType)
                    builder.metadata(
                        ProviderEnvelope.mergeMetadata(metadata, envelope.metadata),
                    )
                },
                AsyncRequestBody.fromBytes(envelope.ciphertext),
            ).await()
        }

        override suspend fun downloadEncryptedBytes(
            bucket: String,
            key: String,
            encryptionContext: Map<String, String>,
        ): ByteArray {
            checkOpen()
            val response = s3AsyncClient.getObject(
                { builder -> builder.bucket(bucket).key(key) },
                AsyncResponseTransformer.toBytes(),
            ).await()
            return decryptProviderPayload(
                response.asByteArray(),
                response.response().metadata(),
                encryptionContext,
            )
        }

        internal fun decryptProviderPayload(
            ciphertext: ByteArray,
            metadata: Map<String, String>,
            encryptionContext: Map<String, String> = emptyMap(),
        ): ByteArray {
            checkOpen()
            return ProviderEnvelope.decrypt(
                ciphertext,
                material,
                metadata,
                effectiveKeyIdentity(),
                effectiveKeyVersion(),
                effectiveEncryptionContext(encryptionContext),
            )
        }

        override val canonicalKeyIdentity: String
            get() = "bluetape4k.s3.cse/" + material.providerToken + "/" +
                effectiveKeyIdentity() + "/" + effectiveKeyVersion()

        override val keyFingerprint: String
            get() = sha256Url(canonicalKeyIdentity + encryptionContextForIdentity())

        private fun effectiveEncryptionContext(
            callContext: Map<String, String>,
        ): Map<String, String> =
            properties.clientSideEncryption.encryptionContext + callContext

        override fun close() {
            if (!closed) {
                closed = true
                material.close()
            }
        }
    }

downloadEncryptedBytesBounded는 기존 KMS 구현의 AsyncResponseTransformer.toPublisher와 SqsExtendedPayloadReadException 경계를 그대로 사용하고 ciphertext를 모두 모은 뒤 metadata 검증과 doFinal을 수행한다. chunk를 받을 때마다 `maxCiphertextBytes`를 검사하고 `max + 1` byte를 할당하기 전에 publisher를 취소한다. effectiveKeyIdentity는 설정 keyId 또는 provider public material SHA-256의 sha256:base64url 값이며 keyVersion은 설정 값 또는 빈 token이다. identity fingerprint에는 정렬된 encryption context를 포함하고, provider envelope에는 effective context의 canonical AAD byte만 전달하며 context 평문은 전달하지 않는다.

Provider template은 transfer adapter가 사용할 다음 internal helper도 제공한다. newEncryptionEnvelope는 새 32-byte data key와 12-byte payload nonce를 만들고 wrapped key metadata를 반환하며, newStreamingEnvelope는 같은 metadata와 effective context AAD를 반환한다. newPayloadCipher는 data key와 nonce로 AES/GCM/NoPadding cipher를 초기화하고 `updateAAD(envelope.aad)`를 호출한다. helper와 material은 모두 checkOpen 뒤에만 호출하며 envelope의 data key/AAD 임시 배열은 cipher 생성 직후 zeroize한다.

    internal fun newEncryptionEnvelope(
        plaintext: ByteArray,
        encryptionContext: Map<String, String> = emptyMap(),
    ): ProviderEncryptedPayload = ProviderEnvelope.encrypt(
        plaintext,
        material,
        effectiveKeyIdentity(),
        effectiveKeyVersion(),
        random,
        effectiveEncryptionContext(encryptionContext),
    )
    internal fun newStreamingEnvelope(
        encryptionContext: Map<String, String> = emptyMap(),
    ): ProviderStreamingEnvelope = ProviderEnvelope.newStreamingEnvelope(
        material,
        effectiveKeyIdentity(),
        effectiveKeyVersion(),
        random,
        effectiveEncryptionContext(encryptionContext),
    )
    internal fun newPayloadCipher(envelope: ProviderStreamingEnvelope): Cipher =
        payloadCipher(envelope.dataKey, envelope.nonce).also { it.updateAAD(envelope.aad) }

- [ ] Step 4: byte/bounded/identity/lifecycle GREEN과 회귀 테스트를 실행한다.

bounded 테스트에는 `MAX_CIPHERTEXT_BYTES` 정확히 허용, `max + 1` 즉시 거부·publisher 취소,
취소 중 output buffer/임시 배열 정리, metadata 인증 실패 시 partial plaintext 미반환을 포함한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3CoroutinesTemplateAwsEmulatorTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: provider unit tests and existing KMS emulator test pass; no KMS source behavior is changed.

- [ ] Step 5: Lore commit을 만든다.

    git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTemplate.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTest.kt
    git commit -m "#475 provider template의 bounded byte 경계를 구현한다" \
      -m "Constraint: S3BoundedEncryptedReadOperations와 S3ClientSideEncryptionIdentity 계약을 유지한다.
    Rejected: 기존 KMS template 수정으로 provider를 흡수 | KMS metadata와 byte 경계를 깨뜨릴 위험이 있다.
    Confidence: high
    Scope-risk: moderate
    Directive: 모든 provider plaintext는 GCM doFinal 성공 뒤에만 반환한다.
    Tested: provider unit tests, KMS emulator regression
    Not-tested: Spring 조건부 wiring과 transfer"


---

### Task 4: Spring auto-configuration 선택, missing/ambiguous 오류, backoff

Files:
- Modify: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt
- Modify: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfigurationTest.kt

- [ ] Step 1: provider 조건과 lifecycle의 RED context tests를 추가한다.

    @Test
    fun aes_provider_selection_ignores_available_kms() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.s3.client-side-encryption.enabled=true",
                "bluetape4k.aws.s3.client-side-encryption.provider=aes",
            )
            .withBean(S3AesProvider::class.java) {
                S3AesProvider.of(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
            }
            .withBean(KmsOperations::class.java) { FixedKmsOperations }
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(S3ClientSideEncryptionProviderTemplate::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(S3ClientSideEncryptionTemplate::class.java).size shouldBeEqualTo 0
            }
    }

    @Test
    fun selected_provider_without_bean_fails_startup() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.s3.client-side-encryption.enabled=true",
                "bluetape4k.aws.s3.client-side-encryption.provider=rsa",
            )
            .run { context ->
                val failure = context.startupFailure.shouldNotBeNull()
                val messages = generateSequence(failure) { it.cause }
                    .mapNotNull(Throwable::getMessage)
                    .joinToString("\n")
                messages shouldContain "S3RsaProvider"
                messages shouldContain "client-side-encryption.provider"
            }
    }

    @Test
    fun two_selected_provider_beans_fail_instead_of_choosing_one() {
        contextRunner
            .withPropertyValues(
                "bluetape4k.aws.s3.client-side-encryption.enabled=true",
                "bluetape4k.aws.s3.client-side-encryption.provider=aes",
            )
            .withBean("firstAes", S3AesProvider::class.java) {
                S3AesProvider.of(SecretKeySpec(ByteArray(32) { 2 }, "AES"))
            }
            .withBean("secondAes", S3AesProvider::class.java) {
                S3AesProvider.of(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
            }
            .run { context ->
                context.startupFailure.shouldNotBeNull()
                context.startupFailure!!.message shouldContain "exactly one"
            }
    }

- [ ] Step 2: 조건 구현 부재의 실패를 확인한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: FAIL in new provider selection tests; existing KMS tests remain the baseline reference.

- [ ] Step 3: provider selection과 transfer-ready bean을 구현한다.

S3AutoConfiguration에 provider 값을 대소문자 무시해 읽는 ConditionalOnS3CseProvider condition을 추가한다. condition은 environment에서 bluetape4k.aws.s3.client-side-encryption.provider를 읽고 누락 값은 KMS로 해석한다. 각 method는 enabled=true와 ConditionalOnMissingBean(S3ClientSideEncryptionOperations)를 사용한다. AES/RSA provider template만 key material을 소유하므로 `destroyMethod = "close"`를 지정하고, 기존 KMS template은 S3 client나 KMS resource를 소유하지 않으므로 destroy method를 추가하지 않는다.

    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    @Conditional(S3CseProviderCondition::class)
    annotation class ConditionalOnS3CseProvider(
        val value: ClientSideEncryptionProvider,
    )

    internal class S3CseProviderCondition : Condition {
        override fun matches(
            context: ConditionContext,
            metadata: AnnotatedTypeMetadata,
        ): Boolean {
            val attributes = metadata.getAnnotationAttributes(
                ConditionalOnS3CseProvider::class.java.name,
            ) ?: return false
            val requested = attributes["value"] as ClientSideEncryptionProvider
            val configured = context.environment
                .getProperty("bluetape4k.aws.s3.client-side-encryption.provider")
                ?.trim()
                ?.uppercase()
                ?: ClientSideEncryptionProvider.KMS.name
            return configured == requested.name
        }
    }

    @Bean
    @ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnS3CseProvider(ClientSideEncryptionProvider.KMS)
    fun s3KmsClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        kmsOperations: ObjectProvider<KmsOperations>,
        properties: S3Properties,
    ): S3ClientSideEncryptionOperations =
        S3ClientSideEncryptionTemplate(
            s3AsyncClient,
            kmsOperations.getIfUnique() ?: error(
                "KmsOperations is required exactly once when provider=KMS.",
            ),
            properties,
        )

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnS3CseProvider(ClientSideEncryptionProvider.AES)
    fun s3AesClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        aesProvider: ObjectProvider<S3AesProvider>,
        properties: S3Properties,
    ): S3ClientSideEncryptionProviderTemplate =
        S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = s3AsyncClient,
            properties = properties,
            aesProvider = aesProvider.getIfUnique() ?: error(
                "S3AesProvider is required exactly once when provider=AES.",
            ),
        )

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3ClientSideEncryptionOperations::class)
    @ConditionalOnProperty(
        prefix = "bluetape4k.aws.s3.client-side-encryption",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnS3CseProvider(ClientSideEncryptionProvider.RSA)
    fun s3RsaClientSideEncryptionOperations(
        s3AsyncClient: S3AsyncClient,
        rsaProvider: ObjectProvider<S3RsaProvider>,
        properties: S3Properties,
    ): S3ClientSideEncryptionProviderTemplate =
        S3ClientSideEncryptionProviderTemplate(
            s3AsyncClient = s3AsyncClient,
            properties = properties,
            rsaProvider = rsaProvider.getIfUnique() ?: error(
                "S3RsaProvider is required exactly once when provider=RSA.",
            ),
        )

getIfUnique가 null이면 zero/multiple candidate를 동일하게 임의 선택하지 않고 provider name과 property를 담은 명시적 IllegalStateException으로 실패시킨다. provider transfer adapter는 provider template, S3TransferOperations, S3OutputStreamProvider가 모두 있을 때만 등록하고 KMS template에는 등록하지 않는다.

    @Bean
    @ConditionalOnBean(
        value = [
            S3ClientSideEncryptionProviderTemplate::class,
            S3TransferOperations::class,
            S3OutputStreamProvider::class,
        ],
    )
    @ConditionalOnMissingBean(S3ClientSideEncryptionTransferOperations::class)
    fun s3ClientSideEncryptionTransferOperations(
        providerTemplate: S3ClientSideEncryptionProviderTemplate,
        transferOperations: S3TransferOperations,
        outputStreamProvider: S3OutputStreamProvider,
    ): S3ClientSideEncryptionTransferOperations =
        S3ClientSideEncryptionTransferTemplate(
            providerTemplate,
            transferOperations,
            outputStreamProvider,
        )

- [ ] Step 4: context tests와 KMS 회귀를 GREEN으로 만든다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3CoroutinesTemplateAwsEmulatorTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: provider selection, missing/ambiguous/backoff tests and all existing KMS tests pass with BUILD SUCCESSFUL.

- [ ] Step 5: Lore commit을 만든다.

    git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfiguration.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfigurationTest.kt
    git commit -m "#475 Spring provider 선택과 KMS backoff를 고정한다" \
      -m "Constraint: enabled=false와 custom S3ClientSideEncryptionOperations backoff을 유지한다.
    Rejected: KMS bean 존재 여부로 AES/RSA를 자동 대체 | 명시 provider 설정의 안정성을 훼손한다.
    Confidence: high
    Scope-risk: moderate
    Directive: provider candidate는 항상 unique 여부를 검증하고 임의 선택하지 않는다.
    Tested: S3AutoConfigurationTest, KMS emulator regression
    Not-tested: transfer stream"

---

### Task 5: Typed encrypted object와 transfer API

Files:
- Create: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionObjectExtensions.kt
- Create: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionTransferOperations.kt
- Modify: aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTemplate.kt
- Create: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionObjectExtensionsTest.kt
- Create: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionTransferTest.kt

- [ ] Step 1: converter extension과 ciphertext-only stream의 RED 테스트를 작성한다.

    @Test
    fun typed_encrypted_extensions_reuse_converter() = runSuspendIO {
        val converter = RecordingObjectConverter()
        val operations = FakeEncryptedOperations(byteArrayOf(4, 5, 6))
        val value = mapOf("name" to "bluetape")

        operations.uploadEncryptedObject("bucket", "object", value, converter)
        operations.downloadEncryptedObject(
            "bucket", "object", Map::class.java as Class<Map<String, String>>, converter,
        ) shouldBeEqualTo value
        converter.writes shouldBeEqualTo 1
        converter.reads shouldBeEqualTo 1
    }

    @Test
    fun encrypted_stream_never_sends_plaintext_to_delegate() = runSuspendIO {
        val delegate = RecordingS3OutputStreamProvider(thresholdBytes = 1)
        val template = testProviderTemplate(
            aesProvider = S3AesProvider.of(SecretKeySpec(ByteArray(32) { 6 }, "AES")),
        )
        val encrypted = S3EncryptedOutputStream.create(
            template = template,
            outputStreamProvider = delegate,
            bucket = "bucket",
            key = "large.bin",
            contentType = "application/octet-stream",
            metadata = emptyMap(),
            encryptionContext = mapOf("service" to "orders"),
        )

        encrypted.use { it.write("plaintext that crosses threshold".encodeToByteArray()) }
        delegate.uploadedFileContents.single().decodeToString() shouldNotBeEqualTo
            "plaintext that crosses threshold"
    }

RecordingS3OutputStreamProvider는 주입한 `Path`를 temp directory로 사용하고 spill 전후에
delegate가 받은 모든 chunk를 보존한다. failing delegate는 `complete()` 또는 `close()`에서
지정한 오류를 던지도록 만들어 성공·실패·취소 후 directory가 비고 plaintext byte subsequence가
어느 chunk에도 없음을 검증한다. `downloadEncryptedFile` 테스트는 기존 destination을 sentinel
내용으로 만든 뒤 tag/metadata를 변조하고, 인증 실패 후 sentinel이 그대로이며 새 destination은
생기지 않는지 확인한다. ciphertext 크기가 `MAX_CIPHERTEXT_BYTES + 1`이면 임시 파일을
plaintext로 읽기 전에 거부하고 destination과 임시 경로를 변경하지 않는지도 검증한다.

- [ ] Step 2: RED 확인 명령을 실행한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionObjectExtensionsTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: FAIL with missing extension, transfer interface, or encrypted stream symbols.

- [ ] Step 3: typed extension을 구현한다.

    suspend fun <T : Any> S3ClientSideEncryptionOperations.uploadEncryptedObject(
        bucket: String,
        key: String,
        value: T,
        converter: S3ObjectConverter<T>,
        contentType: String? = null,
        metadata: Map<String, String> = emptyMap(),
        encryptionContext: Map<String, String> = emptyMap(),
    ): PutObjectResponse =
        uploadEncrypted(
            bucket,
            key,
            converter.write(value),
            contentType ?: converter.contentType,
            metadata,
            encryptionContext,
        )

    suspend fun <T : Any> S3ClientSideEncryptionOperations.downloadEncryptedObject(
        bucket: String,
        key: String,
        targetType: Class<T>,
        converter: S3ObjectConverter<T>,
        encryptionContext: Map<String, String> = emptyMap(),
    ): T = converter.read(downloadEncryptedBytes(bucket, key, encryptionContext), targetType)

- [ ] Step 4: transfer 계약과 stream 구현을 추가한다.

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

`complete()`는 `kotlinx.coroutines.sync.Mutex`를 단일 completion owner로 사용하고
`write`는 별도 state lock에서 terminal 시작 이후를 거부한다. 따라서 concurrent
`complete/close`는 첫 completion을 기다린 뒤 no-op이 되고, 실패 후에도 delegate close와
state 전이가 정확히 한 번 일어난다.

    class S3EncryptedOutputStream internal constructor(
        private val delegate: S3OutputStream,
        private val cipher: Cipher,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : OutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(stateLock) {
                check(!terminalStarted) { "S3EncryptedOutputStream is already closed." }
                cipher.update(b, off, len)?.let(delegate::write)
            }
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()))

        suspend fun complete() = withContext(ioDispatcher) { completeOnIo() }

        override fun close() = runBlocking { complete() }

        private suspend fun completeOnIo() = completionMutex.withLock {
            synchronized(stateLock) {
                if (completed) return@withLock
                check(!terminalStarted)
                terminalStarted = true
            }
            try {
                cipher.doFinal()?.let(delegate::write)
                delegate.complete()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + ioDispatcher) { runCatching { delegate.close() } }
                throw cancelled
            } catch (error: Throwable) {
                withContext(NonCancellable + ioDispatcher) { runCatching { delegate.close() } }
                throw error
            } finally {
                synchronized(stateLock) { completed = true }
            }
        }

        private val completionMutex = Mutex()
        private val stateLock = Any()
        private var terminalStarted: Boolean = false
        private var completed: Boolean = false

        companion object {
            internal fun create(
                template: S3ClientSideEncryptionProviderTemplate,
                outputStreamProvider: S3OutputStreamProvider,
                bucket: String,
                key: String,
                contentType: String?,
                metadata: Map<String, String>,
                encryptionContext: Map<String, String>,
                ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            ): S3EncryptedOutputStream {
                val envelope = template.newStreamingEnvelope(encryptionContext)
                var delegate: S3OutputStream? = null
                try {
                    val created = outputStreamProvider.outputStream(
                        bucket,
                        key,
                        contentType,
                        ProviderEnvelope.mergeMetadata(metadata, envelope.metadata),
                    )
                    delegate = created
                    return S3EncryptedOutputStream(
                        created,
                        template.newPayloadCipher(envelope),
                        ioDispatcher = ioDispatcher,
                    )
                } catch (cancelled: CancellationException) {
                    runCatching { delegate?.close() }
                    throw cancelled
                } catch (error: Throwable) {
                    runCatching { delegate?.close() }
                    throw error
                } finally {
                    envelope.dataKey.fill(0)
                    envelope.nonce.fill(0)
                    envelope.aad.fill(0)
                }
            }
        }
    }

S3ClientSideEncryptionTransferTemplate는 provider template의 `newStreamingEnvelope`로 reserved metadata를 먼저 만들고 S3OutputStreamProvider.outputStream을 ciphertext delegate로 연다. downloadEncryptedFile은 S3TransferOperations.downloadFile로 Files.createTempFile 경로에 ciphertext만 받은 뒤 response metadata를 provider template에 전달한다. ProviderEnvelope.decrypt가 성공한 후에만 Files.write(destination, plaintext)를 실행하고 ciphertext 임시 경로는 성공·실패·취소 모두 finally에서 삭제한다. transfer adapter는 provider CSE가 선택되고 S3TransferOperations와 S3OutputStreamProvider가 모두 있을 때만 auto-configuration한다.

    class S3ClientSideEncryptionTransferTemplate(
        private val providerTemplate: S3ClientSideEncryptionProviderTemplate,
        private val transferOperations: S3TransferOperations,
        private val outputStreamProvider: S3OutputStreamProvider,
        internal val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : S3ClientSideEncryptionTransferOperations {
        override fun encryptedOutputStream(
            bucket: String,
            key: String,
            contentType: String?,
            metadata: Map<String, String>,
            encryptionContext: Map<String, String>,
        ): S3EncryptedOutputStream {
            return S3EncryptedOutputStream.create(
                template = providerTemplate,
                outputStreamProvider = outputStreamProvider,
                bucket = bucket,
                key = key,
                contentType = contentType,
                metadata = metadata,
                encryptionContext = encryptionContext,
                ioDispatcher = ioDispatcher,
            )
        }

        override suspend fun downloadEncryptedFile(
            bucket: String,
            key: String,
            destination: Path,
            encryptionContext: Map<String, String>,
        ) {
            val temporary = withContext(ioDispatcher) {
                Files.createTempFile("bluetape-s3-cse-", ".ciphertext")
            }
            try {
                val completed = transferOperations.downloadFile(bucket, key, temporary)
                val plaintext = withContext(ioDispatcher) {
                    val size = Files.size(temporary)
                    require(size <= S3BoundedEncryptedReadOperations.MAX_CIPHERTEXT_BYTES) {
                        "Encrypted S3 object exceeds max ciphertext size: $size"
                    }
                    providerTemplate.decryptProviderPayload(
                        Files.readAllBytes(temporary),
                        completed.response().metadata(),
                        encryptionContext,
                    )
                }
                withContext(ioDispatcher) { Files.write(destination, plaintext) }
            } finally {
                withContext(NonCancellable + ioDispatcher) {
                    Files.deleteIfExists(temporary)
                }
            }
        }
    }

- [ ] Step 5: transfer/typed GREEN과 no-plaintext 테스트를 실행한다.

스트리밍 테스트는 다음 terminal 경계를 모두 고정한다: 빈 stream의 `complete()` logical EOF에서 tag와 delegate completion이 한 번만 발생하는지, `complete()`의 `doFinal`이 한 번만 호출되는지, `complete()`와 `close()`의 double terminal call이 idempotent한지, terminal 이후 `write`가 거절되는지, concurrent `complete/close`가 exactly-once인지, ciphertext를 잘라낸 truncated final input이 인증 실패하고 destination을 만들지 않는지, `CancellationException`이 그대로 전파되면서 delegate와 임시 파일이 정리되는지. broad `Throwable` cleanup은 cancellation을 새 예외로 감싸지 않고 원래 instance를 다시 throw한다.

    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionObjectExtensionsTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3OutputStreamTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: all selected tests pass; recording delegate sees ciphertext and its temporary directory is empty after completion, authentication failure, or cancellation. The stream tests prove logical EOF, truncated final input, post-terminal reuse, and double-terminal behavior.

- [ ] Step 6: Lore commit을 만든다.

    git add aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionObjectExtensions.kt \
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionTransferOperations.kt \
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderTemplate.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionObjectExtensionsTest.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionTransferTest.kt
    git commit -m "#475 typed object와 ciphertext streaming 경계를 추가한다" \
      -m "Constraint: 기존 S3ObjectConverter와 S3OutputStream lifecycle을 재사용한다.
    Rejected: plaintext를 S3OutputStream에 먼저 전달 | threshold 초과 시 OS 임시 파일에 평문이 남는다.
    Confidence: high
    Scope-risk: broad
    Directive: authenticated decrypt 성공 전 destination에 평문을 쓰지 않는다.
    Tested: typed/transfer/output stream unit tests
    Not-tested: Floci acceptance"


---

### Task 6: Floci acceptance와 KMS 회귀

Files:
- Create: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderAwsEmulatorTest.kt
- Modify: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfigurationTest.kt
- Modify: aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3CoroutinesTemplateAwsEmulatorTest.kt

- [ ] Step 1: Floci acceptance RED 테스트를 추가한다.

새 test class는 기존 AwsSpringBootTestEmulator.get("s3"), getCredentialProvider(), path-style-access-enabled=true 패턴을 사용하고 `@Execution(ExecutionMode.SAME_THREAD)`로 병렬 실행하지 않는다. 각 bucket/key에는 issue-475와 생성한 owner-token을 넣는다. 이 module의 Floci acceptance는 `--max-workers=1` 단일 Gradle lane에서만 실행하고, 별도 emulator lane과 동시 실행하지 않는 운영 계약을 lesson에 기록한다.

    @Test
    fun aes_provider_round_trips_byte_typed_and_transfer_payload() =
        contextRunner(ClientSideEncryptionProvider.AES).run { context ->
            val encrypted = context.getBean(S3ClientSideEncryptionOperations::class.java)
            val transfer = context.getBean(S3ClientSideEncryptionTransferOperations::class.java)
            val bucket = ownerBucket("aes")
            context.getBean(S3Client::class.java).createBucket { it.bucket(bucket) }

            runSuspendIO {
                encrypted.uploadEncrypted(
                    bucket,
                    "issue-475/aes.txt",
                    "aes payload".encodeToByteArray(),
                )
                encrypted.downloadEncryptedText(
                    bucket,
                    "issue-475/aes.txt",
                ) shouldBeEqualTo "aes payload"

                val converter = JacksonS3ObjectConverter(tools.jackson.databind.ObjectMapper())
                val typed = mapOf("issue" to 475, "provider" to "aes")
                encrypted.uploadEncryptedObject(bucket, "issue-475/aes.json", typed, converter)
                encrypted.downloadEncryptedObject(
                    bucket,
                    "issue-475/aes.json",
                    Map::class.java as Class<Map<String, Any>>,
                    converter,
                ) shouldBeEqualTo typed

                transfer.encryptedOutputStream(bucket, "issue-475/aes-stream.bin").use {
                    it.write(ByteArray(32 * 1024) { 0x41 })
                }
                val destination = tempDir.resolve("aes-download.bin")
                transfer.downloadEncryptedFile(bucket, "issue-475/aes-stream.bin", destination)
                Files.readAllBytes(destination).all { it == 0x41.toByte() }.shouldBeTrue()
            }
        }

    @Test
    fun rsa_provider_stores_ciphertext_and_round_trips_its_own_key() =
        contextRunner(ClientSideEncryptionProvider.RSA).run { context ->
            val encrypted = context.getBean(S3ClientSideEncryptionOperations::class.java)
            val bucket = ownerBucket("rsa")
            context.getBean(S3Client::class.java).createBucket { it.bucket(bucket) }

            runSuspendIO {
                encrypted.uploadEncrypted(
                    bucket,
                    "issue-475/rsa.bin",
                    "rsa payload".encodeToByteArray(),
                )
                encrypted.downloadEncryptedText(
                    bucket,
                    "issue-475/rsa.bin",
                ) shouldBeEqualTo "rsa payload"
                val stored = context.getBean(S3Client::class.java).getObjectAsBytes {
                    it.bucket(bucket).key("issue-475/rsa.bin")
                }
                stored.asUtf8String() shouldNotBeEqualTo "rsa payload"
                stored.response().metadata()["bt4k-cek-provider"] shouldBeEqualTo "rsa"
            }
        }

- [ ] Step 2: emulator RED 실행을 확인한다.

    DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderAwsEmulatorTest' \
      --no-daemon --max-workers=1 --console=plain

Expected before implementation: FAIL from missing provider context or transfer bean; no test may silently skip the Floci container.

- [ ] Step 3: test fixtures와 provider beans를 완성한다.

AES fixture는 고정 32-byte SecretKeySpec, RSA fixture는 KeyPairGenerator("RSA").initialize(2048) 결과를 context에 등록한다. contextRunner(provider)는 provider, key-id, key-version, endpoint, credentials를 함께 넣고 S3TransferAutoConfiguration을 포함한다. 테스트 종료 시 owner bucket/object만 제거하고 emulator 공유 자원은 재시작하지 않는다.

- [ ] Step 4: exact acceptance와 기존 KMS suite를 실행한다.

    DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderAwsEmulatorTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3AutoConfigurationTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3CoroutinesTemplateAwsEmulatorTest' \
      --no-daemon --max-workers=1 --console=plain

Expected: AES/RSA byte·typed·stream/file round-trip, ciphertext metadata, mismatch/no-plaintext, auto-config, and KMS regression all pass with BUILD SUCCESSFUL.

Acceptance에는 다음 인증 경계도 포함한다: 업로드 context와 다른 context로 byte/stream/file을
복호화하면 `S3ClientSideEncryptionException`이 발생하고 plaintext/destination을 만들지 않으며,
metadata의 provider·algorithm·version·key ID·key version을 바꾸거나 ciphertext tag를 자르면
동일하게 실패한다. 실패한 다운로드의 기존 destination 내용은 변경되지 않고 ciphertext 임시
경로는 제거된다.

- [ ] Step 5: Lore commit을 만든다.

    git add aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3ClientSideEncryptionProviderAwsEmulatorTest.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3AutoConfigurationTest.kt \
      aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/s3/S3CoroutinesTemplateAwsEmulatorTest.kt
    git commit -m "#475 Floci에서 AES RSA CSE acceptance를 검증한다" \
      -m "Constraint: 공유 Colima/Floci 자원은 sequential test와 owner-token으로 격리한다.
    Rejected: 실제 AWS 계정 검증 | issue 범위와 재현성 경계를 벗어난다.
    Confidence: high
    Scope-risk: broad
    Directive: emulator failure는 skip이 아니라 환경 또는 구현 오류로 분류한다.
    Tested: provider Floci acceptance, auto-config, KMS regression
    Not-tested: module-wide detekt"

---

### Task 7: README/manual/KDoc 문서 parity

Files:
- Modify: aws-spring-boot/README.md
- Modify: aws-spring-boot/README.ko.md
- Modify: docs/manual/en/modules/bluetape4k-aws-spring-boot.md
- Modify: docs/manual/ko/modules/bluetape4k-aws-spring-boot.md
- Modify: KDoc in new/modified Kotlin files

- [ ] Step 1: 문서에서 기존 KMS-only 문구를 찾고 parity 기준을 고정한다.

    rg -n -C 3 'client-side encryption|client-side-encryption|KmsOperations|multipart|streaming' \
      aws-spring-boot/README.md aws-spring-boot/README.ko.md \
      docs/manual/en/modules/bluetape4k-aws-spring-boot.md \
      docs/manual/ko/modules/bluetape4k-aws-spring-boot.md

Expected: current KMS byte-array-only paragraph와 configuration sample 위치가 출력되고 영어/한국어 문서의 같은 section/anchor 수정 대상을 확정한다.

- [ ] Step 2: 문서 변경을 작성한다.

각 locale의 S3 section에 다음 configuration을 넣는다.

    bluetape4k:
      aws:
        s3:
          client-side-encryption:
            enabled: true
            provider: aes
            key-id: orders-key
            key-version: 2026-08
            encryption-context:
              service: order-api

문서에는 S3AesProvider/S3RsaProvider bean이 선택 provider와 정확히 하나여야 한다는 점, KMS 기본값이 기존 byte API를 유지한다는 점, provider streaming이 ciphertext-only 임시 파일과 authenticated destination write를 사용한다는 점, S3ClientSideEncryptionException/identity mismatch, AWS Encryption Client wire compatibility를 약속하지 않는다는 점, 실제 key rotation/storage/HSM을 제공하지 않는다는 점을 적는다. KDoc에는 public method의 bucket/key/provider/close 계약과 실패 시 평문 미반환을 명시한다. `complete()`는 권장하는 suspend 경로이고 `close()`는 `runBlocking`을 사용한 blocking 호환 경로임을 분명히 하며, bulk `write(ByteArray, off, len)`가 기본이고 작은 `write(Int)` 반복에는 allocation 비용이 있음을 기록한다.

- [ ] Step 3: writer gate와 문서 검증을 실행한다.

    git diff --check
    ruby scripts/manual/manual_contract_test.rb
    ruby scripts/manual/export_manifest.rb docs/manual/manifest.yaml docs/manual/generated/manifest.json --check

Expected: all commands succeed; English/Korean manual headings, anchors, API names, links, and configuration keys remain aligned.

- [ ] Step 4: Lore commit을 만든다.

    git add aws-spring-boot/README.md aws-spring-boot/README.ko.md \
      docs/manual/en/modules/bluetape4k-aws-spring-boot.md \
      docs/manual/ko/modules/bluetape4k-aws-spring-boot.md \
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3
    git commit -m "#475 S3 provider 사용법과 경계를 문서화한다" \
      -m "Constraint: 영어/한국어 manual 구조와 releaseRef 계약을 보존한다.
    Rejected: README에 전체 manual을 중복 | 상세 설명은 docs/manual source of truth에 둔다.
    Confidence: high
    Scope-risk: moderate
    Directive: wire compatibility와 HSM/rotation 보장을 주장하지 않는다.
    Tested: diff check, manual contract, manifest check
    Not-tested: releaseRef validation"

---

### Task 8: 통합 검증, lesson, Type A handoff

Files:
- Create: docs/lessons/2026-08-27-issue-475-s3-cse-providers.md
- No further production edits after verification unless a failing check identifies a concrete regression.

- [ ] Step 1: module-wide verification을 순차 실행한다.

    ./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --max-workers=1 --console=plain
    ./gradlew detekt --no-daemon --max-workers=1 --console=plain
    git diff --check

Floci를 사용하는 module test가 Docker socket을 상속하지 않으면 다음 명시적 환경으로 동일 test를 재실행한다.

    DOCKER_HOST=unix:///Users/debop/.colima/default/docker.sock \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    ./gradlew :bluetape4k-aws-spring-boot:test --no-daemon --max-workers=1 --console=plain

Expected: test와 detekt가 BUILD SUCCESSFUL, git diff --check 무출력. 실패한 test는 이름·stack trace·환경 원인을 lesson에 기록하고 해결 전 완료 상태로 표시하지 않는다.

- [ ] Step 1A: Step 4-P 성능·안정성 검토를 fresh diff에 대해 수행한다.

`references/performance-stability-scan.md` 기준으로 provider 변경 파일과 transfer stream 경계를 읽고 다음을 기록한다.

    rg -n "runBlocking|withContext|Files\\.(createTempFile|readAllBytes|write)|readAllBytes|doFinal|SecureRandom|Cipher" \
      aws-spring-boot/src/main/kotlin/io/bluetape4k/aws/spring/s3
    ./gradlew :bluetape4k-aws-spring-boot:test \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionTransferTest' \
      --tests 'io.bluetape4k.aws.spring.s3.S3ClientSideEncryptionProviderAwsEmulatorTest' \
      --no-daemon --max-workers=1 --console=plain

Review evidence must address per-object data-key allocation, RSA key wrapping cost, bounded ciphertext buffering, IO dispatcher boundaries, cancellation propagation, provider close/cleanup, and sequential Floci/Testcontainers use. A repeatable benchmark is not required for this feature; record benchmark as N/A with the reason that no throughput target or representative production payload distribution was supplied, and do not make an unsupported performance claim.

- [ ] Step 2: lesson을 검증 결과로 작성한다.

    # 이슈 #475 S3 CSE provider 구현 lesson

    ## 결정

    - provider key material을 생성 시 snapshot하고 caller key는 zeroize하지 않았다.
    - provider metadata version 2와 Bluetape 전용 wire 경계를 유지했다.
    - streaming 임시 경로에는 ciphertext만 쓰고 authenticated decrypt 뒤 destination에 평문을 썼다.

    ## 검증 증거

    - 실행 명령, 통과한 테스트 수, emulator 선택, detekt 결과
    - 실패한 첫 시도와 재현 가능한 해결 명령

    ## 후속 guard

    - KMS metadata를 provider template에서 해석하지 않는다.
    - provider candidate가 0개 또는 2개 이상이면 startup을 실패시킨다.
    - 새 provider가 추가되면 metadata token, identity, no-plaintext acceptance를 함께 추가한다.

- [ ] Step 3: Type A checklist를 채우고 독립 review를 수행한다.

| Gate | Evidence |
| --- | --- |
| SPW-01~05 | 승인 spec/plan, Korean naturalness, source ledger, read-back |
| Security/API/Spring/stream/test perspective | provider review notes와 해결된 P0/P1 목록 |
| TDD | 각 task의 RED/GREEN 명령과 commit |
| Acceptance | Floci AES/RSA byte·typed·stream·file 및 KMS regression |
| Quality | detekt, module test, git diff --check, manual contract |
| Scope | PR/push/merge/tag/release를 실행하지 않음 |

- [ ] Step 4: 최종 변경과 lesson을 commit한다.

    git add docs/lessons/2026-08-27-issue-475-s3-cse-providers.md
    git commit -m "#475 S3 CSE provider 검증 lesson을 기록한다" \
      -m "Constraint: Type A handoff에는 fresh test/detekt evidence와 known gap이 필요하다.
    Rejected: CI 또는 PR merge를 완료 증거로 간주 | 별도 권한 게이트를 침범한다.
    Confidence: high
    Scope-risk: moderate
    Directive: 다음 수정자는 provider metadata와 no-plaintext acceptance를 함께 갱신한다.
    Tested: full module test, detekt, docs contract, diff check
    Not-tested: GitHub PR/CI/merge"

- [ ] Step 5: 사용자에게 DoD handoff를 보고하고 별도 PR/merge 승인 전에는 외부 mutation을 하지 않는다.

최종 보고에는 plan item별 DONE/PENDING/BLOCKED, 실행 명령과 fresh evidence, 변경 파일, 알려진 위험, unchecked item, 사용자 action 없음, 다음 단계(PR 생성 여부)를 순서대로 적는다. PR 생성·push·merge·tag·release는 이 계획의 실행 범위가 아니므로 수행하지 않는다.

---

## 계획 자체의 self-review 기록

## Plan writer gate

| Gate | Result | Evidence |
| --- | --- | --- |
| SPW-01 | PASS | issue #475, 승인된 design spec, 현재 source paths, official Spring/AWS/JDK URLs, baseline test command와 unsupported wire boundary를 위에 고정했다. |
| SPW-02 | PASS | 파일 경계, dependency order, RED/GREEN commands, expected output, rollback, approval gate, acceptance와 DoD를 Tasks 1–8에 기록했다. |
| SPW-03 | PASS | 한국어 technical register와 `references/korean-naturalness-checklist.md` 기준을 적용하고 API/command/token은 그대로 보존했다. |
| SPW-04 | PASS | 설계 spec의 provider, metadata, identity, lifecycle, transfer, KMS compatibility 요구를 task와 test로 trace했다. |
| SPW-05 | PASS | read-back으로 headings, tables, indented code blocks, commands를 확인했고 아래 self-review 결과를 남겼다. |

- Spec coverage: provider contract(Tasks 1–2), properties/auto-config(Task 4), metadata/algorithm(Task 2), byte/bounded/identity(Task 3), typed/stream/file(Task 5), KMS backward compatibility(Tasks 4/6), lifecycle/zeroization(Tasks 2/3), docs/lesson(Tasks 7/8), acceptance matrix(Task 6)를 모두 task에 연결했다.
- Placeholder scan: 미완성 지시어와 추상적인 오류 처리 문구를 사용하지 않았다. 각 task는 실제 경로, type, method, command, expected output을 지정한다.
- Type consistency: ClientSideEncryptionProvider, S3AesProvider, S3RsaProvider, S3ClientSideEncryptionProviderTemplate, S3ClientSideEncryptionTransferOperations, S3EncryptedOutputStream, uploadEncryptedObject, downloadEncryptedObject, S3ClientSideEncryptionException의 이름과 인자를 앞뒤 task에서 동일하게 사용했다.
- Rollback points: task별 Lore commit 직전 targeted test가 실패하면 다음 task로 진행하지 않고 해당 task 원인을 수정한다. 이미 commit된 task를 되돌릴 때는 실제 task commit SHA를 지정한 git revert로 해당 task만 되돌리고 user-owned 파일을 reset하지 않는다.
- Approval gate: 이 plan commit 이후 사용자 계획 승인을 받은 다음 executing-plans skill로 inline execution을 시작한다.
- Lifecycle/test boundary: 기존 KMS bean에는 destroy method를 추가하지 않고 AES/RSA template만 close한다. streaming은 logical EOF, truncated final input, post-terminal reuse, double terminal call, cancellation cleanup을 별도 테스트한다.
- Performance/stability gate: Task 8 Step 1A에서 fresh diff와 transfer/emulator 테스트를 대상으로 `performance-stability-scan.md`를 적용하고 benchmark N/A 사유를 기록한다.
- Security remediation: provider context는 length-prefixed canonical AAD로 인증하고 raw key/data-key 복사 API를 노출하지 않으며, RSA modulus pair 검증·metadata collision/malformed negative cases·destination immutability를 task와 테스트에 고정했다.
