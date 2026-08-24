@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import software.amazon.awssdk.services.s3.S3Client

@Testcontainers
@SpringBootTest(
    classes = [AwsServiceConnectionTestApplication::class],
    webEnvironment = WebEnvironment.NONE,
)
class AwsServiceConnectionLocalStackAwsEmulatorTest {

    companion object {
        @JvmField
        @Container
        @ServiceConnection(name = "s3")
        val localStack: LocalStackServer = LocalStackServer.Launcher.getLocalStack("s3")
    }

    @Autowired
    private lateinit var context: ConfigurableApplicationContext

    @Autowired
    private lateinit var s3Client: S3Client

    @Autowired
    private lateinit var connectionDetails: S3ConnectionDetails

    @Test
    fun serviceConnectionUsesExpectedBackend() {
        context.isActive shouldBeEqualTo true
        connectionDetails.endpoint shouldBeEqualTo localStack.awsEndpoint
        connectionDetails.region shouldBeEqualTo localStack.regionName
        connectionDetails.accessKey.shouldNotBeBlank()
        connectionDetails.secretKey.shouldNotBeBlank()
        s3Client.serviceClientConfiguration().endpointOverride().orElse(null) shouldBeEqualTo localStack.awsEndpoint
    }

    @Test
    fun s3RoundTripStaysWithinOwnerBucket() {
        val receipt = AwsServiceConnectionTestFixtures.roundTrip(s3Client, localStack)
        receipt.backend shouldBeEqualTo "LocalStackServer"
        receipt.key.contains(receipt.ownerToken) shouldBeEqualTo true
    }
}
