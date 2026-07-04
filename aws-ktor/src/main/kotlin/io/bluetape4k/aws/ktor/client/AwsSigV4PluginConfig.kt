package io.bluetape4k.aws.ktor.client

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import java.time.Clock

/**
 * Configuration for [AwsSigV4Plugin].
 *
 * ## Behavior/Contract
 * - [region] and [service] are used for the SigV4 credential scope and cannot be blank.
 * - [credentialsProvider] is called for each request send to resolve current credentials.
 * - When [payloadSigningEnabled] is `true`, only replayable bodies are signed.
 *
 * ```kotlin
 * import java.time.Clock
 *
 * install(AwsSigV4Plugin) {
 *     region = "ap-northeast-2"
 *     service = "execute-api"
 *     authLocation = AwsSigV4AuthLocation.QueryString
 *     signingClock = Clock.systemUTC()
 * }
 * ```
 */
class AwsSigV4PluginConfig {
    var region: String = ""
    var service: String = ""
    var credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build()
    var authLocation: AwsSigV4AuthLocation = AwsSigV4AuthLocation.Header
    var doubleUrlEncode: Boolean = true
    var normalizePath: Boolean = true
    var payloadSigningEnabled: Boolean = true
    var signingClock: Clock? = null
    var signer: AwsV4HttpSigner = AwsV4HttpSigner.create()
}
