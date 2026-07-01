package io.bluetape4k.aws.kotlin.eventbridge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.aws.kotlin.eventbridge.model.putEventsRequestEntryOf
import io.bluetape4k.aws.kotlin.eventbridge.model.putEventsRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.putRuleRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.putTargetsRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.removeTargetsRequestOf
import io.bluetape4k.aws.kotlin.eventbridge.model.targetOf
import org.junit.jupiter.api.Test

class EventBridgeRequestSupportTest {

    @Test
    fun `putEventsRequestOf validates entry count`() {
        assertFailsWith<IllegalArgumentException> {
            putEventsRequestOf(emptyList())
        }

        val entries = (1..11).map {
            putEventsRequestEntryOf("app.test", "event.$it", """{"id":$it}""")
        }

        assertFailsWith<IllegalArgumentException> {
            putEventsRequestOf(entries)
        }
    }

    @Test
    fun `putEventsRequestEntryOf validates required fields and optional resources`() {
        assertFailsWith<IllegalArgumentException> {
            putEventsRequestEntryOf("", "type", "{}")
        }
        assertFailsWith<IllegalArgumentException> {
            putEventsRequestEntryOf("source", " ", "{}")
        }
        assertFailsWith<IllegalArgumentException> {
            putEventsRequestEntryOf("source", "type", " ")
        }
        assertFailsWith<IllegalArgumentException> {
            putEventsRequestEntryOf("source", "type", "{}", resources = listOf("arn", " "))
        }

        val entry = putEventsRequestEntryOf("source", "type", "{}", resources = emptyList())

        entry.source shouldBeEqualTo "source"
        entry.resources.orEmpty().size shouldBeEqualTo 0
    }

    @Test
    fun `putRuleRequestOf requires event pattern or schedule expression`() {
        assertFailsWith<IllegalArgumentException> {
            putRuleRequestOf(name = "rule")
        }
        assertFailsWith<IllegalArgumentException> {
            putRuleRequestOf(name = "rule", eventPattern = " ")
        }

        val request = putRuleRequestOf(name = "rule", eventPattern = """{"source":["app.test"]}""")

        request.name shouldBeEqualTo "rule"
        request.eventPattern shouldBeEqualTo """{"source":["app.test"]}"""
    }

    @Test
    fun `putTargetsRequestOf and targetOf validate target limits`() {
        assertFailsWith<IllegalArgumentException> {
            targetOf("", "arn:aws:lambda:us-east-1:123456789012:function:test")
        }
        assertFailsWith<IllegalArgumentException> {
            targetOf("target", " ")
        }
        assertFailsWith<IllegalArgumentException> {
            putTargetsRequestOf("rule", emptyList())
        }

        val targets = (1..11).map {
            targetOf("target-$it", "arn:aws:lambda:us-east-1:123456789012:function:test-$it")
        }

        assertFailsWith<IllegalArgumentException> {
            putTargetsRequestOf("rule", targets)
        }
    }

    @Test
    fun `removeTargetsRequestOf validates id limits`() {
        assertFailsWith<IllegalArgumentException> {
            removeTargetsRequestOf("rule", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            removeTargetsRequestOf("rule", listOf("target", " "))
        }
        assertFailsWith<IllegalArgumentException> {
            removeTargetsRequestOf("rule", (1..11).map { "target-$it" })
        }

        val request = removeTargetsRequestOf("rule", listOf("target-1"))

        request.rule shouldBeEqualTo "rule"
        request.ids shouldBeEqualTo listOf("target-1")
    }
}
