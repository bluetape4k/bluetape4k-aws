package io.bluetape4k.aws.kotlin.auth

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import io.bluetape4k.support.requireNotBlank

/**
 * Default access key used by local AWS emulator tests.
 */
const val AWS_LOCAL_ACCESS_KEY = "accesskey"

/**
 * Default secret key used by local AWS emulator tests.
 */
const val AWS_LOCAL_SECRET_KEY = "secretkey"

/**
 * [StaticCredentialsProvider] for local AWS emulator tests.
 */
@JvmField
val LocalCredentialsProvider: StaticCredentialsProvider =
    staticCredentialsProviderOf(AWS_LOCAL_ACCESS_KEY, AWS_LOCAL_SECRET_KEY)

/**
 * Creates a [StaticCredentialsProvider] from an access key and secret key.
 *
 * ```
 * private val credentialsProvider: StaticCredentialsProvider by lazy {
 *      staticCredentialsProviderOf(s3Server.accessKey, s3Server.secretKey)
 * }
 * ```
 * @param accessKeyId      AWS access key
 * @param secretAccessKey  AWS secret key
 * @return [StaticCredentialsProvider] backed by the supplied credentials.
 */
fun staticCredentialsProviderOf(accessKeyId: String, secretAccessKey: String): StaticCredentialsProvider {
    return staticCredentialsProviderOf(credentialsOf(accessKeyId, secretAccessKey))
}

/**
 * Creates a [StaticCredentialsProvider] from existing [Credentials].
 *
 * @param credentials [Credentials] to expose through the provider.
 */
fun staticCredentialsProviderOf(credentials: Credentials): StaticCredentialsProvider =
    StaticCredentialsProvider {
        this.accessKeyId = credentials.accessKeyId
        this.secretAccessKey = credentials.secretAccessKey
    }

/**
 * Creates AWS [Credentials] after validating the required key values.
 *
 * @param accessKeyId      AWS access key
 * @param secretAccessKey  AWS secret key
 */
fun credentialsOf(accessKeyId: String, secretAccessKey: String): Credentials {
    accessKeyId.requireNotBlank("accessKeyId")
    secretAccessKey.requireNotBlank("secretAccessKey")

    return Credentials(accessKeyId, secretAccessKey)
}
