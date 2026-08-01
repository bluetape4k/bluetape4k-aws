package io.bluetape4k.aws.spring

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * 전역 bluetape4k AWS 스위치가 활성화된 경우에만 AWS 자동 구성을 활성화합니다.
 *
 * 서비스별 자동 구성 단계도 더 좁은 서비스 플래그를 유지해야 합니다. 이 조건은
 * `bluetape4k.aws.enabled=false`를 리포지토리 전체 비활성화 스위치로 만듭니다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ConditionalOnProperty(prefix = "bluetape4k.aws", name = ["enabled"], havingValue = "true", matchIfMissing = true)
annotation class ConditionalOnAwsEnabled
