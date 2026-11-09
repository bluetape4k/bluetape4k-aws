package io.bluetape4k.aws.sfn

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class SfnHelperVisibilityTest {

    @Test
    fun `execution source validators remain private and old bridges stay public`() {
        assertPrivate("validateStateMachineSource")
        assertPrivate("validateMapRunSource")
        assertPublic("requireStateMachineSource")
        assertPublic("requireMapRunSource")
    }

    private fun assertPrivate(name: String) {
        val methods = Class.forName("io.bluetape4k.aws.sfn.SfnExtensionsKt").declaredMethods
            .filter { it.name == name }
        methods.isNotEmpty().shouldBeTrue()
        methods.all { Modifier.isPrivate(it.modifiers) }.shouldBeTrue()
    }

    private fun assertPublic(name: String) {
        val methods = Class.forName("io.bluetape4k.aws.sfn.SfnExtensionsKt").declaredMethods
            .filter { it.name == name }
        methods.isNotEmpty().shouldBeTrue()
        methods.all { Modifier.isPublic(it.modifiers) }.shouldBeTrue()
    }
}
