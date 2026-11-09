package io.bluetape4k.aws.sqs.model

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.kms.model.deleteAliasOf
import io.bluetape4k.aws.s3.model.objectIdentifier
import io.bluetape4k.aws.s3.model.getObjectRequestOf
import io.bluetape4k.aws.s3.model.headObjectRequestOf
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry

/**
 * 이전에 배포된 aws-java 소비자가 계속 호출할 수 있는 JVM 메서드 설명자를 고정합니다.
 *
 * Kotlin 소스 호출이 계속 컴파일되는 것만으로는 기존 Java/사전 컴파일 소비자의
 * `NoSuchMethodError` 회귀를 잡을 수 없으므로 facade 클래스의 실제 메서드를 검사합니다.
 */
class LegacyRequestBuilderAbiTest {

    @Test
    fun `legacy request helper descriptors remain available`() {
        assertMethod(
            className = "io.bluetape4k.aws.sqs.model.DeleteMessageKt",
            methodName = "deleteMessageRequestOf",
            String::class,
            String::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.sqs.model.DeleteMessageKt",
            methodName = "deleteMessageBatchRequestOf",
            String::class,
            Collection::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.sqs.model.DeleteMessageKt",
            methodName = "deleteMessageBatchRequestEntryOf",
            String::class,
            String::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.kms.model.DeleteAliasRequestKt",
            methodName = "deleteAliasOf",
            String::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.s3.model.GetObjectRequestKt",
            methodName = "getObjectRequestOf",
            String::class,
            String::class,
            String::class,
            Integer::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.s3.model.HeadObjectRequestKt",
            methodName = "headObjectRequestOf",
            String::class,
            String::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.s3.model.DeleteKt",
            methodName = "deleteOfObjectsArray",
            Array<ObjectIdentifier>::class,
            Boolean::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.s3.model.DeleteKt",
            methodName = "deleteOfObjectsCollection",
            Collection::class,
            Boolean::class,
        )
        assertMethod(
            className = "io.bluetape4k.aws.s3.model.DeleteObjectRequestKt",
            methodName = "deleteObjectsRequestOf",
            String::class,
            Delete::class,
            String::class,
        )
    }

    @Test
    fun `legacy overloads retain their original request mapping`() {
        val deleteRequest = deleteMessageRequestOf("https://sqs.example/queue", "receipt")
        deleteRequest.queueUrl() shouldBeEqualTo "https://sqs.example/queue"
        deleteRequest.receiptHandle() shouldBeEqualTo "receipt"

        val batchRequest = deleteMessageBatchRequestOf(
            "https://sqs.example/queue",
            emptyList<DeleteMessageBatchRequestEntry>(),
        )
        batchRequest.queueUrl() shouldBeEqualTo "https://sqs.example/queue"
        batchRequest.entries() shouldBeEqualTo emptyList<DeleteMessageBatchRequestEntry>()

        val entry = deleteMessageBatchRequestEntryOf("message", "receipt")
        entry.id() shouldBeEqualTo "message"
        entry.receiptHandle() shouldBeEqualTo "receipt"

        deleteAliasOf("alias/example").aliasName() shouldBeEqualTo "alias/example"
        getObjectRequestOf("bucket", "key", null, null).bucket() shouldBeEqualTo "bucket"
        headObjectRequestOf("bucket", "key").key() shouldBeEqualTo "key"

        val delete = io.bluetape4k.aws.s3.model.deleteOf(objectIdentifier("a"))
        delete.objects().size shouldBeEqualTo 1
        val deleteObjectsRequest = io.bluetape4k.aws.s3.model.deleteObjectsRequestOf("bucket", delete, "requester")
        deleteObjectsRequest.requestPayerAsString() shouldBeEqualTo "requester"
    }

    private fun assertMethod(
        className: String,
        methodName: String,
        vararg parameterTypes: KClass<*>,
    ) {
        Class.forName(className).getMethod(
            methodName,
            *parameterTypes.map { parameterType ->
                if (parameterType == Boolean::class) {
                    requireNotNull(Boolean::class.javaPrimitiveType)
                } else {
                    parameterType.java
                }
            }.toTypedArray(),
        )
    }
}
