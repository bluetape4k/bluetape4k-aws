package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SqsObservationDocumentationParityTest {

    private val repositoryRoot: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    @Test
    fun `English and Korean manuals use the tested customization example`() {
        val source = taggedSource(
            read("aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/" +
                "SqsObservationCustomizationExampleTest.kt"),
        )
        val english = fencedExample(
            read("docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md"),
        )
        val korean = fencedExample(
            read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md"),
        )

        english shouldBeEqualTo source
        korean shouldBeEqualTo source
    }

    @Test
    fun `English and Korean manuals keep the SQS observation contract aligned`() {
        val englishStorage = read("docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md")
        val koreanStorage = read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md")
        val englishRuntime = read("docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        val koreanRuntime = read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        val requiredTokens = listOf(
            "bluetape4k.aws.sqs.observation.enabled",
            "bluetape4k.aws.sqs.receive",
            "bluetape4k.aws.sqs.process",
            "bluetape4k.aws.sqs.acknowledgement",
            "SqsObservationContextCustomizer",
            "SqsObservationConvention",
            "SqsObservationFactory",
            "ObservationRegistry.NOOP",
            "BT4K-SQS-OBS-101",
            "BT4K-SQS-OBS-201",
            "BT4K-SQS-OBS-202",
            "reason=heartbeat_telemetry_setup",
            "STOPPING_RECEIVE -> DRAINING -> STOPPED",
            "FlociServer.Launcher.floci",
            "#453",
        )

        requiredTokens.forEach { token ->
            val english = if (token.startsWith("BT4K-") || token.startsWith("STOPPING") || token == "#453") {
                englishRuntime
            } else {
                englishStorage
            }
            val korean = if (token.startsWith("BT4K-") || token.startsWith("STOPPING") || token == "#453") {
                koreanRuntime
            } else {
                koreanStorage
            }
            english shouldContain token
            korean shouldContain token
        }
        englishStorage.lineSequence().count { it.startsWith("## ") } shouldBeEqualTo
            koreanStorage.lineSequence().count { it.startsWith("## ") }
        englishRuntime.lineSequence().count { it.startsWith("## ") } shouldBeEqualTo
            koreanRuntime.lineSequence().count { it.startsWith("## ") }
    }

    private fun taggedSource(source: String): String = source
        .also {
            it shouldContain "// tag::sqs-observation-customization[]"
            it shouldContain "// end::sqs-observation-customization[]"
        }
        .substringAfter("// tag::sqs-observation-customization[]")
        .substringBefore("// end::sqs-observation-customization[]")
        .trimIndent()
        .trim()

    private fun fencedExample(manual: String): String = manual
        .also {
            it shouldContain "<!-- sqs-observation-customization:start -->"
            it shouldContain "<!-- sqs-observation-customization:end -->"
        }
        .substringAfter("<!-- sqs-observation-customization:start -->")
        .substringBefore("<!-- sqs-observation-customization:end -->")
        .substringAfter("```kotlin")
        .substringBefore("```")
        .trimIndent()
        .trim()

    private fun read(relativePath: String): String =
        Files.readString(repositoryRoot.resolve(relativePath))
}
