package io.bluetape4k.aws.kotlin.http

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class CrtHttpEngineSupportTest {

    companion object: KLogging()

    @Test
    fun `crtHttpEngineOf는 CrtHttpEngine 인스턴스를 생성한다`() {
        crtHttpEngineOf().use { engine ->
            log.debug { "CrtHttpEngine: $engine" }
            engine.shouldNotBeNull()
        }
    }

    @Test
    fun `okHttpEngineOf는 CrtHttpEngine 인스턴스를 생성한다`() {
        okHttpEngineOf().use { engine ->
            log.debug { "OkHttpHttpEngine: $engine" }
            engine.shouldNotBeNull()
        }
    }

    @Test
    fun `HttpClientEngineProvider default는 Crt singleton을 재사용한다`() {
        val defaultEngine = HttpClientEngineProvider.defaultHttpEngine
        val crtHttpEngine = HttpClientEngineProvider.Crt.httpEngine
        defaultEngine shouldBeSameInstanceAs crtHttpEngine
    }
}
