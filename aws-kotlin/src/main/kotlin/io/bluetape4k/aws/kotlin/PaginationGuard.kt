package io.bluetape4k.aws.kotlin

/** Pagination 진행을 중단해야 하는 원인을 나타냅니다. */
internal enum class PaginationFailure {
    MISSING_TOKEN,
    REPEATED_TOKEN,
    PAGE_LIMIT_EXCEEDED,
}

/**
 * AWS 서비스 타입과 무관하게 pagination token의 진행과 page 상한을 검사합니다.
 *
 * 각 response를 처리한 뒤 [nextTokenOrNull]을 한 번 호출해야 합니다. 계속되는 page의 token만
 * 최대 [maxPages]까지 보관하므로 반복 검출 메모리는 page 상한을 넘지 않습니다. 예외 타입과
 * 공개 메시지는 [exceptionFactory]를 통해 서비스별 계약으로 유지합니다.
 */
internal class PaginationGuard<T : Any>(
    private val maxPages: Int,
    private val exceptionFactory: (PaginationFailure) -> RuntimeException,
) {
    private val seenTokens = mutableSetOf<T>()
    private var pageCount = 0

    init {
        require(maxPages >= 1) { "maxPages must be >= 1, but was $maxPages" }
    }

    fun nextTokenOrNull(
        hasNext: Boolean,
        nextToken: T?,
    ): T? {
        pageCount++
        if (!hasNext) return null

        val failure = when {
            nextToken == null -> PaginationFailure.MISSING_TOKEN
            nextToken in seenTokens -> PaginationFailure.REPEATED_TOKEN
            pageCount >= maxPages -> PaginationFailure.PAGE_LIMIT_EXCEEDED
            else -> {
                seenTokens += nextToken
                return nextToken
            }
        }
        throw exceptionFactory(failure)
    }
}
