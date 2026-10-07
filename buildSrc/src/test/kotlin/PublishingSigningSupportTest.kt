import java.util.Base64
import groovy.util.Node
import groovy.xml.XmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import org.gradle.api.GradleException
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.testfixtures.ProjectBuilder

class PublishingSigningSupportTest {
    @Test
    fun `resolves escaped and base64 private key armor`() {
        val armor = "-----BEGIN PGP PRIVATE KEY BLOCK-----\\nkey-body\\n-----END PGP PRIVATE KEY BLOCK-----"
        val expected = armor.replace("\\n", "\n")

        assertEquals(expected, io.bluetape4k.gradle.resolveSigningKey(armor))
        assertEquals(expected, io.bluetape4k.gradle.resolveSigningKey(Base64.getEncoder().encodeToString(armor.toByteArray())))
    }

    @Test
    fun `normalizes prefixed long key id without leaking raw input`() {
        val raw = "0x1234567890ABCDEF"

        val normalized = io.bluetape4k.gradle.normalizeSigningKeyId(raw)

        assertEquals("0x90ABCDEF", normalized.value)
        assertNotNull(normalized.warning)
        assertFalse(normalized.warning.orEmpty().contains(raw))
    }

    @Test
    fun `uses normalized key id as blank gpg key name fallback`() {
        val project = ProjectBuilder.builder().build()
        project.extensions.extraProperties["signingKeyId"] = "0x1234567890ABCDEF"
        project.extensions.extraProperties["signingKey"] = ""
        project.extensions.extraProperties["signingPassword"] = ""
        project.extensions.extraProperties["signingUseGpgCmd"] = "true"
        project.extensions.extraProperties["signing.gnupg.keyName"] = ""

        val config = project.resolveSigningConfig()

        assertEquals("0x90ABCDEF", config.keyId)
        assertEquals("0x90ABCDEF", config.gpgKeyName)
    }

    @Test
    fun `removes fully identical managed dependencies`() {
        val pom = pomWithManagedDependencies(
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
        )

        normalizeMavenDependencyManagement(pom)

        assertEquals(1, managedDependencies(pom).size)
    }

    @Test
    fun `removes identical managed dependencies when child fields are reordered`() {
        val reordered = Node(null, "dependency").apply {
            appendNode("version", "2.2.20")
            appendNode("artifactId", "kotlin-stdlib")
            appendNode("groupId", "org.jetbrains.kotlin")
        }
        val pom = pomWithManagedDependencies(
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
            reordered,
        )

        normalizeMavenDependencyManagement(pom)

        assertEquals(1, managedDependencies(pom).size)
    }

    @Test
    fun `fails when managed dependencies share a key but differ`() {
        val pom = pomWithManagedDependencies(
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
        )

        val failure = assertFailsWith<GradleException> {
            normalizeMavenDependencyManagement(pom)
        }

        assertEquals(true, failure.message.orEmpty().contains("org.jetbrains.kotlin:kotlin-stdlib:jar:"))
    }

    @Test
    fun `normalizes duplicate managed dependencies after pom task actions`() {
        val task = pomTaskWith(
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
        )

        task.actions.forEach { it.execute(task) }

        val pom = XmlParser(false, false).parse(task.destination)
        assertEquals(1, managedDependencies(pom).count())
    }

    @Test
    fun `rejects conflicting managed dependencies after pom task actions`() {
        val task = pomTaskWith(
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.20"),
            managedDependency("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
        )

        assertFailsWith<GradleException> {
            task.actions.forEach { it.execute(task) }
        }
    }

    private fun pomTaskWith(vararg dependencies: Node): GenerateMavenPom {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply("maven-publish")
        project.pluginManager.apply("signing")
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val publication = publishing.publications.create("Test", MavenPublication::class.java)
        publication.groupId = "io.example"
        publication.artifactId = "test"
        publication.version = "1.0.0"
        publication.pom.withXml {
            val dependencyManagement = asNode().appendNode("dependencyManagement")
            val managedDependencies = dependencyManagement.appendNode("dependencies")
            dependencies.forEach(managedDependencies::append)
        }
        project.configurePublishingSigning("Test")
        return project.tasks.getByName("generatePomFileForTestPublication") as GenerateMavenPom
    }

    private fun pomWithManagedDependencies(vararg dependencies: Node): Node {
        val pom = Node(null, "project")
        val dependencyManagement = pom.appendNode("dependencyManagement")
        val managedDependencies = dependencyManagement.appendNode("dependencies")
        dependencies.forEach(managedDependencies::append)
        return pom
    }

    private fun managedDependency(groupId: String, artifactId: String, version: String): Node =
        Node(null, "dependency").apply {
            appendNode("groupId", groupId)
            appendNode("artifactId", artifactId)
            appendNode("version", version)
        }

    private fun managedDependencies(pom: Node): List<Node> =
        pom.children()
            .filterIsInstance<Node>()
            .first { it.name().toString() == "dependencyManagement" }
            .children()
            .filterIsInstance<Node>()
            .first { it.name().toString() == "dependencies" }
            .children()
            .filterIsInstance<Node>()
            .filter { it.name().toString() == "dependency" }
}
