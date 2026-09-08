package io.bluetape4k.aws.ktor

import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProvider
import aws.smithy.kotlin.runtime.collections.Attributes
import aws.smithy.kotlin.runtime.http.HttpCall
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngineConfig
import aws.smithy.kotlin.runtime.http.request.HttpRequest
import aws.smithy.kotlin.runtime.operation.ExecutionContext
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.ktor.http.Url
import io.ktor.client.HttpClientConfig
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class AwsKtorDefaultsTest {

    @Test
    fun `toString hides endpoint user info query and path`() {
        val defaults = AwsKtorDefaults(
            region = "us-east-1",
            endpointOverride = Url(
                "https://endpoint-user:endpoint-password@example.com/private-path?token=endpoint-token",
            ),
        )

        val text = defaults.toString()

        text shouldNotContain "endpoint-user"
        text shouldNotContain "endpoint-password"
        text shouldNotContain "private-path"
        text shouldNotContain "endpoint-token"
    }


    @Test
    fun `toString redacts runtime collaborators without invoking their toString`() {
        val fixture = secretDefaults()
        val equivalent = fixture.defaults()

        val text = fixture.defaults.toString()

        text shouldNotContain JAVA_CREDENTIAL_SECRET
        text shouldNotContain KOTLIN_CREDENTIAL_SECRET
        text shouldNotContain CUSTOMIZER_SECRET
        text shouldNotContain HTTP_ENGINE_SECRET
        fixture.assertNoCollaboratorToStringCalls()
        fixture.defaults shouldBeEqualTo equivalent
        fixture.defaults.hashCode() shouldBeEqualTo equivalent.hashCode()
    }

    @Test
    fun `toString with limit redacts runtime collaborators without invoking their toString`() {
        val fixture = secretDefaults()

        val text = fixture.defaults.toString(limit = 1)

        text shouldNotContain JAVA_CREDENTIAL_SECRET
        text shouldNotContain KOTLIN_CREDENTIAL_SECRET
        text shouldNotContain CUSTOMIZER_SECRET
        text shouldNotContain HTTP_ENGINE_SECRET
        fixture.assertNoCollaboratorToStringCalls()
    }

    private fun secretDefaults(): SecretDefaultsFixture {
        val javaCredentialsProvider = SecretJavaCredentialsProvider()
        val kotlinCredentialsProvider = SecretKotlinCredentialsProvider()
        val customizer = SecretHttpClientCustomizer()
        val httpClientEngine = SecretHttpClientEngine()

        return SecretDefaultsFixture(
            javaCredentialsProvider = javaCredentialsProvider,
            kotlinCredentialsProvider = kotlinCredentialsProvider,
            customizer = customizer,
            httpClientEngine = httpClientEngine,
        )
    }

    private class SecretDefaultsFixture(
        private val javaCredentialsProvider: SecretJavaCredentialsProvider,
        private val kotlinCredentialsProvider: SecretKotlinCredentialsProvider,
        private val customizer: SecretHttpClientCustomizer,
        private val httpClientEngine: SecretHttpClientEngine,
    ) {
        fun defaults(): AwsKtorDefaults = AwsKtorDefaults(
            region = "ap-northeast-2",
            javaCredentialsProvider = javaCredentialsProvider,
            kotlinCredentialsProvider = kotlinCredentialsProvider,
            kotlinHttpClient = httpClientEngine,
            httpClientCustomizers = listOf(customizer),
        )

        val defaults: AwsKtorDefaults
            get() = defaults()

        fun assertNoCollaboratorToStringCalls() {
            javaCredentialsProvider.toStringCalls.get() shouldBeEqualTo 0
            kotlinCredentialsProvider.toStringCalls.get() shouldBeEqualTo 0
            customizer.toStringCalls.get() shouldBeEqualTo 0
            httpClientEngine.toStringCalls.get() shouldBeEqualTo 0
        }
    }

    private class SecretJavaCredentialsProvider : AwsCredentialsProvider {
        val toStringCalls = AtomicInteger()

        override fun resolveCredentials(): AwsCredentials =
            AwsBasicCredentials.create("access-key", JAVA_CREDENTIAL_SECRET)

        override fun toString(): String {
            toStringCalls.incrementAndGet()
            return JAVA_CREDENTIAL_SECRET
        }
    }

    private class SecretKotlinCredentialsProvider : CredentialsProvider {
        val toStringCalls = AtomicInteger()

        override suspend fun resolve(attributes: Attributes): Credentials = error("unused")

        override fun toString(): String {
            toStringCalls.incrementAndGet()
            return KOTLIN_CREDENTIAL_SECRET
        }
    }

    private class SecretHttpClientCustomizer : AwsKtorHttpClientCustomizer {
        val toStringCalls = AtomicInteger()

        override fun customize(config: HttpClientConfig<*>) = Unit

        override fun toString(): String {
            toStringCalls.incrementAndGet()
            return CUSTOMIZER_SECRET
        }
    }

    private class SecretHttpClientEngine : HttpClientEngine {
        val toStringCalls = AtomicInteger()

        override val coroutineContext: CoroutineContext = Dispatchers.Unconfined
        override val config: HttpClientEngineConfig = HttpClientEngineConfig.Default

        override suspend fun roundTrip(context: ExecutionContext, request: HttpRequest): HttpCall =
            error("unused")

        override fun toString(): String {
            toStringCalls.incrementAndGet()
            return HTTP_ENGINE_SECRET
        }
    }

    private companion object {
        const val JAVA_CREDENTIAL_SECRET = "java-credential-secret"
        const val KOTLIN_CREDENTIAL_SECRET = "kotlin-credential-secret"
        const val CUSTOMIZER_SECRET = "customizer-secret"
        const val HTTP_ENGINE_SECRET = "http-engine-secret"
    }
}
