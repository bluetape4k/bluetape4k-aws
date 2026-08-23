package io.bluetape4k.aws.s3tables

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import java.time.Duration
import java.util.UUID

/**
 * 자격 증명 입력이 필요한 S3 Tables smoke 검증이다.
 *
 * Gradle test task는 명시적 smoke property와 모든 필수 환경 변수가 있을 때만 두 tag를
 * 포함한다. mutating lane은 resource를 만들기 전에 caller account를 검증하고 이번 실행이
 * 만든 resource만 삭제한다.
 */
class S3TablesSmokeTest {

    @Test
    @Tag(READ_ONLY_TAG)
    fun `read-only lane gets the configured bucket and one namespace page`() {
        val regionName = requiredInput(READ_ONLY_REGION)
        val tableBucketArn = requiredInput(READ_ONLY_TABLE_BUCKET_ARN)
        val startedAt = System.nanoTime()

        try {
            assertTimeoutPreemptively(SMOKE_TIMEOUT) {
                withS3TablesClient(
                    region = Region.of(regionName),
                    builder = ::configureTimeout,
                ) { client ->
                    val bucket = client.getTableBucket(tableBucketArn)
                    val namespaces = client.listNamespaces(tableBucketArn, maxNamespaces = 1)
                    println(
                        smokeEvidence(
                            lane = READ_ONLY_TAG,
                            result = "PASS",
                            elapsedMillis = elapsedMillis(startedAt),
                            region = regionName,
                            requestId = namespaces.responseMetadata().requestId(),
                            detail =
                                "namespaceCount=${namespaces.namespaces().size} " +
                                    "bucketKnown=${bucket.arn().isNotBlank()}",
                        ),
                    )
                }
            }
        } catch (failure: Throwable) {
            throw sanitizedSmokeFailure(READ_ONLY_TAG, failure, elapsedMillis(startedAt), regionName)
        }
    }

    @Test
    @Tag(MUTATING_TAG)
    fun `mutating lane cleans resources`() {
        val regionName = requiredInput(MUTATING_REGION)
        val expectedAccountId = requiredInput(EXPECTED_ACCOUNT_ID)
        val prefix = requiredInput(MUTATING_PREFIX)
        val startedAt = System.nanoTime()
        runMutatingLane(regionName, expectedAccountId, prefix, startedAt)?.let { failure ->
            throw sanitizedSmokeFailure(MUTATING_TAG, failure, elapsedMillis(startedAt), regionName)
        }
    }

