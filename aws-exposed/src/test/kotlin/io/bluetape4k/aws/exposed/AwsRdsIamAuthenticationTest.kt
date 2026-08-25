package io.bluetape4k.aws.exposed

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.rds.AwsRdsIamAuthTokenException as CoreRdsIamAuthTokenException
import io.bluetape4k.jdbc.datasource.RefreshingJdbcPasswordDataSource
import io.bluetape4k.jdbc.datasource.RefreshingJdbcPasswordDataSourceConfig
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.rds.RdsUtilities
import software.amazon.awssdk.services.rds.model.GenerateAuthenticationTokenRequest
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AwsRdsIamAuthenticationTest {

    companion object: KLogging()

    @Test
    fun `rds iam properties validate invalid settings`() {
        val invalidPort = assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthenticationProperties(
                region = "ap-northeast-2",
                hostname = "rds.example.com",
                port = 0,
            )
        }
        invalidPort.message.orEmpty() shouldContain "port"

        val invalidTokenTtl = assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthenticationProperties(
                region = "ap-northeast-2",
                hostname = "rds.example.com",
                port = 5432,
                tokenTtl = Duration.ofMinutes(16),
            )
        }
        invalidTokenTtl.message.orEmpty() shouldContain "tokenTtl"

        val invalidTokenTtlLowerBound = assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthenticationProperties(
                region = "ap-northeast-2",
                hostname = "rds.example.com",
                port = 5432,
                tokenTtl = Duration.ZERO,
            )
        }
        invalidTokenTtlLowerBound.message.orEmpty() shouldContain "tokenTtl"

        val invalidRefreshBeforeExpiry = assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthenticationProperties(
                region = "ap-northeast-2",
                hostname = "rds.example.com",
                port = 5432,
                refreshBeforeExpiry = Duration.ZERO,
            )
        }
        invalidRefreshBeforeExpiry.message.orEmpty() shouldContain "refreshBeforeExpiry"

        assertFailsWith<IllegalArgumentException> {
            AwsDatabaseConnectionProperties(
                authenticationMode = AwsDatabaseAuthenticationMode.RDS_IAM,
                password = AwsSecretString.of("static-password"),
                rdsIam = rdsIamProperties(),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            AwsDatabaseConnectionProperties(
                rdsIam = rdsIamProperties(),
            )
        }
    }

    @Test
    fun `rds iam validation accepts port boundaries and rejects token request port`() {
        rdsIamProperties().copy(port = 1).port shouldBeEqualTo 1
        rdsIamProperties().copy(port = 65_535).port shouldBeEqualTo 65_535

        val invalidTokenRequestPort = assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthTokenRequest(
                region = "ap-northeast-2",
                hostname = "database.example.com",
                port = 65_536,
                username = "app_user",
            )
        }
        invalidTokenRequestPort.message.orEmpty() shouldContain "port"
    }

    @Test
    fun `rds iam provider maps request shape and reuses token before refresh boundary`() {
        val clock = MutableClock(Instant.parse("2026-05-21T00:00:00Z"))
        val requests = mutableListOf<AwsRdsIamAuthTokenRequest>()
        val counter = AtomicInteger()
        val generator = AwsRdsIamAuthTokenGenerator { request ->
            requests += request
            awsSecretStringOf("token-${counter.incrementAndGet()}")
        }
        val provider = AwsDatabasePasswordProviders.rdsIam(
            properties = rdsIamConnectionProperties(),
            tokenGenerator = generator,
            clock = clock,
        )

        provider.currentPassword()?.reveal() shouldBeEqualTo "token-1"
        clock.advance(Duration.ofMinutes(10))
        provider.currentPassword()?.reveal() shouldBeEqualTo "token-1"

        counter.get() shouldBeEqualTo 1
        requests.single() shouldBeEqualTo AwsRdsIamAuthTokenRequest(
            region = "ap-northeast-2",
            hostname = "database-1.cluster-example.ap-northeast-2.rds.amazonaws.com",
            port = 5432,
            username = "app_user",
        )
    }

    @Test
    fun `rds iam provider regenerates token after refresh boundary`() {
        val clock = MutableClock(Instant.parse("2026-05-21T00:00:00Z"))
        val counter = AtomicInteger()
        val provider = AwsDatabasePasswordProviders.rdsIam(
            properties = rdsIamConnectionProperties(),
            tokenGenerator = {
                awsSecretStringOf("token-${counter.incrementAndGet()}")
            },
            clock = clock,
        )

        provider.currentPassword()?.reveal() shouldBeEqualTo "token-1"
        clock.advance(Duration.ofMinutes(14))
        provider.currentPassword()?.reveal() shouldBeEqualTo "token-2"

        counter.get() shouldBeEqualTo 2
    }

    @Test
    fun `rds iam provider coalesces concurrent refresh`() {
        val clock = MutableClock(Instant.parse("2026-05-21T00:00:00Z"))
        val counter = AtomicInteger()
        val provider = AwsDatabasePasswordProviders.rdsIam(
            properties = rdsIamConnectionProperties(),
            tokenGenerator = AwsRdsIamAuthTokenGenerator {
                awsSecretStringOf("token-${counter.incrementAndGet()}")
            },
            clock = clock,
        )
        provider.currentPassword()?.reveal() shouldBeEqualTo "token-1"
        clock.advance(Duration.ofMinutes(14))

        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val failure = AtomicReference<Throwable?>()
        repeat(8) {
            executor.execute {
                try {
                    start.await()
                    provider.currentPassword()?.reveal() shouldBeEqualTo "token-2"
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        done.await(5, TimeUnit.SECONDS).shouldBeTrue()
        executor.shutdownNow()
        failure.get()?.let { throw it }
        counter.get() shouldBeEqualTo 2
    }

    @Test
    fun `rds iam provider wraps generator failure without token leakage`() {
        val provider = AwsDatabasePasswordProviders.rdsIam(
            properties = rdsIamConnectionProperties(),
            tokenGenerator = AwsRdsIamAuthTokenGenerator {
                error("credential chain failed")
            },
        )

        val error = assertFailsWith<AwsRdsIamAuthTokenException> {
            provider.currentPassword()
        }

        error.message.orEmpty() shouldContain "Failed to generate RDS IAM authentication token"
        error.stackTraceToString().contains("raw-token").shouldBeFalse()
    }

    @Test
    fun `exposed sdk generator keeps core failure in cause chain without token leakage`() {
        val generator = AwsSdkRdsIamAuthTokenGenerator(
            rdsUtilities = object: RdsUtilities {
                override fun generateAuthenticationToken(request: GenerateAuthenticationTokenRequest): String =
                    error("credential chain failed")
            },
        )

        val error = assertFailsWith<AwsRdsIamAuthTokenException> {
            generator.generate(
                AwsRdsIamAuthTokenRequest(
                    region = "ap-northeast-2",
                    hostname = "database.example.com",
                    port = 5432,
                    username = "app_user",
                ),
            )
        }

        error.causeChain().any { it is CoreRdsIamAuthTokenException }.shouldBeTrue()
        error.message.orEmpty() shouldContain "database.example.com:5432"
        error.message.orEmpty().contains("raw-token").shouldBeFalse()
    }

    @Test
    fun `rds iam data source opens connection with generic refreshing password helper`() {
        val counter = AtomicInteger()
        val dataSource = refreshingRdsIamDataSource(counter)

        dataSource.connection.use { connection ->
            connection.isValid(1).shouldBeTrue()
        }
        counter.get() shouldBeEqualTo 1
    }

    @Test
    fun `rds iam data source rejects caller supplied credentials without token lookup`() {
        val counter = AtomicInteger()
        val dataSource = refreshingRdsIamDataSource(counter)

        val error = assertFailsWith<SQLException> {
            dataSource.getConnection("sa", "caller-password")
        }

        error.message.orEmpty() shouldContain "does not accept caller-supplied credentials"
        counter.get() shouldBeEqualTo 0
    }

    private fun refreshingRdsIamDataSource(counter: AtomicInteger): RefreshingJdbcPasswordDataSource =
        RefreshingJdbcPasswordDataSource(
            config = RefreshingJdbcPasswordDataSourceConfig(
                url = "jdbc:h2:mem:rds_iam_data_source;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                driverClassName = "org.h2.Driver",
                username = "sa",
                nullPasswordMessage = "RDS IAM password provider returned null.",
            ),
            passwordProvider = {
                awsSecretStringOf("token-${counter.incrementAndGet()}").reveal()
            },
        )

    private fun rdsIamConnectionProperties(): AwsDatabaseConnectionProperties =
        AwsDatabaseConnectionProperties(
            url = "jdbc:postgresql://database-1.cluster-example.ap-northeast-2.rds.amazonaws.com:5432/app",
            username = "app_user",
            authenticationMode = AwsDatabaseAuthenticationMode.RDS_IAM,
            rdsIam = rdsIamProperties(),
        )

    private fun rdsIamProperties(): AwsRdsIamAuthenticationProperties =
        AwsRdsIamAuthenticationProperties(
            region = "ap-northeast-2",
            hostname = "database-1.cluster-example.ap-northeast-2.rds.amazonaws.com",
            port = 5432,
            refreshBeforeExpiry = Duration.ofMinutes(2),
        )

    private class MutableClock(
        private var current: Instant,
        private val zone: ZoneId = ZoneId.of("UTC"),
    ): Clock() {

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

        override fun instant(): Instant = current
    }

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }.drop(1)
}
