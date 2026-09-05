package io.bluetape4k.aws.kotlin

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test

class PaginationGuardTest {

    @Test
    fun `guard rejects a missing continuation token`() {
        val guard = paginationGuard(maxPages = 2)

        val error = assertFailsWith<IllegalStateException> {
            guard.nextTokenOrNull(hasNext = true, nextToken = null)
        }

        error.message shouldBeEqualTo PaginationFailure.MISSING_TOKEN.name
    }

    @Test
    fun `guard rejects a repeated continuation token`() {
        val guard = paginationGuard(maxPages = 3)

        guard.nextTokenOrNull(hasNext = true, nextToken = "page-a") shouldBeEqualTo "page-a"
        val error = assertFailsWith<IllegalStateException> {
            guard.nextTokenOrNull(hasNext = true, nextToken = "page-a")
        }

        error.message shouldBeEqualTo PaginationFailure.REPEATED_TOKEN.name
    }

    @Test
    fun `guard permits the configured final page`() {
        val guard = paginationGuard(maxPages = 2)

        guard.nextTokenOrNull(hasNext = true, nextToken = "page-a") shouldBeEqualTo "page-a"
        guard.nextTokenOrNull(hasNext = false, nextToken = null).shouldBeNull()
    }

    @Test
    fun `guard rejects continuation beyond the page limit`() {
        val guard = paginationGuard(maxPages = 2)

        guard.nextTokenOrNull(hasNext = true, nextToken = "page-a") shouldBeEqualTo "page-a"
        val error = assertFailsWith<IllegalStateException> {
            guard.nextTokenOrNull(hasNext = true, nextToken = "page-b")
        }

        error.message shouldBeEqualTo PaginationFailure.PAGE_LIMIT_EXCEEDED.name
    }

    private fun paginationGuard(maxPages: Int): PaginationGuard<String> =
        PaginationGuard(maxPages) { failure -> IllegalStateException(failure.name) }
}
