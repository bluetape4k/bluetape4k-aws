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

    private val lifecycleStarted = AtomicBoolean(false)
    private val containerMap = ConcurrentHashMap<String, SqsMessageListenerContainer>()
    private val activeIds = ConcurrentHashMap.newKeySet<String>()
    private val stoppingIds = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLock = ReentrantLock()

    val containers: List<SqsMessageListenerContainer>
        get() = containerMap.values.toList()

    internal fun register(id: String, container: SqsMessageListenerContainer) {
        lifecycleLock.withLock {
            require(containerMap.putIfAbsent(id, container) == null) {
                "Duplicate SQS listener id: $id"
            }
            if (lifecycleStarted.get() && container.isAutoStartup) {
                container.start()
                activeIds.add(id)
            }
        }
    }

    internal fun getContainer(id: String): SqsMessageListenerContainer? =
        containerMap[id]

    /** 등록된 하나의 listener를 시작합니다. asynchronous stop 중에는 새 generation을 만들지 않습니다. */
    fun start(id: String) {
        lifecycleLock.withLock {
            check(!stoppingIds.contains(id)) { "listener is stopping" }
            val container = containerMap[id] ?: error("Unknown SQS listener id: $id")
            container.start()
            lifecycleStarted.set(true)
            activeIds.add(id)
        }
    }

    /** 등록된 하나의 listener를 중지하고 callback을 정확히 한 번 호출합니다. */
    fun stop(id: String, callback: Runnable = Runnable {}) {
        val container = lifecycleLock.withLock {
            val value = containerMap[id] ?: error("Unknown SQS listener id: $id")
            if (!stoppingIds.add(id)) {
                callback.run()
                return
            }
            activeIds.remove(id)
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
        lifecycleLock.withLock {
            if (!lifecycleStarted.compareAndSet(false, true)) return
            containerMap.forEach { (id, container) ->
                if (container.isAutoStartup) {
                    container.start()
                    activeIds.add(id)
                }
            }
        }
    }

    override fun stop() {
        stop(Runnable {})
    }

    override fun stop(callback: Runnable) {
        val snapshot: List<SqsMessageListenerContainer>
        lifecycleLock.withLock {
            if (!lifecycleStarted.compareAndSet(true, false)) {
                callback.run()
                return
            }
            snapshot = containers.toList()
            activeIds.clear()
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

    override fun isRunning(): Boolean = activeIds.isNotEmpty()

    override fun getPhase(): Int =
        containers.maxOfOrNull { it.phase } ?: Int.MAX_VALUE
}
