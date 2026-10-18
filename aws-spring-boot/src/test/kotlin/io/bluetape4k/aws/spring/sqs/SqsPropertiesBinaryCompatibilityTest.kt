package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class SqsPropertiesBinaryCompatibilityTest {

    private companion object: KLoggingChannel() {
        const val LEGACY_FIXTURE_RESOURCE =
            "/sqs-properties-abi/io/bluetape4k/aws/spring/sqs/consumer/LegacySqsPropertiesFixture.class"
        const val LEGACY_FIXTURE_CLASS_NAME =
            "io.bluetape4k.aws.spring.sqs.consumer.LegacySqsPropertiesFixture"
        const val LEGACY_FIXTURE_SHA256 =
            "62b9db238a80de182e963a1bb36dcb3508d7f09d1ed903af2f8720163da5c488"
    }

    @Test
    fun `pre batch consumer can still construct and copy SqsProperties`() = runSuspendIO {
        val fixtureBytes = javaClass.getResourceAsStream(LEGACY_FIXTURE_RESOURCE)
            .requireNotNull { "legacy fixture resource is missing: $LEGACY_FIXTURE_RESOURCE" }
            .use { it.readBytes() }

        sha256(fixtureBytes) shouldBeEqualTo LEGACY_FIXTURE_SHA256

        val loader = LegacyFixtureClassLoader(javaClass.classLoader, LEGACY_FIXTURE_CLASS_NAME, fixtureBytes)
        val type = loader.loadClass(LEGACY_FIXTURE_CLASS_NAME)
        type.classLoader shouldBeSameInstanceAs loader

        val fixture = type.getDeclaredConstructor().newInstance()
        val properties = type.getDeclaredMethod("createProperties").invoke(fixture) as SqsProperties

        log.debug { "properties=$properties" }
        properties.enabled.shouldBeTrue()
        properties.region shouldBeEqualTo "us-east-1"
        properties.listener.concurrency shouldBeEqualTo 2
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private class LegacyFixtureClassLoader(
    parent: ClassLoader,
    private val fixtureClassName: String,
    private val fixtureBytes: ByteArray,
): ClassLoader(parent) {

    protected override fun findClass(name: String): Class<*> =
        if (name == fixtureClassName) {
            defineClass(name, fixtureBytes, 0, fixtureBytes.size)
        } else {
            super.findClass(name)
        }
}
