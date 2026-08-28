package io.bluetape4k.aws.kotlin.dynamodb.coordination

import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DynamoDB coordination adapter의 실행 옵션입니다.
 *
 * options는 immutable runtime configuration이며 Serializable contract를 제공하지
 * 않습니다. 특히 [clock]은 테스트와 runtime 주입을 위한 값으로 durable state에
 * 직렬화하지 않아야 합니다.
 */
class DynamoDbCoordinationOptions(
    val defaultLeaseDuration: Duration = 60.seconds,
    val consistentRead: Boolean = true,
    val clock: Clock = Clock.systemUTC(),
) {

    init {
        validateDynamoDbCoordinationDuration(defaultLeaseDuration, "defaultLeaseDuration")
    }
}
