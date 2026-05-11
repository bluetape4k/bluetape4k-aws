package io.bluetape4k.aws.ktor.client

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner
import java.time.Clock

/**
 * [AwsSigV4Plugin] 설정입니다.
 *
 * ## 동작/계약
 * - [region]과 [service]는 SigV4 credential scope에 사용되며 빈 문자열일 수 없다.
 * - [credentialsProvider]는 요청 전송마다 호출되어 최신 자격 증명을 해석한다.
 * - [payloadSigningEnabled]가 `true`이면 replay 가능한 body만 서명한다.
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
