package io.bluetape4k.aws.kotlin.s3tables.model

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class S3TablesHelperVisibilityTest {

    @Test
    fun `request validation helpers keep private implementation and old bridges`() {
        val methods = Class.forName("io.bluetape4k.aws.kotlin.s3tables.model.S3TablesRequestSupportKt")
            .declaredMethods
            .filter {
                it.name in setOf(
                    "requireSelectorInternal",
                    "requireNamespaceInternal",
                    "validateListTableBucketsInternal",
                    "validateListNamespacesInternal",
                    "validateListTablesInternal",
                    "validatePageInternal",
                    "requireTableLookupInternal",
                )
            }
        methods.isNotEmpty().shouldBeTrue()
        methods.all { Modifier.isPrivate(it.modifiers) }.shouldBeTrue()

        val bridges = Class.forName("io.bluetape4k.aws.kotlin.s3tables.model.S3TablesRequestSupportKt")
            .declaredMethods
            .filter {
                it.name in setOf(
                    "requireSelector",
                    "requireNamespace",
                    "validateList",
                    "validatePage",
                    "requireTableLookup",
                )
            }
        bridges.isNotEmpty().shouldBeTrue()
        bridges.all { Modifier.isPublic(it.modifiers) }.shouldBeTrue()
    }
}
