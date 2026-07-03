package io.bluetape4k.aws.spring

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Enables AWS auto-configuration only when the global bluetape4k AWS switch is enabled.
 *
 * Service-specific auto-configuration phases must also keep their narrower
 * service flags, but this condition makes `bluetape4k.aws.enabled=false` a
 * repository-wide kill switch.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@ConditionalOnProperty(prefix = "bluetape4k.aws", name = ["enabled"], havingValue = "true", matchIfMissing = true)
annotation class ConditionalOnAwsEnabled