    private fun runMutatingLane(
        regionName: String,
        expectedAccountId: String,
        prefix: String,
        startedAt: Long,
    ): Throwable? {
        val region = Region.of(regionName)
        var tableBucketArn: String? = null
        var namespaceCreated = false
        var tableCreated = false
        var namespace = ""
        var tableName = ""
        var primaryFailure: Throwable? = null

        try {
            assertTimeoutPreemptively(SMOKE_TIMEOUT) {
                verifyExpectedAccount(region, expectedAccountId)
                withS3TablesClient(region = region, builder = ::configureTimeout) { client ->
                    val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
                    val bucketName = normalizedName(prefix, "bucket-$suffix")
                    namespace = normalizedName(prefix, "ns-$suffix")
                    tableName = normalizedName(prefix, "table-$suffix")
                    val createdBucketArn = client.createTableBucket(bucketName).arn()
                    tableBucketArn = createdBucketArn
                    client.createNamespace(createdBucketArn, listOf(namespace))
                    namespaceCreated = true
                    client.createTable(createdBucketArn, namespace, tableName)
                    tableCreated = true
                }
            }
            println(
                smokeEvidence(
                    lane = MUTATING_TAG,
                    result = "PASS",
                    elapsedMillis = elapsedMillis(startedAt),
                    region = regionName,
                    requestId = null,
                    detail = "created=table-bucket,namespace,table cleanup=reverse-order",
                ),
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            primaryFailure = cleanupMutatingResources(
                region = region,
                tableBucketArn = tableBucketArn,
                namespace = namespace,
                tableName = tableName,
                namespaceCreated = namespaceCreated,
                tableCreated = tableCreated,
                primaryFailure = primaryFailure,
            )
        }
        return primaryFailure
    }

    private fun verifyExpectedAccount(region: Region, expectedAccountId: String) {
        StsClient.builder()
            .region(region)
            .overrideConfiguration(timeoutConfiguration())
            .build()
            .use { sts ->
                val actualAccountId = sts.getCallerIdentity().account()
                check(actualAccountId == expectedAccountId) {
                    "configured account does not match the credential account"
                }
            }
    }

    private fun cleanupMutatingResources(
        region: Region,
        tableBucketArn: String?,
        namespace: String,
        tableName: String,
        namespaceCreated: Boolean,
        tableCreated: Boolean,
        primaryFailure: Throwable?,
    ): Throwable? {
        val cleanupFailure = runCatching {
            tableBucketArn?.let { arn ->
                withS3TablesClient(region = region, builder = ::configureTimeout) { client ->
                    if (tableCreated) client.deleteTable(arn, namespace, tableName)
                    if (namespaceCreated) client.deleteNamespace(arn, namespace)
                    client.deleteTableBucket(arn)
                }
            }
        }.exceptionOrNull()
        if (cleanupFailure != null) {
            primaryFailure?.addSuppressed(cleanupFailure) ?: return cleanupFailure
        }
        return primaryFailure
    }

    private companion object {
        const val READ_ONLY_TAG = "s3-tables-read-only-smoke"
        const val MUTATING_TAG = "s3-tables-mutating-smoke"
        const val READ_ONLY_REGION = "S3_TABLES_READ_ONLY_REGION"
        const val READ_ONLY_TABLE_BUCKET_ARN = "S3_TABLES_READ_ONLY_TABLE_BUCKET_ARN"
        const val EXPECTED_ACCOUNT_ID = "S3_TABLES_EXPECTED_ACCOUNT_ID"
        const val MUTATING_REGION = "S3_TABLES_MUTATING_REGION"
        const val MUTATING_PREFIX = "S3_TABLES_MUTATING_PREFIX"
        val SMOKE_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}

private fun requiredInput(name: String): String =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }
        ?: throw AssertionError("s3-tables-smoke: missing or invalid input=$name")

private fun timeoutConfiguration(): ClientOverrideConfiguration =
    ClientOverrideConfiguration.builder()
        .apiCallTimeout(Duration.ofSeconds(30))
        .apiCallAttemptTimeout(Duration.ofSeconds(15))
        .build()

private fun configureTimeout(builder: software.amazon.awssdk.services.s3tables.S3TablesClientBuilder) {
    builder.overrideConfiguration(timeoutConfiguration())
}

private fun normalizedName(prefix: String, suffix: String): String {
    val normalizedPrefix = prefix.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
    require(normalizedPrefix.length >= 3) { "S3_TABLES_MUTATING_PREFIX must contain at least three valid characters" }
    return "$normalizedPrefix-$suffix".take(63).trimEnd('-')
}

private fun elapsedMillis(startedAt: Long): Long =
    Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

private fun smokeEvidence(
    lane: String,
    result: String,
    elapsedMillis: Long,
    region: String,
    requestId: String?,
    detail: String,
): String =
    "s3-tables-smoke lane=$lane result=$result elapsedMs=$elapsedMillis region=$region " +
        "requestId=${requestId.orNotAvailable()} detail=$detail"

private fun sanitizedSmokeFailure(
    lane: String,
    failure: Throwable,
    elapsedMillis: Long,
    region: String,
): AssertionError {
    val errorCode = (failure as? AwsServiceException)?.awsErrorDetails()?.errorCode()
    val requestId = (failure as? SdkServiceException)?.requestId()
    return AssertionError(
        "s3-tables-smoke lane=$lane result=FAIL elapsedMs=$elapsedMillis region=$region " +
            "exceptionClass=${failure.javaClass.name} errorCode=${errorCode.orNotAvailable()} " +
            "requestId=${requestId.orNotAvailable()}",
    )
}

private fun String?.orNotAvailable(): String =
    this?.takeIf(String::isNotBlank) ?: "not-available"
