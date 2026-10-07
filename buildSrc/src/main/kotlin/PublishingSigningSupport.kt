import io.bluetape4k.gradle.NormalizedSigningKeyId
import io.bluetape4k.gradle.normalizeSigningKeyId
import io.bluetape4k.gradle.resolveSigningKey
import io.bluetape4k.gradle.resolveSigningKeyId
import groovy.util.Node
import groovy.xml.XmlParser
import groovy.xml.XmlUtil
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension

/**
 * Project property 또는 환경 변수에서 값을 조회합니다.
 */
fun Project.getEnvOrProperty(propertyKey: String, envKey: String): String =
    findProperty(propertyKey) as? String ?: System.getenv(envKey).orEmpty()

data class CentralPublishingConfig(
    val username: String,
    val password: String,
)

/**
 * Central Portal 자격증명을 project property / 환경 변수에서 로딩합니다.
 *
 * Property keys: `central.user`, `central.password`
 * Env var keys:  `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`
 */
fun Project.resolveCentralPublishingConfig(): CentralPublishingConfig = CentralPublishingConfig(
    username = getEnvOrProperty("central.user", "CENTRAL_USERNAME")
        .ifBlank { getEnvOrProperty("centralPortalUsername", "CENTRAL_USERNAME") },
    password = getEnvOrProperty("central.password", "CENTRAL_PASSWORD")
        .ifBlank { getEnvOrProperty("centralPortalPassword", "CENTRAL_PASSWORD") },
)

data class SigningConfig(
    val keyId: String,
    val key: String,
    val password: String,
    val useGpgCmd: Boolean,
    val gpgExecutable: String,
    val gpgKeyName: String,
)

/**
 * Signing 설정을 project property / 환경 변수에서 로딩합니다.
 *
 * Env var keys: `SIGNING_KEY_ID`, `SIGNING_KEY`, `SIGNING_PASSWORD`
 */
fun Project.resolveSigningConfig(): SigningConfig {
    val normalizedKeyId: NormalizedSigningKeyId =
        normalizeSigningKeyId(getEnvOrProperty("signingKeyId", "SIGNING_KEY_ID"))
    val keyId = resolveSigningKeyId(normalizedKeyId.value)
    normalizedKeyId.warning?.let(project.logger::warn)
    val key = resolveSigningKey(getEnvOrProperty("signingKey", "SIGNING_KEY"))
    val password = getEnvOrProperty("signingPassword", "SIGNING_PASSWORD")
    val useGpgCmd = getEnvOrProperty("signingUseGpgCmd", "SIGNING_USE_GPG_CMD").toBoolean()
    val gpgExecutable = getEnvOrProperty("signing.gnupg.executable", "GPG_EXECUTABLE")
        .ifBlank { "/opt/homebrew/bin/gpg" }
    val gpgKeyName = getEnvOrProperty("signing.gnupg.keyName", "GPG_KEY_NAME").ifBlank { keyId }
    return SigningConfig(keyId, key, password, useGpgCmd, gpgExecutable, gpgKeyName)
}

/**
 * Maven publication 서명을 설정합니다.
 * - CI: `SIGNING_KEY` + `SIGNING_PASSWORD` 환경 변수로 in-memory PGP 서명
 * - 로컬: `signingUseGpgCmd=true` 또는 gpg-cmd 설정으로 서명
 */
fun Project.configurePublishingSigning(publicationName: String) {
    tasks.withType<GenerateMavenPom>().configureEach {
        doLast("normalizePublishedMavenPom") {
            val pomFile = destination
            if (pomFile.isFile) {
                val pom = XmlParser(false, false).parse(pomFile)
                normalizeMavenDependencyManagement(pom)
                pomFile.writeText(XmlUtil.serialize(pom))
            }
        }
    }

    val config = resolveSigningConfig()
    extensions.configure<SigningExtension> {
        when {
            config.key.isNotBlank() && config.password.isNotBlank() -> {
                useInMemoryPgpKeys(config.keyId.ifBlank { null }, config.key, config.password)
                val publishing = project.extensions.findByType(PublishingExtension::class.java)
                publishing?.publications?.findByName(publicationName)?.let { sign(it) }
            }
            config.useGpgCmd -> {
                if (file(config.gpgExecutable).exists()) {
                    project.extensions.extraProperties["signing.gnupg.executable"] = config.gpgExecutable
                }
                if (config.gpgKeyName.isNotBlank()) {
                    project.extensions.extraProperties["signing.gnupg.keyName"] = config.gpgKeyName
                }
                useGpgCmd()
                val publishing = project.extensions.findByType(PublishingExtension::class.java)
                publishing?.publications?.findByName(publicationName)?.let { sign(it) }
            }
            else -> {
                // 서명 키 없음 — 로컬 개발 빌드에서는 서명 건너뜀
            }
        }
    }
}

private data class ManagedDependencyKey(
    val groupId: String,
    val artifactId: String,
    val type: String,
    val classifier: String,
) {
    override fun toString(): String = listOf(groupId, artifactId, type, classifier)
        .joinToString(":")
}

private fun Node.childText(name: String): String =
    children()
        .filterIsInstance<Node>()
        .firstOrNull { it.name().toString() == name }
        ?.value()
        ?.toString()
        ?.trim()
        .orEmpty()

private fun Node.managedDependencyKey(): ManagedDependencyKey = ManagedDependencyKey(
    groupId = childText("groupId"),
    artifactId = childText("artifactId"),
    type = childText("type").ifBlank { "jar" },
    classifier = childText("classifier"),
)

private fun Node.fingerprint(): String {
    val attributes = attributes().toString()
    val childrenFingerprint = children()
        .filterIsInstance<Node>()
        .joinToString("|") { it.fingerprint() }
    val value = if (children().filterIsInstance<Node>().isEmpty()) {
        value()?.toString()?.trim().orEmpty()
    } else {
        ""
    }
    return "${name()}[$attributes]($value){$childrenFingerprint}"
}

/**
 * Removes fully identical dependencyManagement entries from a generated Maven POM.
 *
 * Gradle's dependency-management and platform publication paths can contribute the
 * same managed dependency more than once. Maven rejects duplicate keys, so identical
 * nodes are collapsed while entries with different content fail loudly instead of
 * silently changing the published dependency contract.
 */
fun normalizeMavenDependencyManagement(pom: Node) {
    pom.children()
        .filterIsInstance<Node>()
        .filter { it.name().toString() == "dependencyManagement" }
        .flatMap { dependencyManagement ->
            dependencyManagement.children()
                .filterIsInstance<Node>()
                .filter { it.name().toString() == "dependencies" }
        }
        .forEach { dependencies ->
            val firstByKey = linkedMapOf<ManagedDependencyKey, Node>()
            val duplicates = mutableListOf<Node>()
            dependencies.children()
                .filterIsInstance<Node>()
                .filter { it.name().toString() == "dependency" }
                .forEach { dependency ->
                    val key = dependency.managedDependencyKey()
                    val first = firstByKey.putIfAbsent(key, dependency)
                    when {
                        first == null -> Unit
                        first.fingerprint() == dependency.fingerprint() -> duplicates += dependency
                        else -> throw GradleException(
                            "Conflicting dependencyManagement entries for $key in published Maven POM",
                        )
                    }
                }
            duplicates.forEach { dependencies.children().remove(it) }
        }
}
