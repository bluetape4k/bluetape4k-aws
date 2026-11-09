package io.bluetape4k.aws.consumer;

import io.bluetape4k.aws.sfn.SfnExtensionsKt;
import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;

public final class SfnInlineEvolutionConsumer {
    private SfnInlineEvolutionConsumer() {
    }

    public static void main(String[] args) {
        ListExecutionsRequest stateMachineRequest = ListExecutionsRequest.builder()
            .stateMachineArn("arn:aws:states:ap-northeast-2:123456789012:stateMachine:orders")
            .build();
        ListExecutionsRequest mapRunRequest = ListExecutionsRequest.builder()
            .mapRunArn("arn:aws:states:ap-northeast-2:123456789012:mapRun:orders")
            .build();

        SfnExtensionsKt.requireStateMachineSource(
            stateMachineRequest,
            stateMachineRequest.stateMachineArn()
        );
        SfnExtensionsKt.requireMapRunSource(mapRunRequest, mapRunRequest.mapRunArn());
        System.out.println("legacy SFN inline helper consumer passed");
    }
}
