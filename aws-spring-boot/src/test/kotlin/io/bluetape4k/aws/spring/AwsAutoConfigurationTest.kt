package io.bluetape4k.aws.spring

import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

@SpringBootTest(classes = [AwsAutoConfiguration::class])
class AwsAutoConfigurationTest {

    @Autowired
    private lateinit var credentialsProvider: AwsCredentialsProvider

    @Test
    fun `DefaultCredentialsProvider bean registered`() {
        credentialsProvider.shouldNotBeNull()
    }
}
