package io.bluetape4k.aws.ktor.consumerfixture;

import io.bluetape4k.aws.ktor.AwsKtorCoreConfig;
import io.bluetape4k.aws.ktor.sqs.SqsConsumerPluginConfig;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

final class SqsConsumerFixture {

    private final AwsKtorCoreConfig core = new AwsKtorCoreConfig();
    private final SqsConsumerPluginConfig sqs = new SqsConsumerPluginConfig();

    void configure(SqsAsyncClient client) {
        core.setRegion("ap-northeast-2");
        sqs.setSqsAsyncClient(client);
        sqs.setQueueName("orders");
    }
}
