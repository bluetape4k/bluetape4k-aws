package io.bluetape4k.aws.spring.dynamodb

import software.amazon.awssdk.enhanced.dynamodb.TableSchema
import java.util.concurrent.ConcurrentHashMap

/** entity class에서 Enhanced Client용 [TableSchema]를 해석합니다. */
interface DynamoDbTableSchemaResolver {

    /** 명시적 schema가 있으면 우선 사용하고, 없으면 entity class 기준으로 조회합니다. */
    fun <T : Any> resolve(
        entityClass: Class<T>?,
        explicitSchema: TableSchema<T>? = null,
    ): TableSchema<T>
}

/** Bean/immutable schema를 class별로 한 번만 생성하는 기본 resolver입니다. */
class DefaultDynamoDbTableSchemaResolver : DynamoDbTableSchemaResolver {

    private val schemas = ConcurrentHashMap<Class<*>, TableSchema<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> resolve(
        entityClass: Class<T>?,
        explicitSchema: TableSchema<T>?,
    ): TableSchema<T> {
        requireNotNull(entityClass) { "DynamoDB entityClass must not be null." }
        explicitSchema?.let { return it }

        return schemas.computeIfAbsent(entityClass) {
            TableSchema.fromClass(entityClass)
        } as TableSchema<T>
    }

    /** 테스트 또는 애플리케이션 재구성 시 schema cache를 비웁니다. */
    fun clear() {
        schemas.clear()
    }
}
