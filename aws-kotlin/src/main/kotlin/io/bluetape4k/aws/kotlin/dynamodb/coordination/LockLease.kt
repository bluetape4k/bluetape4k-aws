package io.bluetape4k.aws.kotlin.dynamodb.coordination

import java.io.ObjectInputStream
import java.io.Serializable

/**
 * DynamoDB lock의 owner와 monotonic fencing token을 나타내는 immutable lease입니다.
 *
 * [fencingToken]은 downstream side effect를 보호하기 위한 token이므로, lease를
 * 직렬화해 전달하는 caller는 token을 신뢰하지 않는 입력으로 취급하고 downstream
 * write의 조건식에서 현재 token을 다시 확인해야 합니다.
 */
data class LockLease(
    val key: String,
    val ownerId: String,
    val fencingToken: Long,
    val expiresAtEpochSeconds: Long,
    val tableName: String,
    val partitionKeyAttributeName: String,
    val namespace: String,
    val physicalKey: String,
    val scopeId: String,
) : Serializable {

    init {
        validateInvariants()
    }

    @Suppress("UnusedPrivateMember")
    private fun readObject(stream: ObjectInputStream) {
        stream.defaultReadObject()
        validateInvariants()
    }

    private fun validateInvariants() {
        key.validateCoordinationIdentifier("key")
        ownerId.validateCoordinationIdentifier("ownerId")
        require(fencingToken > 0) {
            "fencingToken must be positive, but was $fencingToken"
        }
        require(expiresAtEpochSeconds >= 0) {
            "expiresAtEpochSeconds must be non-negative, but was $expiresAtEpochSeconds"
        }
        DynamoDbCoordinationSchema.validateTableName(tableName)
        partitionKeyAttributeName.validateCoordinationIdentifier("partitionKeyAttributeName")
        namespace.validateCoordinationIdentifier("namespace")
        physicalKey.validateCoordinationIdentifier(
            "physicalKey",
            DynamoDbCoordinationSchema.MAX_RESOLVED_KEY_UTF8_BYTES,
        )
        scopeId.validateCoordinationIdentifier(
            "scopeId",
            DynamoDbCoordinationSchema.MAX_SCOPE_ID_UTF8_BYTES,
        )
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
