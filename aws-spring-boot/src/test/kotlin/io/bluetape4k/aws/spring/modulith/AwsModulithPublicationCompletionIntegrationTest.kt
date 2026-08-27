package io.bluetape4k.aws.spring.modulith

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry
import org.springframework.modulith.events.core.EventPublicationRepository
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.core.PublicationTargetIdentifier
import org.springframework.modulith.events.core.TargetEventPublication
import org.springframework.modulith.events.support.CompletionRegisteringAdvisor
import org.springframework.modulith.events.support.EventExternalizationTransport
import org.springframework.modulith.events.support.EventExternalizerModuleListener
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream

class AwsModulithPublicationCompletionIntegrationTest {

    @Test
    fun `publication completes only after the actual transport future succeeds`() {
        val fixture = completionFixture("serialized:success")
        val event = TestEvent("success")
        fixture.store(event)

        val completion = fixture.listener.externalize(event)

        fixture.serializerCalls.get() shouldBeEqualTo 1
        fixture.repository.findIncompletePublications().size shouldBeEqualTo 1
        completion.isDone.shouldBeFalse()

        fixture.transportFuture.complete("provider-message-id")
        completion.join()

        fixture.repository.findIncompletePublications().isEmpty().shouldBeTrue()
        fixture.repository.findCompletedPublications().size shouldBeEqualTo 1
    }

    @Test
    fun `exceptional transport future remains incomplete for resubmission`() {
        val fixture = completionFixture("serialized:retry")
        val event = TestEvent("retry")
        fixture.store(event)

        val completion = fixture.listener.externalize(event)
        fixture.transportFuture.completeExceptionally(IllegalStateException("publish failed"))

        assertFailsWith<CompletionException> { completion.join() }
        fixture.repository.findIncompletePublications().size shouldBeEqualTo 1
        fixture.repository.findCompletedPublications().isEmpty().shouldBeTrue()
    }

    private fun completionFixture(expectedPayload: String): CompletionFixture {
        val clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC)
        val repository = RecordingEventPublicationRepository()
        val registry = DefaultEventPublicationRegistry(repository, clock)
        val transportFuture = CompletableFuture<Any>()
        val serializerCalls = AtomicInteger()
        val serializer = object : EventSerializer {
            override fun serialize(event: Any): Any = "serialized:${(event as TestEvent).id}"
                .also { serializerCalls.incrementAndGet() }

            override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T =
                type.cast(TestEvent(serialized.toString().substringAfter(':')))
        }
        val configuration = object : EventExternalizationConfiguration {
            override fun supports(event: Any): Boolean = event is TestEvent

            override fun map(event: Any): Any = serializer.serialize(event)

            override fun determineTarget(event: Any): RoutingTarget = RoutingTarget.forTarget("events").withoutKey()

            override fun getHeadersFor(event: Any): Map<String, Any> = emptyMap()

            override fun serializeExternalization(): Boolean = true
        }
        val transport = EventExternalizationTransport { payload, _ ->
            payload shouldBeEqualTo expectedPayload
            transportFuture
        }
        val target = EventExternalizerModuleListener(configuration, transport)
        val proxyFactory = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvisor(CompletionRegisteringAdvisor { registry })
        }
        val listener = proxyFactory.proxy as EventExternalizerModuleListener
        return CompletionFixture(listener, repository, registry, transportFuture, serializerCalls)
    }

    private data class CompletionFixture(
        val listener: EventExternalizerModuleListener,
        val repository: RecordingEventPublicationRepository,
        val registry: DefaultEventPublicationRegistry,
        val transportFuture: CompletableFuture<Any>,
        val serializerCalls: AtomicInteger,
    ) {
        fun store(event: Any) {
            registry.store(event, Stream.of(PublicationTargetIdentifier.of(listenerId())))
        }

        private fun listenerId(): String {
            val method = EventExternalizerModuleListener::class.java.getMethod("externalize", Any::class.java)
            return TransactionalApplicationListenerMethodAdapter(
                "awsModulithExternalizer",
                EventExternalizerModuleListener::class.java,
                method,
            ).listenerId
        }
    }

    private class RecordingEventPublicationRepository : EventPublicationRepository {
        private val publications = CopyOnWriteArrayList<TargetEventPublication>()

        override fun create(publication: TargetEventPublication): TargetEventPublication =
            publication.also(publications::add)

        override fun markCompleted(event: Any, identifier: PublicationTargetIdentifier, completionDate: Instant) {
            findIncompletePublicationsByEventAndTargetIdentifier(event, identifier)
                .ifPresent { it.markCompleted(completionDate) }
        }

        override fun markCompleted(identifier: UUID, completionDate: Instant) {
            publications.firstOrNull { it.identifier == identifier }?.markCompleted(completionDate)
        }

        override fun findIncompletePublications(): List<TargetEventPublication> =
            publications.filter { !it.isCompleted }

        override fun findIncompletePublicationsPublishedBefore(instant: Instant): List<TargetEventPublication> =
            findIncompletePublications().filter { it.publicationDate.isBefore(instant) }

        override fun findIncompletePublicationsByEventAndTargetIdentifier(
            event: Any,
            targetIdentifier: PublicationTargetIdentifier,
        ): Optional<TargetEventPublication> = Optional.ofNullable(
            findIncompletePublications().firstOrNull {
                it.event == event && it.targetIdentifier == targetIdentifier
            }
        )

        override fun findCompletedPublications(): List<TargetEventPublication> =
            publications.filter(TargetEventPublication::isCompleted)

        override fun deletePublications(identifiers: List<UUID>) {
            publications.removeIf { it.identifier in identifiers }
        }

        override fun deleteCompletedPublications() {
            publications.removeIf(TargetEventPublication::isCompleted)
        }

        override fun deleteCompletedPublicationsBefore(instant: Instant) {
            publications.removeIf {
                it.completionDate.map { completed -> completed.isBefore(instant) }.orElse(false)
            }
        }
    }

    private data class TestEvent(val id: String)
}
