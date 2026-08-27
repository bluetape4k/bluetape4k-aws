package io.bluetape4k.aws.spring.s3

import java.security.KeyPair
import javax.crypto.SecretKey

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
