package io.bluetape4k.aws.kotlin.http

import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngine
import aws.smithy.kotlin.runtime.http.engine.okhttp.OkHttpEngineConfig
import io.bluetape4k.support.useSafe

/**
 * [OkHttpEngineConfig]을 사용하여 [OkHttpEngine] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val engine = okHttpEngineOf()
 * val client = S3Client { httpClient = engine }
 * // 함께 사용하는 모든 클라이언트를 닫은 뒤 엔진도 닫습니다.
 * engine.close()
 * ```
 *
 * 생성된 엔진은 호출자가 소유합니다. 함께 사용하는 모든 클라이언트를 닫은 뒤 엔진도 닫아야 합니다.
 *
 * @param config [OkHttpEngineConfig] 설정 (기본값: [OkHttpEngineConfig.Default])
 * @return 호출자가 수명 주기를 관리하는 [OkHttpEngine] 인스턴스
 */
fun okHttpEngineOf(config: OkHttpEngineConfig = OkHttpEngineConfig.Default): OkHttpEngine =
    OkHttpEngine(config)


inline fun okHttpEngine(
    crossinline block: OkHttpEngineConfig.Builder.() -> Unit = {},
): OkHttpEngine =
    OkHttpEngine { block() }


inline fun <R> withOkHttpEngine(
    config: OkHttpEngineConfig = OkHttpEngineConfig.Default,
    crossinline block: (OkHttpEngine) -> R,
): R =
    okHttpEngineOf(config).useSafe { okHttpEngine ->
        block(okHttpEngine)
    }
