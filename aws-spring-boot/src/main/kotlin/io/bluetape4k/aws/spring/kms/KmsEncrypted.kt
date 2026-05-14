package io.bluetape4k.aws.spring.kms

/**
 * Marks a field whose String value is explicitly encrypted through [KmsEncryptedFieldCodec].
 *
 * This annotation is metadata only. It does not transparently mutate DTOs,
 * entities, or configuration objects. Application code should call
 * [KmsEncryptedFieldCodec] at mapper, converter, or boundary points where the
 * plaintext lifecycle is clear.
 *
 * Supported field type for the first slice is `String`/`String?`.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class KmsEncrypted(
    /**
     * Optional per-field KMS key id. Blank uses `bluetape4k.aws.kms.key-id`.
     */
    val keyId: String = "",

    /**
     * Per-field encryption context entries in `name=value` form.
     *
     * Entries are merged over `bluetape4k.aws.kms.encryption-context`, so a
     * field entry with the same name overrides the configured default.
     */
    val encryptionContext: Array<String> = [],
)
