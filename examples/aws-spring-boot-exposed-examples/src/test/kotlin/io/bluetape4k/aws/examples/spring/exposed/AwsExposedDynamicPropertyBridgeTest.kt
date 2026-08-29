package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.PropertyExportingServer
import io.bluetape4k.testcontainers.spring.registerDynamicProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.function.Supplier

/**
 * Spring Exposed 예제가 사용하는 Testcontainers property bridge 계약을 검증합니다.
 *
 * Docker 없이 generic key mapping, lazy supplier, lifecycle 비변경 및 JVM system property
 * 비변경을 고정합니다. AWS 전용 placeholder 해석은 Spring Boot 통합 테스트에서 검증합니다.
 */
class AwsExposedDynamicPropertyBridgeTest {

    private val systemPropertyKey = "testcontainers.postgresql.jdbc-url"

    @AfterEach
    fun clearSystemProperty() {
        System.clearProperty(systemPropertyKey)
    }

    @Test
    fun `PostgreSQL generic properties 를 표준 key 로 lazy 등록한다`() {
        val server = FakeServer(
            keys = linkedSetOf("jdbc-url", "driver-class-name", "username", "password"),
            values = mapOf(
                "jdbc-url" to "jdbc:postgresql://before/orders",
                "driver-class-name" to "org.postgresql.Driver",
                "username" to "test",
                "password" to "test",
            ),
        )
        val registry = RecordingRegistry()

        server.registerDynamicProperties(registry)

        server.propertiesCalls shouldBeEqualTo 0
        registry.names shouldBeEqualTo listOf(
            "testcontainers.postgresql.jdbc-url",
            "testcontainers.postgresql.driver-class-name",
            "testcontainers.postgresql.username",
            "testcontainers.postgresql.password",
        )

        server.values = server.values + ("jdbc-url" to "jdbc:postgresql://after/orders")
        registry.value(systemPropertyKey) shouldBeEqualTo "jdbc:postgresql://after/orders"
        server.propertiesCalls shouldBeEqualTo 1
    }

    @Test
    fun `bridge 등록은 supplier 평가를 지연하고 JVM system property를 변경하지 않는다`() {
        System.setProperty(systemPropertyKey, "existing")
        val server = FakeServer(
            keys = setOf("jdbc-url"),
            values = mapOf("jdbc-url" to "jdbc:postgresql://localhost/orders"),
        )

        server.registerDynamicProperties(RecordingRegistry())

        server.propertiesCalls shouldBeEqualTo 0
        System.getProperty(systemPropertyKey) shouldBeEqualTo "existing"
    }

    private class FakeServer(
        override val propertyNamespace: String = "postgresql",
        private val keys: Set<String>,
        var values: Map<String, String>,
    ): PropertyExportingServer {
        var propertiesCalls: Int = 0
            private set

        override fun propertyKeys(): Set<String> = keys

        override fun properties(): Map<String, String> {
            propertiesCalls++
            return values
        }
    }

    private class RecordingRegistry: DynamicPropertyRegistry {
        private val entries = mutableListOf<Pair<String, Supplier<Any>>>()

        val names: List<String>
            get() = entries.map { it.first }

        override fun add(name: String, valueSupplier: Supplier<Any>) {
            entries += name to valueSupplier
        }

        fun value(name: String): Any = entries.single { it.first == name }.second.get()
    }
}
