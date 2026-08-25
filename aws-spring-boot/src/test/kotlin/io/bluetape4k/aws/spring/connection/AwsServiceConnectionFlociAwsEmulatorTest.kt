package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.testcontainers.aws.FlociServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.ConfigurableApplicationContext
import software.amazon.awssdk.services.s3.S3Client

@SpringBootTest(
    classes = [AwsServiceConnectionTestApplication::class],
    webEnvironment = WebEnvironment.NONE,
)
class AwsServiceConnectionFlociAwsEmulatorTest {

    companion object {
        @JvmField
        @ServiceConnection(name = "s3")
        val floci: FlociServer = FlociServer.Launcher.floci
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
        connectionDetails.endpoint shouldBeEqualTo floci.awsEndpoint
        connectionDetails.region shouldBeEqualTo floci.regionName
        connectionDetails.accessKey.shouldNotBeBlank()
        connectionDetails.secretKey.shouldNotBeBlank()
        s3Client.serviceClientConfiguration().endpointOverride().orElse(null) shouldBeEqualTo floci.awsEndpoint
    }

    @Test
    fun s3RoundTripStaysWithinOwnerBucket() {
        val receipt = AwsServiceConnectionTestFixtures.roundTrip(s3Client, floci)
        receipt.backend shouldBeEqualTo "FlociServer"
        receipt.key.contains(receipt.ownerToken) shouldBeEqualTo true
    }
}
