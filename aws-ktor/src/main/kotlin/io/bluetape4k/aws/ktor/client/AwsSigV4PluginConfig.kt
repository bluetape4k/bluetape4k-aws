package io.bluetape4k.aws.ktor.client

import aws.smithy.kotlin.runtime.auth.awssigning.AwsSigner
import aws.smithy.kotlin.runtime.auth.awssigning.AwsSignedBodyHeader
import aws.smithy.kotlin.runtime.auth.awssigning.DefaultAwsSigner
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import java.time.Clock

/**
 * [AwsSigV4Plugin] 설정입니다.
 *
 * [credentialsProvider]는 호출자가 소유하며 요청 전송마다 호출됩니다. HTTPS endpoint를 사용해야 합니다.
 * 로컬 loopback HTTP는 [allowInsecureLocalHttpEndpoint]를 `true`로 설정한 경우에만 허용합니다.
 *
 * ```kotlin
 * import aws.sdk.kotlin.runtime.auth.credentials.DefaultChainCredentialsProvider
 * import java.time.Clock
 *
 * install(AwsSigV4Plugin) {
 *     region = "ap-northeast-2"
 *     service = "execute-api"
 *     credentialsProvider = DefaultChainCredentialsProvider()
 *     authLocation = AwsSigV4AuthLocation.QueryString
 *     signingClock = Clock.systemUTC()
 * }
 * ```
 */
class AwsSigV4PluginConfig {
    var region: String = ""
    var service: String = ""
    var credentialsProvider: CredentialsProvider? = null
    var authLocation: AwsSigV4AuthLocation = AwsSigV4AuthLocation.Header
    var doubleUrlEncode: Boolean = true
    var normalizePath: Boolean = true
    var payloadSigningEnabled: Boolean = true
    var signedBodyHeader: AwsSignedBodyHeader = AwsSignedBodyHeader.X_AMZ_CONTENT_SHA256
    var allowInsecureLocalHttpEndpoint: Boolean = false
    var signingClock: Clock? = null
    var signer: AwsSigner = DefaultAwsSigner
}
