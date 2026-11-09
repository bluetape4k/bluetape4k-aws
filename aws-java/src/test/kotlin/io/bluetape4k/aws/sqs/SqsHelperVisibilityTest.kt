package io.bluetape4k.aws.sqs

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class SqsHelperVisibilityTest {

    @Test
    fun `receive validation helpers keep private implementation and old bridge`() {
        assertPrivate("io.bluetape4k.aws.sqs.SqsClientExtensionsKt", "validateReceiveMessageCountInternal")
        assertPublic("io.bluetape4k.aws.sqs.SqsClientExtensionsKt", "validateReceiveMessageCount")
        assertPrivate("io.bluetape4k.aws.sqs.SqsAsyncClientExtensionsKt", "validateReceiveMessageCountAsync")
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
