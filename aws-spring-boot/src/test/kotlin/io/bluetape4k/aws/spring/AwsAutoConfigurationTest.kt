package io.bluetape4k.aws.spring

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

@SpringBootTest(classes = [AwsAutoConfiguration::class])
class AwsAutoConfigurationTest {

    companion object: KLogging()

    @Autowired
    private val credentialsProvider: AwsCredentialsProvider = uninitialized()

    @Test
    fun `DefaultCredentialsProvider bean registered`() {
        credentialsProvider.shouldNotBeNull()
    }
}
