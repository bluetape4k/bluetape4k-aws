package io.bluetape4k.aws.kotlin.http

import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngineConfig

/**
 * Creates an [OkHttpEngine] with the supplied [OkHttpEngineConfig].
 *
 * ```kotlin
 * val engine = okHttpEngineOf()
 * val client = S3Client { httpClient = engine }
 * ```
 *
 * @param config engine configuration. Defaults to [OkHttpEngineConfig.Default].
 * @return configured [OkHttpEngine] instance.
 */
fun okHttpEngineOf(config: OkHttpEngineConfig = OkHttpEngineConfig.Default): OkHttpEngine =
    OkHttpEngine(config)
