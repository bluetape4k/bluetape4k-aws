package io.bluetape4k.aws.kotlin.http

import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngine
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine

/**
 * Provides reusable singleton HTTP engines for AWS SDK for Kotlin clients.
 *
 * ## Ownership contract
 *
 * Engines from this provider are shared and externally managed. Pass them to a
 * client only when several clients intentionally share the same transport. The
 * SDK treats explicitly supplied engines as `isManaged=false`, so
 * `client.close()` does not close the engine.
 *
 * For single-client usage, omit the `httpClient` parameter and let the SDK own
 * the engine lifecycle, or use the `withXxxClient { }` helpers.
 */
object HttpClientEngineProvider {

    /**
     * Singleton provider for the AWS CRT HTTP engine.
     *
     * The CRT engine uses non-daemon threads. Explicitly close this engine at
     * application shutdown after all clients sharing it have been closed.
     */
    object Crt {
        @JvmStatic
        val httpEngine: CrtHttpEngine by lazy { crtHttpEngineOf() }
    }

    /**
     * Singleton provider for the OkHttp HTTP engine.
     */
    object OkHttp {
        @JvmStatic
        val httpEngine: OkHttpEngine by lazy { okHttpEngineOf() }
    }

    /**
     * Shared CRT HTTP engine for callers that explicitly opt into external
     * engine ownership.
     */
    val defaultHttpEngine get() = Crt.httpEngine
}
