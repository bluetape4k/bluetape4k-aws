package io.bluetape4k.aws.exposed

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer

class AwsExposedTestcontainersReusePolicyTest {

    @Test
    fun `postgresql container disables docker reuse by default`() {
        PostgreSQLServer().isReuseRequested.shouldBeFalse()
    }

    @Test
    fun `postgresql reusable container requires explicit local opt in`() {
        PostgreSQLServer(reuse = true).isReuseRequested.shouldBeTrue()
    }

    private val GenericContainer<*>.isReuseRequested: Boolean
        get() = GenericContainer::class.java
            .getDeclaredField("shouldBeReused")
            .apply { isAccessible = true }
            .getBoolean(this)
}
