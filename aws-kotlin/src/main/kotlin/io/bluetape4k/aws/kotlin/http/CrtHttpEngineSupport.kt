package io.bluetape4k.aws.kotlin.http

import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngine
import aws.smithy.kotlin.runtime.http.engine.crt.CrtHttpEngineConfig

/**
 * [CrtHttpEngineConfig]을 사용하여 [CrtHttpEngine] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val engine = crtHttpEngineOf()
 * val client = DynamoDbClient { httpClient = engine }
 * // 함께 사용하는 모든 클라이언트를 닫은 뒤 엔진도 닫습니다.
 * engine.close()
 * ```
 *
 * 생성된 엔진은 호출자가 소유합니다. 함께 사용하는 모든 클라이언트를 닫은 뒤 엔진도 닫아야 합니다.
 *
 * @param config [CrtHttpEngineConfig] 설정 (기본값: [CrtHttpEngineConfig.Default])
 * @return 호출자가 수명 주기를 관리하는 [CrtHttpEngine] 인스턴스
 */
fun crtHttpEngineOf(config: CrtHttpEngineConfig = CrtHttpEngineConfig.Default): CrtHttpEngine =
    CrtHttpEngine(config)
