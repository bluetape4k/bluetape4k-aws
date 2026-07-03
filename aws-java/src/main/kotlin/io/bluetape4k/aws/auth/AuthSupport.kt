package io.bluetape4k.aws.auth

import io.bluetape4k.support.requireNotBlank
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

/**
 * Default access key string used by local test environments.
 *
 * ## Behavior / Contract
 * - Used as the default `accessKeyId` when creating local credentials.
 * - The value is fixed to the `"accesskey"` constant.
 *
 * ```kotlin
 * val accessKey = AWS_LOCAL_ACCESS_KEY
 * // accessKey == "accesskey"
 * ```
 */
const val AWS_LOCAL_ACCESS_KEY = "accesskey"

/**
 * Default secret key string used by local test environments.
 *
 * ## Behavior / Contract
 * - Used as the default `secretAccessKey` when creating local credentials.
 * - The value is fixed to the `"secretkey"` constant.
 *
 * ```kotlin
 * val secretKey = AWS_LOCAL_SECURITY_KEY
 * // secretKey == "secretkey"
 * ```
 */
const val AWS_LOCAL_SECURITY_KEY = "secretkey"

/**
 * Provides a [StaticCredentialsProvider] backed by the local default key pair.
 *
 * ## Behavior / Contract
 * - Created eagerly from `AWS_LOCAL_ACCESS_KEY` and `AWS_LOCAL_SECURITY_KEY`.
 * - Exposed as a reusable `val` instance.
 *
 * ```kotlin
 * val provider = LocalAwsCredentialsProvider
 * val credentials = provider.resolveCredentials()
 * // credentials.accessKeyId() == AWS_LOCAL_ACCESS_KEY
 * ```
 */
@JvmField
val LocalAwsCredentialsProvider: StaticCredentialsProvider =
    staticCredentialsProviderOf(AWS_LOCAL_ACCESS_KEY, AWS_LOCAL_SECURITY_KEY)

/**
 * Creates [AwsBasicCredentials] from access key and secret key strings.
 *
 * ## Behavior / Contract
 * - Delegates to [AwsBasicCredentials.create] and returns a new instance.
 * - Maps input strings directly to the credential fields without conversion.
 *
 * ```kotlin
 * val credentials = awsBasicCredentialsOf("ak", "sk")
 * // credentials.accessKeyId() == "ak"
 * ```
 */
fun awsBasicCredentialsOf(accessKeyId: String, securityAccessKey: String): AwsBasicCredentials {
    // WHY: Blank credential strings fail later as ambiguous AWS 401/403 responses.
    accessKeyId.requireNotBlank("accessKeyId")
    securityAccessKey.requireNotBlank("securityAccessKey")
    return AwsBasicCredentials.create(accessKeyId, securityAccessKey)
}

/**
 * Creates a [StaticCredentialsProvider] for [AwsBasicCredentials].
 *
 * ## Behavior / Contract
 * - Delegates to [StaticCredentialsProvider.create].
 * - Resolves the provided [credentials] as-is.
 *
 * ```kotlin
 * val credentials = awsBasicCredentialsOf("ak", "sk")
 * val provider = staticCredentialsProviderOf(credentials)
 * // provider.resolveCredentials().secretAccessKey() == "sk"
 * ```
 */
fun staticCredentialsProviderOf(credentials: AwsBasicCredentials): StaticCredentialsProvider =
    StaticCredentialsProvider.create(credentials)

/**
 * Creates [AwsBasicCredentials] from key strings and wraps them in a [StaticCredentialsProvider].
 *
 * ## Behavior / Contract
 * - Calls [awsBasicCredentialsOf] and then [staticCredentialsProviderOf].
 * - Returns a fixed credential provider from the two key strings.
 *
 * ```kotlin
 * private val credentialsProvider: StaticCredentialsProvider by lazy {
 *      staticCredentialsProviderOf(s3Server.accessKey, s3Server.secretKey)
 * }
 * // credentialsProvider.resolveCredentials().accessKeyId() == s3Server.accessKey
 * ```
 */
fun staticCredentialsProviderOf(accessKeyId: String, securityAccessKey: String): StaticCredentialsProvider =
    staticCredentialsProviderOf(awsBasicCredentialsOf(accessKeyId, securityAccessKey))
