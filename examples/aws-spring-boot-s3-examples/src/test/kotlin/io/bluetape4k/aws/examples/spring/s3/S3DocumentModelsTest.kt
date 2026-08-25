package io.bluetape4k.aws.examples.spring.s3

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.URL

class S3DocumentModelsTest {

    @Test
    fun `public S3 response models preserve values through Java serialization`() {
        val models = listOf<Serializable>(
            S3DocumentUploadResponse("bucket", "docs/readme.txt", "etag"),
            S3DocumentObjectResponse("docs/readme.txt", 128L),
            S3PresignedUrlResponse(URL("https://s3.example.test/docs/readme.txt")),
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
            type.getDeclaredField("serialVersionUID").apply { isAccessible = true }.getLong(null) shouldBeEqualTo 1L
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as T }
    }
}
