package io.bluetape4k.aws.spring.config

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class AwsConfigDataBootstrapBridgeTest {

    @Test
    fun `initialized-only holder does not create or close unused client`() {
        var created = 0
        var closed = 0
        val holder = AwsConfigDataBootstrapBridge.InitializedClientHolder(
            create = { created += 1; "client" },
            close = { closed += 1 },
        )

        holder.closeIfInitialized()
        created shouldBeEqualTo 0
        closed shouldBeEqualTo 0

        holder.getOrCreate() shouldBeEqualTo "client"
        holder.getOrCreate() shouldBeEqualTo "client"
        created shouldBeEqualTo 1

        holder.closeIfInitialized()
        holder.closeIfInitialized()
        closed shouldBeEqualTo 1
    }

    @Test
    fun `class guard reports dependency without resolving SDK type`() {
        AwsConfigDataBootstrapBridge.isClassPresent("java.lang.String") shouldBeEqualTo true
        AwsConfigDataBootstrapBridge.isClassPresent("no.such.aws.Client") shouldBeEqualTo false
    }
}
