package io.bluetape4k.aws.kotlin.s3tables

import aws.smithy.kotlin.runtime.InternalApi
import aws.smithy.kotlin.runtime.ServiceException
import io.bluetape4k.aws.kotlin.s3tables.model.createTableBucketRequestOf
import io.bluetape4k.aws.kotlin.sts.getCallerIdentity
import io.bluetape4k.aws.kotlin.sts.withStsClient
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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
    fun `bucket name normalization preserves unique suffix and bucket alphabet`() {
        val suffix = "bucket-0123456789abcdef"

        val actual = normalizedTableBucketName("Issue #311 / Production", suffix)

        assertTrue(actual.endsWith("-$suffix"))
        assertTrue(actual.matches(Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")))
    }

    @Test
    fun `bucket name normalization avoids reserved aws prefix`() {
        val actual = normalizedTableBucketName("AWS", "bucket-0123456789abcdef")

        assertTrue(actual.startsWith("bt-"))
    }

    @Test
    fun `namespace and table normalization preserves unique suffix and identifier alphabet`() {
        val suffix = "namespace_0123456789abcdef"

        val actual = normalizedTableIdentifierName("Issue #311 / Production", suffix)

        assertTrue(actual.endsWith("_$suffix"))
        assertTrue(actual.matches(Regex("[a-z0-9_]+")))
    }

    @Test
    fun `namespace and table normalization avoids reserved aws prefix`() {
        val actual = normalizedTableIdentifierName("AWS", "namespace_0123456789abcdef")

        assertTrue(actual.first().isLetterOrDigit())
        assertTrue(!actual.startsWith("aws"))
    }

    @Test
    fun `cleanup continues with later resources after an earlier deletion fails`() = runSuspendIO {
        val failures = mutableListOf<Throwable>()
        val attempted = mutableListOf<String>()

        cleanupIndependently(failures) {
            attempted += "table"
            error("table-delete-failed")
        }
        cleanupIndependently(failures) { attempted += "namespace" }
        cleanupIndependently(failures) { attempted += "bucket" }

        assertTrue(attempted == listOf("table", "namespace", "bucket"))
        assertTrue(failures.single().message == "table-delete-failed")
    }

    @Test
    @Tag(READ_ONLY_TAG)
    fun `read-only lane gets the configured bucket and one namespace page`() = runSuspendIO {
        val region = requiredInput(READ_ONLY_REGION)
        val tableBucketArn = requiredInput(READ_ONLY_TABLE_BUCKET_ARN)
        val startedAt = System.nanoTime()

        try {
            withTimeout(SMOKE_TIMEOUT) {
                withS3TablesClient(region = region, builder = { callTimeout = 30.seconds }) { client ->
                    client.getTableBucket(tableBucketArn)
                    val namespaces = client.listNamespaces(tableBucketArn, maxNamespaces = 1)
                    println(
                        smokeEvidence(
                            lane = READ_ONLY_TAG,
                            result = "PASS",
                            elapsedMillis = elapsedMillis(startedAt),
                            region = region,
                            detail = "namespaceCount=${namespaces.namespaces.size}",
                        ),
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Throwable) {
            throw sanitizedSmokeFailure(READ_ONLY_TAG, failure, elapsedMillis(startedAt), region)
        }
    }

    @Test
    @Tag(MUTATING_TAG)
    fun `mutating lane cleans resources`() = runSuspendIO {
        val region = requiredInput(MUTATING_REGION)
        val expectedAccountId = requiredInput(EXPECTED_ACCOUNT_ID)
        val prefix = requiredInput(MUTATING_PREFIX)
        val startedAt = System.nanoTime()
        runMutatingLane(region, expectedAccountId, prefix, startedAt)?.let { failure ->
            if (failure is CancellationException) throw failure
            throw sanitizedSmokeFailure(MUTATING_TAG, failure, elapsedMillis(startedAt), region)
        }
    }

    private suspend fun runMutatingLane(
        region: String,
        expectedAccountId: String,
        prefix: String,
        startedAt: Long,
    ): Throwable? {
        val created = CreatedResources()
        var primaryFailure: Throwable? = null

        try {
            withTimeout(SMOKE_TIMEOUT) {
                verifyExpectedAccount(region, expectedAccountId)
                createResources(region, prefix, created)
            }
        } catch (ce: CancellationException) {
            primaryFailure = ce
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            val cleanupFailures = withContext(NonCancellable) {
                cleanupResources(
                    region = region,
                    created = created,
                )
            }
            if (cleanupFailures.isNotEmpty()) {
                if (primaryFailure != null) {
                    cleanupFailures.forEach(primaryFailure::addSuppressed)
                } else {
                    val cleanupFailure = cleanupFailures.first()
                    cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
                    primaryFailure = cleanupFailure
                }
            }
        }
        if (primaryFailure == null) {
            println(
                smokeEvidence(
                    lane = MUTATING_TAG,
                    result = "PASS",
                    elapsedMillis = elapsedMillis(startedAt),
                    region = region,
                    detail = "created=table-bucket,namespace,table cleanup=reverse-order",
                ),
            )
        }
        return primaryFailure
    }

    private suspend fun verifyExpectedAccount(region: String, expectedAccountId: String) {
        withStsClient(region = region) { sts ->
            val actualAccountId = sts.getCallerIdentity().account
            check(actualAccountId == expectedAccountId) {
                "configured account does not match the credential account"
            }
        }
    }

    private data class CreatedResources(
        var tableBucketArn: String? = null,
        var tableBucketName: String = "",
        var tableBucketCreationAttempted: Boolean = false,
        var namespace: String = "",
        var tableName: String = "",
        var namespaceCreationAttempted: Boolean = false,
        var tableCreationAttempted: Boolean = false,
    )

    private suspend fun createResources(region: String, prefix: String, created: CreatedResources) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        created.tableBucketName = normalizedTableBucketName(prefix, "bucket-$suffix")
        created.namespace = normalizedTableIdentifierName(prefix, "namespace_$suffix")
        created.tableName = normalizedTableIdentifierName(prefix, "table_$suffix")
        withS3TablesClient(region = region, builder = { callTimeout = 30.seconds }) { client ->
            created.tableBucketCreationAttempted = true
            val createdBucketArn = client.createTableBucket(createTableBucketRequestOf(created.tableBucketName)).arn
            created.tableBucketArn = createdBucketArn.takeIf(String::isNotBlank)
            val bucketArn = requireNotNull(created.tableBucketArn) {
                "createTableBucket returned a blank ARN"
            }
            created.namespaceCreationAttempted = true
            client.createNamespace(bucketArn, listOf(created.namespace))
            created.tableCreationAttempted = true
            client.createTable(bucketArn, created.namespace, created.tableName)
        }
    }

    private suspend fun cleanupResources(
        region: String,
        created: CreatedResources,
    ): List<Throwable> {
        if (created.tableBucketArn == null && !created.tableBucketCreationAttempted) return emptyList()

        val cleanupFailures = mutableListOf<Throwable>()
        runCatching {
            withS3TablesClient(region = region, builder = { callTimeout = 30.seconds }) { client ->
                var resolvedTableBucketArn = created.tableBucketArn
                if (resolvedTableBucketArn == null && created.tableBucketCreationAttempted) {
                    runCatching {
                        resolvedTableBucketArn = client
                            .listTableBuckets(prefix = created.tableBucketName, maxBuckets = 10)
                            .tableBuckets
                            .firstOrNull { it.name == created.tableBucketName }
                            ?.arn
                    }.exceptionOrNull()?.takeUnless(::isAlreadyAbsent)?.let(cleanupFailures::add)
                }
                resolvedTableBucketArn?.let { arn ->
                    cleanupIndependently(cleanupFailures) {
                        if (created.tableCreationAttempted) {
                            client.deleteTable(arn, created.namespace, created.tableName)
                        }
                    }
                    cleanupIndependently(cleanupFailures) {
                        if (created.namespaceCreationAttempted) client.deleteNamespace(arn, created.namespace)
                    }
                    cleanupIndependently(cleanupFailures) {
                        client.deleteTableBucket(arn)
                    }
                }
            }
        }.exceptionOrNull()?.takeUnless(::isAlreadyAbsent)?.let(cleanupFailures::add)
        return cleanupFailures
    }

    private suspend fun cleanupIndependently(
        failures: MutableList<Throwable>,
        action: suspend () -> Unit,
    ) {
        runCatching { action() }.exceptionOrNull()?.takeUnless(::isAlreadyAbsent)?.let(failures::add)
    }

    private companion object {
        const val READ_ONLY_TAG = "s3-tables-read-only-smoke"
        const val MUTATING_TAG = "s3-tables-mutating-smoke"
        const val READ_ONLY_REGION = "S3_TABLES_READ_ONLY_REGION"
        const val READ_ONLY_TABLE_BUCKET_ARN = "S3_TABLES_READ_ONLY_TABLE_BUCKET_ARN"
        const val EXPECTED_ACCOUNT_ID = "S3_TABLES_EXPECTED_ACCOUNT_ID"
        const val MUTATING_REGION = "S3_TABLES_MUTATING_REGION"
        const val MUTATING_PREFIX = "S3_TABLES_MUTATING_PREFIX"
        val SMOKE_TIMEOUT = 2.minutes
    }
}

private fun requiredInput(name: String): String =
    System.getenv(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it }
        ?: throw AssertionError("s3-tables-smoke: missing or invalid input=$name")

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
    val serviceFailure = failure as? ServiceException ?: return false
    return serviceFailure.sdkErrorMetadata.errorCode in setOf(
        "NoSuchTableBucket",
        "NoSuchNamespace",
        "NoSuchTable",
        "ResourceNotFoundException",
    )
}

private fun elapsedMillis(startedAt: Long): Long =
    (System.nanoTime() - startedAt) / 1_000_000L

private fun smokeEvidence(
    lane: String,
    result: String,
    elapsedMillis: Long,
    region: String,
    detail: String,
): String =
    "s3-tables-smoke lane=$lane result=$result elapsedMs=$elapsedMillis region=$region " +
        "requestId=not-available detail=$detail"

@OptIn(InternalApi::class)
private fun sanitizedSmokeFailure(
    lane: String,
    failure: Throwable,
    elapsedMillis: Long,
    region: String,
): AssertionError {
    val serviceFailure = failure as? ServiceException
    return AssertionError(
        "s3-tables-smoke lane=$lane result=FAIL elapsedMs=$elapsedMillis region=$region " +
            "exceptionClass=${failure.javaClass.name} " +
            "errorCode=${serviceFailure?.sdkErrorMetadata?.errorCode.orNotAvailable()} " +
            "requestId=${serviceFailure?.sdkErrorMetadata?.requestId.orNotAvailable()}",
    )
}

private fun String?.orNotAvailable(): String =
    this?.takeIf(String::isNotBlank) ?: "not-available"
