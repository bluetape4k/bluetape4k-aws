package io.bluetape4k.aws.spring.s3

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable
import java.io.IOException

class S3ResourcePatternResolverTest {

    @Test
    fun `exact S3 resources do not list and return one resource`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        every { provider.getObject() } returns client
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            val resources = resolver.getResources("s3://bucket/config/application.yml")

            resources.size shouldBeEqualTo 1
            (resources.single() as S3Resource).location shouldBeEqualTo
                S3ObjectLocation("bucket", "config/application.yml")
            verify(exactly = 0) { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) }
        } finally {
            context.close()
        }
    }

    @Test
    fun `pattern resources consume all pages deduplicate and sort without delimiter`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        val iterable = mockk<ListObjectsV2Iterable>()
        val request = slot<ListObjectsV2Request>()
        every { provider.getObject() } returns client
        every { client.listObjectsV2Paginator(capture(request)) } returns iterable
        every { iterable.iterator() } returns mutableListOf(
            page("config/z.json", "config/a.json"),
            page("config/nested/b.json", "other/no.json"),
        ).iterator()
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            val resources = resolver.getResources("s3://bucket/config/**/*.json")
            val keys = resources.map { (it as S3Resource).location.key }

            keys shouldBeEqualTo listOf("config/a.json", "config/nested/b.json", "config/z.json")
            request.captured.bucket() shouldBeEqualTo "bucket"
            request.captured.prefix() shouldBeEqualTo "config/"
            request.captured.delimiter().shouldBeNull()
            verify(exactly = 1) { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) }
        } finally {
            context.close()
        }
    }

    @Test
    fun `no match returns an empty array and does not perform per key calls`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        val iterable = mockk<ListObjectsV2Iterable>()
        every { provider.getObject() } returns client
        every { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) } returns iterable
        every { iterable.iterator() } returns mutableListOf(page("config/readme.txt")).iterator()
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            resolver.getResources("s3://bucket/config/**/*.json").size shouldBeEqualTo 0
            verify(exactly = 1) { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) }
            verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
        } finally {
            context.close()
        }
    }

    @Test
    fun `listing failures become IOException with cause and bounded diagnostics`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        val failure = IllegalStateException("synthetic-secret\nAuthorization: Bearer secret")
        every { provider.getObject() } returns client
        every { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) } throws failure
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            val error = assertFailsWith<IOException> {
                resolver.getResources("s3://safe-bucket/config/**/*.json")
            }

            error.cause shouldBeSameInstanceAs failure
            error.message shouldContain "safe-bucket"
            error.message shouldContain "config/"
            error.message.orEmpty().contains("synthetic-secret").shouldBeEqualTo(false)
            error.message.orEmpty().contains("Authorization").shouldBeEqualTo(false)
        } finally {
            context.close()
        }
    }

    @Test
    fun `mid-page failure becomes IOException without partial results or retry`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        val iterable = mockk<ListObjectsV2Iterable>()
        val failure = SdkClientException.create("synthetic transport failure")
        every { provider.getObject() } returns client
        every { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) } returns iterable
        every { iterable.iterator() } returns object : MutableIterator<ListObjectsV2Response> {
            private var index = 0

            override fun hasNext(): Boolean = index < 2

            override fun next(): ListObjectsV2Response =
                if (index++ == 0) page("config/first.json") else throw failure

            override fun remove(): Unit = throw UnsupportedOperationException()
        }
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            val error = assertFailsWith<IOException> {
                resolver.getResources("s3://bucket/config/**/*.json")
            }

            error.cause shouldBeSameInstanceAs failure
            verify(exactly = 1) { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) }
        } finally {
            context.close()
        }
    }

    @Test
    fun `invalid pattern never queries provider`() {
        val provider = mockk<ObjectProvider<S3Client>>()
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            assertFailsWith<IllegalArgumentException> {
                resolver.getResources("s3://bucket/*.json")
            }
            assertFailsWith<IllegalArgumentException> {
                resolver.getResources("s3://bucket/%20")
            }

            verify(exactly = 0) { provider.getObject() }
        } finally {
            context.close()
        }
    }

    @Test
    fun `escaped wildcard is matched literally and PUA key collision remains safe`() {
        val client = mockk<S3Client>(relaxed = true)
        val provider = mockk<ObjectProvider<S3Client>>()
        val iterable = mockk<ListObjectsV2Iterable>()
        every { provider.getObject() } returns client
        every { client.listObjectsV2Paginator(any<ListObjectsV2Request>()) } returns iterable
        every { iterable.iterator() } returns mutableListOf(
            page("config/*/literal.txt", "config/x/literal.txt", "config/${'\uE000'}/literal.txt"),
        ).iterator()
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver = S3ResourcePatternResolver(context, provider)

            val resources = resolver.getResources("s3://bucket/config/%2A/literal.txt")
            resources.map { (it as S3Resource).location.key } shouldBeEqualTo listOf("config/*/literal.txt")
        } finally {
            context.close()
        }
    }

    @Test
    fun `Java declaration exposes IOException`() {
        val method = S3ResourcePatternResolver::class.java.getDeclaredMethod("getResources", String::class.java)

        method.exceptionTypes.toList() shouldContain IOException::class.java
    }

    @Test
    fun `non S3 resources use the application context delegate`() {
        val context = GenericApplicationContext().apply { refresh() }
        try {
            val resolver: ResourcePatternResolver = S3ResourcePatternResolver(
                context,
                mockk<ObjectProvider<S3Client>>(),
            )

            resolver.getResource("classpath:missing-resource.txt").shouldNotBeNull()
            resolver.getResources(
                "classpath*:META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            )
                .isNotEmpty().shouldBeTrue()
            resolver.getClassLoader() shouldBeSameInstanceAs context.classLoader
        } finally {
            context.close()
        }
    }

    private fun page(vararg keys: String): ListObjectsV2Response =
        ListObjectsV2Response.builder()
            .contents(keys.map { S3Object.builder().key(it).build() })
            .build()
}
