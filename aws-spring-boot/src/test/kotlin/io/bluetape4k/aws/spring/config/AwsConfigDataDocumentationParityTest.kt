package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AwsConfigDataDocumentationParityTest {

    private val repositoryRoot: Path by lazy {
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    @Test
    fun `README summarizes the manual source of truth`() {
        val readme = read("aws-spring-boot/README.md")
        val koreanReadme = read("aws-spring-boot/README.ko.md")

        readme shouldContain "docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md"
        koreanReadme shouldContain "docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md"
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
            "optional:",
            "Floci",
            "LocalStack",
        )

        requiredTokens.forEach { token ->
            english shouldContain token
            korean shouldContain token
        }
        english.lineSequence().filter { it.startsWith("## ") }.count() shouldBeEqualTo
            korean.lineSequence().filter { it.startsWith("## ") }.count()
    }

    private fun read(relativePath: String): String =
        Files.readString(repositoryRoot.resolve(relativePath))
}
