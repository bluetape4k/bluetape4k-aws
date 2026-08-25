package io.bluetape4k.aws.s3tables

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldEndWith
import io.bluetape4k.assertions.shouldMatch
import io.bluetape4k.assertions.shouldNotStartWith
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.idgenerators.uuid.Uuid
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sts.StsClient
import java.time.Duration

/**
 * 자격 증명 입력이 필요한 S3 Tables smoke 검증이다.
 *
 * Gradle test task는 명시적 smoke property와 모든 필수 환경 변수가 있을 때만 두 tag를
 * 포함한다. mutating lane은 resource를 만들기 전에 caller account를 검증하고 이번 실행이
 * 만든 resource만 삭제한다.
 */
class S3TablesSmokeTest {

    @Test
    fun `bucket name normalization preserves unique suffix and bucket alphabet`() {
        val suffix = "bucket-0123456789abcdef"

        val actual = normalizedTableBucketName("Issue #311 / Production", suffix)

        actual shouldEndWith "-$suffix"
        actual shouldMatch Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
    }

    @Test
    fun `bucket name normalization avoids reserved aws prefix`() {
        val actual = normalizedTableBucketName("AWS", "bucket-0123456789abcdef")

        actual shouldStartWith "bt-"
    }

    @Test
    fun `namespace and table normalization preserves unique suffix and identifier alphabet`() {
        val suffix = "namespace_0123456789abcdef"

        val actual = normalizedTableIdentifierName("Issue #311 / Production", suffix)

        actual shouldEndWith "_$suffix"
        actual shouldMatch Regex("[a-z0-9_]+")
    }

    @Test
    fun `namespace and table normalization avoids reserved aws prefix`() {
        val actual = normalizedTableIdentifierName("AWS", "namespace_0123456789abcdef")

        actual.first().isLetterOrDigit().shouldBeTrue()
        actual shouldNotStartWith "aws"
    }

    @Test
    fun `cleanup continues with later resources after an earlier deletion fails`() {
        val failures = mutableListOf<Throwable>()
        val attempted = mutableListOf<String>()

        cleanupIndependently(failures) {
            attempted += "table"
            error("table-delete-failed")
        }
        cleanupIndependently(failures) { attempted += "namespace" }
        cleanupIndependently(failures) { attempted += "bucket" }

        attempted shouldBeEqualTo listOf("table", "namespace", "bucket")
        failures.single().message shouldBeEqualTo "table-delete-failed"
    }

