package io.bluetape4k.aws.spring.s3

/** 민감한 resource 식별자를 포함하지 않는 bounded S3 cleanup operation입니다. */
internal enum class S3TransferCleanupOperation {
    DELEGATE_DISCARD,
    OUTPUT_STREAM_DISCARD,
    TEMPORARY_FILE_DELETE,
}

/** 원래 오류 message/cause 대신 operation과 failure type만 보존하는 cleanup signal입니다. */
internal class S3TransferCleanupException(
    val operation: S3TransferCleanupOperation,
    failures: List<Throwable>,
) : IllegalStateException(
    "S3 transfer cleanup failed: operation=${operation.name.lowercase()}, attempts=${failures.size}, " +
        "failureTypes=${failures.joinToString(",") { it.safeTypeName() }}",
) {
    val attemptFailureTypes: List<String> = failures.map(Throwable::safeTypeName)
}

private fun Throwable.safeTypeName(): String = this::class.qualifiedName ?: javaClass.name
