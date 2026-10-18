package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import kotlin.jvm.internal.DefaultConstructorMarker

class S3PropertiesCompatibilityTest {

    companion object: KLogging()

    @Test
    fun `legacy constructor and copy preserve the four field API`() {
        val properties = S3Properties.ClientSideEncryption(
            false,
            "alias/legacy",
            mapOf("purpose" to "test"),
            false,
        )

        log.debug { "properties=$properties" }
        properties.enabled.shouldBeFalse()
        properties.keyId shouldBeEqualTo "alias/legacy"
        properties.encryptionContext shouldBeEqualTo mapOf("purpose" to "test")
        properties.useDataKeyCache.shouldBeFalse()
        properties.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        properties.keyVersion.shouldBeNull()

        val providerProperties = S3Properties.ClientSideEncryption(
            provider = ClientSideEncryptionProvider.AES,
            keyVersion = "v2",
        )
        val copied = providerProperties.copy(
            enabled = false,
            keyId = "alias/copied",
            encryptionContext = emptyMap(),
            useDataKeyCache = false,
        )

        log.debug { "copied=$copied" }
        copied.provider shouldBeEqualTo ClientSideEncryptionProvider.AES
        copied.keyVersion shouldBeEqualTo "v2"
    }

    @Test
    @Suppress("LongMethod")
    fun `legacy JVM descriptors remain available`() {
        val type = S3Properties.ClientSideEncryption::class.java
        val properties = S3Properties.ClientSideEncryption(
            false,
            "alias/legacy",
            emptyMap(),
            true,
        )
        log.debug { "properties=$properties" }

        type.getConstructor(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType,
        ).newInstance(false, "alias/legacy", emptyMap<String, String>(), true)

        type.getMethod(
            "copy",
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType,
        )

        val legacyCopyDefault = type.getMethod(
            $$"copy$default",
            type,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        val copiedWithDefaults = legacyCopyDefault.invoke(
            null,
            properties,
            properties.enabled,
            properties.keyId,
            properties.encryptionContext,
            properties.useDataKeyCache,
            15,
            null,
        ).shouldBeInstanceOf<S3Properties.ClientSideEncryption>()
        copiedWithDefaults shouldBeEqualTo properties

        val legacyDefaultConstructor = type
            .getConstructor(
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Map::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                DefaultConstructorMarker::class.java,
            )
            .newInstance(false, "alias/legacy", emptyMap<String, String>(), true, 15, null)
            .shouldBeInstanceOf<S3Properties.ClientSideEncryption>()

        log.debug { "legacyDefaultConstructor=$legacyDefaultConstructor" }
        legacyDefaultConstructor.enabled.shouldBeFalse()
        legacyDefaultConstructor.keyId.shouldBeNull()
        legacyDefaultConstructor.encryptionContext.shouldBeEmpty()
        legacyDefaultConstructor.useDataKeyCache.shouldBeTrue()
        legacyDefaultConstructor.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        legacyDefaultConstructor.keyVersion.shouldBeNull()
    }
}
