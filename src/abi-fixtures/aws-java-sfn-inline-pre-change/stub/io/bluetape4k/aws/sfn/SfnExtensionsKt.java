package io.bluetape4k.aws.sfn;

import software.amazon.awssdk.services.sfn.model.ListExecutionsRequest;

public final class SfnExtensionsKt {
    private SfnExtensionsKt() {
    }

    public static void requireStateMachineSource(ListExecutionsRequest request, String expectedArn) {
        throw new UnsupportedOperationException("pre-change stub");
    }

    public static void requireMapRunSource(ListExecutionsRequest request, String expectedArn) {
        throw new UnsupportedOperationException("pre-change stub");
    }
}
