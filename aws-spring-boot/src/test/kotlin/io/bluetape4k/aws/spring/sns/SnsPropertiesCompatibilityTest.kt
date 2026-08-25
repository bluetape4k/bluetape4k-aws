package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import kotlin.jvm.internal.DefaultConstructorMarker
import java.net.URI

class SnsPropertiesCompatibilityTest {

    @Test
    fun `legacy constructor and copy preserve the five field API`() {
        val topics = mapOf("orders" to SnsProperties.Topic())
        val properties = SnsProperties(
            false,
            "us-east-1",
            URI("http://localhost:4566"),
            topics,
            SnsProperties.Verification(false),
        )

        properties.enabled shouldBeEqualTo false
        properties.region shouldBeEqualTo "us-east-1"
        properties.topics shouldBeEqualTo topics
        properties.verification.enabled shouldBeEqualTo false
        properties.accountId.shouldBeNull()

        val copied = properties.copy(
            false,
            "eu-west-1",
            URI("http://localhost:4567"),
            emptyMap(),
            SnsProperties.Verification(),
        )
        copied.region shouldBeEqualTo "eu-west-1"
        copied.topics shouldBeEqualTo emptyMap()
        copied.accountId.shouldBeNull()
        copied.topicArnCache shouldBeEqualTo properties.topicArnCache
    }

    @Test
    fun `legacy JVM descriptors remain available`() {
        val propertiesType = SnsProperties::class.java
        val properties = SnsProperties(
            false,
            "us-east-1",
            null,
            emptyMap(),
            SnsProperties.Verification(),
        )
        assertLegacyConstructorDescriptor(propertiesType)
        assertLegacyCopyDescriptors(propertiesType, properties)
        assertLegacyDefaultConstructorDescriptor(propertiesType)
    }

    private fun assertLegacyConstructorDescriptor(propertiesType: Class<SnsProperties>) {
        val constructor = propertiesType.getConstructor(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            URI::class.java,
            Map::class.java,
            SnsProperties.Verification::class.java,
        )
        constructor.newInstance(
            false,
            "us-east-1",
            null,
            emptyMap<String, SnsProperties.Topic>(),
            SnsProperties.Verification(),
        )
    }

    private fun assertLegacyCopyDescriptors(
        propertiesType: Class<SnsProperties>,
        properties: SnsProperties,
    ) {
        propertiesType.getMethod(
            "copy",
            Boolean::class.javaPrimitiveType,
            String::class.java,
            URI::class.java,
            Map::class.java,
            SnsProperties.Verification::class.java,
        )
        val legacyCopyDefault = propertiesType.getMethod(
            "copy\$default",
            propertiesType,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            URI::class.java,
            Map::class.java,
            SnsProperties.Verification::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        val copiedWithDefaults = legacyCopyDefault.invoke(
            null,
            properties,
            properties.enabled,
            properties.region,
            properties.endpointOverride,
            properties.topics,
            properties.verification,
            31,
            null,
        ) as SnsProperties
        copiedWithDefaults shouldBeEqualTo properties
    }

    private fun assertLegacyDefaultConstructorDescriptor(propertiesType: Class<SnsProperties>) {
        val legacyDefaultConstructor = propertiesType.getConstructor(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            URI::class.java,
            Map::class.java,
            SnsProperties.Verification::class.java,
            Int::class.javaPrimitiveType,
            DefaultConstructorMarker::class.java,
        ).newInstance(
            false,
            "us-east-1",
            null,
            emptyMap<String, SnsProperties.Topic>(),
            SnsProperties.Verification(),
            31,
            null,
        ) as SnsProperties
        legacyDefaultConstructor.enabled shouldBeEqualTo true
        legacyDefaultConstructor.region.shouldBeNull()
        legacyDefaultConstructor.topics shouldBeEqualTo emptyMap<String, SnsProperties.Topic>()
    }
}
