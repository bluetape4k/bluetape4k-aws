package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

class AwsConfigDataDocumentationParityTest {

    private val repositoryRoot: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    private val manualRoot: Path by lazy {
        Path.of(System.getenv("BLUETAPE4K_MANUAL_ROOT") ?: repositoryRoot.resolve("docs/manual").toString())
    }

    @Test
    fun `README summarizes the manual source of truth`() {
        val readme = read("aws-spring-boot/README.md")
        val koreanReadme = read("aws-spring-boot/README.ko.md")

        readme shouldContain "https://bluetape4k.github.io/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-spring-boot/runtime-operations/"
        koreanReadme shouldContain "https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/bluetape4k-aws-spring-boot/runtime-operations/"
    }

    @Test
    fun `English and Korean manual pages keep ConfigData snippets and contract headings aligned`() {
        val english = read("docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        val korean = read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        val requiredTokens = listOf(
            "spring.config.import",
            "aws-s3:",
            "aws-parameterstore:",
            "aws-secretsmanager:",
            "aws-app-config:",
            "optional:",
            "Floci",
            "LocalStack",
            "required-minimum-poll-interval",
            "Spring Cloud Context",
        )

        requiredTokens.forEach { token ->
            english shouldContain token
            korean shouldContain token
        }
        english.lineSequence().filter { it.startsWith("## ") }.count() shouldBeEqualTo
            korean.lineSequence().filter { it.startsWith("## ") }.count()
    }

    private fun read(relativePath: String): String {
        if (relativePath.startsWith("docs/manual/")) {
            assumeTrue(Files.isDirectory(manualRoot), "central manual checkout is not available")
            return Files.readString(manualRoot.resolve(relativePath.removePrefix("docs/manual/")))
        }
        return Files.readString(repositoryRoot.resolve(relativePath))
    }
}
