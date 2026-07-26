@file:Suppress("DEPRECATION")

package io.bluetape4k.aws.testcontainers

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.useLines

class AwsTestcontainersReusePolicyTest {

    @Test
    fun `test and example paths do not enable reusable containers implicitly`() {
        val violations = executablePolicyFiles()
            .flatMap { file -> file.reusePolicyViolations() }

        violations.shouldBeEmpty()
    }

    @Test
    fun `aws emulator containers disable docker reuse by default`() {
        FlociServer().isReuseRequested.shouldBeFalse()

        val localStack = LocalStackServer()
        localStack.isReuseRequested.shouldBeFalse()
    }

    @Test
    fun `aws emulator reusable containers require explicit local opt in`() {
        FlociServer(reuse = true).isReuseRequested.shouldBeTrue()

        val localStack = LocalStackServer(reuse = true)
        localStack.isReuseRequested.shouldBeTrue()
    }

    private fun executablePolicyFiles(): List<Path> {
        val root = repositoryRoot()
        return SCAN_ROOTS
            .map(root::resolve)
            .filter(Files::exists)
            .flatMap { scanRoot ->
                if (scanRoot.isDirectory()) {
                    Files.walk(scanRoot).use { stream ->
                        stream
                            .filter(Files::isRegularFile)
                            .filter { it.isExecutablePolicyFile() }
                            .toList()
                    }
                } else {
                    listOf(scanRoot).filter { it.isExecutablePolicyFile() }
                }
            }
    }

    private fun Path.isExecutablePolicyFile(): Boolean {
        if (name in EXCLUDED_FILES) {
            return false
        }
        if (any { it.name in EXCLUDED_PATH_SEGMENTS }) {
            return false
        }

        return extension in SCANNED_EXTENSIONS
    }

    private fun Path.reusePolicyViolations(): List<String> {
        val root = repositoryRoot()
        return useLines { lines ->
            lines.withIndex()
                .filter { (_, line) ->
                    FORBIDDEN_REUSE_PATTERNS.any { it.containsMatchIn(line) }
                }
                .map { (index, line) ->
                    "${relativeTo(root)}:${index + 1}: ${line.trim()}"
                }
                .toList()
        }
    }

    private fun repositoryRoot(): Path {
        return generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
    }

    private val GenericContainer<*>.isReuseRequested: Boolean
        get() = GenericContainer::class.java
            .getDeclaredField("shouldBeReused")
            .apply { isAccessible = true }
            .getBoolean(this)

    companion object {
        private val SCAN_ROOTS = listOf(
            ".github",
            "aws-java",
            "aws-kotlin",
            "aws-exposed",
            "aws-ktor",
            "aws-spring-boot",
            "examples",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
        )

        private val SCANNED_EXTENSIONS = setOf("kt", "kts", "properties", "yml", "yaml")

        private val EXCLUDED_FILES = setOf(
            "AwsTestcontainersReusePolicyTest.kt",
            "AwsExposedTestcontainersReusePolicyTest.kt",
        )

        private val EXCLUDED_PATH_SEGMENTS = setOf("build", ".gradle", ".idea", ".omx")

        private val FORBIDDEN_REUSE_PATTERNS = listOf(
            Regex("""withReuse\s*\(\s*true\s*\)"""),
            Regex("""\breuse\s*=\s*true\b"""),
            Regex("""reuse\s*:\s*Boolean\s*=\s*true\b"""),
            Regex("""testcontainers\.reuse\.enable\s*=\s*true\b"""),
        )
    }
}
