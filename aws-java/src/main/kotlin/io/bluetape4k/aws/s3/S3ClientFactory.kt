package io.bluetape4k.aws.s3

import io.bluetape4k.aws.http.SdkAsyncHttpClientProvider
import io.bluetape4k.aws.http.SdkHttpClientProvider
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.utils.ShutdownQueue
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3ClientBuilder
import software.amazon.awssdk.services.s3.S3CrtAsyncClientBuilder
import software.amazon.awssdk.transfer.s3.S3TransferManager
import software.amazon.awssdk.transfer.s3.SizeConstant.MB
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * See the API documentation for details.
 */
object S3ClientFactory {

    /**
     * See the API documentation for details.
     */
    object Sync {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = S3ClientFactory.Sync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         *
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            builder: S3ClientBuilder.() -> Unit,
        ): S3Client {
            return S3Client.builder().apply(builder).build()
                .apply {
                    ShutdownQueue.register(this)
                }
        }

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = S3ClientFactory.Sync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         *
         * @param endpointOverride      S3 endpoint
         * @param region                S3 region
         * @param credentialsProvider Parameter.
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkHttpClient = SdkHttpClientProvider.defaultHttpClient,
            accelerate: Boolean = false,
            builder: S3ClientBuilder.() -> Unit = {},
        ): S3Client {
            return create {
                endpointOverride?.let { endpointOverride(it) }
                region?.let { region(it) }
                credentialsProvider?.let { credentialsProvider(it) }
                httpClient(httpClient)

                // Transfer Acceleration requires bucket-level enablement; leave disabled by default.
                accelerate(accelerate)

                builder()
            }
        }
    }

    /**
     * See the API documentation for details.
     */
    object Async {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = S3ClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         *
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            builder: S3AsyncClientBuilder.() -> Unit,
        ): S3AsyncClient {
            return S3AsyncClient.builder()
                .apply(builder)
                .build()
                .apply {
                    ShutdownQueue.register(this)
                }
        }

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = S3ClientFactory.Async.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         *
         * @param endpointOverride      S3 endpoint
         * @param region                S3 region
         * @param credentialsProvider Parameter.
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            httpClient: SdkAsyncHttpClient = SdkAsyncHttpClientProvider.defaultHttpClient,
            accelerate: Boolean = false,
            builder: S3AsyncClientBuilder.() -> Unit = {},
        ): S3AsyncClient {
            return create {
                endpointOverride?.let { endpointOverride(it) }
                region?.let { region(it) }
                credentialsProvider?.let { credentialsProvider(it) }
                httpClient(httpClient)

                // Transfer Acceleration requires bucket-level enablement; leave disabled by default.
                accelerate(accelerate)

                builder()
            }
        }
    }


    /**
     * See the API documentation for details.
     *
     * Note: See the referenced documentation.
     */
    object CrtAsync {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = S3ClientFactory.CrtAsync.create { region(Region.AP_NORTHEAST_2) }
         * // client != null
         * ```
         *
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            builder: S3CrtAsyncClientBuilder.() -> Unit,
        ): S3AsyncClient {
            return S3AsyncClient.crtBuilder().apply(builder).build()
                .apply {
                    ShutdownQueue.register(this)
                }
        }

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val client = S3ClientFactory.CrtAsync.create(region = Region.AP_NORTHEAST_2)
         * // client != null
         * ```
         *
         * @param endpointOverride      S3 endpoint
         * @param region                S3 region
         * @param credentialsProvider Parameter.
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            miminumPartSizeInBytes: Long = 1 * MB,
            builder: S3CrtAsyncClientBuilder.() -> Unit = {},
        ): S3AsyncClient {
            return create {
                endpointOverride?.let { endpointOverride(it) }
                region?.let { region(it) }
                credentialsProvider?.let { credentialsProvider(it) }
                maxConcurrency(Runtimex.availableProcessors * 2)
                minimumPartSizeInBytes(miminumPartSizeInBytes)
                // Transfer Acceleration requires bucket-level enablement; leave disabled by default.
                // accelerate(false)
                builder()
            }
        }
    }

    /**
     * See the API documentation for details.
     */
    object TransferManager {

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val tm = S3ClientFactory.TransferManager.create { }
         * // tm != null
         * ```
         *
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            builder: S3TransferManager.Builder.() -> Unit,
        ): S3TransferManager {
            return S3TransferManager.builder().apply(builder).build()
                .apply {
                    ShutdownQueue.register(this)
                }
        }

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val tm = S3ClientFactory.TransferManager.create(region = Region.AP_NORTHEAST_2)
         * // tm != null
         * ```
         *
         * Note: See the referenced documentation.
         *
         * @param endpointOverride      S3 endpoint
         * @param region                S3 region
         * @param credentialsProvider Parameter.
         * @param executor Parameter.
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            endpointOverride: URI? = null,
            region: Region? = null,
            credentialsProvider: AwsCredentialsProvider? = null,
            executor: Executor = Executors.newVirtualThreadPerTaskExecutor(),
            uploadDirectoryMaxDepth: Int? = null,
            builder: S3TransferManager.Builder.() -> Unit = {},
        ): S3TransferManager {
            return create {
                // See the API documentation for details.
                val asyncClient = CrtAsync.create(endpointOverride, region, credentialsProvider)

                s3Client(asyncClient)
                executor(executor)
                uploadDirectoryMaxDepth?.let { this.uploadDirectoryMaxDepth(it) }
                builder()
            }
        }

        /**
         * See the API documentation for details.
         *
         * ```kotlin
         * val asyncClient = S3ClientFactory.Async.create { region(Region.AP_NORTHEAST_2) }
         * val tm = S3ClientFactory.TransferManager.create(asyncClient)
         * // tm != null
         * ```
         *
         * @param asyncClient Parameter.
         * @param executor Parameter.
         * @param builder Parameter.
         * @return Return value.
         */
        inline fun create(
            asyncClient: S3AsyncClient,
            executor: Executor = Executors.newVirtualThreadPerTaskExecutor(),
            uploadDirectoryMaxDepth: Int? = null,
            builder: S3TransferManager.Builder.() -> Unit = {},
        ): S3TransferManager {
            return create {
                s3Client(asyncClient)
                executor(executor)
                uploadDirectoryMaxDepth?.let { uploadDirectoryMaxDepth(it) }

                builder()
            }
        }
    }
}
