package io.bluetape4k.aws.ktor.cloudwatch

import io.bluetape4k.aws.ktor.AwsKtorCloudWatchLogsAsyncClientCustomizer
import io.bluetape4k.aws.ktor.AwsKtorDefaults
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsAsyncClient
import java.net.URI
import java.time.Duration

/**
 * Configuration for [CloudWatchLogsKtorPlugin].
 *
 * ## Contract
 *
 * Installing the plugin registers operations and runtime only. It does not
 * create log groups, create streams, or publish events unless configured or
 * invoked explicitly.
 */
class CloudWatchLogsKtorPluginConfig {

    /** Enables Ktor CloudWatch Logs runtime registration. */
    var enabled: Boolean = true

    /** Optional application-owned AWS SDK v2 CloudWatch Logs async client. */
    var cloudWatchLogsAsyncClient: CloudWatchLogsAsyncClient? = null

    /** Optional application-owned operations facade. */
    var cloudWatchLogsOperations: CloudWatchLogsKtorOperations? = null

    /** Optional CloudWatch Logs region used when the plugin creates the client. */
    var region: String? = null

    /** Optional CloudWatch Logs endpoint override used when the plugin creates the client. */
    var endpointOverride: URI? = null

    /** Optional credentials provider used when the plugin creates the client. */
    var credentialsProvider: AwsCredentialsProvider? = null

    /** Default CloudWatch Logs group name for operations that omit stream identity. */
    var logGroupName: String? = null

    /** Default CloudWatch Logs stream name for operations that omit stream identity. */
    var logStreamName: String? = null

    /** CloudWatch Logs PutLogEvents batch size. AWS allows 1..10000. */
    var batchSize: Int = CLOUDWATCH_LOGS_MAX_BATCH_SIZE

    /** Periodic flush interval for explicitly buffered log events. */
    var flushInterval: Duration = Duration.ofSeconds(5)

    /** Maximum time allowed for shutdown flush. */
    var shutdownFlushTimeout: Duration = Duration.ofSeconds(5)

    /** Creates the configured log group during application start. */
    var createLogGroupOnStart: Boolean = false

    /** Creates the configured log stream during application start. */
    var createLogStreamOnStart: Boolean = false

    private val clientCustomizers = mutableListOf<AwsKtorCloudWatchLogsAsyncClientCustomizer>()

    /**
     * Adds CloudWatch Logs async client builder customization for plugin-created clients.
     */
    fun cloudWatchLogsAsyncClient(customizer: AwsKtorCloudWatchLogsAsyncClientCustomizer) {
        clientCustomizers += customizer
    }

    internal fun toRuntime(defaults: AwsKtorDefaults = AwsKtorDefaults()): CloudWatchLogsKtorRuntime? {
        if (!enabled) {
            return null
        }

        val defaultLogStream = defaultLogStreamOrNull()
        cloudWatchLogsOperations?.let {
            return CloudWatchLogsKtorRuntime(
                operations = it,
                logStream = defaultLogStream,
                batchSize = batchSize,
                flushInterval = flushInterval,
                shutdownFlushTimeout = shutdownFlushTimeout,
                createLogGroupOnStart = createLogGroupOnStart,
                createLogStreamOnStart = createLogStreamOnStart,
            )
        }

        val injectedClient = cloudWatchLogsAsyncClient
        val client = injectedClient ?: createCloudWatchLogsAsyncClient(defaults)
        val operations = CloudWatchLogsKtorTemplate(client, defaultLogStream, batchSize)

        return CloudWatchLogsKtorRuntime(
            operations = operations,
            ownedClient = if (injectedClient == null) client else null,
            logStream = defaultLogStream,
            batchSize = batchSize,
            flushInterval = flushInterval,
            shutdownFlushTimeout = shutdownFlushTimeout,
            createLogGroupOnStart = createLogGroupOnStart,
            createLogStreamOnStart = createLogStreamOnStart,
        )
    }

    private fun defaultLogStreamOrNull(): CloudWatchLogStream? {
        val groupName = logGroupName?.takeIf { it.isNotBlank() }
        val streamName = logStreamName?.takeIf { it.isNotBlank() }
        return when {
            groupName == null && streamName == null -> null
            groupName != null && streamName != null -> CloudWatchLogStream(groupName, streamName)
            else -> throw IllegalArgumentException("logGroupName and logStreamName must be configured together.")
        }
    }

    private fun createCloudWatchLogsAsyncClient(defaults: AwsKtorDefaults): CloudWatchLogsAsyncClient {
        val effectiveRegion = region?.takeIf { it.isNotBlank() } ?: defaults.region?.takeIf { it.isNotBlank() }
        val effectiveEndpoint = endpointOverride ?: defaults.javaEndpointOverride
        require(effectiveEndpoint == null || !effectiveRegion.isNullOrBlank()) {
            "region must be configured when endpointOverride is configured."
        }

        val builder = CloudWatchLogsAsyncClient.builder()
        effectiveRegion?.let { builder.region(Region.of(it)) }
        (credentialsProvider ?: defaults.javaCredentialsProvider)?.let(builder::credentialsProvider)
        effectiveEndpoint?.let(builder::endpointOverride)
        defaults.cloudWatchLogsAsyncClientCustomizers.forEach { it.customize(builder) }
        clientCustomizers.forEach { it.customize(builder) }

        return builder.build()
    }
}
