package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.jupiter.api.Test

class S3PropertiesCompatibilityTest {

    @Test
    fun `legacy constructor and copy preserve the four field API`() {
        val properties = S3Properties.ClientSideEncryption(
            false,
            "alias/legacy",
            mapOf("purpose" to "test"),
            false,
        )

        properties.enabled shouldBeEqualTo false
        properties.keyId shouldBeEqualTo "alias/legacy"
        properties.encryptionContext shouldBeEqualTo mapOf("purpose" to "test")
        properties.useDataKeyCache shouldBeEqualTo false
        properties.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        properties.keyVersion.shouldBeNull()

        val providerProperties = S3Properties.ClientSideEncryption(
            provider = ClientSideEncryptionProvider.AES,
            keyVersion = "v2",
        )
        val copied = providerProperties.copy(
            false,
            "alias/copied",
            emptyMap(),
            false,
        )

        copied.provider shouldBeEqualTo ClientSideEncryptionProvider.AES
        copied.keyVersion shouldBeEqualTo "v2"
    }

    @Test
    fun `legacy JVM descriptors remain available`() {
        val type = S3Properties.ClientSideEncryption::class.java
        val properties = S3Properties.ClientSideEncryption(
            false,
            "alias/legacy",
            emptyMap(),
            true,
        )

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
            "copy\$default",
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
        ) as S3Properties.ClientSideEncryption
        copiedWithDefaults shouldBeEqualTo properties

        val legacyDefaultConstructor = type.getConstructor(
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Map::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            DefaultConstructorMarker::class.java,
        ).newInstance(false, "alias/legacy", emptyMap<String, String>(), true, 15, null)
            as S3Properties.ClientSideEncryption

        legacyDefaultConstructor.enabled shouldBeEqualTo false
        legacyDefaultConstructor.keyId.shouldBeNull()
        legacyDefaultConstructor.encryptionContext shouldBeEqualTo emptyMap()
        legacyDefaultConstructor.useDataKeyCache shouldBeEqualTo true
        legacyDefaultConstructor.provider shouldBeEqualTo ClientSideEncryptionProvider.KMS
        legacyDefaultConstructor.keyVersion.shouldBeNull()
    }
}
