package io.bluetape4k.aws.examples.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.serializer.JdkBinarySerializer
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.net.URI

class S3DocumentModelsTest {

    companion object: KLogging()

    @Test
    fun `public S3 response models preserve values through Java serialization`() {
        val models = listOf(
            S3DocumentUploadResponse("bucket", "docs/readme.txt", "etag"),
            S3DocumentObjectResponse("docs/readme.txt", 128L),
            S3PresignedUrlResponse(URI("https://s3.example.test/docs/readme.txt").toURL()),
        )

        models.forEach { model ->
            model.shouldBeInstanceOf<Serializable>()
            roundTrip(model) shouldBeEqualTo model
        }

        listOf(
            S3DocumentUploadResponse::class.java,
            S3DocumentObjectResponse::class.java,
            S3PresignedUrlResponse::class.java,
        ).forEach { type ->
            type.getDeclaredField("serialVersionUID")
                .apply { isAccessible = true }
                .getLong(null) shouldBeEqualTo 1L
        }
    }

    private val serializer = JdkBinarySerializer(objectInputFilter = null)

    @Suppress("UNCHECKED_CAST")
    private fun <T: Any> roundTrip(value: T): T {
        val bytes = serializer.serialize(value)
        return serializer.deserialize<T>(bytes).shouldNotBeNull()
    }
}
