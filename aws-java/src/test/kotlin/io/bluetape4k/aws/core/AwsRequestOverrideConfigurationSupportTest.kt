package io.bluetape4k.aws.core

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.AbstractAwsTest
import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class AwsRequestOverrideConfigurationSupportTest: AbstractAwsTest() {

    companion object: KLogging()

    @Test
    fun `awsRequestOverrideConfigurationOf는 credentials provider를 설정한다`() {
        val provider = staticCredentialsProviderOf("ak", "sk")
        val configuration = awsRequestOverrideConfigurationOf(provider)

        log.debug { "configuration=$configuration" }
        configuration.credentialsProvider().get().resolveCredentials().accessKeyId() shouldBeEqualTo "ak"
    }
}
