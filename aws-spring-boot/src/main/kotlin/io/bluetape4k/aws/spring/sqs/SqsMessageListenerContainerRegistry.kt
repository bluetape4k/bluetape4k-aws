package io.bluetape4k.aws.spring.sqs

import org.springframework.context.SmartLifecycle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 애플리케이션의 SQS 리스너 컨테이너를 수명주기 단위로 관리하는 레지스트리.
 */
class SqsMessageListenerContainerRegistry: SmartLifecycle {

    private val running = AtomicBoolean(false)
    private val containerMap = ConcurrentHashMap<String, SqsMessageListenerContainer>()
    private val stoppingIds = ConcurrentHashMap.newKeySet<String>()
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

    /** 등록된 하나의 listener를 시작합니다. asynchronous stop 중에는 새 generation을 만들지 않습니다. */
    fun start(id: String) {
        val container = lifecycleLock.withLock {
            check(!stoppingIds.contains(id)) { "listener is stopping" }
            containerMap[id] ?: error("Unknown SQS listener id: $id")
        }
        running.set(true)
        container.start()
    }

    /** 등록된 하나의 listener를 중지하고 callback을 정확히 한 번 호출합니다. */
    fun stop(id: String, callback: Runnable = Runnable {}) {
        val container = lifecycleLock.withLock {
            val value = containerMap[id] ?: error("Unknown SQS listener id: $id")
            if (!stoppingIds.add(id)) {
                callback.run()
                return
            }
            running.set(false)
            value
        }
        container.stop {
            try {
                callback.run()
            } finally {
                stoppingIds.remove(id)
            }
        }
    }

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
            containerMap.keys.forEach { stoppingIds.add(it) }
        }
        if (snapshot.isEmpty()) {
            callback.run()
            return
        }
        val remaining = AtomicInteger(snapshot.size)
        snapshot.forEach {
            it.stop {
                if (remaining.decrementAndGet() == 0) {
                    try {
                        callback.run()
                    } finally {
                        stoppingIds.clear()
                    }
                }
            }
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun getPhase(): Int =
        containers.maxOfOrNull { it.phase } ?: Int.MAX_VALUE
}
