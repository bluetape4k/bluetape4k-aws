package io.bluetape4k.aws.kotlin.http

import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngine
import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngineConfig

/**
 * Creates a [CrtHttpEngine] with the supplied [CrtHttpEngineConfig].
 *
 * ```kotlin
 * val engine = crtHttpEngineOf()
 * val client = DynamoDbClient { httpClient = engine }
 * ```
 *
 * @param config engine configuration. Defaults to [CrtHttpEngineConfig.Default].
 * @return configured [CrtHttpEngine] instance.
 */
fun crtHttpEngineOf(config: CrtHttpEngineConfig = CrtHttpEngineConfig.Default): CrtHttpEngine =
    CrtHttpEngine(config)
