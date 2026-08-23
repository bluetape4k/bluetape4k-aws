package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.core.io.ResourceLoader
import org.springframework.beans.factory.ObjectProvider
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest

class S3ProtocolResolverTest {

    private val s3Client = mockk<S3Client>(relaxed = true)
    private val s3ClientProvider = mockk<ObjectProvider<S3Client>>()
    private val resolver = S3ProtocolResolver(s3ClientProvider)
    private val resourceLoader = mockk<ResourceLoader>(relaxed = true)

    @Test
    fun `non S3 locations are delegated by returning null`() {
        resolver.resolve("classpath:application.yml", resourceLoader).shouldBeNull()
        verify(exactly = 0) { s3ClientProvider.getObject() }
    }

    @Test
    fun `exact S3 resolution is lazy with respect to object IO`() {
        every { s3ClientProvider.getObject() } returns s3Client

        val resource = resolver.resolve("s3://config-bucket/config/application.yml", resourceLoader)

        resource.shouldNotBeNull()
        (resource as S3Resource).location shouldBeEqualTo S3ObjectLocation("config-bucket", "config/application.yml")
        verify(exactly = 1) { s3ClientProvider.getObject() }
        verify(exactly = 0) { s3Client.headObject(any<HeadObjectRequest>()) }
        verify(exactly = 0) { s3Client.close() }
    }

    @Test
    fun `malformed S3 syntax fails without consulting the client provider`() {
        val error = assertFailsWith<IllegalArgumentException> {
            resolver.resolve("s3://bucket/config/%", resourceLoader)
        }

        error.message shouldContain "S3"
        verify(exactly = 0) { s3ClientProvider.getObject() }
    }

    @Test
    fun `provider is not consulted for invalid bucket or root pattern syntax`() {
        listOf(
            "s3://bucket-*/config/*.json",
            "s3://bucket/*.json",
            "s3://bucket/**",
        ).forEach { location ->
            assertFailsWith<IllegalArgumentException> {
                S3ResourceLocationParser().parsePattern(location)
            }
        }

        verify(exactly = 0) { s3ClientProvider.getObject() }
    }
}
