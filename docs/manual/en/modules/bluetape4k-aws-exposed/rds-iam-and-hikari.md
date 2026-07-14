---
title: RDS IAM and Hikari
description: Use short-lived RDS IAM tokens safely with Hikari physical connections.
manualId: bluetape4k-aws-exposed
chapterId: rds-iam-and-hikari
---

# RDS IAM and Hikari

RDS IAM replaces a static JDBC password with a short-lived signed token. The pool therefore needs a password provider that refreshes tokens when opening physical connections.

## Valid configuration

Set `authenticationMode = RDS_IAM`, leave the static password null, and provide region, the actual RDS endpoint hostname, port, and username. A custom DNS alias cannot be used for token signing.

```kotlin
val connection = AwsDatabaseConnectionProperties(
    url = jdbcUrl,
    username = "orders_app",
    authenticationMode = AwsDatabaseAuthenticationMode.RDS_IAM,
    rdsIam = AwsRdsIamAuthenticationProperties(
        region = "ap-northeast-2",
        hostname = "orders.cluster-xxx.ap-northeast-2.rds.amazonaws.com",
        port = 5432,
    ),
)
```

Add `software.amazon.awssdk:rds` at runtime. The library declares service SDKs `compileOnly`; without that module token generation fails with a focused exception.

## Token and pool lifetime

AWS limits the token lifetime to 15 minutes. The provider caches a token and refreshes it before expiry under a lock. Hikari still owns physical connections; a refreshed token is used when a new connection opens, not retroactively on an existing connection.

Set Hikari `maxLifetime` and database-side connection policy deliberately. Token expiry does not terminate an already authenticated connection, but replacement connections need a fresh token.

## Security

Do not log the generated token or place it in metrics. Grant the runtime identity only `rds-db:connect` for the target database user. TLS and server certificate validation remain separate JDBC responsibilities.

## Failure diagnosis

Check region, exact hostname, port, IAM database user, runtime RDS SDK, credentials, clock, and network path. Diagnose pool exhaustion separately from token-generation failure.

## Sources

- [RDS IAM authentication](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthentication.kt)
- [Hikari data-source factory](../../../../../aws-exposed/src/main/kotlin/io/bluetape4k/aws/exposed/AwsJdbcDataSourceFactory.kt)
- [RDS IAM tests](../../../../../aws-exposed/src/test/kotlin/io/bluetape4k/aws/exposed/AwsRdsIamAuthenticationTest.kt)
