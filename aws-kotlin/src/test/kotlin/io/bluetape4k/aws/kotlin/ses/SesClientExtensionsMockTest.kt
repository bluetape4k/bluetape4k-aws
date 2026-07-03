package io.bluetape4k.aws.kotlin.ses

import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.ses.model.GetTemplateRequest
import aws.sdk.kotlin.services.ses.model.GetTemplateResponse
import aws.sdk.kotlin.services.ses.model.Template
import aws.sdk.kotlin.services.ses.model.TemplateDoesNotExistException
import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SesClientExtensionsMockTest {

    private val client = mockk<SesClient>()

    @BeforeEach
    fun setup() {
        clearMocks(client)
    }

    @Test
    fun `getTemplateOrNull returns template when request succeeds`() = runSuspendIO {
        val templateName = "welcome-template"
        coEvery { client.getTemplate(any<GetTemplateRequest>()) } returns GetTemplateResponse {
            template = Template {
                this.templateName = templateName
                subjectPart = "Hello"
                textPart = "Welcome"
            }
        }

        val result = client.getTemplateOrNull(templateName)

        result.shouldNotBeNull()
        result.templateName shouldBeEqualTo templateName
        coVerify(exactly = 1) { client.getTemplate(any<GetTemplateRequest>()) }
    }

    @Test
    fun `getTemplateOrNull returns null for missing template errors`() = runSuspendIO {
        coEvery { client.getTemplate(any<GetTemplateRequest>()) } throws
                TemplateDoesNotExistException { message = "missing template" }

        val result = client.getTemplateOrNull("missing-template")

        result.shouldBeNull()
        coVerify(exactly = 1) { client.getTemplate(any<GetTemplateRequest>()) }
    }

    @Test
    fun `getTemplateOrNull propagates access denied errors`() = runSuspendIO {
        coEvery { client.getTemplate(any<GetTemplateRequest>()) } throws
                serviceException(errorCode = "AccessDenied", statusCode = 403)

        assertFailsWith<ServiceException> {
            client.getTemplateOrNull("private-template")
        }

        coVerify(exactly = 1) { client.getTemplate(any<GetTemplateRequest>()) }
    }

    @OptIn(InternalApi::class)
    private fun serviceException(
        errorCode: String,
        statusCode: Int,
    ): ServiceException {
        val exception = ServiceException("test error")
        exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ErrorCode] = errorCode
        exception.sdkErrorMetadata.attributes[ServiceErrorMetadata.ProtocolResponse] =
            HttpResponse(status = HttpStatusCode.fromValue(statusCode))
        return exception
    }
}
