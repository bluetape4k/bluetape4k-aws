package io.bluetape4k.aws.spring.connection

import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.bluetape4k.testcontainers.aws.LocalStackServer
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource
import org.testcontainers.containers.Container
import java.net.URI
import java.util.function.Supplier

/** Returns whether a container is one of the explicitly supported bluetape4k AWS wrappers. */
@Suppress("DEPRECATION")
internal fun isSupportedAwsEmulator(container: Container<*>): Boolean =
    container.javaClass == FlociServer::class.java || container.javaClass == LocalStackServer::class.java

/**
 * Copies and validates the wrapper values without retaining a live container reference.
 */
internal fun snapshotAwsServiceConnection(
    container: Container<*>,
    serviceName: String,
): AwsServiceConnectionValues? {
    if (!isSupportedAwsEmulator(container)) {
        return null
    }

    val emulator = (container as? AwsEmulatorServer) ?: throw malformedDetails(serviceName, 1)

    val endpoint = emulator.awsEndpoint
    val region = emulator.regionName
    val accessKey = emulator.awsAccessKey
    val secretKey = emulator.awsSecretKey

    val credentialsPresent = accessKey.isNotBlank() && secretKey.isNotBlank()
    if (!endpoint.isAbsolute || region.isBlank() || !credentialsPresent) {
        throw malformedDetails(serviceName, 1)
    }

    return AwsServiceConnectionValues(
        endpoint = endpoint,
        region = region,
        accessKey = accessKey,
        secretKey = secretKey,
    )
}

internal fun malformedDetails(serviceName: String, candidateCount: Int): AwsServiceConnectionConfigurationException =
    AwsServiceConnectionConfigurationException(
        reason = AwsServiceConnectionConfigurationException.Reason.MALFORMED_DETAILS,
        serviceNames = setOf(serviceName),
        candidateCount = candidateCount,
    )

internal fun factoryLinkageFailure(
    serviceName: String,
    candidateCount: Int,
    failure: Throwable,
): AwsServiceConnectionConfigurationException =
    AwsServiceConnectionConfigurationException(
        reason = AwsServiceConnectionConfigurationException.Reason.FACTORY_LINKAGE,
        serviceNames = setOf(serviceName),
        candidateCount = candidateCount,
        causeSummary = failure::class.qualifiedName,
    )

private fun rethrowFactoryLinkageFailure(serviceName: String, failure: Throwable): Nothing =
    throw factoryLinkageFailure(serviceName, 1, failure)

/** Initializes Boot details and converts linkage failures to the stable public error. */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun initializeAwsServiceConnectionDetails(
    serviceName: String,
    initialize: () -> Unit,
    snapshot: () -> AwsServiceConnectionValues?,
): AwsServiceConnectionValues {
    try {
        initialize()
    } catch (failure: AwsServiceConnectionConfigurationException) {
        throw failure
    } catch (failure: Exception) {
        throw factoryLinkageFailure(serviceName, 1, failure)
    }
    return snapshot() ?: throw malformedDetails(serviceName, 1)
}

/**
 * Spring Boot keeps the container supplier package-private. Resolve it only for the
 * fail-closed concrete-wrapper allow-list check; the initialized details subclass still
 * obtains its container through Boot's protected lifecycle API.
 */
@Suppress("UNCHECKED_CAST")
internal fun <C: Container<*>> resolveContainer(
    source: ContainerConnectionSource<C>,
    serviceName: String,
): C {
    return try {
        val method = source.javaClass.getDeclaredMethod("getContainerSupplier")
        check(method.trySetAccessible()) { "ContainerConnectionSource supplier is not accessible" }
        val supplier = method.invoke(source) as Supplier<*>
        supplier.get() as C
    } catch (failure: ReflectiveOperationException) {
        rethrowFactoryLinkageFailure(serviceName, failure)
    } catch (failure: IllegalStateException) {
        rethrowFactoryLinkageFailure(serviceName, failure)
    } catch (failure: ClassCastException) {
        rethrowFactoryLinkageFailure(serviceName, failure)
    } catch (failure: SecurityException) {
        rethrowFactoryLinkageFailure(serviceName, failure)
    }
}
