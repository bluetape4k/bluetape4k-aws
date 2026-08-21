package io.bluetape4k.aws.spring.config

import org.springframework.boot.bootstrap.BootstrapRegistry
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext
import org.springframework.context.ApplicationListener
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * AWS SDK가 없는 classpath에서도 ConfigData SPI가 로드되도록 하는 경계입니다.
 * SDK 의존성은 class name guard를 통과한 뒤 supplier가 실제로 호출될 때만
 * adapter에서 참조합니다.
 */
internal object AwsConfigDataBootstrapBridge {

    fun isClassPresent(
        className: String,
        classLoader: ClassLoader? = Thread.currentThread().contextClassLoader,
    ): Boolean = try {
        Class.forName(className, false, classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }

    fun requireClass(
        className: String,
        dependency: String,
        classLoader: ClassLoader? = Thread.currentThread().contextClassLoader,
    ) {
        check(isClassPresent(className, classLoader)) {
            "AWS SDK dependency is required for ConfigData import. Add '$dependency'."
        }
    }

    fun registerClient(
        bootstrapContext: ConfigurableBootstrapContext,
        clientClassName: String,
        dependency: String,
        supplier: () -> Any,
        closer: (Any) -> Unit,
    ): Boolean {
        requireClass(clientClassName, dependency)
        val clientType = Class.forName(clientClassName, false, Thread.currentThread().contextClassLoader)
        if (bootstrapContext.isRegistered(clientType)) {
            return false
        }

        val holder = InitializedClientHolder(supplier, closer)
        @Suppress("UNCHECKED_CAST")
        bootstrapContext.registerIfAbsent(
            clientType as Class<Any>,
            BootstrapRegistry.InstanceSupplier.from { holder.getOrCreate() },
        )
        bootstrapContext.addCloseListener(ApplicationListener { holder.closeIfInitialized() })
        return true
    }

    /** supplier가 실제로 호출된 client만 종료하는 bootstrap lifecycle holder입니다. */
    internal class InitializedClientHolder<T>(
        private val create: () -> T,
        private val close: (T) -> Unit,
    ) {
        private val lock = ReentrantLock()
        private var initialized: T? = null

        fun getOrCreate(): T = lock.withLock {
            initialized ?: create().also { initialized = it }
        }

        fun closeIfInitialized() {
            val value = lock.withLock {
                initialized.also { initialized = null }
            }
            value?.let(close)
        }
    }
}
