package io.bluetape4k.aws.exposed

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer

class AwsExposedTestcontainersReusePolicyTest {

    companion object: KLogging()

    @Test
    fun `postgresql container disables docker reuse by default`() {
        PostgreSQLServer().use { postgres ->
            postgres.isReuseRequested.shouldBeFalse()
        }
    }

    @Test
    fun `postgresql reusable container requires explicit local opt in`() {
        PostgreSQLServer(reuse = true).use {postgres ->
            postgres.isReuseRequested.shouldBeTrue()
        }
    }

    private val GenericContainer<*>.isReuseRequested: Boolean
        get() = GenericContainer::class.java
            .getDeclaredField("shouldBeReused")
            .apply { isAccessible = true }
            .getBoolean(this)
}
