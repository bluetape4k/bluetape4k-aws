---
title: Service plugins
description: Configure typed Ktor AWS plugins with explicit client and runtime ownership.
manualId: bluetape4k-aws-ktor
chapterId: service-plugins
---

# Service plugins

Ktor service plugins translate application configuration into a typed runtime and expose it through application attributes or operations interfaces.

## SQS consumer

Exactly one of `queueUrl` and `queueName` is required, and one plugin instance has one handler. Configure poller count, receive size, long poll, visibility, deletion, failure visibility, heartbeat, conversion policy, and shutdown timeout as one delivery contract.

```kotlin
install(SqsConsumer) {
    queueName = "orders"
    coroutines = 4
    maxMessages = 10
    waitTimeSeconds = 20
    onMessage<OrderMessage> { order -> process(order) }
}
```

An injected `SqsAsyncClient` is never closed by the plugin. A plugin-created client is owned and closed by its runtime.

## DynamoDB

The DynamoDB plugin creates or accepts async and enhanced clients and exposes a repository runtime. Table creation, index selection, capacity, retries, and idempotency remain application responsibilities.

## Exposed

Choose either prebuilt `databaseProperties` or the Ktor DSL; mixing both is rejected. Configure startup and stop timeouts, a blocking transaction context, and the settings resolver. The runtime closes the registry it created.

## Other plugins

CloudWatch, Logs, IMDS, Access Grants, and S3 Vectors follow the same pattern: optional service SDK, typed configuration, explicit ownership, runtime operations, and stop hooks.

## Testing

Use `testApplication` with injected fake clients for lifecycle tests and Floci for service behavior. Test missing/duplicate configuration, cancellation, conversion failure, redelivery, and stop cleanup.

## Sources

- [SQS consumer configuration](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/sqs/SqsConsumerPluginConfig.kt)
- [DynamoDB plugin](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/dynamodb/DynamoDbKtorPlugin.kt)
- [Exposed plugin configuration](../../../../../aws-ktor/src/main/kotlin/io/bluetape4k/aws/ktor/exposed/AwsExposedPluginConfig.kt)

