package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.codec.Base58
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.services.sqs.model.SqsException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SqsAutomaticBatchExceptionsTest {

    @Test
    fun `send exception keeps normalized result and redacts raw failure graph`() {
        val entryId = entryId("send")
        val result = SqsSendManyResult(
            successful = emptyList(),
            failed = listOf(SqsBatchEntryFailure(entryId, SqsBatchFailureKind.TRANSPORT, null)),
        )
        val exception = SqsSendBatchFailedException(result)

        exception.result shouldBeEqualTo result
        exception.cause.shouldBeNull()
        exception.suppressed.shouldBeEmpty()
        val rendered = "${exception.message}$exception${exception.cause}${exception.suppressed.toList()}"
        rendered shouldNotContain entryId
        rendered shouldNotContain "body-secret"
        rendered shouldNotContain "https://sqs.local/queue-secret"
        rendered shouldNotContain "receipt-secret"
        rendered shouldNotContain "raw-sdk-error"
        rendered shouldNotContain "\r"
        rendered shouldNotContain "\n"
        roundTrip(exception).result shouldBeEqualTo result
    }

    @Test
    fun `close and startup exceptions retain only safe component counts`() {
        val close = SqsBatchCloseException(
            listOf(
                SqsBatchCleanupComponent.TIMEOUT,
                SqsBatchCleanupComponent.MANAGER,
                SqsBatchCleanupComponent.MANAGER,
            ),
        )
        close.components shouldBeEqualTo listOf(
            SqsBatchCleanupComponent.MANAGER,
            SqsBatchCleanupComponent.TIMEOUT,
        )
        close.failureCount shouldBeEqualTo 2
        close.cause.shouldBeNull()
        close.suppressed.shouldBeEmpty()

        val startup = SqsBatchStartupException(
            startupComponent = SqsBatchStartupComponent.TRANSPORT,
            cleanupComponents = listOf(
                SqsBatchCleanupComponent.EXECUTOR,
                SqsBatchCleanupComponent.EXECUTOR,
            ),
        )
        startup.startupComponent shouldBeEqualTo SqsBatchStartupComponent.TRANSPORT
        startup.cleanupComponents shouldBeEqualTo listOf(SqsBatchCleanupComponent.EXECUTOR)
        startup.cleanupFailureCount shouldBeEqualTo 1
        startup.cause.shouldBeNull()
        startup.suppressed.shouldBeEmpty()

        close.message shouldContain "MANAGER"
        close.message shouldContain "failureCount=2"
        startup.message shouldContain "TRANSPORT"
        startup.message shouldContain "cleanupFailureCount=1"

        val rendered = "$close$startup"
        rendered shouldNotContain "body-secret"
        rendered shouldNotContain "queue-secret"
        rendered shouldNotContain "receipt-secret"
        rendered shouldNotContain "entry-secret"
        rendered shouldNotContain "raw-sdk-error"
        rendered shouldNotContain "\r"
        rendered shouldNotContain "\n"
        roundTrip(close).components shouldBeEqualTo close.components
        roundTrip(startup).cleanupComponents shouldBeEqualTo startup.cleanupComponents

        listOf(
            SqsSendBatchFailedException::class.java,
            SqsBatchCloseException::class.java,
            SqsBatchStartupException::class.java,
        ).forEach { type ->
            val serialVersionUid = type.getDeclaredField("serialVersionUID").apply { isAccessible = true }
            serialVersionUid.getLong(null) shouldBeEqualTo 1L
        }
    }

    @Test
    fun `failure normalizer unwraps wrappers and keeps only allow listed service codes`() {
        val entryId = entryId("failure")
        val service = SqsException.builder()
            .message("service-secret body-secret")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
            .build()
        val wrapped = java.util.concurrent.CompletionException(
            java.util.concurrent.ExecutionException(service),
        )
        val serviceFailure = normalizeBatchFailure(entryId, wrapped)
        serviceFailure.kind shouldBeEqualTo SqsBatchFailureKind.SERVICE
        serviceFailure.code shouldBeEqualTo "AccessDenied"

        val invalidCode = SqsException.builder()
            .message("raw-sdk-error")
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("bad code\r\n").build())
            .build()
        normalizeBatchFailure(entryId, invalidCode).code shouldBeEqualTo "UNKNOWN"

        val oversizedCode = SqsException.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("a".repeat(65)).build())
            .build()
        normalizeBatchFailure(entryId, oversizedCode).code shouldBeEqualTo "UNKNOWN"

        val missingDetails = SqsException.builder().message("raw-sdk-error").build()
        normalizeBatchFailure(entryId, missingDetails).kind shouldBeEqualTo SqsBatchFailureKind.TRANSPORT

        val transport = normalizeBatchFailure(entryId, IllegalStateException("raw-sdk-error"))
        transport.kind shouldBeEqualTo SqsBatchFailureKind.TRANSPORT
        transport.code.shouldBeNull()
    }

    @Test
    fun `startup exception exposes safe component data without throwable inputs`() {
        val first = SqsBatchStartupException(SqsBatchStartupComponent.MANAGER)
        val second = SqsBatchStartupException(
            SqsBatchStartupComponent.MANAGER,
            listOf(SqsBatchCleanupComponent.MANAGER),
        )

        first.startupComponent shouldBeEqualTo SqsBatchStartupComponent.MANAGER
        first.cleanupComponents.shouldBeEmpty()
        second.cleanupFailureCount shouldBeEqualTo 1
        "${first.message}$first${second.message}$second" shouldNotContain "Throwable"
    }

    private fun entryId(prefix: String): String = "$prefix-${Base58.randomString(16)}"

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(value) }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as T }
    }
}
