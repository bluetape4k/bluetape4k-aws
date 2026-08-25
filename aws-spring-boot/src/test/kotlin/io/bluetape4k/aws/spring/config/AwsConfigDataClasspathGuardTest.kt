package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test

class AwsConfigDataClasspathGuardTest {

    @Test
    fun `filtered classloader hides AWS SDK while SDK-free bridge remains loadable`() {
        val sdkClassName = "software.amazon.awssdk.services.s3.S3Client"
        val filtered = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == sdkClassName) {
                    throw ClassNotFoundException(name)
                }
                return super.loadClass(name, resolve)
            }
        }

        AwsConfigDataBootstrapBridge.isClassPresent(sdkClassName, filtered) shouldBeEqualTo false
        Class.forName(AwsConfigDataBootstrapBridge::class.java.name, false, filtered)
        Class.forName(AwsConfigDataSupport::class.java.name, false, filtered)
    }

    @Test
    fun `missing SDK error is sanitized and contains dependency guidance`() {
        val error = assertFailsWith<IllegalStateException> {
            AwsConfigDataBootstrapBridge.requireClass(
                className = "no.such.aws.Client",
                dependency = "software.amazon.awssdk:missing",
            )
        }

        error.message.orEmpty() shouldNotContain "secret"
        error.message.orEmpty() shouldBeEqualTo
            "AWS SDK dependency is required for ConfigData import. Add 'software.amazon.awssdk:missing'."
    }
}
