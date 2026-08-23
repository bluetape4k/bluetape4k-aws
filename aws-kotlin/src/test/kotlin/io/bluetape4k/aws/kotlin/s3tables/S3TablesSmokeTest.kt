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
            println(
                smokeEvidence(
                    lane = MUTATING_TAG,
                    result = "PASS",
                    elapsedMillis = elapsedMillis(startedAt),
                    region = region,
                    detail = "created=table-bucket,namespace,table cleanup=reverse-order",
                ),
            )
        } catch (ce: CancellationException) {
            primaryFailure = ce
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            val cleanupFailure = withContext(NonCancellable) {
                runCatching {
                    cleanupResources(
                        region = region,
                        created = created,
                    )
                }.exceptionOrNull()
            }
            if (cleanupFailure != null) {
                primaryFailure?.addSuppressed(cleanupFailure) ?: run { primaryFailure = cleanupFailure }
            }
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
        var namespace: String = "",
        var tableName: String = "",
        var namespaceCreated: Boolean = false,
        var tableCreated: Boolean = false,
    )

    private suspend fun createResources(region: String, prefix: String, created: CreatedResources) {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val bucketName = normalizedName(prefix, "bucket-$suffix")
        created.namespace = normalizedName(prefix, "ns-$suffix")
        created.tableName = normalizedName(prefix, "table-$suffix")
        withS3TablesClient(region = region, builder = { callTimeout = 30.seconds }) { client ->
            val createdBucketArn = client.createTableBucket(createTableBucketRequestOf(bucketName)).arn
            created.tableBucketArn = createdBucketArn
            client.createNamespace(createdBucketArn, listOf(created.namespace))
            created.namespaceCreated = true
            client.createTable(createdBucketArn, created.namespace, created.tableName)
            created.tableCreated = true
        }
    }

    private suspend fun cleanupResources(
        region: String,
        created: CreatedResources,
    ) {
        created.tableBucketArn?.let { arn ->
            withS3TablesClient(region = region, builder = { callTimeout = 30.seconds }) { client ->
                if (created.tableCreated) client.deleteTable(arn, created.namespace, created.tableName)
                if (created.namespaceCreated) client.deleteNamespace(arn, created.namespace)
                client.deleteTableBucket(arn)
            }
        }
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

private fun normalizedName(prefix: String, suffix: String): String {
    val normalizedPrefix = prefix.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
    require(normalizedPrefix.length >= 3) { "S3_TABLES_MUTATING_PREFIX must contain at least three valid characters" }
    return "$normalizedPrefix-$suffix".take(63).trimEnd('-')
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
