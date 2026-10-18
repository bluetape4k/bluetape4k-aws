package io.bluetape4k.aws.spring.connection

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test

class AwsServiceConnectionLifecycleTest {

    companion object: KLogging()

    @Test
    fun `normal cleanup runs fixture context and container in order`() {
        val events = mutableListOf<String>()
        executeLifecycle(events, fixtureCreated = true)
        events shouldBeEqualTo listOf("fixture cleanup", "context close", "container teardown")
    }

    @Test
    fun `primary failure keeps cleanup and close failures sanitized and suppressed`() {
        val events = mutableListOf<String>()
        val primary = IllegalStateException("primary failure")
        val thrown = assertFailsWith<IllegalStateException> {
            executeLifecycle(
                events = events,
                primary = primary,
                fixtureFailure = IllegalStateException("secret-cleanup-value"),
                closeFailure = IllegalStateException("secret-close-value"),
            )
        }

        thrown.shouldBeSameInstanceAs(primary)
        thrown.suppressed.size shouldBeEqualTo 2
        thrown.suppressed.all { it.message.orEmpty().contains("secret") }.shouldBeFalse()
        events shouldBeEqualTo listOf("fixture cleanup", "context close", "container teardown")
    }

    @Test
    fun `cleanup failure is promoted when there is no primary failure`() {
        val events = mutableListOf<String>()

        val thrown = assertFailsWith<IllegalStateException> {
            executeLifecycle(
                events = events,
                fixtureFailure = IllegalStateException("secret-cleanup-value"),
                closeFailure = IllegalStateException("secret-close-value"),
            )
        }

        log.debug { "thrown=$thrown" }
        thrown.message shouldContain "IllegalStateException"
        thrown.message shouldNotContain "secret"
        thrown.suppressed shouldHaveSize 1
        
        events shouldBeEqualTo listOf("fixture cleanup", "context close", "container teardown")
    }

    @Test
    fun `cancellation is rethrown after every cleanup boundary`() {
        val events = mutableListOf<String>()
        val cancellation = CancellationException("cancelled by test")

        val thrown = assertFailsWith<CancellationException> {
            executeLifecycle(
                events = events,
                fixtureFailure = cancellation,
                closeFailure = IllegalStateException("secret-close-value"),
                teardownFailure = IllegalStateException("secret-teardown-value"),
            )
        }

        log.debug { "thrown=$thrown" }
        thrown shouldBeSameInstanceAs cancellation
        thrown.suppressed shouldHaveSize 2
        thrown.suppressed.all { it.message.orEmpty().contains("secret") }.shouldBeFalse()
        
        events shouldBeEqualTo listOf("fixture cleanup", "context close", "container teardown")
    }

    @Test
    fun `startup failure skips absent fixture but still closes context before teardown`() {
        val events = mutableListOf<String>()
        executeLifecycle(events, fixtureCreated = false)
        events shouldBeEqualTo listOf("fixture cleanup skipped", "context close", "container teardown")
    }

    private fun executeLifecycle(
        events: MutableList<String>,
        fixtureCreated: Boolean = true,
        primary: Throwable? = null,
        fixtureFailure: Throwable? = null,
        closeFailure: Throwable? = null,
        teardownFailure: Throwable? = null,
    ) {
        var failure = primary

        fun runStep(name: String, action: () -> Unit) {
            events += name
            try {
                action()
            } catch (candidate: Throwable) {
                if (failure == null) {
                    failure = candidate as? CancellationException ?: sanitize(candidate)
                } else {
                    failure?.addSuppressed(sanitize(candidate))
                }
            }
        }

        if (fixtureCreated) {
            runStep("fixture cleanup") { throwIfPresent(fixtureFailure) }
        } else {
            events += "fixture cleanup skipped"
        }
        runStep("context close") { throwIfPresent(closeFailure) }
        runStep("container teardown") { throwIfPresent(teardownFailure) }

        failure?.let { throw it }
    }

    private fun throwIfPresent(failure: Throwable?) {
        if (failure != null) {
            throw failure
        }
    }

    private fun sanitize(failure: Throwable): IllegalStateException =
        IllegalStateException("sanitized lifecycle failure: ${failure::class.java.name}")
}
