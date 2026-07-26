package io.bluetape4k.aws.dynamodb.model

import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val projection = projection {
 *    projectionType(ProjectionType.ALL)
 *    nonKeyAttributes("name", "age")
 * }
 * ```
 *
 * @return Return value.
 */
inline fun Projection(
    builder: Projection.Builder.() -> Unit,
): Projection {
    return Projection.builder().apply(builder).build()
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val projection = projectionOf(ProjectionType.ALL, listOf("name", "age"))
 * ```
 *
 * @param projectionType Parameter.
 * @param nonKeyAttrs Parameter.
 *
 * @return Return value.
 */
fun projectionOf(
    projectionType: ProjectionType = ProjectionType.ALL,
    nonKeyAttrs: Collection<String>? = null,
): Projection {
    return Projection {
        projectionType(projectionType)
        nonKeyAttributes(nonKeyAttrs)
    }
}

/**
 * See the API documentation for details.
 *
 * ```kotlin
 * val projection = projectionOf("ALL", listOf("name", "age"))
 * ```
 *
 * @param projectionType Parameter.
 * @param nonKeyAttrs Parameter.
 *
 * @return Return value.
 */
fun projectionOf(
    projectionType: String,
    nonKeyAttrs: Collection<String>? = null,
): Projection {
    return Projection {
        projectionType(projectionType)
        nonKeyAttributes(nonKeyAttrs)
    }
}
