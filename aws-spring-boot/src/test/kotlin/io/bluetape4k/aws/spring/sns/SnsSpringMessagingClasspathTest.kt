package io.bluetape4k.aws.spring.sns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message

class SnsSpringMessagingClasspathTest {

    @Test
    fun `converter exposes only additive public descriptors`() {
        SnsBatchMessageConverter::class.java.constructors
            .map { it.parameterTypes.toList() }
            .toSet() shouldBeEqualTo setOf(
            emptyList(),
            listOf(SnsPayloadSerializer::class.java),
        )

        SnsBatchMessageConverter::class.java.methods
            .filter { it.name == "convert" || it.name == "convertAll" }
            .map { it.name to it.parameterTypes.firstOrNull() }
            .toSet() shouldBeEqualTo setOf(
            "convert" to Message::class.java,
            "convertAll" to String::class.java,
        )
    }

    @Test
    fun `legacy SNS consumer remains usable when messaging classes are denied`() {
        val fixtureBytes = requireNotNull(javaClass.getResourceAsStream(LEGACY_FIXTURE_RESOURCE)) {
            "legacy fixture resource is missing: $LEGACY_FIXTURE_RESOURCE"
        }.use { it.readBytes() }
        val loader = MessagingDenyingFixtureClassLoader(
            parent = javaClass.classLoader,
            fixtureClassName = LEGACY_FIXTURE_CLASS_NAME,
            fixtureBytes = fixtureBytes,
        )

        val fixture = loader.loadClass(LEGACY_FIXTURE_CLASS_NAME).getDeclaredConstructor().newInstance()
        fixture::class.java.classLoader shouldBeEqualTo loader
        Class.forName("io.bluetape4k.aws.spring.sns.SnsOperations", false, loader)
        runCatching {
            Class.forName("org.springframework.messaging.Message", false, loader)
        }
        loader.deniedLookup.shouldBeTrue()
    }

    @Test
    fun `spring messaging dependency is compileOnly in the module source contract`() {
        val cwd = java.io.File(System.getProperty("user.dir"))
        val moduleRoot = if (cwd.name == "aws-spring-boot") cwd else cwd.resolve("aws-spring-boot")
        val repoRoot = moduleRoot.parentFile
        val buildFile = moduleRoot.resolve("build.gradle.kts").readText()
        val catalog = repoRoot.resolve("gradle/libs.versions.toml").readText()

        buildFile.contains("compileOnly(libs.spring.messaging)").shouldBeTrue()
        buildFile.contains("api(libs.spring.messaging)").shouldBeEqualTo(false)
        catalog.contains("spring-messaging = { module = \"org.springframework:spring-messaging\" }")
            .shouldBeTrue()
    }

    private companion object {
        const val LEGACY_FIXTURE_RESOURCE =
            "/sns-abi/io/bluetape4k/aws/spring/sns/consumer/LegacySnsOperationsFixture.class"
        const val LEGACY_FIXTURE_CLASS_NAME =
            "io.bluetape4k.aws.spring.sns.consumer.LegacySnsOperationsFixture"
    }
}

private class MessagingDenyingFixtureClassLoader(
    parent: ClassLoader,
    private val fixtureClassName: String,
    private val fixtureBytes: ByteArray,
) : ClassLoader(parent) {

    var deniedLookup: Boolean = false
        private set

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("org.springframework.messaging.")) {
            deniedLookup = true
            throw ClassNotFoundException(name)
        }
        return super.loadClass(name, resolve)
    }

    protected override fun findClass(name: String): Class<*> =
        if (name == fixtureClassName) {
            defineClass(name, fixtureBytes, 0, fixtureBytes.size)
        } else {
            super.findClass(name)
        }
}
