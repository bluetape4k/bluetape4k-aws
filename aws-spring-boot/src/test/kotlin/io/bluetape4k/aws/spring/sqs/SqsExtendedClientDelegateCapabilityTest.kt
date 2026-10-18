@file:Suppress("MaxLineLength")

package io.bluetape4k.aws.spring.sqs

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class SqsExtendedClientDelegateCapabilityTest {

    companion object: KLogging()

    @Test
    fun `full request capability is additive and default template exposes it`() {
        SqsFullRequestOperations::class.java
            .isAssignableFrom(SqsCoroutinesTemplate::class.java).shouldBeTrue()

        SqsOperations::class.java
            .isAssignableFrom(SqsFullRequestOperations::class.java).shouldBeTrue()
    }

    @Test
    fun `legacy operations remain unchanged`() {
        SqsOperations::class.java.declaredMethods
            .count { method ->
                method.name == "send" && method.parameterTypes.any { it == SqsSendRequest::class.java }
            } shouldBeEqualTo 1

        SqsOperations::class.java.declaredMethods
            .single { method ->
                method.name == "send" && method.parameterTypes.any { it == SqsSendRequest::class.java }
            }
            .parameterTypes.any { it == SqsSendRequest::class.java }.shouldBeTrue()
    }
}
