package io.bluetape4k.aws.bedrock

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.aws.bedrock.model.textContents
import io.bluetape4k.aws.bedrock.model.userMessageOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import java.time.Duration
import java.util.concurrent.CancellationException

class BedrockRuntimeSmokeTest {

    @Test
    @Tag(BEDROCK_SMOKE_TAG)
    fun `converse returns at least one native text block`() {
        val regionName = requiredSmokeInput(BEDROCK_REGION)
        val modelId = requiredSmokeInput(BEDROCK_MODEL_ID)
        val startedAt = System.nanoTime()

        try {
            val overrideConfiguration = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(30))
                .build()
            bedrockRuntimeClientOf(region = Region.of(regionName)) {
                overrideConfiguration(overrideConfiguration)
            }.use { client ->
                val response = client.converse(
                    modelId = modelId,
                    messages = listOf(userMessageOf("Return one short text response.")),
                    inferenceConfig = InferenceConfiguration.builder()
                        .maxTokens(8)
                        .build(),
                )

                response.textContents().isNotEmpty().shouldBeTrue()
                println(
                    smokeEvidence(
                        result = "PASS",
                        elapsedMillis = elapsedMillis(startedAt),
                        region = regionName,
                        modelId = modelId,
                        requestId = response.responseMetadata().requestId(),
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Throwable) {
            throw sanitizedSmokeFailure(
                failure = failure,
                elapsedMillis = elapsedMillis(startedAt),
                region = regionName,
                modelId = modelId,
            )
        }
    }

    @Test
    fun `sanitized failure exposes only allowlisted evidence`() {
        val failure = SdkClientException.create(
            SENTINEL_MESSAGE,
            IllegalStateException(SENTINEL_CAUSE),
        )

        val sanitized = sanitizedSmokeFailure(
            failure = failure,
            elapsedMillis = 17L,
            region = "us-east-1",
            modelId = "approved-model",
        )

        sanitized.message shouldBeEqualTo
            "bedrock-smoke lane=java result=FAIL elapsedMs=17 region=us-east-1 " +
            "modelId=approved-model exceptionClass=software.amazon.awssdk.core.exception.SdkClientException " +
            "errorCode=not-available requestId=not-available"
        sanitized.message.orEmpty() shouldNotContain SENTINEL_MESSAGE
        sanitized.message.orEmpty() shouldNotContain SENTINEL_CAUSE
        sanitized.cause.shouldBeNull()
        sanitized.suppressed.isEmpty().shouldBeTrue()
    }

    private companion object {
        const val BEDROCK_SMOKE_TAG = "bedrock-smoke"
        const val BEDROCK_REGION = "BEDROCK_REGION"
        const val BEDROCK_MODEL_ID = "BEDROCK_MODEL_ID"
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
    Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

private fun smokeEvidence(
    result: String,
    elapsedMillis: Long,
    region: String,
    modelId: String,
    requestId: String?,
): String =
    "bedrock-smoke lane=java result=$result elapsedMs=$elapsedMillis region=$region " +
        "modelId=$modelId requestId=${requestId.orNotAvailable()}"

private fun sanitizedSmokeFailure(
    failure: Throwable,
    elapsedMillis: Long,
    region: String,
    modelId: String,
): AssertionError {
    val errorCode = (failure as? AwsServiceException)
        ?.awsErrorDetails()
        ?.errorCode()
    val requestId = (failure as? SdkServiceException)?.requestId()
    val evidence =
        "bedrock-smoke lane=java result=FAIL elapsedMs=$elapsedMillis region=$region " +
            "modelId=$modelId exceptionClass=${failure.javaClass.name} " +
            "errorCode=${errorCode.orNotAvailable()} requestId=${requestId.orNotAvailable()}"
    return AssertionError(evidence)
}

private fun String?.orNotAvailable(): String =
    this?.takeIf(String::isNotBlank) ?: "not-available"