    @Test
    @Tag(READ_ONLY_TAG)
    fun `read-only lane gets the configured bucket and one namespace page`() {
        val regionName = requiredInput(READ_ONLY_REGION)
        val tableBucketArn = requiredInput(READ_ONLY_TABLE_BUCKET_ARN)
        val startedAt = System.nanoTime()

        try {
            assertTimeout(SMOKE_TIMEOUT) {
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
        var tableBucketName = ""
        var tableBucketCreationAttempted = false
        var namespaceCreationAttempted = false
        var tableCreationAttempted = false
        var namespace = ""
        var tableName = ""
        var primaryFailure: Throwable? = null

        try {
            assertTimeout(SMOKE_TIMEOUT) {
                verifyExpectedAccount(region, expectedAccountId)
                withS3TablesClient(region = region, builder = ::configureTimeout) { client ->
                    val suffix = Uuid.V7.nextId().toString().replace("-", "").take(16)
                    tableBucketName = normalizedTableBucketName(prefix, "bucket-$suffix")
                    namespace = normalizedTableIdentifierName(prefix, "namespace_$suffix")
                    tableName = normalizedTableIdentifierName(prefix, "table_$suffix")
                    tableBucketCreationAttempted = true
                    val createdBucketArn = client.createTableBucket(tableBucketName).arn()
                    tableBucketArn = createdBucketArn.takeIf(String::isNotBlank)
                    val bucketArn = requireNotNull(tableBucketArn) {
                        "createTableBucket returned a blank ARN"
                    }
                    namespaceCreationAttempted = true
                    client.createNamespace(bucketArn, listOf(namespace))
                    tableCreationAttempted = true
                    client.createTable(bucketArn, namespace, tableName)
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            primaryFailure = cleanupMutatingResources(
                region = region,
                tableBucketArn = tableBucketArn,
                tableBucketName = tableBucketName,
                tableBucketCreationAttempted = tableBucketCreationAttempted,
                namespace = namespace,
                tableName = tableName,
                namespaceCreationAttempted = namespaceCreationAttempted,
                tableCreationAttempted = tableCreationAttempted,
                primaryFailure = primaryFailure,
            )
        }
        if (primaryFailure == null) {
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
        tableBucketName: String,
        tableBucketCreationAttempted: Boolean,
        namespace: String,
        tableName: String,
        namespaceCreationAttempted: Boolean,
        tableCreationAttempted: Boolean,
        primaryFailure: Throwable?,
    ): Throwable? {
        var result = primaryFailure
        if (tableBucketArn != null || tableBucketCreationAttempted) {
            val cleanupFailures = mutableListOf<Throwable>()
            runCatching {
                withS3TablesClient(region = region, builder = ::configureTimeout) { client ->
                    var resolvedTableBucketArn = tableBucketArn
                    if (resolvedTableBucketArn == null && tableBucketCreationAttempted) {
                        runCatching {
                            resolvedTableBucketArn = client
                                .listTableBuckets(prefix = tableBucketName, maxBuckets = 10)
                                .tableBuckets()
                                .firstOrNull { it.name() == tableBucketName }
                                ?.arn()
                        }.exceptionOrNull()?.takeUnless(::isAlreadyAbsent)?.let(cleanupFailures::add)
                    }
                    resolvedTableBucketArn?.let { arn ->
                        cleanupIndependently(cleanupFailures) {
                            if (tableCreationAttempted) client.deleteTable(arn, namespace, tableName)
                        }
                        cleanupIndependently(cleanupFailures) {
                            if (namespaceCreationAttempted) client.deleteNamespace(arn, namespace)
                        }
                        cleanupIndependently(cleanupFailures) {
                            client.deleteTableBucket(arn)
                        }
                    }
                }
            }.exceptionOrNull()?.takeUnless(::isAlreadyAbsent)?.let(cleanupFailures::add)
            if (cleanupFailures.isNotEmpty()) {
                if (result != null) {
                    cleanupFailures.forEach(result::addSuppressed)
                } else {
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    result = cleanupFailure
                }
            }
        }
        return result
    }

    private fun cleanupIndependently(
        failures: MutableList<Throwable>,
        action: () -> Unit,
    ) {
        runCatching(action).exceptionOrNull()?.takeUnless(::isAlreadyAbsent)?.let(failures::add)
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

private fun normalizedTableBucketName(prefix: String, suffix: String): String {
    var normalizedPrefix = prefix.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
    val normalizedSuffix = suffix.lowercase().replace(Regex("[^a-z0-9-]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')
    require(normalizedPrefix.length >= 3) { "S3_TABLES_MUTATING_PREFIX must contain at least three valid characters" }
    require(normalizedSuffix.isNotEmpty()) { "S3 Tables resource suffix must not be blank" }
    if (listOf("xn--", "sthree-", "amzn-s3-demo-", "aws").any(normalizedPrefix::startsWith)) {
        normalizedPrefix = "bt-$normalizedPrefix"
    }
    val prefixLimit = 63 - normalizedSuffix.length - 1
    require(prefixLimit >= 3) { "S3 Tables resource suffix is too long" }
    return "${normalizedPrefix.take(prefixLimit).trimEnd('-')}-$normalizedSuffix"
}

private fun normalizedTableIdentifierName(prefix: String, suffix: String): String {
    var normalizedPrefix = prefix.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    val normalizedSuffix = suffix.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        .replace(Regex("_+"), "_")
        .trim('_')
    require(normalizedPrefix.length >= 3) { "S3_TABLES_MUTATING_PREFIX must contain at least three valid characters" }
    require(normalizedSuffix.isNotEmpty()) { "S3 Tables resource suffix must not be blank" }
    if (normalizedPrefix.startsWith("aws")) {
        normalizedPrefix = "bt_$normalizedPrefix"
    }
    val prefixLimit = 255 - normalizedSuffix.length - 1
    require(prefixLimit >= 3) { "S3 Tables resource suffix is too long" }
    return "${normalizedPrefix.take(prefixLimit).trimEnd('_')}_$normalizedSuffix"
}

private fun isAlreadyAbsent(failure: Throwable): Boolean {
    val serviceFailure = failure as? AwsServiceException ?: return false
    return serviceFailure.statusCode() == 404 ||
        serviceFailure.awsErrorDetails()?.errorCode() in setOf(
            "NoSuchTableBucket",
            "NoSuchNamespace",
            "NoSuchTable",
            "ResourceNotFoundException",
        )
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
