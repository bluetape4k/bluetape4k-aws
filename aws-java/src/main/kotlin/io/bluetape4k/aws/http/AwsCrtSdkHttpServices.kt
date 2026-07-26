package io.bluetape4k.aws.http

/**
 * Initialization object that pins the AWS SDK async HTTP service implementation to CRT.
 *
 * ## Behavior and contract
 * - Sets the JVM system property `software.amazon.awssdk.http.coroutines.service.impl` when the class is loaded.
 * - The configured value is fixed to `software.amazon.awssdk.http.crt.AwsCrtSdkHttpService`.
 *
 * ```kotlin
 * AwsCrtSdkHttpServices
 * val serviceImpl = System.getProperty("software.amazon.awssdk.http.coroutines.service.impl")
 * // serviceImpl == "software.amazon.awssdk.http.crt.AwsCrtSdkHttpService"
 * ```
 */
object AwsCrtSdkHttpServices {
    init {
        System.setProperty(
            "software.amazon.awssdk.http.coroutines.service.impl",
            "software.amazon.awssdk.http.crt.AwsCrtSdkHttpService"
        )
    }
}
