package io.bluetape4k.aws.client

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption
import java.util.concurrent.Executors

class ClientConfigurationSupportTest {

    @Test
    fun `clientOverrideConfiguration은 builder 설정값을 반영한다`() {
        val configuration = clientOverrideConfiguration {
            putHeader("x-test", "true")
        }

        configuration.headers()["x-test"]?.first() shouldBeEqualTo "true"
    }

    @Test
    fun `clientAsyncConfigurationOf는 advanced option 값을 보존한다`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configuration = clientAsyncConfigurationOf(
                SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR,
                executor,
            )

            configuration.advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR).shouldNotBeNull()
        } finally {
            executor.shutdownNow()
        }
    }
}
