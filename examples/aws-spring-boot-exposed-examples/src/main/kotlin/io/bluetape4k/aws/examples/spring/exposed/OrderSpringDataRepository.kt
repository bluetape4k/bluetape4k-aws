package io.bluetape4k.aws.examples.spring.exposed

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository

/**
 * Spring Data 4의 Query by Example과 FluentQuery projection을 노출하는 주문 저장소입니다.
 */
interface OrderSpringDataRepository: ExposedJdbcRepository<OrderEntity, Long>
