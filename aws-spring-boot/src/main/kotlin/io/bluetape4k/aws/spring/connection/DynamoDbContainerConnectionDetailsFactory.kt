package io.bluetape4k.aws.spring.connection

import org.springframework.boot.testcontainers.service.connection.ContainerConnectionDetailsFactory
import org.springframework.boot.testcontainers.service.connection.ContainerConnectionSource
import org.testcontainers.containers.Container

internal class DynamoDbContainerConnectionDetailsFactory :
    ContainerConnectionDetailsFactory<Container<*>, DynamoDbConnectionDetails>(
        "dynamodb",
        "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
) {
    override fun sourceAccepts(
        source: ContainerConnectionSource<Container<*>>,
        requiredContainerType: Class<*>,
        requiredConnectionDetailsType: Class<*>,
    ): Boolean =
        super.sourceAccepts(source, requiredContainerType, requiredConnectionDetailsType) ||
            source.accepts(null, requiredContainerType, requiredConnectionDetailsType)

    override fun getContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): DynamoDbConnectionDetails? {
        val container = resolveContainer(source, "dynamodb")
        return if (isSupportedAwsEmulator(container)) DynamoDbContainerConnectionDetails(source) else null
    }

    private class DynamoDbContainerConnectionDetails(
        source: ContainerConnectionSource<Container<*>>,
    ): ContainerConnectionDetailsFactory.ContainerConnectionDetails<Container<*>>(source), DynamoDbConnectionDetails {
        private var values: AwsServiceConnectionValues? = null

        override fun afterPropertiesSet() {
            values = initializeAwsServiceConnectionDetails(
                serviceName = "dynamodb",
                initialize = { super.afterPropertiesSet() },
                snapshot = { snapshotAwsServiceConnection(getContainer(), "dynamodb") },
            )
        }

        override val endpoint get() = requireValues().endpoint
        override val region get() = requireValues().region
        override val accessKey get() = requireValues().accessKey
        override val secretKey get() = requireValues().secretKey

        override fun toString(): String = values?.toString()
            ?: "DynamoDbConnectionDetails(endpoint=[UNINITIALIZED], region=[UNINITIALIZED], " +
            "accessKey=[REDACTED], secretKey=[REDACTED])"

        private fun requireValues(): AwsServiceConnectionValues =
            checkNotNull(values) { "DynamoDB connection details have not been initialized" }
    }
}
