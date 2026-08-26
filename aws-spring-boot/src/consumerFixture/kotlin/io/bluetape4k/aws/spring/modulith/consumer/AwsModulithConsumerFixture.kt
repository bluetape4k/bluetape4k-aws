package io.bluetape4k.aws.spring.modulith.consumer

import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistration
import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistry

/** Spring Modulith public registration API를 사용하는 외부 consumer fixture입니다. */
class FixtureEvent(val id: String)

val fixtureRegistry = AwsModulithEventTypeRegistry.of(
    AwsModulithEventTypeRegistration(
        type = "fixture.event",
        version = 1,
        eventClass = FixtureEvent::class.java,
        eventId = FixtureEvent::id,
    ),
)
