package io.bluetape4k.aws.spring.sqs

import org.springframework.context.SmartLifecycle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Registry that manages application SQS listener containers by lifecycle.
 */
class SqsMessageListenerContainerRegistry: SmartLifecycle {

    private val running = AtomicBoolean(false)
    private val containerMap = ConcurrentHashMap<String, SqsMessageListenerContainer>()
    private val lifecycleLock = ReentrantLock()

    val containers: List<SqsMessageListenerContainer>
        get() = containerMap.values.toList()

    internal fun register(id: String, container: SqsMessageListenerContainer) {
        val shouldStart: Boolean
        lifecycleLock.withLock {
            require(containerMap.putIfAbsent(id, container) == null) {
                "Duplicate SQS listener id: $id"
            }
            shouldStart = running.get() && container.isAutoStartup
        }
        if (shouldStart) container.start()
    }

    internal fun getContainer(id: String): SqsMessageListenerContainer? =
        containerMap[id]

    override fun start() {
        val toStart: List<SqsMessageListenerContainer>
        lifecycleLock.withLock {
            if (!running.compareAndSet(false, true)) return
            toStart = containers.filter { it.isAutoStartup }
        }
        toStart.forEach { it.start() }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        val snapshot: List<SqsMessageListenerContainer>
        lifecycleLock.withLock {
            if (!running.compareAndSet(true, false)) {
                callback.run()
                return
            }
            snapshot = containers.toList()
        }
        if (snapshot.isEmpty()) {
            callback.run()
            return
        }
        val remaining = AtomicInteger(snapshot.size)
        snapshot.forEach {
            it.stop {
                if (remaining.decrementAndGet() == 0) {
                    callback.run()
                }
            }
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun getPhase(): Int =
        containers.maxOfOrNull { it.phase } ?: Int.MAX_VALUE
}
