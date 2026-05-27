package io.bluetape4k.aws.dynamodb.model

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContainSame
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.dynamodb.model.ProjectionType

class ProjectionSupportTest {

    @Test
    fun `Projection builder DSL sets type and attributes`() {
        val projection = Projection {
            projectionType(ProjectionType.INCLUDE)
            nonKeyAttributes("a", "b")
        }

        projection.projectionType() shouldBeEqualTo ProjectionType.INCLUDE
        projection.nonKeyAttributes().shouldContainSame(listOf("a", "b"))
    }

    @Test
    fun `projectionOf with ProjectionType builds projection`() {
        val projection = projectionOf(ProjectionType.KEYS_ONLY, listOf("x"))

        projection.projectionType() shouldBeEqualTo ProjectionType.KEYS_ONLY
        projection.nonKeyAttributes().shouldContainSame(listOf("x"))
    }

    @Test
    fun `projectionOf with string type resolves ProjectionType`() {
        val projection = projectionOf("ALL", null)

        projection.shouldNotBeNull()
        projection.projectionType() shouldBeEqualTo ProjectionType.ALL
        projection.nonKeyAttributes().isEmpty().shouldBeTrue()
    }
}
