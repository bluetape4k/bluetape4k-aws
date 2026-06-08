package io.bluetape4k.aws.kotlin.http

import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test

class HttpClientEngineProviderTest {

    companion object : KLogging()

    @Test
    fun `CRT httpEngine singleton은 null이 아니다`() {
        val engine = HttpClientEngineProvider.Crt.httpEngine
        engine.shouldNotBeNull()
    }

    @Test
    fun `CRT httpEngine은 매번 같은 singleton 인스턴스를 반환한다`() {
        val engine1 = HttpClientEngineProvider.Crt.httpEngine
        val engine2 = HttpClientEngineProvider.Crt.httpEngine
        engine1 shouldBeSameInstanceAs engine2
    }

    @Test
    fun `OkHttp httpEngine singleton은 null이 아니다`() {
        val engine = HttpClientEngineProvider.OkHttp.httpEngine
        engine.shouldNotBeNull()
    }

    @Test
    fun `OkHttp httpEngine은 매번 같은 singleton 인스턴스를 반환한다`() {
        val engine1 = HttpClientEngineProvider.OkHttp.httpEngine
        val engine2 = HttpClientEngineProvider.OkHttp.httpEngine
        engine1 shouldBeSameInstanceAs engine2
    }

    @Test
    fun `defaultHttpEngine은 CRT httpEngine과 같은 인스턴스다`() {
        val defaultEngine = HttpClientEngineProvider.defaultHttpEngine
        val crtEngine = HttpClientEngineProvider.Crt.httpEngine
        defaultEngine shouldBeSameInstanceAs crtEngine
    }
}
