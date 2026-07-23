package io.bluetape4k.aws.kotlin.bedrock

import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.kotlin.bedrock.model.textContents
import io.bluetape4k.aws.kotlin.bedrock.model.userMessageOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class BedrockRuntimeSmokeTest {

    @Test
    @Tag(BEDROCK_SMOKE_TAG)
    fun `converse returns at least one native text block`() = runSuspendIO {
        val region = requiredSmokeInput(BEDROCK_REGION)
        val modelId = requiredSmokeInput(BEDROCK_MODEL_ID)
        val startedAt = System.nanoTime()

        try {
            val response = withBedrockRuntimeClient(
                region = region,
                builder = { callTimeout = 30.seconds },
            ) { client ->
                withTimeout(35.seconds) {
                    client.converse(
                        modelId = modelId,
                        messages = listOf(userMessageOf("Return one short text response.")),
                        inferenceConfig = InferenceConfiguration { maxTokens = 8 },
                    )
                }
            }
            response.textContents().isNotEmpty().shouldBeTrue()
            println(
                smokeEvidence(
                    result = "PASS",
                    elapsedMillis = elapsedMillis(startedAt),
                    region = region,
                    modelId = modelId,
                    requestId = KOTLIN_SUCCESS_REQUEST_ID,
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Throwable) {
            throw sanitizedSmokeFailure(
                failure = failure,
                elapsedMillis = elapsedMillis(startedAt),
                region = region,
                modelId = modelId,
            )
        }
    }

    @Test
    @OptIn(InternalApi::class)
    fun `sanitized failure exposes only allowlisted evidence`() {
        val failure = ServiceException(SENTINEL_MESSAGE, IllegalStateException(SENTINEL_CAUSE))
        failure.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = "AccessDenied"
        failure.sdkErrorMetadata.attributes[ServiceErrorMetadata.RequestId] = "request-123"

        val sanitized = sanitizedSmokeFailure(
            failure = failure,
            elapsedMillis = 17L,
            region = "us-east-1",
            modelId = "approved-model",
        )

        sanitized.message shouldBeEqualTo
            "bedrock-smoke lane=kotlin result=FAIL elapsedMs=17 region=us-east-1 " +
            "modelId=approved-model exceptionClass=aws.smithy.kotlin.runtime.ServiceException " +
            "errorCode=AccessDenied requestId=request-123"
        sanitized.message.orEmpty() shouldNotContain SENTINEL_MESSAGE
        sanitized.message.orEmpty() shouldNotContain SENTINEL_CAUSE
        sanitized.cause.shouldBeNull()
        sanitized.suppressed.isEmpty().shouldBeTrue()
    }

    private companion object {
        const val BEDROCK_SMOKE_TAG = "bedrock-smoke"
        const val BEDROCK_REGION = "BEDROCK_REGION"
        const val BEDROCK_MODEL_ID = "BEDROCK_MODEL_ID"
        const val KOTLIN_SUCCESS_REQUEST_ID = "not-exposed-by-sdk-1.8.0"
        const val SENTINEL_MESSAGE = "sentinel-secret-message"
        const val SENTINEL_CAUSE = "sentinel-secret-cause"
    }
}

private fun requiredSmokeInput(name: String): String =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }
        ?: throw AssertionError("bedrock-smoke: missing or invalid input=$name")

private fun elapsedMillis(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L

private fun smokeEvidence(
    result: String,
    elapsedMillis: Long,
    region: String,
    modelId: String,
    requestId: String?,
): String =
    "bedrock-smoke lane=kotlin result=$result elapsedMs=$elapsedMillis region=$region " +
        "modelId=$modelId requestId=${requestId.orNotAvailable()}"

private fun sanitizedSmokeFailure(
    failure: Throwable,
    elapsedMillis: Long,
    region: String,
    modelId: String,
): AssertionError {
    val serviceFailure = failure as? ServiceException
    val evidence =
        "bedrock-smoke lane=kotlin result=FAIL elapsedMs=$elapsedMillis region=$region " +
            "modelId=$modelId exceptionClass=${failure.javaClass.name} " +
            "errorCode=${serviceFailure?.sdkErrorMetadata?.errorCode.orNotAvailable()} " +
            "requestId=${serviceFailure?.sdkErrorMetadata?.requestId.orNotAvailable()}"
    return AssertionError(evidence)
}

private fun String?.orNotAvailable(): String =
    this?.takeIf(String::isNotBlank) ?: "not-available"
