package io.bluetape4k.aws.exposed

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
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

    @Test
    fun `rds iam properties validate invalid settings`() {
        assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthenticationProperties(
                region = "ap-northeast-2",
                hostname = "rds.example.com",
                port = 0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            AwsRdsIamAuthenticationProperties(
                region = "ap-northeast-2",
                hostname = "rds.example.com",
                port = 5432,
                tokenTtl = Duration.ofMinutes(16),
            )
        }

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
    fun `rds iam provider maps request shape and reuses token before refresh boundary`() {
        val clock = MutableClock(Instant.parse("2026-05-21T00:00:00Z"))
        val requests = mutableListOf<AwsRdsIamAuthTokenRequest>()
        val counter = AtomicInteger()
        val generator = AwsRdsIamAuthTokenGenerator { request ->
            requests += request
            AwsSecretString.of("token-${counter.incrementAndGet()}")
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
            tokenGenerator = AwsRdsIamAuthTokenGenerator {
                AwsSecretString.of("token-${counter.incrementAndGet()}")
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
                AwsSecretString.of("token-${counter.incrementAndGet()}")
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
                throw IllegalStateException("credential chain failed")
            },
        )

        val error = assertFailsWith<AwsRdsIamAuthTokenException> {
            provider.currentPassword()
        }

        error.message.orEmpty() shouldContain "Failed to generate RDS IAM authentication token"
        error.stackTraceToString().contains("raw-token").shouldBeFalse()
    }

    @Test
    fun `rds iam data source opens connection with provider token`() {
        val counter = AtomicInteger()
        val dataSource = RdsIamRefreshingDataSource(
            url = "jdbc:h2:mem:rds_iam_data_source;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driverClassName = "org.h2.Driver",
            username = "sa",
            dataSourceProperties = emptyMap(),
            passwordProvider = AwsDatabasePasswordProvider {
                AwsSecretString.of("token-${counter.incrementAndGet()}")
            },
        )

        dataSource.connection.use { connection ->
            connection.isValid(1).shouldBeTrue()
        }
        counter.get() shouldBeEqualTo 1
    }

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
}
