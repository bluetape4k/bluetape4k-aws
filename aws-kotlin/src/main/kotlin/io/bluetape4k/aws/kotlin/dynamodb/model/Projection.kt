package io.bluetape4k.aws.kotlin.dynamodb.model

import aws.sdk.kotlin.services.dynamodb.model.Projection
import aws.sdk.kotlin.services.dynamodb.model.ProjectionType

/**
 * Builds a DynamoDB [Projection] with a DSL block.
 *
 * ## Behavior and contract
 * - [projectionType] defaults to [ProjectionType.All], which projects every attribute.
 * - [nonKeyAttributes] is valid only with [ProjectionType.Include] and is omitted when null.
 * - Additional fields can be overridden through [builder].
 *
 * ```kotlin
 * val projection = projectionOf(ProjectionType.Include, listOf("name", "age"))
 * // projection.projectionType == ProjectionType.Include
 * // projection.nonKeyAttributes == listOf("name", "age")
 * ```
 *
 * @param projectionType projection type. Defaults to [ProjectionType.All].
 * @param nonKeyAttributes non-key attributes to include in the projection. Valid with [ProjectionType.Include].
 */
inline fun projectionOf(
    projectionType: ProjectionType = ProjectionType.All,
    nonKeyAttributes: List<String>? = null,
    crossinline builder: Projection.Builder.() -> Unit = {},
): Projection = Projection {
    this.projectionType = projectionType
    this.nonKeyAttributes = nonKeyAttributes

    builder()
}
