package io.bluetape4k.aws.sns

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class SnsBatchValidationVisibilityTest {

    @Test
    fun `batch validation helpers keep private implementation and old bridges`() {
        assertPrivate("io.bluetape4k.aws.sns.SnsAsyncClientExtensionsKt", "validatePublishBatchRequestLocal")
        assertPrivate("io.bluetape4k.aws.sns.model.PublishKt", "validatePublishBatchRequest")
        assertPrivate("io.bluetape4k.aws.sns.SnsBatchValidationKt", "validatePublishBatchRequestInternal")
        assertPublic("io.bluetape4k.aws.sns.SnsBatchValidationKt", "validatePublishBatchRequest")
    }

    private fun assertPrivate(className: String, methodName: String) {
        val methods = Class.forName(className).declaredMethods
            .filter { it.name == methodName }
        methods.isNotEmpty().shouldBeTrue()
        methods.all { Modifier.isPrivate(it.modifiers) }.shouldBeTrue()
    }

    private fun assertPublic(className: String, methodName: String) {
        val methods = Class.forName(className).declaredMethods
            .filter { it.name == methodName }
        methods.isNotEmpty().shouldBeTrue()
        methods.all { Modifier.isPublic(it.modifiers) }.shouldBeTrue()
    }
}
